/*
 * Copyright (c) 2021. Linkit and or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can only use it for personal uses, studies or documentation.
 * You can download this source code, and modify it ONLY FOR PERSONAL USE and you
 * ARE NOT ALLOWED to distribute your MODIFIED VERSION.
 *
 * Please contact maximebatista18@gmail.com if you need additional information or have any
 * questions.
 */

package fr.`override`.linkit.client

import java.nio.channels.AsynchronousCloseException

import fr.`override`.linkit.api.connection.{ConnectionException, ExternalConnection}
import fr.`override`.linkit.api.connection.network.{ExternalConnectionState, Network}
import fr.`override`.linkit.api.connection.packet.serialization.PacketTranslator
import fr.`override`.linkit.api.connection.packet.traffic.ChannelScope.ScopeFactory
import fr.`override`.linkit.api.connection.packet.traffic.PacketTraffic.SystemChannelID
import fr.`override`.linkit.api.connection.packet.traffic.{ChannelScope, PacketInjectable, PacketInjectableFactory, PacketTraffic}
import fr.`override`.linkit.api.connection.packet.{DedicatedPacketCoordinates, Packet}
import fr.`override`.linkit.api.local.concurrency.{packetWorkerExecution, workerExecution}
import fr.`override`.linkit.api.local.system.config.ConnectionConfiguration
import fr.`override`.linkit.api.local.system.event.EventNotifier
import fr.`override`.linkit.api.local.system.security.BytesHasher
import fr.`override`.linkit.client.config.ClientConnectionConfiguration
import fr.`override`.linkit.client.network.ClientSideNetwork
import fr.`override`.linkit.core.connection.network.cache.SimpleSharedCacheManager
import fr.`override`.linkit.core.connection.packet.UnexpectedPacketException
import fr.`override`.linkit.core.connection.packet.fundamental.ValPacket.BooleanPacket
import fr.`override`.linkit.core.connection.packet.serialization.{CompactedPacketTranslator, NumberSerializer}
import fr.`override`.linkit.core.connection.packet.traffic.{DynamicSocket, PacketInjections, PacketReader, SocketPacketTraffic, SocketPacketWriter}
import fr.`override`.linkit.core.local.concurrency.{BusyWorkerPool, PacketWorkerThread}
import fr.`override`.linkit.core.local.system.event.DefaultEventNotifier
import fr.`override`.linkit.core.local.system.{ContextLogger, Rules, SystemPacket, SystemPacketChannel}

import scala.reflect.ClassTag
import scala.util.control.NonFatal

