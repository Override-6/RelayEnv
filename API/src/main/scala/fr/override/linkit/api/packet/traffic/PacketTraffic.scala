package fr.`override`.linkit.api.packet.traffic

import fr.`override`.linkit.api.concurrency.relayWorkerExecution
import fr.`override`.linkit.api.packet.Packet
import fr.`override`.linkit.api.packet.traffic.ChannelScope.ScopeFactory
import fr.`override`.linkit.api.packet.traffic.PacketInjections.PacketInjection
import fr.`override`.linkit.api.system.JustifiedCloseable

import scala.reflect.ClassTag


trait PacketTraffic extends JustifiedCloseable {

    val relayID: String
    val ownerID: String

    def createInjectable[C <: PacketInjectable : ClassTag](id: Int,
                                                           scopeFactory: ScopeFactory[_ <: ChannelScope],
                                                           factory: PacketInjectableFactory[C]): C

    @relayWorkerExecution
    def handleInjection(injection: PacketInjection): Unit

    def canConflict(id: Int, scope: ChannelScope): Boolean

    def newWriter(id: Int, transform: Packet => Packet = p => p): PacketWriter

}

object PacketTraffic {
    val SystemChannelID = 1
    val RemoteConsoles = 2
}