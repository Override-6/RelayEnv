package fr.overridescala.vps.ftp.api.utils

import java.net.InetSocketAddress

object Constants {

    val PORT = 48484
    val MAX_PACKET_LENGTH: Int = 4096 * 8 // 32ko
    val LOCALHOST: InetSocketAddress = new InetSocketAddress("localhost", PORT)
    val PUBLIC_ADDRESS: InetSocketAddress = Utils.getPublicAddress
    /**
     * The server identifier is forced to be this id.
     *  @see [[fr.overridescala.vps.ftp.api.Relay]]
     * */
    val SERVER_ID = "server"

}
