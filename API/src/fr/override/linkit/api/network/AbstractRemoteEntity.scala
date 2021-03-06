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

package fr.`override`.linkit.api.network

import fr.`override`.linkit.api.Relay
import fr.`override`.linkit.api.network.cache.SharedCacheHandler
import fr.`override`.linkit.api.network.cache.collection.SharedCollection
import fr.`override`.linkit.api.packet.fundamental.RefPacket.ObjectPacket
import fr.`override`.linkit.api.packet.traffic.channel.{CommunicationPacketChannel, PacketChannelCategories}
import fr.`override`.linkit.api.packet.traffic.{ChannelScope, PacketTraffic}
import fr.`override`.linkit.api.system.Version

import java.sql.Timestamp

abstract class AbstractRemoteEntity(private val relay: Relay,
                                    override val identifier: String,
                                    private val communicator: CommunicationPacketChannel) extends NetworkEntity {

    //println(s"CREATED REMOTE ENTITY NAMED '$identifier'")
    protected implicit val traffic: PacketTraffic = relay.traffic

    println(s"Created entity $identifier")
    override val cache: SharedCacheHandler = SharedCacheHandler.create(identifier, identifier)
    override val connectionDate: Timestamp = cache(2)
    private val remoteFragments = {
        val communicator = traffic
                .createInjectable(4, ChannelScope.broadcast, PacketChannelCategories)
                .subInjectable(Array(identifier), PacketChannelCategories, true)

        cache
                .get(6, SharedCollection.set[String])
                .mapped(name => new RemoteFragmentController(name, communicator.createCategory(name, ChannelScope.broadcast, CommunicationPacketChannel)))
    }

    override def addOnStateUpdate(action: ConnectionState => Unit): Unit

    override def getConnectionState: ConnectionState

    override def getProperty(name: String): Serializable = {
        communicator.sendRequest(ObjectPacket(("getProp", name)))
        communicator.nextResponse[ObjectPacket].casted
    }

    override def setProperty(name: String, value: Serializable): Unit = {
        communicator.sendRequest(ObjectPacket(("setProp", name, value)))
    }

    override def getRemoteConsole: RemoteConsole = relay.getConsoleOut(identifier)

    override def getRemoteErrConsole: RemoteConsole = relay.getConsoleErr(identifier)

    override def getApiVersion: Version = cache(4)

    override def getRelayVersion: Version = cache(5)


    override def listRemoteFragmentControllers: List[RemoteFragmentController] = remoteFragments.toList

    override def getFragmentController(nameIdentifier: String): Option[RemoteFragmentController] = {
        remoteFragments.find(_.nameIdentifier == nameIdentifier)
    }

    override def toString: String = s"${getClass.getSimpleName}(identifier: $identifier, state: $getConnectionState)"
}
