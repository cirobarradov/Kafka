/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package kafka.coordinator.transaction

import java.util
import java.util.concurrent.LinkedBlockingQueue

import kafka.common.{BrokerNotAvailableException, RequestAndCompletionHandler}
import kafka.server.{DelayedOperationPurgatory, MetadataCache}
import kafka.utils.Logging
import org.apache.kafka.common.network.ListenerName
import org.apache.kafka.common.requests.{TransactionResult, WriteTxnMarkersRequest}
import org.apache.kafka.common.requests.WriteTxnMarkersRequest.TxnMarkerEntry
import org.apache.kafka.common.utils.Utils
import org.apache.kafka.common.{Node, TopicPartition}

import scala.collection.{concurrent, immutable, mutable}
import collection.JavaConverters._
import collection.JavaConversions._

class TransactionMarkerChannel(interBrokerListenerName: ListenerName, metadataCache: MetadataCache) extends Logging {

  // we need the metadataPartition so we can clean up when Transaction Log partitions emigrate
  case class PendingTxnKey(metadataPartition: Int, producerId: Long)

  private val brokerStateMap: concurrent.Map[Int, DestinationBrokerAndQueuedMarkers] = concurrent.TrieMap.empty[Int, DestinationBrokerAndQueuedMarkers]
  private val pendingTxnMap: concurrent.Map[PendingTxnKey, TransactionMetadata] = concurrent.TrieMap.empty[PendingTxnKey, TransactionMetadata]

  // visible for testing
  private[transaction] def queueForBroker(brokerId: Int) = {
    brokerStateMap.get(brokerId)
  }

  private[transaction]
  def drainQueuedTransactionMarkers(txnMarkerPurgatory: DelayedOperationPurgatory[DelayedTxnMarker]): Iterable[RequestAndCompletionHandler] = {
    brokerStateMap.flatMap {case (brokerId: Int, destAndMarkerQueue: DestinationBrokerAndQueuedMarkers) =>
      val markersToSend: java.util.List[CoordinatorEpochAndMarkers] = new util.ArrayList[CoordinatorEpochAndMarkers] ()
      destAndMarkerQueue.markersQueue.drainTo (markersToSend)
      markersToSend.groupBy{ epochAndMarker => (epochAndMarker.metadataPartition, epochAndMarker.coordinatorEpoch) }
        .map { case((metadataPartition: Int, coordinatorEpoch:Int), buffer: mutable.Buffer[CoordinatorEpochAndMarkers]) =>
          val txnMarkerEntries = buffer.flatMap{_.txnMarkerEntries }.asJava
          val requestCompletionHandler = new TransactionMarkerRequestCompletionHandler(
            this,
            txnMarkerPurgatory,
            CoordinatorEpochAndMarkers(metadataPartition, coordinatorEpoch, txnMarkerEntries),
            brokerId)
          RequestAndCompletionHandler(destAndMarkerQueue.destBrokerNode, new WriteTxnMarkersRequest.Builder(coordinatorEpoch, txnMarkerEntries), requestCompletionHandler)
        }
    }
  }


  def addOrUpdateBroker(broker: Node) {
    if (brokerStateMap.contains(broker.id())) {
      val brokerQueue = brokerStateMap(broker.id())
      if (!brokerQueue.destBrokerNode.equals(broker)) {
        brokerStateMap.put(broker.id(), DestinationBrokerAndQueuedMarkers(broker, brokerQueue.markersQueue))
        trace(s"Updated destination broker for ${broker.id} from: ${brokerQueue.destBrokerNode} to: $broker")
      }
    } else {
      val markersQueue = new LinkedBlockingQueue[CoordinatorEpochAndMarkers]()
      brokerStateMap.put(broker.id, DestinationBrokerAndQueuedMarkers(broker, markersQueue))
      trace(s"Added destination broker ${broker.id}: $broker")
    }
  }

  private[transaction] def addRequestForBroker(brokerId: Int, txnMarkerRequest: CoordinatorEpochAndMarkers) {
    val markersQueue = brokerStateMap(brokerId).markersQueue
    markersQueue.add(txnMarkerRequest)
    trace(s"Added markers $txnMarkerRequest for broker $brokerId")
  }

  def addRequestToSend(metadataPartition: Int, pid: Long, epoch: Short, result: TransactionResult, coordinatorEpoch: Int, topicPartitions: immutable.Set[TopicPartition]): Unit = {
    val partitionsByDestination: immutable.Map[Int, immutable.Set[TopicPartition]] = topicPartitions.groupBy { topicPartition: TopicPartition =>
      val leaderForPartition = metadataCache.getPartitionInfo(topicPartition.topic, topicPartition.partition)
      val currentBrokers = mutable.Set.empty[Int]
      leaderForPartition match {
        case Some(partitionInfo) =>
          val brokerId = partitionInfo.leaderIsrAndControllerEpoch.leaderAndIsr.leader
          if (currentBrokers.add(brokerId)) {
            // TODO: What should we do if we get BrokerEndPointNotAvailableException?
            val broker = metadataCache.getAliveEndpoint(brokerId, interBrokerListenerName).get
            addOrUpdateBroker(broker)
          }
          brokerId
        case None =>
          // TODO: there is a rare case that the producer gets the partition info from another broker who has the newer information of the
          // partition, while the TC itself has not received the propagated metadata update; do we need to block when this happens?
          throw new IllegalStateException("Cannot find the leader broker for the txn marker to write for topic partition " + topicPartition)
      }
    }

    for ((brokerId: Int, topicPartitions: immutable.Set[TopicPartition]) <- partitionsByDestination) {
      val txnMarker = new TxnMarkerEntry(pid, epoch, result, topicPartitions.toList.asJava)
      addRequestForBroker(brokerId, CoordinatorEpochAndMarkers(metadataPartition, coordinatorEpoch, Utils.mkList(txnMarker)))
    }
  }

  def maybeAddPendingRequest(metadataPartition: Int, metadata: TransactionMetadata): Boolean = {
    val existingMetadataToWrite = pendingTxnMap.putIfAbsent(PendingTxnKey(metadataPartition, metadata.pid), metadata)
    existingMetadataToWrite.isEmpty
  }

  def removeCompletedTxn(metadataPartition: Int, pid: Long): Unit = {
    pendingTxnMap.remove(PendingTxnKey(metadataPartition, pid))
  }

  def pendingTxnMetadata(metadataPartition: Int, pid: Long): Option[TransactionMetadata] = {
    pendingTxnMap.get(PendingTxnKey(metadataPartition, pid))
  }

  def clear(): Unit = {
    brokerStateMap.clear()
    pendingTxnMap.clear()
  }

  def removeStateForPartition(partition: Int): Unit = {
    brokerStateMap.foreach {case(_, destinationAndQueue: DestinationBrokerAndQueuedMarkers) =>
      val allMarkers: java.util.List[CoordinatorEpochAndMarkers] = new util.ArrayList[CoordinatorEpochAndMarkers] ()
      destinationAndQueue.markersQueue.drainTo(allMarkers)
      destinationAndQueue.markersQueue.addAll(allMarkers.asScala.filter{ epochAndMarkers => epochAndMarkers.metadataPartition != partition}.asJava)
    }
    pendingTxnMap.filter {case (key:PendingTxnKey, _) => key.metadataPartition == partition}
      .foreach{case (key:PendingTxnKey, _) => pendingTxnMap.remove(key)}
  }

}
