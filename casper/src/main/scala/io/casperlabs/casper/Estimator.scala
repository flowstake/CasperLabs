package io.casperlabs.casper

import cats.Monad
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.casper.equivocations.{EquivocationDetector, EquivocationsTracker}
import io.casperlabs.casper.util.DagOperations
import io.casperlabs.casper.util.ProtoUtil.weightFromValidatorByDag
import io.casperlabs.catscontrib.MonadThrowable
import io.casperlabs.storage.dag.DagRepresentation

import scala.collection.immutable.Map

object Estimator {
  type BlockHash = ByteString
  type Validator = ByteString

  implicit val decreasingOrder = Ordering[Long].reverse

  /* Should not be used as long as `DagRepresentation` is not immutable. See NODE-923
  def tips[F[_]: MonadThrowable](
      dag: DagRepresentation[F],
      genesis: BlockHash,
      equivocationsTracker: EquivocationsTracker
  ): F[List[BlockHash]] =
    for {
      latestMessageHashes <- dag.latestMessageHashes
                              .map(_.collect {
                                case (v, messages)
                                    if !equivocationsTracker.contains(v) && messages.size == 1 =>
                                  // Honest validator should have only one "latest message"
                                  v -> messages.head
                              })
      result <- Estimator
                 .tips[F](dag, genesis, latestMessageHashes, equivocationsTracker)
    } yield result
   */

  def tips[F[_]: MonadThrowable](
      dag: DagRepresentation[F],
      genesis: BlockHash,
      latestMessageHashes: Map[Validator, Set[BlockHash]],
      equivocationsTracker: EquivocationsTracker
  ): F[List[BlockHash]] = {

    /** Finds children of the block b that have been scored by the LMD algorithm.
      * If no children exist (block B is the tip) return the block.
      *
      * @param b block for which we want to find tips.
      * @param scores map of the scores from the block hash to a score
      * @return Children of the block.
      */
    def getChildrenOrSelf(
        b: BlockHash,
        scores: Map[BlockHash, Long]
    ): F[List[BlockHash]] =
      dag
        .children(b)
        .map(_.filter(scores.contains))
        .map(c => if (c.isEmpty) List(b) else c.toList)

    /*
     * Returns latestMessages except those blocks whose descendant
     * exists in latestMessages.
     */
    def tipsOfLatestMessages(
        blocks: List[BlockHash],
        scores: Map[BlockHash, Long]
    ): F[List[BlockHash]] =
      if (blocks.isEmpty) {
        // In the case of empty set of validators' messages.
        // This is the very first block in the DAG (not counting Genesis),
        // so it builds on top of it.
        List(genesis).pure[F]
      } else {
        for {
          children <- blocks.flatTraverse(getChildrenOrSelf(_, scores)).map(_.distinct)
          result <- if (blocks.toSet == children.toSet) {
                     children.pure[F]
                   } else {
                     tipsOfLatestMessages(children, scores)
                   }
        } yield result
      }

    for {
      lca <- if (latestMessageHashes.isEmpty) genesis.pure[F]
            else
              DagOperations.latestCommonAncestorsMainParent(
                dag,
                latestMessageHashes.values.flatten.toList
              )
      equivocatingValidators <- EquivocationDetector.detectVisibleFromJustifications(
                                 dag,
                                 latestMessageHashes,
                                 equivocationsTracker
                               )
      scores           <- lmdScoring(dag, lca, latestMessageHashes, equivocatingValidators)
      newMainParent    <- forkChoiceTip(dag, lca, scores)
      parents          <- tipsOfLatestMessages(latestMessageHashes.values.flatten.toList, scores)
      secondaryParents = parents.filter(_ != newMainParent)
      sortedSecParents = secondaryParents
        .sortBy(b => scores.getOrElse(b, 0L) -> b.toStringUtf8)
        .reverse
    } yield newMainParent +: sortedSecParents
  }

  /** Computes scores for LMD GHOST.
    *
    * Starts at the latest messages from currently bonded validators
    * and traverses up to the stop hash, collecting blocks' scores
    * (which is the weight of validators who include that block as main parent of their block).
    *
    * @param stopHash Block at which we stop computing scores. Should be latest common ancestor of `latestMessagesHashes`.
    * @return Scores map.
    */
  def lmdScoring[F[_]: MonadThrowable](
      dag: DagRepresentation[F],
      stopHash: BlockHash,
      latestMessageHashes: Map[Validator, Set[BlockHash]],
      equivocatingValidators: Set[Validator]
  ): F[Map[BlockHash, Long]] =
    latestMessageHashes.toList.foldLeftM(Map.empty[BlockHash, Long]) {
      case (acc, (validator, latestMessageHashes)) =>
        DagOperations
          .bfTraverseF[F, BlockHash](latestMessageHashes.toList)(
            hash => dag.lookup(hash).map(_.get.parents.take(1).toList)
          )
          .takeUntil(_ == stopHash)
          .foldLeftF(acc) {
            case (acc2, blockHash) =>
              (if (equivocatingValidators.contains(validator)) {
                 0L.pure[F]
               } else {
                 weightFromValidatorByDag(dag, blockHash, validator)
               }).map { realWeight =>
                val oldValue = acc2.getOrElse(blockHash, 0L)
                acc2.updated(blockHash, realWeight + oldValue)
              }
          }
    }

  /**
    * Computes fork choice.
    *
    * @param dag Representation of the Block DAG.
    * @param startingBlock Starting block for the fork choice rule.
    * @param scores Map of block's scores.
    * @return Block hash chosen by the fork choice rule.
    */
  def forkChoiceTip[F[_]: Monad](
      dag: DagRepresentation[F],
      startingBlock: BlockHash,
      scores: Map[BlockHash, Long]
  ): F[BlockHash] =
    dag.getMainChildren(startingBlock).flatMap { mainChildren =>
      {
        // make sure they are reachable from latestMessages
        val reachableMainChildren = mainChildren.filter(scores.contains)
        if (reachableMainChildren.isEmpty) {
          startingBlock.pure[F]
        } else {
          val highestScoreChild =
            reachableMainChildren.maxBy(b => scores(b) -> b.toStringUtf8)
          forkChoiceTip[F](
            dag,
            highestScoreChild,
            scores
          )
        }
      }
    }

}
