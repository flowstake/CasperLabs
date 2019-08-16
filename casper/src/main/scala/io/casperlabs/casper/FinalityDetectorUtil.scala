package io.casperlabs.casper

import cats.Monad
import cats.implicits._
import io.casperlabs.blockstorage.{BlockMetadata, DagRepresentation}
import io.casperlabs.casper.Estimator.{BlockHash, Validator}
import io.casperlabs.casper.util.{DagOperations, ProtoUtil}

object FinalityDetectorUtil {

  /*
   * Returns a list of validators whose latest messages are votes for `candidateBlockHash`.
   * i.e. checks whether latest blocks from these validators are in the main chain of `candidateBlockHash`.
   */
  private def getAgreeingValidators[F[_]: Monad](
      dag: DagRepresentation[F],
      candidateBlockHash: BlockHash,
      weights: Map[Validator, Long]
  ): F[List[Validator]] =
    weights.keys.toList.filterA { validator =>
      for {
        latestMessageHash <- dag
                              .latestMessageHash(
                                validator
                              )
        result <- latestMessageHash match {
                   case Some(b) =>
                     ProtoUtil.isInMainChain[F](
                       dag,
                       candidateBlockHash,
                       b
                     )
                   case _ => false.pure[F]
                 }
      } yield result
    }

  /**
    * Finding validators who voting on the `candidateBlockHash`,
    * if twice the sum of weight of them are bigger than the
    * total weight, then return these validators and their sum of weight.
    * @param dag blockDag
    * @param candidateBlockHash blockHash of block to be estimate whether finalized
    * @param weights weight map
    * @return
    */
  def committeeApproximation[F[_]: Monad](
      dag: DagRepresentation[F],
      candidateBlockHash: BlockHash,
      weights: Map[Validator, Long]
  ): F[Option[(List[Validator], Long)]] =
    for {
      committee              <- getAgreeingValidators(dag, candidateBlockHash, weights)
      totalWeight            = weights.values.sum
      maxWeightApproximation = committee.map(weights).sum
      // To have a committee of half the total weight,
      // you need at least twice the weight of the maxWeightApproximation to be greater than the total weight.
      // If that is false, we don't need to compute best committee
      // as we know the value is going to be below 0 and thus useless for finalization.
      result = if (2 * maxWeightApproximation > totalWeight) {
        Some((committee, maxWeightApproximation))
      } else {
        None
      }
    } yield result

  /**
    * Finds j-dag level of latest block per each validator as seen in the j-past-cone of a given block.
    * The search is however restricted to given subset of validators.
    *
    * Caution 1: For some validators there may be no blocks visible in j-past-cone(block).
    *            Hence the resulting map will not contain such validators.
    * Caution 2: the j-past-cone(b) includes block b, therefore if validators
    *            contains b.creator then the resulting map will include
    *            the entry b.creator ---> b.rank
    *
    * TODO optimize it: when bonding new validator, it need search back to genesis
    *
    * @param dag
    * @param block
    * @param validators
    * @return
    */
  def panoramaDagLevelsOfBlock[F[_]: Monad](
      dag: DagRepresentation[F],
      block: BlockMetadata,
      validators: Set[Validator]
  ): F[Map[Validator, Long]] = {
    implicit val blockTopoOrdering: Ordering[BlockMetadata] =
      DagOperations.blockTopoOrderingDesc

    val stream = DagOperations.bfToposortTraverseF(List(block)) { b =>
      b.justifications
        .traverse(justification => {
          dag.lookup(justification.latestBlockHash)
        })
        .map(_.flatten)
    }

    stream
      .foldWhileLeft((validators, Map.empty[Validator, Long])) {
        case ((remainingValidators, acc), b) =>
          if (remainingValidators.isEmpty) {
            // Stop traversal if all validators find its latest block
            Right((remainingValidators, acc))
          } else if (remainingValidators.contains(b.validatorPublicKey)) {
            Left(
              (remainingValidators - b.validatorPublicKey, acc + (b.validatorPublicKey -> b.rank))
            )
          } else {
            Left((remainingValidators, acc))
          }
      }
      .map(_._2)
  }

  // Get level zero messages of the specified validator and specified candidateBlock
  def levelZeroMsgsOfValidator[F[_]: Monad](
      dag: DagRepresentation[F],
      validator: Validator,
      candidateBlockHash: BlockHash
  ): F[List[BlockMetadata]] =
    dag.latestMessage(validator).flatMap {
      case Some(latestMsgByValidator) =>
        DagOperations
          .bfTraverseF[F, BlockMetadata](List(latestMsgByValidator))(
            previousAgreedBlockFromTheSameValidator(
              dag,
              _,
              candidateBlockHash,
              validator
            )
          )
          .toList
      case None => List.empty[BlockMetadata].pure[F]
    }

  /*
   * Traverses back the j-DAG of `block` (one step at a time), following `validator`'s blocks
   * and collecting them as long as they are descendants of the `candidateBlockHash`.
   */
  private def previousAgreedBlockFromTheSameValidator[F[_]: Monad](
      dag: DagRepresentation[F],
      block: BlockMetadata,
      candidateBlockHash: BlockHash,
      validator: Validator
  ): F[List[BlockMetadata]] = {
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
          .flatMap[List[BlockMetadata]](
            isActiveVote =>
              // If parent block of `block` is not in the main chain of `candidateBlockHash`
              // we don't include it in the set of level-0 messages.
              if (isActiveVote) dag.lookup(previousHash).map(_.toList)
              else List.empty[BlockMetadata].pure[F]
          )
      case None =>
        List.empty[BlockMetadata].pure[F]
    }
  }
}
