package io.casperlabs.casper.validation

import cats.Applicative
import cats.implicits._
import cats.mtl.FunctorRaise
import com.google.protobuf.ByteString
import io.casperlabs.casper.Estimator.BlockHash
import io.casperlabs.casper._
import io.casperlabs.casper.consensus.{state, Block, BlockSummary, Bond}
import io.casperlabs.casper.equivocations.EquivocationsTracker
import io.casperlabs.casper.util.ProtoUtil.bonds
import io.casperlabs.casper.util.execengine.ExecEngineUtil
import io.casperlabs.casper.util.execengine.ExecEngineUtil.StateHash
import io.casperlabs.casper.util.{CasperLabsProtocolVersions, DagOperations, ProtoUtil}
import io.casperlabs.casper.validation.Errors._
import io.casperlabs.catscontrib.MonadThrowable
import io.casperlabs.crypto.Keys.{PublicKey, PublicKeyBS, Signature}
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.crypto.hash.Blake2b256
import io.casperlabs.crypto.signatures.SignatureAlgorithm
import io.casperlabs.ipc
import io.casperlabs.shared._
import io.casperlabs.smartcontracts.ExecutionEngineService
import io.casperlabs.storage.block.BlockStorage
import io.casperlabs.storage.dag.DagRepresentation

import scala.concurrent.duration.{FiniteDuration, MILLISECONDS}
import scala.util.{Success, Try}

object ValidationImpl {
  type Data        = Array[Byte]
  type BlockHeight = Long

  val DRIFT = 15000 // 15 seconds

  // TODO: put in chainspec https://casperlabs.atlassian.net/browse/NODE-911
  val MAX_TTL: Int          = 24 * 60 * 60 * 1000 // 1 day
  val MIN_TTL: Int          = 60 * 60 * 1000 // 1 hour
  val MAX_DEPENDENCIES: Int = 10

  def apply[F[_]](implicit ev: ValidationImpl[F]) = ev
}

