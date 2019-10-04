package io.casperlabs.casper.util

import java.util.NoSuchElementException

import cats.{Monad, MonoidK}
import cats.implicits._
import cats.kernel.Monoid
import com.google.protobuf.ByteString
import io.casperlabs.casper.Estimator.{BlockHash, Validator}
import io.casperlabs.casper.consensus.Block.{GlobalState, Justification, MessageType}
import io.casperlabs.casper.consensus.state.ProtocolVersion
import io.casperlabs.casper.consensus.{BlockSummary, _}
import io.casperlabs.casper.{PrettyPrinter, ValidatorIdentity}
import io.casperlabs.catscontrib.MonadThrowable
import io.casperlabs.crypto.Keys
import io.casperlabs.crypto.Keys.PrivateKey
import io.casperlabs.crypto.codec.Base16
import io.casperlabs.crypto.hash.Blake2b256
import io.casperlabs.crypto.signatures.SignatureAlgorithm
import io.casperlabs.ipc
import io.casperlabs.models.BlockImplicits._
import io.casperlabs.models.Message
import io.casperlabs.shared.Time
import io.casperlabs.smartcontracts.Abi
import io.casperlabs.storage.block.BlockStorage
import io.casperlabs.storage.dag.DagRepresentation

import scala.collection.immutable

object ProtoUtil {
  /*
   * c is in the blockchain of b iff c == b or c is in the blockchain of the main parent of b
   */
  // TODO: Move into DAG and remove corresponding param once that is moved over from simulator
  def isInMainChain[F[_]: Monad](
      dag: DagRepresentation[F],
      candidateBlockSummary: Message.Block,
      targetBlockHash: BlockHash
  ): F[Boolean] =
    if (candidateBlockSummary.messageHash == targetBlockHash) {
      true.pure[F]
    } else {
      for {
        targetBlockOpt <- dag.lookup(targetBlockHash)
        result <- targetBlockOpt match {
                   case Some(targetBlockMeta) =>
                     if (targetBlockMeta.rank <= candidateBlockSummary.rank)
                       false.pure[F]
                     else {
                       targetBlockMeta.parents.headOption match {
                         case Some(mainParentHash) =>
                           isInMainChain(dag, candidateBlockSummary, mainParentHash)
                         case None => false.pure[F]
                       }
                     }
                   case None => false.pure[F]
                 }
      } yield result
    }

  // If targetBlockHash is main descendant of candidateBlockHash, then
  // it means targetBlockHash vote candidateBlockHash.
  def isInMainChain[F[_]: Monad](
      dag: DagRepresentation[F],
      candidateBlockHash: BlockHash,
      targetBlockHash: BlockHash
  ): F[Boolean] =
    for {
      messageSummary <- dag.lookup(candidateBlockHash).map(_.get)
      result <- messageSummary match {
                 // Ballot is never in a main-chain because it's not a block and main-chain
                 // is a sub-DAG of a p-DAG.
                 case _: Message.Ballot => false.pure[F]
                 case b: Message.Block  => isInMainChain(dag, b, targetBlockHash)
               }
    } yield result

  // calculate which branch of latestFinalizedBlockHash that the newBlockHash vote for
  def votedBranch[F[_]: Monad](
      dag: DagRepresentation[F],
      latestFinalizeBlockHash: BlockHash,
      newBlockHash: BlockHash
  ): F[Option[BlockHash]] =
    for {
      newBlock             <- dag.lookup(newBlockHash)
      latestFinalizedBlock <- dag.lookup(latestFinalizeBlockHash)
      r                    <- votedBranch(dag, latestFinalizedBlock.get, newBlock.get)
    } yield r

  def votedBranch[F[_]: Monad](
      dag: DagRepresentation[F],
      latestFinalizedBlock: Message,
      newBlock: Message
  ): F[Option[BlockHash]] =
    if (newBlock.rank <= latestFinalizedBlock.rank) {
      none[BlockHash].pure[F]
    } else {
      for {
        result <- newBlock.parents.headOption match {
                   case Some(mainParentHash) =>
                     if (mainParentHash == latestFinalizedBlock.messageHash) {
                       newBlock.messageHash.some.pure[F]
                     } else {
                       dag
                         .lookup(mainParentHash)
                         .flatMap(b => votedBranch(dag, latestFinalizedBlock, b.get))
                     }
                   case None => none[BlockHash].pure[F]
                 }
      } yield result
    }