class ClientConnection private(socket: DynamicSocket,
                               appContext: ClientApplication,
                               serverIdentifier: String,
                               val configuration: ConnectionConfiguration) extends ExternalConnection {

    override val supportIdentifier: String = configuration.identifier
    start() //Weird
    override val boundIdentifier: String = serverIdentifier
    override val translator: PacketTranslator = configuration.translator
    override val traffic: PacketTraffic = new SocketPacketTraffic(socket, translator, supportIdentifier, serverIdentifier)
    private val sideNetwork: ClientSideNetwork = initNetwork
    override val network: Network = sideNetwork
    override val eventNotifier: EventNotifier = new DefaultEventNotifier

    private val systemChannel: SystemPacketChannel = new SystemPacketChannel(ChannelScope.reserved(serverIdentifier)(traffic.newWriter(SystemChannelID)))
    @volatile private var alive = true

    override def getInjectable[C <: PacketInjectable : ClassTag](injectableID: Int, scopeFactory: ScopeFactory[_ <: ChannelScope], factory: PacketInjectableFactory[C]): C = {
        traffic.getInjectable(injectableID, scopeFactory, factory)
    }

    override def runLater(@workerExecution task: => Unit): Unit = appContext.runLater(task)

    override def getState: ExternalConnectionState = socket.getState

    override def isAlive: Boolean = alive

    @workerExecution
    override def shutdown(): Unit = {
        BusyWorkerPool.checkCurrentIsWorker("Shutdown must be performed in a contextual thread pool.")
        if (!alive)
            return //already shutdown

        PointPacketWorkerThread.close()
        appContext.unregister(this)

        traffic.close()
        socket.close()

        alive = false
    }

    @workerExecution
    def start(): Unit = {
        BusyWorkerPool.checkCurrentIsWorker("Can't start in a non worker pool !")
        if (alive)
            throw new IllegalStateException(s"Connection already started ! ($supportIdentifier)")

        PointPacketWorkerThread.start()
        socket.addConnectionStateListener(tryReconnect)

        alive = true
    }

    @packetWorkerExecution //So the runLater must be specified in order to perform network operations
    private def tryReconnect(state: ExternalConnectionState): Unit = {
        val bytes = supportIdentifier.getBytes()
        val welcomePacket = NumberSerializer.serializeInt(bytes.length) ++ bytes

        if (state == ExternalConnectionState.CONNECTED && socket.isOpen) runLater {
            socket.write(welcomePacket) //The welcome packet will let the server continue his socket handling
            systemChannel.nextPacket[BooleanPacket]
            sideNetwork.update()
            translator.updateCache(network.globalCache)
        }
    }

    private def initNetwork: ClientSideNetwork = {
        if (network != null)
            throw new IllegalStateException("Network is already initialized !")

        val globalCache = SimpleSharedCacheManager.get("Global Cache", serverIdentifier)(traffic)
        translator.updateCache(globalCache)
        ContextLogger.info(s"$boundIdentifier: Stage 2 completed : Main cache manager created.")
        new ClientSideNetwork(this, globalCache)
    }

    private def handleSystemPacket(system: SystemPacket, coords: DedicatedPacketCoordinates): Unit = {
        val order = system.order
        val reason = system.reason.reversedPOV()
        val sender = coords.senderID

        import fr.`override`.linkit.core.local.system.SystemOrder._
        order match {
            case CLIENT_CLOSE => shutdown()
            //FIXME case ABORT_TASK => tasksHandler.skipCurrent(reason)

            //FIXME weird use of exceptions/remote print
            case SERVER_CLOSE =>
            //FIXME UnexpectedPacketException(s"System packet order '$order' couldn't be handled by this RelayPoint : Received forbidden order")
            //        .printStackTrace(getConsoleErr(sender))

            case _ => //FIXME UnexpectedPacketException(s"System packet order '$order' couldn't be handled by this RelayPoint : Unknown order")
            // .printStackTrace(getConsoleErr(sender))
        }
    }

    private def handlePacket(packet: Packet, coordinates: DedicatedPacketCoordinates, number: Int): Unit = {
        packet match {
            //FIXME case init: TaskInitPacket => tasksHandler.handlePacket(init, coordinates)
            case system: SystemPacket => handleSystemPacket(system, coordinates)
            case _: Packet =>
                val injection = PacketInjections.createInjection(packet, coordinates, number)
                //println(s"START OF INJECTION ($packet, $coordinates, $number) - ${Thread.currentThread()}")
                traffic.handleInjection(injection)
            //println(s"ENT OF INJECTION ($packet, $coordinates, $number) - ${Thread.currentThread()}")
        }
    }

    private object PointPacketWorkerThread extends PacketWorkerThread(supportIdentifier) {
        private val packetReader = new PacketReader(socket, configuration.hasher)
        @volatile private var packetsReceived = 0

        override protected def refresh(): Unit = {
            try {
                readNextPacket()
            } catch {
                case _: AsynchronousCloseException =>
                    onException("Asynchronous close.")

                case NonFatal(e) =>
                    e.printStackTrace()
                    onException(s"Suddenly disconnected from the server.")
            }

            def onException(msg: String): Unit = {
                ContextLogger.warn(msg)

                runLater {
                    shutdown()
                }
            }
        }

        private def readNextPacket(): Unit = {
            val bytes = packetReader.readNextPacketBytes()
            if (bytes == null)
                return
            //NETWORK-DEBUG-MARK
            ContextLogger.network(s"Received : ", bytes)
            val packetNumber = packetsReceived + 1
            packetsReceived += 1

            runLater { //handles and deserializes the packet in the worker thread pool

                val (packet, coordinates) = translator.translate(bytes)

                //println(s"RECEIVED PACKET $packet WITH COORDINATES $coordinates. This packet will be handled in thread ${Thread.currentThread()}")

                coordinates match {
                    case dedicated: DedicatedPacketCoordinates =>
                        //checkCoordinates(dedicated)
                        handlePacket(packet, dedicated, packetNumber)
                    case other => throw UnexpectedPacketException(s"Only DedicatedPacketCoordinates can be handled by a RelayPoint. Received : ${other.getClass.getName}")
                }
            }
        }
    }


}

object ClientConnection {


    @throws[ConnectionException]("If Something went wrong during the initialization")
    def open(socket: ClientDynamicSocket, context: ClientApplication, configuration: ConnectionConfiguration): ClientConnection = {
        val packetReader = new PacketReader(socket, BytesHasher.inactive)
        val translator = configuration.translator
        val identifier = configuration.identifier

        val iDbytes = identifier.getBytes()
        val translatorSignature = translator.signature
        val hasherSignature = configuration.hasher.signature
        val separator = Rules.WPArgsSeparator

        val bytes = iDbytes ++ separator ++ translatorSignature ++ separator ++ hasherSignature
        val welcomePacket = NumberSerializer.serializeInt(bytes.length) ++ bytes
        socket.write(welcomePacket)

        val isAccepted = packetReader.readNextPacketBytes()(0) == Rules.ConnectionAccepted
        if (!isAccepted) {
            val msg = new String(packetReader.readNextPacketBytes())
            val serverPort = socket.remoteSocketAddress().getPort
            throw new ConnectionException(null, s"Server (port: $serverPort) refused connection: $msg")
        }
        val serverIdentifier = new String(packetReader.readNextPacketBytes())
        socket.identifier = serverIdentifier
        ContextLogger.info(s"${identifier}: Stage 1 completed : Connection seems able to support this server configuration.")
        val connection = new ClientConnection(socket, context, serverIdentifier, configuration)
        ContextLogger.info(s"$identifier: Stage 3 completed : Connection instance created.")
        connection
    }
}
