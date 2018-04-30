package coop.rchain.casper

import com.google.protobuf.ByteString
import coop.rchain.casper.internals._
import coop.rchain.casper.protocol.{Resource => ResourceProto, _}
import coop.rchain.casper.protocol.Resource.ResourceClass.ProduceResource
import coop.rchain.crypto.hash.Sha256

import scala.collection.immutable.{HashMap, HashSet}
import scala.language.higherKinds
import coop.rchain.catscontrib._
import Catscontrib._
import cats._
import cats.data._
import cats.implicits._
import coop.rchain.casper.Estimator.BlockHash

trait BlockGenerator {
  def createBlock[F[_]: Monad: ChainState](parentsHashList: Seq[ByteString]): F[BlockMessage] =
    createBlock[F](parentsHashList, ByteString.EMPTY)
  def createBlock[F[_]: Monad: ChainState](parentsHashList: Seq[ByteString],
                                           creator: ByteString): F[BlockMessage] =
    createBlock[F](parentsHashList, creator, Seq.empty[Bond])
  def createBlock[F[_]: Monad: ChainState](parentsHashList: Seq[ByteString],
                                           creator: ByteString,
                                           bonds: Seq[Bond]): F[BlockMessage] =
    createBlock[F](parentsHashList, creator, bonds, HashMap.empty[ByteString, ByteString])
  def createBlock[F[_]: Monad: ChainState](
      parentsHashList: Seq[ByteString],
      creator: ByteString,
      bonds: Seq[Bond],
      justifications: collection.Map[ByteString, ByteString]): F[BlockMessage] =
    for {
      chain          <- chainState[F].get
      nextId         = chain.currentId + 1
      uniqueResource = ResourceProto(ProduceResource(Produce(nextId)))
      postState      = RChainState().withResources(Seq(uniqueResource)).withBonds(bonds)
      postStateHash  = Sha256.hash(postState.toByteArray)
      header = Header()
        .withPostStateHash(ByteString.copyFrom(postStateHash))
        .withParentsHashList(parentsHashList)
      blockHash = Sha256.hash(header.toByteArray)
      body      = Body().withPostState(postState)
      serializedJustifications = justifications.toList.map {
        case (creator: ByteString, latestBlockHash: ByteString) =>
          Justification(creator, latestBlockHash)
      }
      serializedBlockHash = ByteString.copyFrom(blockHash)
      block = BlockMessage(serializedBlockHash,
                           Some(header),
                           Some(body),
                           serializedJustifications,
                           creator)
      idToBlocks  = chain.idToBlocks + (nextId               -> block)
      blockLookup = chain.blockLookup + (serializedBlockHash -> block)
      updatedChildren = parentsHashList.map { parentHash: BlockHash =>
        val currentChildrenHashes = chain.childMap.getOrElse(parentHash, HashSet.empty[BlockHash])
        val updatedChildrenHashes = currentChildrenHashes + serializedBlockHash
        parentHash -> updatedChildrenHashes
      }
      childMap        = chain.childMap ++ updatedChildren
      newChain: Chain = Chain(idToBlocks, blockLookup, childMap, nextId)
      _               <- chainState[F].set(newChain)
    } yield block
}
