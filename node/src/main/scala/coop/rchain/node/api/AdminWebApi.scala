package coop.rchain.node.api

import cats.effect.Concurrent
import cats.effect.concurrent.Semaphore
import cats.syntax.all._
import coop.rchain.casper.api.BlockAPI
import coop.rchain.casper.engine.EngineCell.EngineCell
import coop.rchain.casper.{LastFinalizedHeightConstraintChecker, SynchronyConstraintChecker}
import coop.rchain.metrics.{Metrics, Span}
import coop.rchain.shared.Log

trait AdminWebApi[F[_]] {
  def propose: F[String]
}

object AdminWebApi {

  class AdminWebApiImpl[F[_]: Concurrent: EngineCell: SynchronyConstraintChecker: LastFinalizedHeightConstraintChecker: Log: Span: Metrics](
      blockApiLock: Semaphore[F]
  ) extends AdminWebApi[F] {
    import WebApiSyntax._

    def propose: F[String] =
      BlockAPI.createBlock[F](blockApiLock).flatMap(_.liftToBlockApiErr)
  }

}
