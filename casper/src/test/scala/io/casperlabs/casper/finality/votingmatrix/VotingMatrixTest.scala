package io.casperlabs.casper.finality.votingmatrix

import cats.implicits._
import cats.mtl.MonadState
import com.github.ghik.silencer.silent
import com.google.protobuf.ByteString
import io.casperlabs.casper.Estimator.{BlockHash, Validator}
import io.casperlabs.casper.consensus.{Block, Bond}
import io.casperlabs.casper.equivocations.EquivocationsTracker
import io.casperlabs.casper.finality.votingmatrix.VotingMatrix.VotingMatrix
import io.casperlabs.casper.finality.{CommitteeWithConsensusValue, FinalityDetectorUtil}
import io.casperlabs.casper.helper.BlockUtil.generateValidator
import io.casperlabs.casper.helper.{BlockGenerator, StorageFixture}
import io.casperlabs.casper.util.ProtoUtil
import io.casperlabs.catscontrib.MonadThrowable
import io.casperlabs.models.Message
import io.casperlabs.p2p.EffectsTestInstances.LogStub
import io.casperlabs.shared.Time
import io.casperlabs.storage.block.BlockStorage
import io.casperlabs.storage.dag.IndexedDagStorage
import io.casperlabs.storage.deploy.DeployStorage
import monix.eval.Task
import org.scalatest.{Assertion, FlatSpec, Matchers}

import scala.collection.immutable.{HashMap, Map}

@silent("is never used")
class VotingMatrixTest extends FlatSpec with Matchers with BlockGenerator with StorageFixture {

  behavior of "Voting Matrix"

  implicit val logEff = new LogStub[Task]

  def checkWeightMap(
      expect: Map[Validator, Long]
  )(implicit matrix: VotingMatrix[Task]): Task[Assertion] =
    for {
      votingMatrixState <- MonadState[Task, VotingMatrixState].get
      result            = votingMatrixState.weightMap shouldBe expect
    } yield result

  def checkMatrix(
      expect: Map[Validator, Map[Validator, Long]]
  )(implicit matrix: VotingMatrix[Task]): Task[Assertion] = {

    def fromMapTo2DArray(
        validators: IndexedSeq[Validator],
        mapOfMap: Map[Validator, Map[Validator, Long]]
    ): Array[Array[Long]] =
      validators
        .map(
          rowV =>
            validators
              .map(
                columnV => mapOfMap.get(rowV).flatMap(_.get(columnV)).getOrElse(0L)
              )
              .toArray
        )
        .toArray

    for {
      votingMatrixState <- matrix.get
      result = votingMatrixState.votingMatrix shouldBe fromMapTo2DArray(
        votingMatrixState.validators,
        expect
      )
    } yield result
  }

  def checkFirstLevelZeroVote(
      expect: Map[Validator, Option[(BlockHash, Long)]]
  )(implicit matrix: VotingMatrix[Task]): Task[Assertion] =
    for {
      votingMatrixState <- matrix.get
      result = votingMatrixState.firstLevelZeroVotes shouldBe (FinalityDetectorUtil.fromMapToArray(
        votingMatrixState.validatorToIdx,
        expect
      ))
    } yield result

