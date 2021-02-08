package fr.`override`.linkit.api.packet.traffic

import fr.`override`.linkit.api.concurrency.PacketWorkerThread
import fr.`override`.linkit.api.exception.{ClosedException, ConflictException, RelayException}
import fr.`override`.linkit.api.packet.traffic.ChannelScope.ScopeFactory
import fr.`override`.linkit.api.packet.traffic.{PacketInjectable, PacketTraffic}
import fr.`override`.linkit.api.packet.{Packet, PacketCoordinates}
import fr.`override`.linkit.api.system.config.RelayConfiguration
import fr.`override`.linkit.api.system.{CloseReason, JustifiedCloseable}
import org.jetbrains.annotations.NotNull

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.reflect.ClassTag
import scala.util.control.NonFatal

abstract class AbstractPacketTraffic(@NotNull config: RelayConfiguration,
                                     @NotNull private val ownerId: String) extends PacketTraffic {

    override val relayID: String = ownerId
    private val scopes = mutable.Map.empty[Int, IdentifierScopes]
    @volatile private var closed = false

    @deprecated("Find a better solution; cache every lost packets may be very heavy. (take a look at Relay.scala TODO list)")
    private val lostPackets = mutable.Map.empty[Int, ListBuffer[(Packet, PacketCoordinates)]]

    override def createInjectable[A <: PacketInjectable : ClassTag](id: Int,
                                                                    scopeFactory: ScopeFactory[_ <: ChannelScope],
                                                                    factory: PacketInjectableFactory[A]): A = {
        completeCreation(scopeFactory(newWriter(id)), factory)
    }

    override def createInjectableNoConflicts[C <: PacketInjectable : ClassTag](id: Int,
                                                                               scopeFactory: ScopeFactory[_ <: ChannelScope],
                                                                               factory: PacketInjectableFactory[C]): C = {
        val scope = scopeFactory(newWriter(id))
        if (canConflict(id, scope)) {
            throw new ConflictException("This scope can conflict with other scopes that are registered within this injectable identifier")
        }
        completeCreation(scope, factory)
    }

    override def canConflict(identifier: Int, scope: ChannelScope): Boolean = {
        scopes
                .get(identifier)
                .exists(_.canConflict(scope))
    }

    private def completeCreation[C <: PacketInjectable](scope: ChannelScope, factory: PacketInjectableFactory[C]): C = {
        val channel = factory.createNew(scope)
        register(scope, channel)
        channel
    }

    private def register(scope: ChannelScope, dedicated: PacketInjectable): Unit = {
        ensureOpen()

        val id = dedicated.identifier

        init(dedicated)

        scopes.getOrElseUpdate(id, IdentifierScopes(id)).register(scope, dedicated)
    }

    private def init(injectable: PacketInjectable): Unit = {

        if (scopes.size > config.maxPacketContainerCacheSize) {
            throw new RelayException("Maximum registered packet containers limit exceeded")
        }

        val id = injectable.identifier

        //Will inject every lost packets
        lostPackets
                .get(id)
                .foreach(_.foreach(t => injectable.injectPacket(t._1, t._2)))
        lostPackets.remove(id)
    }

    private def getInjectables(identifier: Int, target: String): Iterable[PacketInjectable] = {
        val opt = scopes.get(identifier)

        if (opt.isEmpty)
            return Iterable()

        opt.get.getInjectables(target)
    }

    override def close(reason: CloseReason): Unit = {
        scopes.values
                .foreach(_.close(reason))
        scopes.clear()
        closed = true
    }

    override def isClosed: Boolean = closed

    protected def ensureOpen(): Unit = {
        if (closed)
            throw new ClosedException("This Traffic handler is closed")
    }

    override def handlePacket(packet: Packet, coordinates: PacketCoordinates): Unit = {
        println(s"INJECTING PACKET : $packet With coordinates : $coordinates (${Thread.currentThread()})")
        PacketWorkerThread.checkNotCurrent()
        ensureOpen()

        val id = coordinates.injectableID

        val sender = coordinates.senderID
        val injectables = getInjectables(id, sender)

        println(s"injectables = ${injectables}")
        println(s"scopes = ${scopes}")
        if (injectables.isEmpty) {
            lostPackets.getOrElseUpdate(id, ListBuffer.empty) += ((packet, coordinates))
            return
        }
        injectables.foreach(_.injectPacket(packet, coordinates))
    }

    protected case class IdentifierScopes(identifier: Int) extends JustifiedCloseable {

        private val cache = mutable.Set.empty[(ChannelScope, PacketInjectable)]
        private var closed = false

        override def close(reason: CloseReason): Unit = {
            for (tuple <- cache if tuple._2.isOpen) try {
                tuple._2.close()
            } catch {
                case NonFatal(e) => e.printStackTrace()
            }
            cache.clear()
            closed = true
        }

        override def isClosed: Boolean = closed

        def canConflict(scope: ChannelScope): Boolean = {
            cache.exists(_._1.canConflictWith(scope))
        }

        def isAuthorised(targetID: String): Boolean = {
            cache.exists(_._1.isAuthorised(targetID))
        }

        def getInjectables(target: String): Seq[PacketInjectable] = {
            cache
                    .filter(_._1.isAuthorised(target))
                    .map(_._2)
                    .toSeq
        }

        def register(scope: ChannelScope, injectable: PacketInjectable): Unit = {
            cache += ((scope, injectable))
        }
    }


}