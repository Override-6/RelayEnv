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

package fr.linkit.engine.connection.packet.serialization.tree

import fr.linkit.api.connection.network.Network
import fr.linkit.api.connection.packet.Packet
import fr.linkit.api.connection.packet.serialization.tree._
import fr.linkit.api.connection.packet.serialization.tree.procedure.Procedure
import fr.linkit.engine.connection.packet.serialization.tree.DefaultSerialContext.{PacketClassNameRequest, SerialContextChannelID}
import fr.linkit.engine.connection.packet.traffic.ChannelScopes
import fr.linkit.engine.connection.packet.traffic.channel.request.RequestPacketChannel
import fr.linkit.engine.local.mapping.ClassMappings
import org.jetbrains.annotations.Nullable

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.reflect.{ClassTag, classTag}

class DefaultSerialContext extends SerialContext {

    private[tree] val userFactories        = ListBuffer.empty[NodeFactory[_]]
    private[tree] val defaultFactories     = ListBuffer.empty[NodeFactory[_]]
    private       val profiles             = new mutable.HashMap[Class[_], ClassProfile[_]]()
    private       val finder               = new DefaultNodeFinder(this)
    @Nullable private var network: Network = _

    private lazy val channel = network
            .connection
            .getInjectable(SerialContextChannelID, ChannelScopes.discardCurrent, RequestPacketChannel)

    override def attachProcedure[C: ClassTag](procedure: Procedure[C]): Unit = {
        getProfile[C].addProcedure(procedure)
    }

    override def detachFactory(nodeFactory: NodeFactory[_]): Unit = {
        userFactories -= nodeFactory
    }

    override def attachFactory(factory: NodeFactory[_]): Unit = {
        userFactories += factory
    }

    override def getProfile[T: ClassTag]: ClassProfile[T] = {
        getClassProfile(classTag[T]
                .runtimeClass
                .asInstanceOf[Class[T]])
    }

    override def getClassProfile[T](clazz: Class[_ <: T]): ClassProfile[T] = {
        //println(s"clazz = ${clazz}")
        profiles.getOrElseUpdate(clazz, new DefaultClassProfile(clazz, this))
                .asInstanceOf[ClassProfile[T]]
    }

    override def getFinder: NodeFinder = finder

    def getNetwork: Option[Network] = Option(network)

    def updateNetwork(network: Network): Unit = {
        if (this.network != null)
            throw new IllegalStateException("Network already initialized !")
        if (network == null)
            throw new NullPointerException
        this.network = network
        channel.addRequestListener(bundle => {
            val packet    = bundle.packet.nextPacket[PacketClassNameRequest]
            val className = ClassMappings.findClass(packet.hash).map(_.getName).orNull
            packet.name = className
            bundle.responseSubmitter
                    .addPacket(packet)
                    .submit()
        })
    }

}

object DefaultSerialContext {

    /**
     * This packet is sent by instance of [[DefaultNodeFinder]] when it can't find a [[NodeFactory]] for a [[DeserialNode]].
     * When this happens, it will first suppose that the first 4 bytes of the sequence that needs to be deserialized
     * is for an object with an unknown class hashcode according to [[ClassMappings]]. Thus, in order to retrieve the class name
     * bounded to the received hash code, the [[DefaultNodeFinder]] will make a request by using this class.
     * It will send a PacketClassNameRequest(hash, null) first, then the [[DefaultSerialContext]]
     * of the sender will process this request and will reply with a PacketClassNameRequest(hash, className).
     * If the className value is null, that's mean that the class was not found on the remote engine,
     * and that the serialized byte sequence can't be deserialized as no [[NodeFactory]] has been found before and after the request
     * */
    case class PacketClassNameRequest(hash: Int, var name: String) extends Packet

    val SerialContextChannelID = 16

    implicit class ByteHelper(self: Byte) {

        def /\(bytes: Array[Byte]): Array[Byte] = {
            Array(self) ++ bytes
        }

        def /\(other: Byte): Array[Byte] = {
            self /\ Array(other)
        }
    }

}