  def getMainChainUntilDepth[F[_]: MonadThrowable: BlockStorage](
      estimate: Block,
      acc: IndexedSeq[Block],
      depth: Int
  ): F[IndexedSeq[Block]] = {
    val parentsHashes       = ProtoUtil.parentHashes(estimate)
    val maybeMainParentHash = parentsHashes.headOption
    for {
      mainChain <- maybeMainParentHash match {
                    case Some(mainParentHash) =>
                      for {
                        updatedEstimate <- unsafeGetBlock[F](mainParentHash)
                        depthDelta      = blockNumber(updatedEstimate) - blockNumber(estimate)
                        newDepth        = depth + depthDelta.toInt
                        mainChain <- if (newDepth <= 0) {
                                      (acc :+ estimate).pure[F]
                                    } else {
                                      getMainChainUntilDepth[F](
                                        updatedEstimate,
                                        acc :+ estimate,
                                        newDepth
                                      )
                                    }
                      } yield mainChain
                    case None => (acc :+ estimate).pure[F]
                  }
    } yield mainChain
  }

  def unsafeGetBlockSummary[F[_]: MonadThrowable: BlockStorage](hash: BlockHash): F[BlockSummary] =
    for {
      maybeBlock <- BlockStorage[F].getBlockSummary(hash)
      block <- maybeBlock match {
                case Some(b) => b.pure[F]
                case None =>
                  MonadThrowable[F].raiseError(
                    new NoSuchElementException(
                      s"BlockStorage is missing hash ${PrettyPrinter.buildString(hash)}"
                    )
                  )
              }
    } yield block

  def unsafeGetBlock[F[_]: MonadThrowable: BlockStorage](hash: BlockHash): F[Block] =
    for {
      maybeBlock <- BlockStorage[F].getBlockMessage(hash)
      block <- maybeBlock match {
                case Some(b) => b.pure[F]
                case None =>
                  MonadThrowable[F].raiseError(
                    new NoSuchElementException(
                      s"BlockStorage is missing hash ${PrettyPrinter.buildString(hash)}"
                    )
                  )
              }
    } yield block

  def nextRank(justificationMsgs: Seq[Message]): Long =
    1L + justificationMsgs.foldLeft(-1L) {
      case (acc, msgSummary) => math.max(acc, msgSummary.rank)
    }

  def nextValidatorBlockSeqNum[F[_]: MonadThrowable](
      dag: DagRepresentation[F],
      justifications: Seq[Justification],
      creator: Validator
  ): F[Int] =
    justifications
      .find {
        case Justification(validator: Validator, _) =>
          validator == creator
      }
      .foldM(0) {
        case (_, Justification(_, latestBlockHash)) =>
          dag.lookup(latestBlockHash).flatMap {
            case Some(meta) =>
              meta.validatorMsgSeqNum.pure[F]

            case None =>
              MonadThrowable[F].raiseError[Int](
                new NoSuchElementException(
                  s"DagStorage is missing hash ${PrettyPrinter.buildString(latestBlockHash)}"
                )
              )
          }
      }
      .map(_ + 1)

  def creatorJustification(header: Block.Header): Option[Justification] =
    header.justifications
      .find {
        case Justification(validator: Validator, _) =>
          validator == header.validatorPublicKey
      }

  def weightMap(block: Block): Map[ByteString, Long] =
    weightMap(block.getHeader)

  def weightMap(header: Block.Header): Map[ByteString, Long] =
    header.getState.bonds.map {
      case Bond(validator, stake) => validator -> stake
    }.toMap

  def weightMapTotal(weights: Map[ByteString, Long]): Long =
    weights.values.sum

  private def mainParent[F[_]: Monad: BlockStorage](
      header: Block.Header
  ): F[Option[BlockSummary]] = {
    val maybeParentHash = header.parentHashes.headOption
    maybeParentHash match {
      case Some(parentHash) => BlockStorage[F].getBlockSummary(parentHash)
      case None             => none[BlockSummary].pure[F]
    }
  }

  /** Computes block's score by the [validator].
    *
    * @param dag
    * @param blockHash Block's hash
    * @param validator Validator that produced the block
    * @tparam F
    * @return Weight `validator` put behind the block
    */
  def weightFromValidatorByDag[F[_]: MonadThrowable](
      dag: DagRepresentation[F],
      blockHash: BlockHash,
      validator: Validator
  ): F[Long] =
    mainParentWeightMap(dag, blockHash).map(_.getOrElse(validator, 0L))

  def weightFromValidator[F[_]: Monad: BlockStorage](
      header: Block.Header,
      validator: Validator
  ): F[Long] =
    for {
      maybeMainParent <- mainParent[F](header)
      weightFromValidator = maybeMainParent
        .map(_.weightMap.getOrElse(validator, 0L))
        .getOrElse(weightMap(header).getOrElse(validator, 0L)) //no parents means genesis -- use itself
    } yield weightFromValidator

