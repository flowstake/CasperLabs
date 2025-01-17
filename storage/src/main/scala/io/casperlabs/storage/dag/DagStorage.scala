package io.casperlabs.storage.dag

import cats.Monad
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.casper.consensus.{Block, BlockSummary}
import io.casperlabs.metrics.Metered
import io.casperlabs.models.Message
import io.casperlabs.storage.block.BlockStorage.BlockHash
import io.casperlabs.storage.dag.DagRepresentation.Validator

trait DagStorage[F[_]] {
  //TODO: Get rid of DagRepresentation if SQLite works out
  /* Doesn't guarantee to return immutable representation */
  def getRepresentation: F[DagRepresentation[F]]
  private[storage] def insert(block: Block): F[DagRepresentation[F]]
  def checkpoint(): F[Unit]
  def clear(): F[Unit]
  def close(): F[Unit]
}

object DagStorage {
  trait MeteredDagStorage[F[_]] extends DagStorage[F] with Metered[F] {

    abstract override def getRepresentation: F[DagRepresentation[F]] =
      incAndMeasure("representation", super.getRepresentation)

    abstract override def insert(block: Block): F[DagRepresentation[F]] =
      incAndMeasure("insert", super.insert(block))

    abstract override def checkpoint(): F[Unit] =
      incAndMeasure("checkpoint", super.checkpoint())
  }

  trait MeteredDagRepresentation[F[_]] extends DagRepresentation[F] with Metered[F] {
    // Not measuring 'latestMessage*' because they return fs2.Stream which doesn't work with 'incAndMeasure'

    abstract override def children(blockHash: BlockHash): F[Set[BlockHash]] =
      incAndMeasure("children", super.children(blockHash))

    abstract override def justificationToBlocks(blockHash: BlockHash): F[Set[BlockHash]] =
      incAndMeasure("justificationToBlocks", super.justificationToBlocks(blockHash))

    abstract override def lookup(blockHash: BlockHash): F[Option[Message]] =
      incAndMeasure("lookup", super.lookup(blockHash))

    abstract override def contains(blockHash: BlockHash): F[Boolean] =
      incAndMeasure("contains", super.contains(blockHash))

    abstract override def latestMessageHash(validator: Validator): F[Option[BlockHash]] =
      incAndMeasure("latestMessageHash", super.latestMessageHash(validator))

    abstract override def latestMessage(validator: Validator): F[Option[Message]] =
      incAndMeasure("latestMessage", super.latestMessage(validator))

    abstract override def latestMessageHashes: F[Map[Validator, BlockHash]] =
      incAndMeasure("latestMessageHashes", super.latestMessageHashes)

    abstract override def latestMessages: F[Map[Validator, Message]] =
      incAndMeasure("latestMessages", super.latestMessages)

    abstract override def topoSort(
        startBlockNumber: Long,
        endBlockNumber: Long
    ): fs2.Stream[F, Vector[BlockSummary]] =
      fs2.Stream.eval(m.incrementCounter("topoSort")) >> super
        .topoSort(startBlockNumber, endBlockNumber)

    abstract override def topoSort(startBlockNumber: Long): fs2.Stream[F, Vector[BlockSummary]] =
      fs2.Stream.eval(m.incrementCounter("topoSort")) >> super.topoSort(startBlockNumber)

    abstract override def topoSortTail(tailLength: Int): fs2.Stream[F, Vector[BlockSummary]] =
      fs2.Stream.eval(m.incrementCounter("topoSortTail")) >> super.topoSortTail(tailLength)
  }

  def apply[F[_]](implicit B: DagStorage[F]): DagStorage[F] = B
}

trait DagRepresentation[F[_]] {
  def children(blockHash: BlockHash): F[Set[BlockHash]]

  /** Return blocks that having a specify justification */
  def justificationToBlocks(blockHash: BlockHash): F[Set[BlockHash]]
  def lookup(blockHash: BlockHash): F[Option[Message]]
  def contains(blockHash: BlockHash): F[Boolean]

  /** Return block summaries with ranks in the DAG between start and end, inclusive. */
  def topoSort(
      startBlockNumber: Long,
      endBlockNumber: Long
  ): fs2.Stream[F, Vector[BlockSummary]]

  /** Return block summaries with ranks of blocks in the DAG from a start index to the end. */
  def topoSort(startBlockNumber: Long): fs2.Stream[F, Vector[BlockSummary]]

  def topoSortTail(tailLength: Int): fs2.Stream[F, Vector[BlockSummary]]

  def latestMessageHash(validator: Validator): F[Option[BlockHash]]
  def latestMessage(validator: Validator): F[Option[Message]]
  def latestMessageHashes: F[Map[Validator, BlockHash]]
  def latestMessages: F[Map[Validator, Message]]
}

object DagRepresentation {
  type Validator = ByteString

  implicit class DagRepresentationRich[F[_]](
      dagRepresentation: DagRepresentation[F]
  ) {
    def getMainChildren(
        blockHash: BlockHash
    )(implicit monad: Monad[F]): F[List[BlockHash]] =
      dagRepresentation
        .children(blockHash)
        .flatMap(
          _.toList
            .filterA(
              child =>
                dagRepresentation.lookup(child).map {
                  // make sure child's main parent's hash equal to `blockHash`
                  case Some(blockSummary) => blockSummary.parents.head == blockHash
                  case None               => false
                }
            )
        )
  }

  def apply[F[_]](implicit ev: DagRepresentation[F]): DagRepresentation[F] = ev
}
