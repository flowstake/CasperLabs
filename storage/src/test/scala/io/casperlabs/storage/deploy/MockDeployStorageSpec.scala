package io.casperlabs.storage.deploy
import io.casperlabs.shared.Log
import io.casperlabs.shared.Log.NOPLog
import monix.eval.Task
import monix.execution.Scheduler.Implicits.global
import monix.execution.schedulers.CanBlock.permit

import scala.concurrent.duration._

class MockDeployStorageSpec extends DeployStorageSpec {
  override protected def testFixture(
      test: (DeployStorageReader[Task], DeployStorageWriter[Task]) => Task[Unit],
      timeout: FiniteDuration = 5.seconds,
      deployBufferChunkSize: Int = 100
  ): Unit =
    (for {
      implicit0(logNOP: Log[Task]) <- Task(new NOPLog[Task])
      mock                         <- MockDeployStorage.create[Task]()
      _                            <- test(mock, mock)
    } yield ()).runSyncUnsafe(timeout)
}