  def weightFromValidator[F[_]: Monad: BlockStorage](
      b: Block,
      validator: ByteString
  ): F[Long] =
    weightFromValidator[F](b.getHeader, validator)

  def weightFromSender[F[_]: Monad: BlockStorage](b: Block): F[Long] =
    weightFromValidator[F](b, b.getHeader.validatorPublicKey)

  def weightFromSender[F[_]: Monad: BlockStorage](header: Block.Header): F[Long] =
    weightFromValidator[F](header, header.validatorPublicKey)

  def mainParentWeightMap[F[_]: MonadThrowable](
      dag: DagRepresentation[F],
      candidateBlockHash: BlockHash
  ): F[Map[BlockHash, Long]] =
    dag.lookup(candidateBlockHash).flatMap { messageOpt =>
      val message = messageOpt.get
      if (message.isGenesisLike) {

        /** We know that Gensis is of [[Message.Block]] type */
        message.asInstanceOf[Message.Block].weightMap.pure[F]
      } else {
        dag.lookup(message.parentBlock).flatMap {
          case Some(b: Message.Block) => b.weightMap.pure[F]
          case Some(b: Message.Ballot) =>
            MonadThrowable[F].raiseError[Map[ByteString, Long]](
              new IllegalArgumentException(
                s"A ballot ${PrettyPrinter.buildString(b.messageHash)} was a parent block for ${PrettyPrinter
                  .buildString(message.messageHash)}"
              )
            )
          // For some reason scalac produces a warning that we are missing a case
          // Some(x for x not in {Message.Block, Message.Ballot}), which is strange b/c such type doesn't exist.
          case Some(_) => ???
          case None =>
            MonadThrowable[F].raiseError[Map[ByteString, Long]](
              new IllegalArgumentException(
                s"Missing dependency ${PrettyPrinter.buildString(message.parentBlock)}"
              )
            )
        }
      }
    }

  def parentHashes(b: Block): Seq[ByteString] =
    b.getHeader.parentHashes

  def unsafeGetParents[F[_]: MonadThrowable: BlockStorage](b: Block): F[List[Block]] =
    ProtoUtil.parentHashes(b).toList.traverse { parentHash =>
      ProtoUtil.unsafeGetBlock[F](parentHash)
    } handleErrorWith {
      case ex: NoSuchElementException =>
        MonadThrowable[F].raiseError {
          new NoSuchElementException(
            s"Could not retrieve parents of ${PrettyPrinter.buildString(b.blockHash)}: ${ex.getMessage}"
          )
        }
    }

  def containsDeploy(b: Block, accountPublicKey: ByteString, timestamp: Long): Boolean =
    deploys(b).toStream
      .flatMap(_.deploy)
      .exists(
        d => d.getHeader.accountPublicKey == accountPublicKey && d.getHeader.timestamp == timestamp
      )

  def deploys(b: Block): Seq[Block.ProcessedDeploy] =
    b.getBody.deploys

  def postStateHash(b: Block): ByteString =
    b.getHeader.getState.postStateHash

  def preStateHash(b: Block): ByteString =
    b.getHeader.getState.preStateHash

  def bonds(b: Block): Seq[Bond] =
    b.getHeader.getState.bonds

  def bonds(b: BlockSummary): Seq[Bond] =
    b.getHeader.getState.bonds

  def blockNumber(b: Block): Long =
    b.getHeader.rank

  def toJustification(
      latestMessages: Seq[Message]
  ): Seq[Justification] =
    latestMessages.map { messageSummary =>
      Block
        .Justification()
        .withValidatorPublicKey(messageSummary.validatorId)
        .withLatestBlockHash(messageSummary.messageHash)
    }

  def getJustificationMsgHashes(
      justifications: Seq[Justification]
  ): immutable.Map[Validator, Set[BlockHash]] =
    justifications.foldLeft(Map.empty[Validator, Set[BlockHash]]) {
      case (acc, Justification(validator, block)) =>
        acc
          .get(validator)
          .fold(acc.updated(validator, Set(block)))(s => acc.updated(validator, s + block))
    }

  def getJustificationMsgs[F[_]: MonadThrowable](
      dag: DagRepresentation[F],
      justifications: Seq[Justification]
  ): F[Map[Validator, Set[Message]]] =
    justifications.toList.foldM(Map.empty[Validator, Set[Message]]) {
      case (acc, Justification(validator, hash)) =>
        dag.lookup(hash).flatMap {
          case Some(meta) =>
            acc.combine(Map(validator -> Set(meta))).pure[F]

          case None =>
            MonadThrowable[F].raiseError(
              new NoSuchElementException(
                s"DagStorage is missing hash ${PrettyPrinter.buildString(hash)}"
              )
            )
        }
    }

  def protoHash[A <: scalapb.GeneratedMessage](protoSeq: A*): ByteString =
    protoSeqHash(protoSeq)

  def protoSeqHash[A <: scalapb.GeneratedMessage](protoSeq: Seq[A]): ByteString =
    hashByteArrays(protoSeq.map(_.toByteArray): _*)

  def hashByteArrays(items: Array[Byte]*): ByteString =
    ByteString.copyFrom(Blake2b256.hash(Array.concat(items: _*)))

  /* Creates a Genesis block. Genesis is not signed */
  def genesis(
      preStateHash: ByteString,
      postStateHash: ByteString,
      bonds: Seq[Bond],
      chainId: String,
      protocolVersion: Long,
      now: Long
  ): Block = {
    val header = Block
      .Header()
      .withMessageType(MessageType.BLOCK)
      .withProtocolVersion(protocolVersion)
      .withTimestamp(now)
      .withChainId(chainId)
      .withState(
        GlobalState()
          .withPreStateHash(preStateHash)
          .withPostStateHash(postStateHash)
          .withBonds(bonds)
      )

    unsignedBlockProto(Block.Body(), header)
  }

  /* Creates a signed block */
  def block(
      justifications: Seq[Justification],
      preStateHash: ByteString,
      postStateHash: ByteString,
      bondedValidators: Seq[Bond],
      deploys: Seq[Block.ProcessedDeploy],
      protocolVersion: ProtocolVersion,
      parents: Seq[ByteString],
      validatorSeqNum: Int,
      chainId: String,
      now: Long,
      rank: Long,
      publicKey: Keys.PublicKey,
      privateKey: Keys.PrivateKey,
      sigAlgorithm: SignatureAlgorithm
  ): Block = {
    val body = Block.Body().withDeploys(deploys)
    val postState = Block
      .GlobalState()
      .withPreStateHash(preStateHash)
      .withPostStateHash(postStateHash)
      .withBonds(bondedValidators)

    val header = blockHeader(
      body,
      parentHashes = parents,
      justifications = justifications,
      state = postState,
      rank = rank,
      protocolVersion = protocolVersion.value,
      timestamp = now,
      chainId = chainId,
      creator = publicKey,
      validatorSeqNum = validatorSeqNum
    )

    val unsigned = unsignedBlockProto(body, header)
    signBlock(
      unsigned,
      privateKey,
      sigAlgorithm
    )
  }

  def blockHeader(
      body: Block.Body,
      creator: Keys.PublicKey,
      parentHashes: Seq[ByteString],
      justifications: Seq[Justification],
      state: Block.GlobalState,
      rank: Long,
      validatorSeqNum: Int,
      protocolVersion: Long,
      timestamp: Long,
      chainId: String
  ): Block.Header =
    Block
      .Header()
      .withParentHashes(parentHashes)
      .withJustifications(justifications)
      .withDeployCount(body.deploys.size)
      .withState(state)
      .withRank(rank)
      .withValidatorPublicKey(ByteString.copyFrom(creator))
      .withValidatorBlockSeqNum(validatorSeqNum)
      .withProtocolVersion(protocolVersion)
      .withTimestamp(timestamp)
      .withChainId(chainId)
      .withBodyHash(protoHash(body))

  def unsignedBlockProto(
      body: Block.Body,
      header: Block.Header
  ): Block = {
    val headerWithBodyHash = header
      .withBodyHash(protoHash(body))

    val blockHash = protoHash(headerWithBodyHash)

    Block()
      .withBlockHash(blockHash)
      .withHeader(headerWithBodyHash)
      .withBody(body)
  }

  def signBlock(
      block: Block,
      sk: PrivateKey,
      sigAlgorithm: SignatureAlgorithm
  ): Block = {
    val blockHash = protoHash(block.getHeader)
    val sig       = ByteString.copyFrom(sigAlgorithm.sign(blockHash.toByteArray, sk))
    block
      .withBlockHash(blockHash)
      .withSignature(
        Signature()
          .withSigAlgorithm(sigAlgorithm.name)
          .withSig(sig)
      )
  }

  def stringToByteString(string: String): ByteString =
    ByteString.copyFrom(Base16.decode(string))

  def getTimeToLive(h: Deploy.Header, default: Int): Int =
    if (h.ttlMillis == 0) default
    else h.ttlMillis

  def basicDeploy[F[_]: Monad: Time](): F[Deploy] =
    Time[F].currentMillis.map { now =>
      // The timestamp needs to be earlier than the time the node
      // thinks it is; in the tests we use "logical time", so 0
      // is the only safe value.
      basicDeploy(0, ByteString.copyFromUtf8(now.toString))
    }

  // This is only used for tests.
  def basicDeploy(
      timestamp: Long,
      sessionCode: ByteString = ByteString.EMPTY,
      accountPublicKey: ByteString = ByteString.EMPTY
  ): Deploy = {
    val b = Deploy
      .Body()
      .withSession(Deploy.Code().withWasm(sessionCode))
      .withPayment(Deploy.Code().withWasm(sessionCode))
    val h = Deploy
      .Header()
      .withAccountPublicKey(accountPublicKey)
      .withTimestamp(timestamp)
      .withBodyHash(protoHash(b))
    Deploy()
      .withDeployHash(protoHash(h))
      .withHeader(h)
      .withBody(b)
  }

  def basicProcessedDeploy[F[_]: Monad: Time](): F[Block.ProcessedDeploy] =
    basicDeploy[F]().map(deploy => Block.ProcessedDeploy(deploy = Some(deploy)))

  def sourceDeploy(source: String, timestamp: Long): Deploy =
    sourceDeploy(ByteString.copyFromUtf8(source), timestamp)

  def sourceDeploy(sessionCode: ByteString, timestamp: Long): Deploy =
    basicDeploy(timestamp, sessionCode)

  // https://casperlabs.atlassian.net/browse/EE-283
  // We are hardcoding exchange rate for DEV NET at 10:1
  // (1 gas costs you 10 motes).
  // Later, post DEV NET, conversion rate will be part of a deploy.
  val GAS_PRICE = 10L

  def deployDataToEEDeploy[F[_]: MonadThrowable](d: Deploy): F[ipc.DeployItem] = {
    def toPayload(maybeCode: Option[Deploy.Code]): F[Option[ipc.DeployPayload]] =
      maybeCode match {
        case None       => none[ipc.DeployPayload].pure[F]
        case Some(code) => (deployCodeToDeployPayload[F](code).map(Some(_)))
      }

    for {
      session <- toPayload(d.getBody.session)
      payment <- toPayload(d.getBody.payment)
    } yield {
      ipc.DeployItem(
        address = d.getHeader.accountPublicKey,
        session = session,
        payment = payment,
        gasPrice = GAS_PRICE,
        authorizationKeys = d.approvals.map(_.approverPublicKey),
        deployHash = d.deployHash
      )
    }
  }

  def deployCodeToDeployPayload[F[_]: MonadThrowable](code: Deploy.Code): F[ipc.DeployPayload] = {
    val argsF: F[ByteString] = if (code.args.nonEmpty) {
      MonadThrowable[F]
        .fromTry(Abi.args(code.args.map(_.getValue: Abi.Serializable[_]): _*))
        .map(ByteString.copyFrom(_))
    } else code.abiArgs.pure[F]

    argsF.map { args =>
      val payload = code.contract match {
        case Deploy.Code.Contract.Wasm(wasm) =>
          ipc.DeployPayload.Payload.DeployCode(ipc.DeployCode(wasm, args))
        case Deploy.Code.Contract.Hash(hash) =>
          ipc.DeployPayload.Payload.StoredContractHash(ipc.StoredContractHash(hash, args))
        case Deploy.Code.Contract.Name(name) =>
          ipc.DeployPayload.Payload.StoredContractName(ipc.StoredContractName(name, args))
        case Deploy.Code.Contract.Uref(uref) =>
          ipc.DeployPayload.Payload.StoredContractUref(ipc.StoredContractURef(uref, args))
        case Deploy.Code.Contract.Empty =>
          ipc.DeployPayload.Payload.Empty
      }
      ipc.DeployPayload(payload)
    }
  }

  def dependenciesHashesOf(b: Block): List[BlockHash] = {
    val missingParents = parentHashes(b).toSet
    val missingJustifications = b.getHeader.justifications
      .map(_.latestBlockHash)
      .toSet
    (missingParents union missingJustifications).toList
  }

  def createdBy(validatorId: ValidatorIdentity, block: Block): Boolean =
    block.getHeader.validatorPublicKey == ByteString.copyFrom(validatorId.publicKey)

  def randomAccountAddress(): ByteString =
    ByteString.copyFrom(scala.util.Random.nextString(32), "UTF-8")
}
