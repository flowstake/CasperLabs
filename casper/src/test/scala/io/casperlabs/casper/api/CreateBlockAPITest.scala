package io.casperlabs.casper.api

import cats.Monad
import cats.data.EitherT
import cats.effect.concurrent.Semaphore
import cats.implicits._
import com.github.ghik.silencer.silent
import com.google.protobuf.ByteString
import io.casperlabs.casper
import io.casperlabs.casper.Estimator.{BlockHash, Validator}
import io.casperlabs.casper.MultiParentCasperRef.MultiParentCasperRef
import io.casperlabs.casper._
import io.casperlabs.casper.helper.{GossipServiceCasperTestNodeFactory, HashSetCasperTestNode}
import io.casperlabs.casper.consensus._
import io.casperlabs.casper.util._
import io.casperlabs.catscontrib.TaskContrib._
import io.casperlabs.crypto.signatures.SignatureAlgorithm.Ed25519
import io.casperlabs.metrics.Metrics
import io.casperlabs.p2p.EffectsTestInstances._
import io.casperlabs.shared.Time
import io.casperlabs.storage.BlockMsgWithTransform
import io.casperlabs.storage.dag.DagRepresentation
import monix.eval.Task
import monix.execution.Scheduler
import org.scalatest.{FlatSpec, Matchers}

import scala.concurrent.duration._

@silent("deprecated")
class CreateBlockAPITest extends FlatSpec with Matchers with GossipServiceCasperTestNodeFactory {
  import HashSetCasperTest._

  implicit val scheduler: Scheduler = Scheduler.fixedPool("create-block-api-test", 4)
  implicit val metrics              = new Metrics.MetricsNOP[Task]
  implicit val raiseValidateErr =
    casper.validation.raiseValidateErrorThroughApplicativeError[Task]
  implicit val logEff = new LogStub[Task]

  implicit val validation = HashSetCasperTestNode.makeValidation[Task]

  private val (validatorKeys, validators)                      = (1 to 4).map(_ => Ed25519.newKeyPair).unzip
  private val bonds                                            = createBonds(validators)
  private val BlockMsgWithTransform(Some(genesis), transforms) = createGenesis(bonds)

  "createBlock" should "not allow simultaneous calls" in {
    implicit val scheduler = Scheduler.fixedPool("three-threads", 3)
    implicit val time = new Time[Task] {
      private val timer                               = Task.timer
      def currentMillis: Task[Long]                   = timer.clock.realTime(MILLISECONDS)
      def nanoTime: Task[Long]                        = timer.clock.monotonic(NANOSECONDS)
      def sleep(duration: FiniteDuration): Task[Unit] = timer.sleep(duration)
    }
    val node   = standaloneEff(genesis, transforms, validatorKeys.head)
    val casper = new SleepingMultiParentCasperImpl[Task](node.casperEff)
    val deploys = List(
      "@0!(0) | for(_ <- @0){ @1!(1) }",
      "for(_ <- @1){ @2!(2) }"
    ).map { deploy =>
      ProtoUtil
        .basicDeploy(
          0,
          ByteString.copyFromUtf8(System.currentTimeMillis().toString),
          ByteString.copyFromUtf8(deploy)
        )
    }

    implicit val logEff       = new LogStub[Task]
    implicit val blockStorage = node.blockStorage
    implicit val safetyOracle = node.safetyOracleEff

    def testProgram(blockApiLock: Semaphore[Task])(
        implicit casperRef: MultiParentCasperRef[Task]
    ): Task[(Either[Throwable, ByteString], Either[Throwable, ByteString])] =
      for {
        t1 <- (BlockAPI.deploy[Task](deploys.head) *> BlockAPI
               .propose[Task](blockApiLock)).start
        _ <- Time[Task].sleep(2.second)
        t2 <- (BlockAPI.deploy[Task](deploys.last) *> BlockAPI
               .propose[Task](blockApiLock)).start //should fail because other not done
        r1 <- t1.join.attempt
        r2 <- t2.join.attempt
      } yield (r1, r2)

    try {
      val (response1, response2) = (for {
        casperRef    <- MultiParentCasperRef.of[Task]
        _            <- casperRef.set(casper)
        blockApiLock <- Semaphore[Task](1)
        result       <- testProgram(blockApiLock)(casperRef)
      } yield result).unsafeRunSync

      response1.isRight shouldBe true
      response2.isRight shouldBe false
      response2.left.get.getMessage should include("ABORTED")
    } finally {
      node.tearDown()
    }
  }

  "deploy" should "reject replayed deploys" in {
    // Create the node with low fault tolerance threshold so it finalizes the blocks as soon as they are made.
    val node =
      standaloneEff(genesis, transforms, validatorKeys.head, faultToleranceThreshold = -2.0f)

    implicit val logEff       = new LogStub[Task]
    implicit val blockStorage = node.blockStorage
    implicit val safetyOracle = node.safetyOracleEff

    def testProgram(blockApiLock: Semaphore[Task])(
        implicit casperRef: MultiParentCasperRef[Task]
    ): Task[Unit] =
      for {
        d <- ProtoUtil.basicDeploy[Task]()
        _ <- BlockAPI.deploy[Task](d)
        _ <- BlockAPI.propose[Task](blockApiLock)
        _ <- BlockAPI.deploy[Task](d)
      } yield ()

    try {
      (for {
        casperRef    <- MultiParentCasperRef.of[Task]
        _            <- casperRef.set(node.casperEff)
        blockApiLock <- Semaphore[Task](1)
        result       <- testProgram(blockApiLock)(casperRef)
      } yield result).unsafeRunSync
    } catch {
      case ex: io.grpc.StatusRuntimeException =>
        ex.getMessage should include("already contains")
    } finally {
      node.tearDown()
    }
  }
}

private class SleepingMultiParentCasperImpl[F[_]: Monad: Time](underlying: MultiParentCasper[F])
    extends MultiParentCasper[F] {
  def addBlock(b: Block): F[BlockStatus]            = underlying.addBlock(b)
  def contains(b: Block): F[Boolean]                = underlying.contains(b)
  def deploy(d: Deploy): F[Either[Throwable, Unit]] = underlying.deploy(d)
  def estimator(
      dag: DagRepresentation[F],
      latestMessagesHashes: Map[ByteString, Set[ByteString]]
  ): F[List[BlockHash]] =
    underlying.estimator(dag, latestMessagesHashes)
  def dag: F[DagRepresentation[F]] = underlying.dag
  def normalizedInitialFault(weights: Map[Validator, Long]): F[Float] =
    underlying.normalizedInitialFault(weights)
  def lastFinalizedBlock: F[Block] = underlying.lastFinalizedBlock
  def faultToleranceThreshold      = underlying.faultToleranceThreshold

  override def createBlock: F[CreateBlockStatus] =
    for {
      result <- underlying.createBlock
      _      <- implicitly[Time[F]].sleep(5.seconds)
    } yield result

}
