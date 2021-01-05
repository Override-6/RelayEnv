package fr.`override`.linkit.api.network

import java.sql.Timestamp

import fr.`override`.linkit.api.Relay
import fr.`override`.linkit.api.exception.UnexpectedPacketException
import fr.`override`.linkit.api.packet.fundamental.DataPacket
import fr.`override`.linkit.api.packet.traffic.ImmediatePacketInjectable
import fr.`override`.linkit.api.packet.{Packet, PacketCoordinates}
import fr.`override`.linkit.api.utils.Tuple3Packet._
import fr.`override`.linkit.api.utils.{ConsumerContainer, Tuple3Packet}

import scala.collection.mutable

abstract class AbstractNetwork(relay: Relay) extends Network {

    private val entities = mutable.Map[String, NetworkEntity]((relay.identifier, new SelfNetworkEntity(relay)))

    override val onlineTimeStamp: Timestamp = new Timestamp(System.currentTimeMillis())

    @volatile private var entityAddedListeners: ConsumerContainer[NetworkEntity] = ConsumerContainer()

    //immutable
    override def listEntities: List[NetworkEntity] = entities.values.to(List)

    override def getEntity(identifier: String): Option[NetworkEntity] = entities.get(identifier)

    override def addOnEntityAdded(action: NetworkEntity => Unit): Unit = entityAddedListeners += action

    protected def addEntity(identifier: String): Unit = {
        addEntity(createEntity(identifier))
    }

    protected def addEntity(entity: NetworkEntity): Unit = {
        val identifier = entity.identifier
        entities.put(identifier, entity)
        println(entities.size + " Connected")
        entityAddedListeners.applyAll(entity)
    }

    protected def removeEntity(identifier: String): Unit = {
        entities.remove(identifier)
        println(entities.size + " Connected")
    }

    protected def createEntity(identifier: String): NetworkEntity

    protected def updateEntityState(entity: NetworkEntity, state: ConnectionState): Unit

    protected def handleOrder(packet: Tuple3Packet, coords: PacketCoordinates): Boolean = false

    protected def sendPacket(packet: Packet, coords: PacketCoordinates): Unit

    protected def getAsyncChannel: ImmediatePacketInjectable

    getAsyncChannel.onPacketInjected((packet, coords) => {
        val tuple = packet.asInstanceOf[Tuple3Packet]
        val order = tuple._1

        if (!handleOrder(tuple, coords)) {
            order match {
                case "add" =>
                    val affected = tuple._2
                    if (!entities.contains(affected))
                        addEntity(affected)

                case "getProperty" =>
                    val name = tuple._2
                    sendPacket(DataPacket("", relay.properties.get(name).getOrElse("")), coords.reversed())
                case "setProperty" =>
                    val name = tuple._2
                    val value = tuple._3
                    relay.properties.putProperty(name, value)

                case "update" =>
                    val entity = getEntity(tuple._2)
                    if (entity.isDefined)
                        updateEntityState(entity.get, ConnectionState.valueOf(tuple._3))
                case "versions" =>
                    sendPacket((Relay.ApiVersion.toString, relay.relayVersion.toString), coords.reversed())

                case _ => throw new UnexpectedPacketException(s"Could not handle network order '$order'")
            }
        }
    })


}