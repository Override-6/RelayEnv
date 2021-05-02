/*
 *  Copyright (c) 2021. Linkit and or its affiliates. All rights reserved.
 *  DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 *  This code is free software; you can only use it for personal uses, studies or documentation.
 *  You can download this source code, and modify it ONLY FOR PERSONAL USE and you
 *  ARE NOT ALLOWED to distribute your MODIFIED VERSION.
 *
 *  Please contact maximebatista18@gmail.com if you need additional information or have any
 *  questions.
 */

package fr.linkit.engine.connection.network.cache.puppet

import fr.linkit.api.connection.packet.PacketAttributesPresence
import fr.linkit.api.connection.packet.channel.ChannelScope
import fr.linkit.api.connection.packet.channel.ChannelScope.ScopeFactory
import fr.linkit.api.local.system.AppLogger
import fr.linkit.engine.connection.packet.fundamental.RefPacket
import fr.linkit.engine.connection.packet.fundamental.RefPacket.ObjectPacket
import fr.linkit.engine.connection.packet.traffic.ChannelScopes
import fr.linkit.engine.connection.packet.traffic.channel.request.RequestPacketChannel

import scala.collection.mutable.ListBuffer

class Puppeteer[S <: Serializable](channel: RequestPacketChannel,
                                   presence: PacketAttributesPresence,
                                   val description: PuppeteerDescription, val desc: PuppetClassDesc) {

    type SW <: S with PuppetWrapper[S]

    private val ownerScope = prepareScope(ChannelScopes.retains(description.owner))
    private val bcScope    = prepareScope(ChannelScopes.discardCurrent)

    private val puppetModifications = ListBuffer.empty[(String, Any)]

    private var puppet       : S  = _
    private var puppetWrapper: SW = _

    def getPuppet: S = puppet

    def getPuppetWrapper: SW = puppetWrapper

    def sendInvokeAndReturn[R](methodName: String, args: Array[Any]): R = {
        AppLogger.debug(s"Remotely invoking method $methodName(${args.mkString(",")})")
        val result = channel.makeRequest(ownerScope)
            .addPacket(ObjectPacket((methodName, Array(args: _*))))
            .submit()
            .nextResponse
            .nextPacket[RefPacket[R]].value
        result match {
                //FIXME ambiguity with broadcast method invocation.
            case ThrowableWrapper(e) => throw new RemoteInvocationFailedException(s"Invocation of method $methodName with arguments '${args.mkString(", ")}' failed.", e)
            case result              => result
        }
    }

    def sendInvoke(methodName: String, args: Array[Any]): Unit = {
        AppLogger.debug(s"Remotely invoking method $methodName(${args.mkString(",")})")
        channel.makeRequest(ownerScope)
            .addPacket(ObjectPacket((methodName, Array(args: _*))))
            .submit()
    }

    def addFieldUpdate(fieldName: String, newValue: Any): Unit = {
        AppLogger.vDebug(s"Field '$fieldName' took value $newValue")
        if (desc.isAutoFlush)
            flushModification((fieldName, newValue))
        else puppetModifications += ((fieldName, newValue))
    }

    def sendUpdatePuppet(newVersion: Serializable): Unit = {
        desc.foreachSharedFields(field => addFieldUpdate(field.getName, field.get(newVersion)))
    }

    def flush(): this.type = {
        puppetModifications.foreach(flushModification)
        puppetModifications.clear()
        this
    }

    def init(wrapper: SW, puppet: S): Unit = {
        if (this.puppet != null || this.puppetWrapper != null) {
            throw new IllegalStateException("This Puppeteer already controls a puppet instance !")
        }
        this.puppetWrapper = wrapper
        this.puppet = puppet
    }

    private def flushModification(mod: (String, Any)): Unit = {
        channel.makeRequest(bcScope)
            .addPacket(ObjectPacket(mod))
            .submit()
            .detach()
    }

    private def prepareScope(factory: ScopeFactory[_ <: ChannelScope]): ChannelScope = {
        if (channel == null)
            return null
        val writer = channel.traffic.newWriter(channel.identifier)
        val scope  = factory.apply(writer)
        presence.drainAllDefaultAttributes(scope)
        scope.addDefaultAttribute("id", description.objectID)
    }

}