class ValidationImpl[F[_]: MonadThrowable: FunctorRaise[?[_], InvalidBlock]: Log: Time]
    extends Validation[F] {
  import ValidationImpl.DRIFT
  import ValidationImpl.MAX_TTL
  import ValidationImpl.MIN_TTL
  import ValidationImpl.MAX_DEPENDENCIES
  import io.casperlabs.models.BlockImplicits._

  type Data        = Array[Byte]
  type BlockHeight = Long

  private implicit val logSource: LogSource = LogSource(this.getClass)

  private def checkDroppable(checks: F[Boolean]*): F[Unit] =
    checks.toList.sequence
      .map(_.forall(identity))
      .ifM(
        ().pure[F],
        MonadThrowable[F]
          .raiseError[Unit](DropErrorWrapper(InvalidUnslashableBlock))
      )

  def signatureVerifiers(sigAlgorithm: String): Option[(Data, Signature, PublicKey) => Boolean] =
    sigAlgorithm match {
      case SignatureAlgorithm(sa) => Some((data, sig, pub) => sa.verify(data, sig, pub))
      case _                      => None
    }

  def signature(d: Data, sig: consensus.Signature, key: PublicKey): Boolean =
    signatureVerifiers(sig.sigAlgorithm).fold(false) { verify =>
      verify(d, Signature(sig.sig.toByteArray), key)
    }

  /** Validate just the BlockSummary, assuming we don't have the block yet, or all its dependencies.
    * We can check that all the fields are present, the signature is fine, etc.
    * We'll need the full body to validate the block properly but this preliminary check can prevent
    * obviously corrupt data from being downloaded. */
  def blockSummary(
      summary: BlockSummary,
      chainId: String
  )(implicit versions: CasperLabsProtocolVersions[F]): F[Unit] = {
    val treatAsGenesis = summary.isGenesisLike
    for {
      _ <- checkDroppable(
            formatOfFields(summary, treatAsGenesis),
            version(
              summary,
              CasperLabsProtocolVersions[F].versionAt(_)
            ),
            if (!treatAsGenesis) blockSignature(summary) else true.pure[F]
          )
      _ <- summaryHash(summary)
      _ <- chainIdentifier(summary, chainId)
      _ <- ballot(summary)
    } yield ()
  }

  /** Check the block without executing deploys. */
  def blockFull(
      block: Block,
      dag: DagRepresentation[F],
      chainId: String,
      maybeGenesis: Option[Block]
  )(implicit bs: BlockStorage[F], versions: CasperLabsProtocolVersions[F]): F[Unit] = {
    val summary = BlockSummary(block.blockHash, block.header, block.signature)
    for {
      _ <- checkDroppable(
            if (block.body.isEmpty)
              Log[F].warn(ignore(block, s"block body is missing.")) *> false.pure[F]
            else true.pure[F],
            // Validate that the sender is a bonded validator.
            maybeGenesis.fold(summary.isGenesisLike.pure[F]) { _ =>
              blockSender(summary)
            }
          )
      _ <- blockSummary(summary, chainId)
      // Checks that need dependencies.
      _ <- missingBlocks(summary)
      _ <- timestamp(summary)
      _ <- blockNumber(summary, dag)
      _ <- sequenceNumber(summary, dag)
      // Checks that need the body.
      _ <- blockHash(block)
      _ <- deployCount(block)
      _ <- deployHashes(block)
      _ <- deploySignatures(block)
      _ <- deployUniqueness(block, dag)
    } yield ()
  }

  def blockSignature(b: BlockSummary): F[Boolean] =
    signatureVerifiers(b.getSignature.sigAlgorithm) map { verify =>
      Try(
        verify(
          b.blockHash.toByteArray,
          Signature(b.getSignature.sig.toByteArray),
          PublicKey(b.validatorPublicKey.toByteArray)
        )
      ) match {
        case Success(true) => true.pure[F]
        case _             => Log[F].warn(ignore(b, "signature is invalid.")).map(_ => false)
      }
    } getOrElse {
      for {
        _ <- Log[F].warn(
              ignore(b, s"signature algorithm '${b.getSignature.sigAlgorithm}' is unsupported.")
            )
      } yield false
    }

  def deploySignature(d: consensus.Deploy): F[Boolean] =
    if (d.approvals.isEmpty) {
      Log[F].warn(
        s"Deploy ${PrettyPrinter.buildString(d.deployHash)} has no signatures."
      ) *> false.pure[F]
    } else {
      d.approvals.toList
        .traverse { a =>
          signatureVerifiers(a.getSignature.sigAlgorithm)
            .map { verify =>
              Try {
                verify(
                  d.deployHash.toByteArray,
                  Signature(a.getSignature.sig.toByteArray),
                  PublicKey(a.approverPublicKey.toByteArray)
                )
              } match {
                case Success(true) =>
                  true.pure[F]
                case _ =>
                  Log[F].warn(
                    s"Signature of deploy ${PrettyPrinter.buildString(d.deployHash)} is invalid."
                  ) *> false.pure[F]
              }
            } getOrElse {
            Log[F].warn(
              s"Signature algorithm ${a.getSignature.sigAlgorithm} of deploy ${PrettyPrinter
                .buildString(d.deployHash)} is unsupported."
            ) *> false.pure[F]
          }
        }
        .map(_.forall(identity))
    }

  private def validateTimeToLive(
      ttl: Int,
      deployHash: ByteString
  ): F[Option[Errors.DeployHeaderError]] =
    if (ttl < MIN_TTL)
      Errors.DeployHeaderError.timeToLiveTooShort(deployHash, ttl, MIN_TTL).logged[F].map(_.some)
    else if (ttl > MAX_TTL)
      Errors.DeployHeaderError.timeToLiveTooLong(deployHash, ttl, MAX_TTL).logged[F].map(_.some)
    else
      none[Errors.DeployHeaderError].pure[F]

  private def validateDependencies(
      dependencies: Seq[ByteString],
      deployHash: ByteString
  ): F[List[Errors.DeployHeaderError]] = {
    val numDependencies = dependencies.length
    val tooMany =
      if (numDependencies > MAX_DEPENDENCIES)
        Errors.DeployHeaderError
          .tooManyDependencies(deployHash, numDependencies, MAX_DEPENDENCIES)
          .logged[F]
          .map(_.some)
      else
        none[Errors.DeployHeaderError].pure[F]

    val invalid = dependencies.toList
      .filter(_.size != 32)
      .traverse(dep => Errors.DeployHeaderError.invalidDependency(deployHash, dep).logged[F])

    Applicative[F].map2(tooMany, invalid)(_.toList ::: _)
  }

  def deployHeader(d: consensus.Deploy): F[List[Errors.DeployHeaderError]] =
    d.header match {
      case Some(header) =>
        Applicative[F].map2(
          validateTimeToLive(ProtoUtil.getTimeToLive(header, MAX_TTL), d.deployHash),
          validateDependencies(header.dependencies, d.deployHash)
        ) {
          case (validTTL, validDependencies) => validTTL.toList ::: validDependencies
        }

      case None =>
        Errors.DeployHeaderError.MissingHeader(d.deployHash).logged[F].map(List(_))
    }

  def blockSender(block: BlockSummary)(implicit bs: BlockStorage[F]): F[Boolean] =
    for {
      weight <- ProtoUtil.weightFromSender[F](block.getHeader)
      result <- if (weight > 0) true.pure[F]
               else
                 for {
                   _ <- Log[F].warn(
                         ignore(
                           block,
                           s"block creator ${PrettyPrinter.buildString(block.validatorPublicKey)} has 0 weight."
                         )
                       )
                 } yield false
    } yield result

  def formatOfFields(
      b: BlockSummary,
      treatAsGenesis: Boolean = false
  ): F[Boolean] =
    if (b.blockHash.isEmpty) {
      for {
        _ <- Log[F].warn(ignore(b, s"block hash is empty."))
      } yield false
    } else if (b.header.isEmpty) {
      for {
        _ <- Log[F].warn(ignore(b, s"block header is missing."))
      } yield false
    } else if (b.getSignature.sig.isEmpty && !treatAsGenesis) {
      for {
        _ <- Log[F].warn(ignore(b, s"block signature is empty."))
      } yield false
    } else if (!b.getSignature.sig.isEmpty && treatAsGenesis) {
      for {
        _ <- Log[F].warn(ignore(b, s"block signature is not empty on Genesis."))
      } yield false
    } else if (b.getSignature.sigAlgorithm.isEmpty && !treatAsGenesis) {
      for {
        _ <- Log[F].warn(ignore(b, s"block signature algorithm is not empty on Genesis."))
      } yield false
    } else if (!b.getSignature.sigAlgorithm.isEmpty && treatAsGenesis) {
      for {
        _ <- Log[F].warn(ignore(b, s"block signature algorithm is empty."))
      } yield false
    } else if (b.chainId.isEmpty) {
      for {
        _ <- Log[F].warn(ignore(b, s"block chain identifier is empty."))
      } yield false
    } else if (b.state.postStateHash.isEmpty) {
      for {
        _ <- Log[F].warn(ignore(b, s"block post state hash is empty."))
      } yield false
    } else if (b.bodyHash.isEmpty) {
      for {
        _ <- Log[F].warn(ignore(b, s"block new code hash is empty."))
      } yield false
    } else {
      true.pure[F]
    }

  // Validates whether block was built using correct protocol version.
  def version(
      b: BlockSummary,
      m: BlockHeight => F[state.ProtocolVersion]
  ): F[Boolean] = {
    val blockVersion = b.protocolVersion
    val blockHeight  = b.rank
    m(blockHeight).map(_.value).flatMap { version =>
      if (blockVersion == version) {
        true.pure[F]
      } else {
        Log[F].warn(
          ignore(
            b,
            s"Received block version $blockVersion, expected version $version."
          )
        ) *> false.pure[F]
      }
    }
  }

  /**
    * Works with either efficient justifications or full explicit justifications
    */
  def missingBlocks(
      block: BlockSummary
  )(implicit bs: BlockStorage[F]): F[Unit] =
    for {
      parentsPresent <- block.parentHashes.toList
                         .forallM(p => BlockStorage[F].contains(p))
      justificationsPresent <- block.justifications.toList
                                .forallM(j => BlockStorage[F].contains(j.latestBlockHash))
      _ <- FunctorRaise[F, InvalidBlock]
            .raise[Unit](MissingBlocks)
            .whenA(!parentsPresent || !justificationsPresent)
    } yield ()

  // This is not a slashable offence
  def timestamp(
      b: BlockSummary
  )(implicit bs: BlockStorage[F]): F[Unit] =
    for {
      currentTime  <- Time[F].currentMillis
      timestamp    = b.timestamp
      beforeFuture = currentTime + ValidationImpl.DRIFT >= timestamp
      latestParentTimestamp <- b.parentHashes.toList.foldM(0L) {
                                case (latestTimestamp, parentHash) =>
                                  ProtoUtil
                                    .unsafeGetBlockSummary[F](parentHash)
                                    .map(parent => {
                                      val timestamp =
                                        parent.header.fold(latestTimestamp)(_.timestamp)
                                      math.max(latestTimestamp, timestamp)
                                    })
                              }
      afterLatestParent = timestamp >= latestParentTimestamp
      _ <- if (beforeFuture && afterLatestParent) {
            Applicative[F].unit
          } else {
            for {
              _ <- Log[F].warn(
                    ignore(
                      b,
                      s"block timestamp $timestamp is not between latest parent block time and current time."
                    )
                  )
              _ <- FunctorRaise[F, InvalidBlock].raise[Unit](InvalidUnslashableBlock)
            } yield ()
          }
    } yield ()

  /* If we receive block from future then we may fail to propose new block on top of it because of Validation.timestamp */
  def preTimestamp(
      b: Block
  ): F[Option[FiniteDuration]] =
    for {
      currentMillis <- Time[F].currentMillis
      delay <- b.timestamp - currentMillis match {
                case n if n <= 0     => none[FiniteDuration].pure[F]
                case n if n <= DRIFT =>
                  // Sleep for a little bit more time to ensure we won't propose block on top of block from future
                  FiniteDuration(n + 500, MILLISECONDS).some.pure[F]
                case _ =>
                  RaiseValidationError[F].raise[Option[FiniteDuration]](InvalidUnslashableBlock)
              }
    } yield delay

  // Block number is 1 plus the maximum of block number of its justifications.
  def blockNumber(
      b: BlockSummary,
      dag: DagRepresentation[F]
  ): F[Unit] =
    for {
      justificationMsgs <- b.justifications.toList.traverse { justification =>
                            dag.lookup(justification.latestBlockHash).flatMap {
                              MonadThrowable[F].fromOption(
                                _,
                                new Exception(
                                  s"Block dag store was missing ${PrettyPrinter.buildString(justification.latestBlockHash)}."
                                )
                              )
                            }
                          }
      calculatedRank = ProtoUtil.nextRank(justificationMsgs)
      actuallyRank   = b.rank
      result         = calculatedRank == actuallyRank
      _ <- if (result) {
            Applicative[F].unit
          } else {
            val logMessage =
              if (justificationMsgs.isEmpty)
                s"block number $actuallyRank is not zero, but block has no justifications."
              else
                s"block number $actuallyRank is not the maximum block number of justifications plus 1, i.e. $calculatedRank."
            for {
              _ <- Log[F].warn(ignore(b, logMessage))
              _ <- FunctorRaise[F, InvalidBlock].raise[Unit](InvalidBlockNumber)
            } yield ()
          }
    } yield ()

  // Validates that a message that is supposed to be a ballot adheres to ballot's specification.
  private def ballot(b: BlockSummary): F[Unit] =
    FunctorRaise[F, InvalidBlock]
      .raise[Unit](InvalidTargetHash)
      .whenA(b.getHeader.messageType.isBallot && b.getHeader.parentHashes.size != 1)

  /**
    * Works with either efficient justifications or full explicit justifications.
    * Specifically, with efficient justifications, if a block B doesn't update its
    * creator justification, this check will fail as expected. The exception is when
    * B's creator justification is the genesis block.
    */
  def sequenceNumber(
      b: BlockSummary,
      dag: DagRepresentation[F]
  ): F[Unit] =
    if (b.isGenesisLike)
      FunctorRaise[F, InvalidBlock]
        .raise[Unit](InvalidSequenceNumber)
        .whenA(b.validatorBlockSeqNum != 0)
    else
      for {
        creatorJustificationSeqNumber <- ProtoUtil.nextValidatorBlockSeqNum(
                                          dag,
                                          b.getHeader.justifications,
                                          b.getHeader.validatorPublicKey
                                        )
        number = b.validatorBlockSeqNum
        ok     = creatorJustificationSeqNumber == number
        _ <- if (ok) {
              Applicative[F].unit
            } else {
              for {
                _ <- Log[F].warn(
                      ignore(
                        b,
                        s"seq number $number is not one more than creator justification number $creatorJustificationSeqNumber."
                      )
                    )
                _ <- FunctorRaise[F, InvalidBlock].raise[Unit](InvalidSequenceNumber)
              } yield ()
            }
      } yield ()

  // Agnostic of justifications
  def chainIdentifier(
      b: BlockSummary,
      chainId: String
  ): F[Unit] =
    if (b.chainId == chainId) {
      Applicative[F].unit
    } else {
      for {
        _ <- Log[F].warn(
              ignore(b, s"got chain identifier ${b.chainId} while $chainId was expected.")
            )
        _ <- FunctorRaise[F, InvalidBlock].raise[Unit](InvalidChainId)
      } yield ()
    }

  def deployHash(d: consensus.Deploy): F[Boolean] = {
    val bodyHash   = ProtoUtil.protoHash(d.getBody)
    val deployHash = ProtoUtil.protoHash(d.getHeader)
    val ok         = bodyHash == d.getHeader.bodyHash && deployHash == d.deployHash

    def logDiff = {
      // Print the full length, maybe the client has configured their hasher to output 64 bytes.
      def b16(bytes: ByteString) = Base16.encode(bytes.toByteArray)
      for {
        _ <- Log[F]
              .warn(
                s"Invalid deploy body hash; got ${b16(d.getHeader.bodyHash)}, expected ${b16(bodyHash)}"
              )
        _ <- Log[F]
              .warn(s"Invalid deploy hash; got ${b16(d.deployHash)}, expected ${b16(deployHash)}")
      } yield ()
    }

    logDiff.whenA(!ok).as(ok)
  }

  def blockHash(
      b: Block
  ): F[Unit] = {
    val blockHashComputed = ProtoUtil.protoHash(b.getHeader)
    val bodyHashComputed  = ProtoUtil.protoHash(b.getBody)

    if (b.blockHash == blockHashComputed &&
        b.bodyHash == bodyHashComputed) {
      Applicative[F].unit
    } else {
      def show(hash: ByteString) = PrettyPrinter.buildString(hash)
      for {
        _ <- Log[F].warn(ignore(b, s"block hash does not match to computed value."))
        _ <- Log[F]
              .warn(
                s"Expected block hash ${show(blockHashComputed)}; got ${show(b.blockHash)}"
              )
              .whenA(b.blockHash != blockHashComputed)
        _ <- Log[F]
              .warn(
                s"Expected body hash ${show(bodyHashComputed)}; got ${show(b.bodyHash)}"
              )
              .whenA(b.bodyHash != bodyHashComputed)
        _ <- FunctorRaise[F, InvalidBlock].raise[Unit](InvalidBlockHash)
      } yield ()
    }
  }

  def summaryHash(
      b: BlockSummary
  ): F[Unit] = {
    val blockHashComputed = ProtoUtil.protoHash(b.getHeader)
    val ok                = b.blockHash == blockHashComputed
    (Log[F].warn(s"Invalid block hash ${PrettyPrinter.buildString(b.blockHash)}") *>
      FunctorRaise[F, InvalidBlock].raise[Unit](InvalidBlockHash)).whenA(!ok)
  }

  def deployCount(
      b: Block
  ): F[Unit] =
    if (b.deployCount == b.getBody.deploys.length) {
      Applicative[F].unit
    } else {
      for {
        _ <- Log[F].warn(ignore(b, s"block deploy count does not match to the amount of deploys."))
        _ <- FunctorRaise[F, InvalidBlock].raise[Unit](InvalidDeployCount)
      } yield ()
    }

  def deployHashes(
      b: Block
  ): F[Unit] =
    b.getBody.deploys.toList.findM(d => deployHash(d.getDeploy).map(!_)).flatMap {
      case None =>
        Applicative[F].unit
      case Some(d) =>
        for {
          _ <- Log[F]
                .warn(ignore(b, s"${PrettyPrinter.buildString(d.getDeploy)} has invalid hash."))
          _ <- FunctorRaise[F, InvalidBlock].raise[Unit](InvalidDeployHash)
        } yield ()
    }

  def deploySignatures(
      b: Block
  ): F[Unit] =
    b.getBody.deploys.toList
      .findM(d => deploySignature(d.getDeploy).map(!_))
      .flatMap {
        case None =>
          Applicative[F].unit
        case Some(d) =>
          for {
            _ <- Log[F]
                  .warn(
                    ignore(b, s"${PrettyPrinter.buildString(d.getDeploy)} has invalid signature.")
                  )
            _ <- FunctorRaise[F, InvalidBlock].raise[Unit](InvalidDeploySignature)
          } yield ()
      }
      .whenA(!b.isGenesisLike)

  /**
    * Checks that the parents of `b` were chosen correctly according to the
    * forkchoice rule. This is done by using the justifications of `b` as the
    * set of latest messages, so the justifications must be fully explicit.
    * For multi-parent blocks this requires doing commutativity checking, so
    * the combined effect of all parents except the first (i.e. the effect
    * which would need to be applied to the first parent's post-state to
    * obtain the pre-state of `b`) is given as the return value in order to
    * avoid repeating work downstream.
    */
  def parents(
      b: Block,
      genesisHash: BlockHash,
      dag: DagRepresentation[F],
      equivocationsTracker: EquivocationsTracker
  )(
      implicit bs: BlockStorage[F]
  ): F[ExecEngineUtil.MergeResult[ExecEngineUtil.TransformMap, Block]] = {
    def printHashes(hashes: Iterable[ByteString]) =
      hashes.map(PrettyPrinter.buildString).mkString("[", ", ", "]")

    val latestMessagesHashes = ProtoUtil
      .getJustificationMsgHashes(b.getHeader.justifications)

    for {
      tipHashes            <- Estimator.tips[F](dag, genesisHash, latestMessagesHashes, equivocationsTracker)
      _                    <- Log[F].debug(s"Estimated tips are ${printHashes(tipHashes)}")
      tips                 <- tipHashes.toVector.traverse(ProtoUtil.unsafeGetBlock[F])
      merged               <- ExecEngineUtil.merge[F](tips, dag)
      computedParentHashes = merged.parents.map(_.blockHash)
      parentHashes         = ProtoUtil.parentHashes(b)
      _ <- if (parentHashes.isEmpty)
            FunctorRaise[F, InvalidBlock].raise[Unit](InvalidParents)
          else if (parentHashes == computedParentHashes)
            Applicative[F].unit
          else {
            val parentsString =
              parentHashes.map(hash => PrettyPrinter.buildString(hash)).mkString(",")
            val estimateString =
              computedParentHashes.map(hash => PrettyPrinter.buildString(hash)).mkString(",")
            val justificationString = latestMessagesHashes.values
              .map(hashes => hashes.map(PrettyPrinter.buildString).mkString("[", ",", "]"))
              .mkString(",")
            val message =
              s"block parents ${parentsString} did not match estimate ${estimateString} based on justification ${justificationString}."
            for {
              _ <- Log[F].warn(
                    ignore(
                      b,
                      message
                    )
                  )
              _ <- FunctorRaise[F, InvalidBlock].raise[Unit](InvalidParents)
            } yield ()
          }
    } yield merged
  }

  // Validates whether received block is valid (according to that nodes logic):
  // 1) Validates whether pre state hashes match
  // 2) Runs deploys from the block
  // 3) Validates whether post state hashes match
  // 4) Validates whether bonded validators, as at the end of executing the block, match.
  def transactions(
      block: Block,
      preStateHash: StateHash,
      effects: Seq[ipc.TransformEntry]
  )(implicit ee: ExecutionEngineService[F]): F[Unit] = {
    val blockPreState  = ProtoUtil.preStateHash(block)
    val blockPostState = ProtoUtil.postStateHash(block)
    if (preStateHash == blockPreState) {
      for {
        possibleCommitResult <- ExecutionEngineService[F].commit(
                                 preStateHash,
                                 effects
                               )
        //TODO: distinguish "internal errors" and "user errors"
        _ <- possibleCommitResult match {
              case Left(ex) =>
                Log[F].error(
                  s"Could not commit effects of block ${PrettyPrinter.buildString(block)}: $ex",
                  ex
                ) *>
                  FunctorRaise[F, InvalidBlock].raise[Unit](InvalidTransaction)
              case Right(commitResult) =>
                for {
                  _ <- FunctorRaise[F, InvalidBlock]
                        .raise[Unit](InvalidPostStateHash)
                        .whenA(commitResult.postStateHash != blockPostState)
                  _ <- bondsCache(block, commitResult.bondedValidators)
                } yield ()
            }
      } yield ()
    } else {
      FunctorRaise[F, InvalidBlock].raise[Unit](InvalidPreStateHash)
    }
  }

  /**
    * If block contains an invalid justification block B and the creator of B is still bonded,
    * return a RejectableBlock. Otherwise return an IncludeableBlock.
    */
  def neglectedInvalidBlock(
      block: Block,
      invalidBlockTracker: Set[BlockHash]
  ): F[Unit] = {
    val invalidJustifications = block.justifications.filter(
      justification => invalidBlockTracker.contains(justification.latestBlockHash)
    )
    val neglectedInvalidJustification = invalidJustifications.exists { justification =>
      val slashedValidatorBond =
        bonds(block).find(_.validatorPublicKey == justification.validatorPublicKey)
      slashedValidatorBond match {
        case Some(bond) => bond.stake > 0
        case None       => false
      }
    }
    if (neglectedInvalidJustification) {
      for {
        _ <- Log[F].warn("Neglected invalid justification.")
        _ <- FunctorRaise[F, InvalidBlock].raise[Unit](NeglectedInvalidBlock)
      } yield ()
    } else {
      Applicative[F].unit
    }
  }

  def bondsCache(
      b: Block,
      computedBonds: Seq[Bond]
  ): F[Unit] = {
    val bonds = ProtoUtil.bonds(b)
    ProtoUtil.postStateHash(b) match {
      case globalStateRootHash if !globalStateRootHash.isEmpty =>
        if (bonds.toSet == computedBonds.toSet) {
          Applicative[F].unit
        } else {
          for {
            _ <- Log[F].warn(
                  "Bonds in proof of stake contract do not match block's bond cache."
                )
            _ <- FunctorRaise[F, InvalidBlock].raise[Unit](InvalidBondsCache)
          } yield ()
        }
      case _ =>
        for {
          _ <- Log[F].warn(s"Block ${PrettyPrinter.buildString(b)} is missing a post state hash.")
          _ <- FunctorRaise[F, InvalidBlock].raise[Unit](InvalidBondsCache)
        } yield ()
    }
  }

  /** Check that none of the deploys in the block have been included in another block already
    * which was in the P-past cone of the block itself.
    */
  def deployUniqueness(
      block: Block,
      dag: DagRepresentation[F]
  )(implicit bs: BlockStorage[F]): F[Unit] = {
    val deploys        = block.getBody.deploys.map(_.getDeploy).toList
    val maybeDuplicate = deploys.groupBy(_.deployHash).find(_._2.size > 1).map(_._2.head)
    def raise(msg: String) =
      for {
        _ <- Log[F].warn(ignore(block, msg))
        _ <- FunctorRaise[F, InvalidBlock].raise[Unit](InvalidRepeatDeploy)
      } yield ()
    maybeDuplicate match {
      case Some(duplicate) =>
        raise(s"block contains duplicate ${PrettyPrinter.buildString(duplicate)}")

      case None =>
        for {
          deployToBlocksMap <- deploys
                                .traverse { deploy =>
                                  bs.findBlockHashesWithDeployhash(deploy.deployHash).map {
                                    blockHashes =>
                                      deploy -> blockHashes.filterNot(_ == block.blockHash)
                                  }
                                }
                                .map(_.toMap)

          blockHashes = deployToBlocksMap.values.flatten.toSet

          duplicateBlockHashes <- DagOperations.collectWhereDescendantPathExists(
                                   dag,
                                   blockHashes,
                                   Set(block.blockHash)
                                 )

          _ <- if (duplicateBlockHashes.isEmpty) ().pure[F]
              else {
                val exampleBlockHash = duplicateBlockHashes.head
                val exampleDeploy = deployToBlocksMap.collectFirst {
                  case (deploy, blockHashes) if blockHashes.contains(exampleBlockHash) =>
                    deploy
                }.get
                raise(
                  s"block contains a duplicate ${PrettyPrinter.buildString(exampleDeploy)} already present in ${PrettyPrinter
                    .buildString(exampleBlockHash)}"
                )
              }

        } yield ()
    }
  }
}