  it should "detect finality as appropriate" in withStorage {
    implicit blockStore => implicit dagStorage =>
      implicit deployStorage =>
        /*
         * The Dag looks like
         *
         *        b5
         *           \
         *             b4
         *           // |
         *        b3    |
         *        || \  |
         *        b1   b2
         *         \   /
         *         genesis
         */
        val v1     = generateValidator("V1")
        val v2     = generateValidator("V2")
        val v1Bond = Bond(v1, 10)
        val v2Bond = Bond(v2, 10)
        val bonds  = Seq(v1Bond, v2Bond)
        for {
          genesis <- createAndStoreBlock[Task](Seq(), ByteString.EMPTY, bonds)
          dag     <- dagStorage.getRepresentation
          implicit0(votingMatrix: VotingMatrix[Task]) <- VotingMatrix
                                                          .create[Task](
                                                            dag,
                                                            genesis.blockHash,
                                                            EquivocationsTracker.empty
                                                          )
          _            <- checkMatrix(Map.empty)
          _            <- checkFirstLevelZeroVote(Map(v1 -> None, v2 -> None))
          _            <- checkWeightMap(Map(v1 -> 10, v2 -> 10))
          updatedBonds = Seq(Bond(v1, 20), v2Bond) // let v1 dominate the chain after finalizing b1
          b1 <- createAndUpdateVotingMatrix[Task](
                 Seq(genesis.blockHash),
                 genesis.blockHash,
                 v1,
                 updatedBonds,
                 justifications = Map(v1 -> genesis.blockHash)
               )
          _ <- checkWeightMap(Map(v1 -> 10, v2 -> 10)) // don't change, because b1 haven't finalized
          _ <- checkMatrix(
                Map(
                  v1 -> Map(v1 -> b1.getHeader.rank, v2 -> 0),
                  v2 -> Map(v1 -> 0, v2                 -> 0)
                )
              )
          _ <- checkFirstLevelZeroVote(
                Map(
                  v1 -> Some((b1.blockHash, b1.getHeader.rank)),
                  v2 -> None
                )
              )
          committee <- checkForCommittee[Task](0.1, EquivocationsTracker.empty)
          _         = committee shouldBe None
          b2 <- createAndUpdateVotingMatrix[Task](
                 Seq(genesis.blockHash),
                 genesis.blockHash,
                 v2,
                 bonds,
                 justifications = Map(v2 -> genesis.blockHash)
               )
          _ <- checkMatrix(
                Map(
                  v1 -> Map(v1 -> b1.getHeader.rank, v2 -> 0),
                  v2 -> Map(v1 -> 0, v2                 -> b2.getHeader.rank)
                )
              )
          _ <- checkFirstLevelZeroVote(
                Map(
                  v1 -> Some((b1.blockHash, b1.getHeader.rank)),
                  v2 -> Some((b2.blockHash, b2.getHeader.rank))
                )
              )
          committee <- checkForCommittee[Task](0.1, EquivocationsTracker.empty)
          _         = committee shouldBe None
          b3 <- createAndUpdateVotingMatrix[Task](
                 Seq(b1.blockHash),
                 genesis.blockHash,
                 v1,
                 bonds,
                 Map(v1 -> b1.blockHash, v2 -> b2.blockHash)
               )
          _ <- checkMatrix(
                Map(
                  v1 -> Map(v1 -> b3.getHeader.rank, v2 -> b2.getHeader.rank),
                  v2 -> Map(v1 -> 0, v2                 -> b2.getHeader.rank)
                )
              )
          _ <- checkFirstLevelZeroVote(
                Map(
                  v1 -> Some((b1.blockHash, b1.getHeader.rank)),
                  v2 -> Some((b2.blockHash, b2.getHeader.rank))
                )
              )
          committee <- checkForCommittee[Task](0.1, EquivocationsTracker.empty)
          _         = committee shouldBe None
          b4 <- createAndUpdateVotingMatrix[Task](
                 Seq(b3.blockHash),
                 genesis.blockHash,
                 v2,
                 bonds,
                 Map(v1 -> b3.blockHash, v2 -> b2.blockHash)
               )
          _ <- checkMatrix(
                Map(
                  v1 -> Map(v1 -> b3.getHeader.rank, v2 -> b2.getHeader.rank),
                  v2 -> Map(v1 -> b3.getHeader.rank, v2 -> b4.getHeader.rank)
                )
              )
          _ <- checkFirstLevelZeroVote(
                Map(
                  v1 -> Some((b1.blockHash, b1.getHeader.rank)),
                  v2 -> Some((b1.blockHash, b4.getHeader.rank))
                )
              )

          committee <- checkForCommittee[Task](0.01, EquivocationsTracker.empty)
          _         = committee shouldBe None

          b5 <- createAndUpdateVotingMatrix[Task](
                 Seq(b4.blockHash),
                 genesis.blockHash,
                 v1,
                 bonds,
                 Map(v1 -> b3.blockHash, v2 -> b4.blockHash)
               )
          _ <- checkMatrix(
                Map(
                  v1 -> Map(v1 -> b5.getHeader.rank, v2 -> b4.getHeader.rank),
                  v2 -> Map(v1 -> b3.getHeader.rank, v2 -> b4.getHeader.rank)
                )
              )
          _ <- checkFirstLevelZeroVote(
                Map(
                  v1 -> Some((b1.blockHash, b1.getHeader.rank)),
                  v2 -> Some((b1.blockHash, b4.getHeader.rank))
                )
              )

          committee <- checkForCommittee[Task](0.1, EquivocationsTracker.empty)
          _         = committee shouldBe Some(CommitteeWithConsensusValue(Set(v1, v2), 20, b1.blockHash))

          committee <- checkForCommittee[Task](0.4, EquivocationsTracker.empty)
          _ = committee shouldBe Some(
            CommitteeWithConsensusValue(Set(v1, v2), 20, b1.blockHash)
          )

          updatedDag <- dagStorage.getRepresentation
          // rebuild from new finalized block b1
          newVotingMatrix <- VotingMatrix
                              .create[Task](
                                updatedDag,
                                b1.blockHash,
                                EquivocationsTracker.empty
                              )
          _ <- checkWeightMap(Map(v1 -> 20, v2 -> 10))(newVotingMatrix)
          _ <- checkMatrix(
                Map(
                  v1 -> Map(v1 -> b5.getHeader.rank, v2 -> b4.getHeader.rank),
                  v2 -> Map(v1 -> b3.getHeader.rank, v2 -> b4.getHeader.rank)
                )
              )(newVotingMatrix)
          result <- checkFirstLevelZeroVote(
                     Map(
                       v1 -> Some((b3.blockHash, b3.getHeader.rank)),
                       v2 -> Some((b3.blockHash, b4.getHeader.rank))
                     )
                   )(newVotingMatrix)
        } yield result
  }

  def createAndUpdateVotingMatrix[F[_]: MonadThrowable: Time: BlockStorage: IndexedDagStorage: DeployStorage](
      parentsHashList: Seq[BlockHash],
      latestFinalizedBlockHash: BlockHash,
      creator: Validator = ByteString.EMPTY,
      bonds: Seq[Bond] = Seq.empty[Bond],
      justifications: collection.Map[Validator, BlockHash] = HashMap.empty[Validator, BlockHash]
  )(
      implicit votingMatrix: VotingMatrix[F]
  ): F[Block] =
    for {
      b           <- createAndStoreBlock[F](parentsHashList, creator, bonds, justifications)
      dag         <- IndexedDagStorage[F].getRepresentation
      votedBranch <- ProtoUtil.votedBranch(dag, latestFinalizedBlockHash, b.blockHash)
      _ <- updateVoterPerspective(
            dag,
            Message.fromBlock(b).get,
            votedBranch.get,
            EquivocationsTracker.empty
          )
    } yield b
}
