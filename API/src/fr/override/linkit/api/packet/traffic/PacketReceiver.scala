package fr.`override`.linkit.api.packet.traffic

import fr.`override`.linkit.api.packet.{DedicatedPacketCoordinates, Packet}

trait PacketAsyncReceiver extends PacketChannel {
    def addOnPacketReceived(callback: (Packet, DedicatedPacketCoordinates) => Unit): Unit
}

trait PacketSyncReceiver extends PacketChannel {

    def nextPacket[P <: Packet]: P

    /**
     * @return true if this channel contains stored packets. In other words, return true if [[nextPacket]] will not wait
     * */
    def haveMorePackets: Boolean
}