package fr.overridescala.vps.ftp.api.packet

import java.nio.ByteBuffer
import java.util

/**
 * Protocol describes how the packets are interpreted and build.
 *
 * <b>/!\ this is not a serious protocol/!\</b>
 * */
object Protocol {
    /**
     * those flags enable to separate fields of packet, and packets type
     * */
    private val DATA_PACKET_TYPE = "[data]".getBytes
    private val DP_HEADER: Array[Byte] = "<header>".getBytes

    private val TASK_INIT_PACKET_TYPE = "[task_init]".getBytes
    private val TIP_TASK_TYPE = "<task_type>".getBytes

    private val TARGET = "<target_id>".getBytes
    private val CONTENT: Array[Byte] = "<content>".getBytes
    private val END: Array[Byte] = "<end>".getBytes

    protected[packet] val PACKET_SIZE_FLAG: Array[Byte] = "(size:".getBytes

    val INIT_ID: Int = -1
    val ERROR_ID: Int = -2
    val ABORT_TASK: String = "ABORT_TASK"


    /**
     * build a [[ByteBuffer]] containing the bytes of a packet from the parameters:
     *
     * @param packet the packet to sequence
     * */
    def toBytes(packet: DataPacket): Array[Byte] = {
        val idBytes = String.valueOf(packet.taskID).getBytes
        val headerBytes = packet.header.getBytes
        val targetIdBytes = packet.targetIdentifier.getBytes
        val bytes = DATA_PACKET_TYPE ++ idBytes ++
                TARGET ++ targetIdBytes ++
                DP_HEADER ++ headerBytes ++
                CONTENT ++ packet.content ++ END
        val bytesLength = bytes.length
        PACKET_SIZE_FLAG ++ s"$bytesLength)".getBytes ++ bytes
    }

    /**
     * build a [[ByteBuffer]] containing the bytes of a packet from the parameters:
     *
     * @param packet the packet to sequence
     * */
    def toBytes(packet: TaskInitPacket): Array[Byte] = {
        val taskIDBytes = String.valueOf(packet.taskID).getBytes
        val typeBytes = packet.taskType.getBytes
        val targetIdBytes = packet.targetIdentifier.getBytes
        val bytes = TASK_INIT_PACKET_TYPE ++ taskIDBytes ++
                TARGET ++ targetIdBytes ++
                TIP_TASK_TYPE ++ typeBytes ++
                CONTENT ++ packet.content ++ END
        val bytesLength = bytes.length
        PACKET_SIZE_FLAG ++ s"$bytesLength)".getBytes ++ bytes
    }

    /**
     * creates a [[DataPacket]] from a byte Array.
     *
     * @param bytes the bytes to transform
     * @return a [[DataPacket]] instance
     * @throws IllegalArgumentException if no packet where found.
     * */
    protected[packet] def toPacket(bytes: Array[Byte]): Packet = {
        getPacketType(bytes) match {
            case PacketType.TASK_INIT_PACKET => toTIP(bytes)
            case PacketType.DATA_PACKET => toDP(bytes)
        }
    }

    private def toTIP(bytes: Array[Byte]): TaskInitPacket = {
        val taskID = cutString(bytes, TASK_INIT_PACKET_TYPE, TARGET).toInt
        val targetID = cutString(bytes, TARGET, TIP_TASK_TYPE)
        val taskType = cutString(bytes, TIP_TASK_TYPE, CONTENT)
        val content = cut(bytes, CONTENT, END)
        TaskInitPacket(taskID, targetID, taskType, content)
    }


    private def toDP(bytes: Array[Byte]): DataPacket = {
        val taskID = cutString(bytes, DATA_PACKET_TYPE, TARGET).toInt
        val targetID = cutString(bytes, TARGET, DP_HEADER)
        val header = cutString(bytes, DP_HEADER, CONTENT)
        val content = cut(bytes, CONTENT, END)
        new DataPacket(taskID, header, targetID, content)
    }


    /**
     * @return the length in the array of the first packet found, -1 if the array do not contains any packet
     * @param bytes the bytes to find first packet length
     * */
    protected[packet] def getFirstPacketLength(bytes: Array[Byte]): Int = {
        val lengthString = bytes.slice(PACKET_SIZE_FLAG.length, bytes.indexOfSlice(")"))
        new String(lengthString).toInt
    }

    /**
     * @return true if this sequence contains a packet
     * @param bytes the sequence to test
     * */
    protected[packet] def containsPacket(bytes: Array[Byte]): Boolean = {
        getFirstPacketLength(bytes) > 0
    }

    protected[packet] def containsFlag(bytes: Array[Byte]): Boolean = {
        if (!bytes.containsSlice(PACKET_SIZE_FLAG))
            return false
        bytes.slice(PACKET_SIZE_FLAG.length, bytes.length).containsSlice(")")
    }

    private def cut(src: Array[Byte], a: Array[Byte], b: Array[Byte]): Array[Byte] =
        util.Arrays.copyOfRange(src, src.indexOfSlice(a) + a.length, src.indexOfSlice(b))

    private def cutString(src: Array[Byte], a: Array[Byte], b: Array[Byte]) =
        new String(cut(src, a, b))

    private def getPacketType(bytes: Array[Byte]): PacketType = {
        if (bytes.containsSlice(DATA_PACKET_TYPE))
            PacketType.DATA_PACKET
        else if (bytes.containsSlice(TASK_INIT_PACKET_TYPE))
            PacketType.TASK_INIT_PACKET
        else throw new IllegalArgumentException("this byte array do not have any packet type field")
    }


    private class PacketType private()

    private object PacketType extends Enumeration {
        val DATA_PACKET: PacketType = new PacketType
        val TASK_INIT_PACKET: PacketType = new PacketType
    }

}
