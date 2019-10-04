package io.casperlabs.casper

import cats.effect.Concurrent
import cats.effect.concurrent.Semaphore
import cats.implicits._
import com.google.protobuf.ByteString
import io.casperlabs.casper.DeploySelection.DeploySelection
import io.casperlabs.casper.Estimator.{BlockHash, Validator}
import io.casperlabs.casper.consensus._
import io.casperlabs.casper.finality.singlesweep.FinalityDetector
import io.casperlabs.casper.util.ProtoUtil
import io.casperlabs.casper.util.execengine.ExecEngineUtil
import io.casperlabs.casper.util.execengine.ExecEngineUtil.StateHash
import io.casperlabs.casper.validation.Validation
import io.casperlabs.catscontrib.MonadThrowable
import io.casperlabs.comm.gossiping
import io.casperlabs.metrics.Metrics
import io.casperlabs.shared._
import io.casperlabs.smartcontracts.ExecutionEngineService
import io.casperlabs.storage.block.BlockStorage
import io.casperlabs.storage.dag.{DagRepresentation, DagStorage}
import io.casperlabs.storage.deploy.DeployStorage
import io.casperlabs.casper.util.CasperLabsProtocolVersions

trait MultiParentCasper[F[_]] {
  //// Brought from Casper trait
  def addBlock(block: Block): F[BlockStatus]
  def contains(block: Block): F[Boolean]
  def deploy(deployData: Deploy): F[Either[Throwable, Unit]]
  def estimator(
      dag: DagRepresentation[F],
      latestMessages: Map[ByteString, Set[ByteString]]
  ): F[List[ByteString]]
  def createBlock: F[CreateBlockStatus]
  ////

  def dag: F[DagRepresentation[F]]
  // This is the weight of faults that have been accumulated so far.
  // We want the clique oracle to give us a fault tolerance that is greater than
  // this initial fault weight combined with our fault tolerance threshold t.
  def normalizedInitialFault(weights: Map[Validator, Long]): F[Float]
  def lastFinalizedBlock: F[Block]
  def faultToleranceThreshold: Float
}

object MultiParentCasper extends MultiParentCasperInstances {
  def apply[F[_]](implicit instance: MultiParentCasper[F]): MultiParentCasper[F] = instance
}

sealed abstract class MultiParentCasperInstances {

  private def init[F[_]: Concurrent: Log: BlockStorage: DagStorage: ExecutionEngineService: Validation](
      genesis: Block,
      genesisPreState: StateHash,
      genesisEffects: ExecEngineUtil.TransformMap
  ) =
    for {
      _                   <- Validation[F].transactions(genesis, genesisPreState, genesisEffects)
      blockProcessingLock <- Semaphore[F](1)
      casperState <- Cell.mvarCell[F, CasperState](
                      CasperState()
                    )
    } yield (blockProcessingLock, casperState)

  /** Create a MultiParentCasper instance from the new RPC style gossiping. */
  def fromGossipServices[F[_]: Concurrent: Log: Time: Metrics: FinalityDetector: BlockStorage: DagStorage: ExecutionEngineService: LastFinalizedBlockHashContainer: DeployStorage: Validation: DeploySelection: CasperLabsProtocolVersions](
      validatorId: Option[ValidatorIdentity],
      genesis: Block,
      genesisPreState: StateHash,
      genesisEffects: ExecEngineUtil.TransformMap,
      chainId: String,
      relaying: gossiping.Relaying[F]
  ): F[MultiParentCasper[F]] =
    for {
      (blockProcessingLock, implicit0(casperState: Cell[F, CasperState])) <- init(
                                                                              genesis,
                                                                              genesisPreState,
                                                                              genesisEffects
                                                                            )
      statelessExecutor <- MultiParentCasperImpl.StatelessExecutor.create[F](chainId)
      casper <- MultiParentCasperImpl.create[F](
                 statelessExecutor,
                 MultiParentCasperImpl.Broadcaster.fromGossipServices(validatorId, relaying),
                 validatorId,
                 genesis,
                 chainId,
                 blockProcessingLock
               )
    } yield casper
}
