package io.casperlabs.casper.finality

import cats.Monad
import cats.implicits._
import io.casperlabs.casper.Estimator.{BlockHash, Validator}
import io.casperlabs.casper.PrettyPrinter
import io.casperlabs.casper.equivocations.EquivocationsTracker
import io.casperlabs.casper.util.{DagOperations, ProtoUtil}
import io.casperlabs.catscontrib.MonadThrowable
import io.casperlabs.models.Message
import io.casperlabs.storage.dag.DagRepresentation

import scala.collection.mutable.{IndexedSeq => MutableSeq}

object FinalityDetectorUtil {

  /**
    * Finds latest block per each validator as seen in the j-past-cone of a given block.
    * The search is however restricted to given subset of validators.
    *
    * Caution 1: For some validators there may be no blocks visible in j-past-cone(block).
    *            Hence the resulting map will not contain such validators.
    * Caution 2: the j-past-cone(b) includes block b, therefore if validators
    *            contains b.creator then the resulting map will include
    *            the entry b.creator ---> b
    *
    * TODO optimize it: when bonding new validator, it need search back to genesis
    *
    * @param dag
    * @param block
    * @param validators
    * @return
    */
  private[casper] def panoramaOfBlockByValidators[F[_]: Monad](
      dag: DagRepresentation[F],
      block: Message,
      validators: Set[Validator]
  ): F[Map[Validator, Message]] = {
    implicit val blockTopoOrdering: Ordering[Message] =
      DagOperations.blockTopoOrderingDesc

    val stream = DagOperations.bfToposortTraverseF(List(block)) { b =>
      b.justifications.toList
        .traverse(justification => {
          dag.lookup(justification.latestBlockHash)
        })
        .map(_.flatten)
    }

    stream
      .foldWhileLeft((validators, Map.empty[Validator, Message])) {
        case ((remainingValidators, acc), b) =>
          if (remainingValidators.isEmpty) {
            // Stop traversal if all validators find its latest block
            Right((remainingValidators, acc))
          } else if (remainingValidators.contains(b.validatorId)) {
            Left(
              (
                remainingValidators - b.validatorId,
                acc + (b.validatorId -> b)
              )
            )
          } else {
            Left((remainingValidators, acc))
          }
      }
      .map(_._2)
  }

  private[casper] def panoramaDagLevelsOfBlock[F[_]: Monad](
      blockDag: DagRepresentation[F],
      block: Message,
      validators: Set[Validator]
  ): F[Map[Validator, Long]] =
    panoramaOfBlockByValidators(blockDag, block, validators)
      .map(_.mapValues(_.rank))

  /**
    * Get level zero messages of the specified validator and specified candidateBlock
    */
  private[casper] def levelZeroMsgsOfValidator[F[_]: MonadThrowable](
      dag: DagRepresentation[F],
      validator: Validator,
      candidateBlockHash: BlockHash
  ): F[List[Message]] =
    dag.latestMessage(validator).flatMap {
      case validatorLatestMessages =>
        if (validatorLatestMessages.isEmpty) {
          List.empty[Message].pure[F]
        } else if (validatorLatestMessages.size > 1) {
          MonadThrowable[F].raiseError(
            new IllegalArgumentException(
              s"Finalizer should be fed only honest validator. ${PrettyPrinter.buildString(validator)} equivocated with blocks ${validatorLatestMessages
                .map(m => PrettyPrinter.buildString(m.messageHash))}"
            )
          )
        } else {
          DagOperations
            .bfTraverseF[F, Message](validatorLatestMessages.toList)(
              previousAgreedBlockFromTheSameValidator(
                dag,
                _,
                candidateBlockHash,
                validator
              )
            )
            .toList
        }
    }

  /*
   * Traverses back the j-DAG of `block` (one step at a time), following `validator`'s blocks
   * and collecting them as long as they are descendants of the `candidateBlockHash`.
   */
  private[casper] def previousAgreedBlockFromTheSameValidator[F[_]: Monad](
      dag: DagRepresentation[F],
      block: Message,
      candidateBlockHash: BlockHash,
      validator: Validator
  ): F[List[Message]] = {
    // Assumes that validator always includes his last message as justification.
    val previousHashO = block.justifications
      .find(
        _.validatorPublicKey == validator
      )
      .map(_.latestBlockHash)

    previousHashO match {
      case Some(previousHash) =>
        ProtoUtil
          .isInMainChain[F](dag, candidateBlockHash, previousHash)
          .flatMap[List[Message]](
            isActiveVote =>
              // If parent block of `block` is not in the main chain of `candidateBlockHash`
              // we don't include it in the set of level-0 messages.
              if (isActiveVote) dag.lookup(previousHash).map(_.toList)
              else List.empty[Message].pure[F]
          )
      case None =>
        List.empty[Message].pure[F]
    }
  }

  /**
    * Find the panorama of M and represent this panorama as an array
    * panoramaM, putting (for j=0,1,2,..., n-1)
    * panoramaM(j) := daglevel(latest message of V(j) seen from block M)
    * When V(j)-swimlane is empty or V(j) happens to be an equivocator - so in cases
    * where latest message of V(i) is not well defined concept - in these cases
    * put 0 (zero) in the corresponding cell.
    */
  private[casper] def panoramaM[F[_]: Monad](
      dag: DagRepresentation[F],
      validatorsToIndex: Map[Validator, Int],
      blockSummary: Message,
      equivocationsTracker: EquivocationsTracker
  ): F[MutableSeq[Long]] =
    FinalityDetectorUtil
      .panoramaDagLevelsOfBlock(
        dag,
        blockSummary,
        validatorsToIndex.keySet
      )
      .map(
        latestBlockDagLevelsAsMap =>
          fromMapToArray(
            validatorsToIndex,
            validator => {
              // When V(j) happens to be an equivocator, put 0L in the corresponding cell
              if (equivocationsTracker.contains(validator)) {
                0L
              } else {
                // When V(j)-swimlane is empty, put 0L in the corresponding cell
                latestBlockDagLevelsAsMap.getOrElse(validator, 0L)
              }
            }
          )
      )

  // Returns an MutableSeq, whose size equals the size of validatorsToIndex and
  // For v in validatorsToIndex.key
  //   Arr[validatorsToIndex[v]] = mapFunction[v]
  def fromMapToArray[A](
      validatorsToIndex: Map[Validator, Int],
      mapFunction: Validator => A
  ): MutableSeq[A] =
    validatorsToIndex
      .map {
        case (v, i) =>
          (i, mapFunction(v))
      }
      .toArray[(Int, A)]
      .sortBy(_._1)
      .map(_._2)
}
