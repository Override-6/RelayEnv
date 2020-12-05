package fr.overridescala.vps.ftp.server.task

import java.util.concurrent.{ArrayBlockingQueue, BlockingQueue}

import fr.overridescala.vps.ftp.api.packet.Packet
import fr.overridescala.vps.ftp.api.system.{JustifiedCloseable, Reason, RemoteConsole}
import fr.overridescala.vps.ftp.api.task.TaskTicket

import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.util.control.NonFatal

class ConnectionTasksThread private(ownerID: String,
                                    ticketQueue: BlockingQueue[TaskTicket],
                                    lostPackets: mutable.Map[Int, ListBuffer[Packet]],
                                    remoteConsoleErr: RemoteConsole.Err) extends Thread with JustifiedCloseable {

    @volatile private var open = false
    @volatile private var currentTicket: TaskTicket = _

    def this(ownerID: String, remoteConsoleErr: RemoteConsole.Err) =
        this(ownerID, new ArrayBlockingQueue[TaskTicket](15000), mutable.Map.empty, remoteConsoleErr)


    override def run(): Unit = {
        open = true
        while (open) {
            try {
                executeNextTicket()
            } catch {
                //normal exception thrown when the thread was suddenly stopped
                case e: InterruptedException =>
                case NonFatal(e) =>
                    e.printStackTrace()
                    remoteConsoleErr.reportExceptionSimplified(e)
            }
        }
    }

    override def close(reason: Reason): Unit = {
        if (currentTicket != null) {
            currentTicket.abort(reason)
            currentTicket = null
        }

        ticketQueue.clear()
        lostPackets.clear()
        open = false

        interrupt()
    }

    def copy(): ConnectionTasksThread =
        new ConnectionTasksThread(ownerID, ticketQueue, lostPackets, remoteConsoleErr)

    private[task] def addTicket(ticket: TaskTicket): Unit = {
        ticketQueue.add(ticket)
    }

    private def executeNextTicket(): Unit = {
        val ticket = ticketQueue.take()
        currentTicket = ticket
        val channel = ticket.channel
        val taskID = channel.channelID
        //Adding eventual lost packets to this task
        if (lostPackets.contains(taskID)) {
            val queue = lostPackets(taskID)
            queue.foreach(channel.injectPacket)
            queue.clear()
            lostPackets.remove(taskID)
        }
        ticket.start()
    }

    setName(s"RP Task Execution ($ownerID)")

}