package fr.`override`.linkit.server.connection

import fr.`override`.linkit.server.RelayServer
import fr.`override`.linkit.api.exception.{RelayException, RelayInitialisationException}
import fr.`override`.linkit.api.system.{JustifiedCloseable, CloseReason}

import scala.collection.mutable

/**
 * TeamMate of RelayServer, handles the RelayPoint Connections.
 *
 * @see [[RelayServer]]
 * @see [[ClientConnection]]
 * */
class ConnectionsManager(server: RelayServer) extends JustifiedCloseable {
    /**
     * java map containing all RelayPointConnection instances
     * */
    private val connections: mutable.Map[String, ClientConnection] = mutable.Map.empty

    override def close(reason: CloseReason): Unit = {
        for ((_, connection) <- connections) {
            println(s"Closing '${connection.identifier}'...")
            connection.close(reason)
        }
    }

    /**
     * creates and register a RelayPoint connection.
     *
     * @param socket the socket to start the connection
     * @throws RelayInitialisationException when a id is already set for this address, or another connection is known under this id.
     * */
    def register(socket: SocketContainer, identifier: String): Unit = {
        if (connections.contains(identifier))
            throw RelayInitialisationException(s"This relay id is already registered ! ('$identifier')")

        if (connections.size > server.configuration.maxConnection)
            throw new RelayException("Maximum connection limit exceeded")

        val connection = ClientConnection.preOpen(socket, server, identifier)
        connections.put(identifier, connection)
        connection.start()

        val canStillConnected = server.securityManager.leaveConnected(connection)
        if (canStillConnected) {
            println(s"Relay Point connected with identifier '${connection.identifier}'")
            return
        }

        val msg = "Connection rejected by security manager"
        connection.getConsoleErr.println(msg)
        Console.err.println(s"Relay Connection '$identifier': " + msg)

        connections.remove(identifier)
        connection.close(CloseReason.INTERNAL)
    }

    def broadcast(err: Boolean, msg: String): Unit = {
        connections.values.foreach(connection => {
            if (err)
                connection.getConsoleErr.println(msg)
            else connection.getConsoleOut.println(msg)
        })
    }

    /**
     * unregisters a Relay point
     *
     * @param identifier the identifier to disconnect
     * */
    def unregister(identifier: String): ClientConnection = {
        connections.remove(identifier).orNull
    }


    /**
     * retrieves a RelayPointConnection based on the address
     *
     * @param identifier the identifier linked [[ClientConnection]]
     * @return the found [[ClientConnection]] bound with the identifier
     * */
    def getConnection(identifier: String): ClientConnection = {
        connections.get(identifier).orNull
    }

    /**
     * determines if the address is not registered
     *
     * @param identifier the identifier to test
     * @return true if the address is not registered, false instead
     * */
    def isNotRegistered(identifier: String): Boolean = {
        !connections.contains(identifier)
    }

    /**
     * @param identifier the identifier to test
     * @return true if any connected Relay have the specified identifier
     * */
    def containsIdentifier(identifier: String): Boolean = {
        identifier == server.identifier || connections.contains(identifier) //reserved server identifier
    }


    /**
     * Deflects a packet to his associated [[ClientConnection]]
     *
     * @throws RelayException if no connection where found for this packet.
     * @param bytes the packet bytes to deflect
     * */
    private[connection] def deflectTo(bytes: Array[Byte], target: String): Unit = {
        val connection = getConnection(target)
        if (connection == null)
            throw new RelayException(s"unknown ID '$target' to deflect packet")
        connection.sendDeflectedBytes(bytes)
    }


}
