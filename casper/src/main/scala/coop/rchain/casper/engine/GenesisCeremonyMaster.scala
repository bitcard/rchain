package coop.rchain.casper.engine

import cats.Applicative
import cats.effect.{Concurrent, Sync}
import cats.implicits._
import coop.rchain.blockstorage.BlockStore
import coop.rchain.blockstorage.casperbuffer.CasperBufferStorage
import coop.rchain.blockstorage.dag.BlockDagStorage
import coop.rchain.blockstorage.deploy.DeployStorage
import coop.rchain.blockstorage.finality.LastFinalizedStorage
import coop.rchain.casper.LastApprovedBlock.LastApprovedBlock
import coop.rchain.casper._
import coop.rchain.casper.engine.EngineCell._
import coop.rchain.casper.protocol._
import coop.rchain.casper.syntax._
import coop.rchain.casper.util.comm.CommUtil
import coop.rchain.casper.util.rholang.RuntimeManager
import coop.rchain.comm.PeerNode
import coop.rchain.comm.rp.Connect.{ConnectionsCell, RPConfAsk}
import coop.rchain.comm.transport.TransportLayer
import coop.rchain.metrics.{Metrics, Span}
import coop.rchain.rspace.state.RSpaceStateManager
import coop.rchain.shared._

import scala.concurrent.duration._

class GenesisCeremonyMaster[F[_]: Sync: BlockStore: CommUtil: TransportLayer: RPConfAsk: Log: Time: SafetyOracle: LastApprovedBlock](
    approveProtocol: ApproveBlockProtocol[F]
) extends Engine[F] {
  import Engine._
  private val F    = Applicative[F]
  private val noop = F.unit

  override def init: F[Unit] = approveProtocol.run()

  override def handle(peer: PeerNode, msg: CasperMessage): F[Unit] = msg match {
    case br: ApprovedBlockRequest     => sendNoApprovedBlockAvailable(peer, br.identifier)
    case ba: BlockApproval            => approveProtocol.addApproval(ba)
    case na: NoApprovedBlockAvailable => logNoApprovedBlockAvailable[F](na.nodeIdentifer)
    case _                            => noop
  }
}

// format: off
object GenesisCeremonyMaster {
  import Engine._
  def waitingForApprovedBlockLoop[F[_]
    /* Execution */   : Concurrent: Time
    /* Transport */   : TransportLayer: CommUtil: BlockRetriever: EventPublisher
    /* State */       : EngineCell: RPConfAsk: ConnectionsCell: LastApprovedBlock
    /* Rholang */     : RuntimeManager
    /* Casper */      : Estimator: SafetyOracle: LastFinalizedBlockCalculator: LastFinalizedHeightConstraintChecker: SynchronyConstraintChecker
    /* Storage */     : BlockStore: BlockDagStorage: LastFinalizedStorage: DeployStorage: CasperBufferStorage: RSpaceStateManager
    /* Diagnostics */ : Log: EventLog: Metrics: Span] // format: on
  (
      shardId: String,
      finalizationRate: Int,
      validatorId: Option[ValidatorIdentity],
      disableStateExporter: Boolean
  ): F[Unit] =
    for {
      // This loop sleep can be short as it does not do anything except checking if there is last approved block available
      _                  <- Time[F].sleep(2.seconds)
      lastApprovedBlockO <- LastApprovedBlock[F].get
      cont <- lastApprovedBlockO match {
               case None =>
                 waitingForApprovedBlockLoop[F](
                   shardId,
                   finalizationRate,
                   validatorId,
                   disableStateExporter
                 )
               case Some(approvedBlock) =>
                 val ab = approvedBlock.candidate.block
                 for {
                   _ <- insertIntoBlockAndDagStore[F](ab, approvedBlock)
                   casper <- MultiParentCasper
                              .hashSetCasper[F](
                                validatorId,
                                ab,
                                shardId,
                                finalizationRate
                              )
                   _ <- Engine
                         .transitionToRunning[F](
                           casper,
                           approvedBlock,
                           validatorId,
                           ().pure[F],
                           disableStateExporter
                         )
                   _ <- CommUtil[F].sendForkChoiceTipRequest
                 } yield ()
             }
    } yield cont
}
