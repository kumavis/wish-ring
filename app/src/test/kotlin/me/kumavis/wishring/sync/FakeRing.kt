package me.kumavis.wishring.sync

import kotlinx.coroutines.channels.Channel
import me.kumavis.wishring.protocol.bytes
import me.kumavis.wishring.protocol.compressedRecord
import me.kumavis.wishring.protocol.collection
import me.kumavis.wishring.protocol.le16
import me.kumavis.wishring.protocol.le32
import me.kumavis.wishring.protocol.packBits
import me.kumavis.wishring.protocol.partRecord
import me.kumavis.wishring.protocol.record
import me.kumavis.wishring.protocol.u32
import me.kumavis.wishring.protocol.u8

/** Plays the ring's side of the protocol from the description: MTU-sized data notifications, then a reply. */
class FakeRing : RingConnection {
    override val notifications = Channel<RingNotification>(Channel.UNLIMITED)

    var start = 0
    var end = 0
    val collections = HashMap<Int, ByteArray>()
    var recording = false
    var count = 0
    var replyFirst = false
    val refuse = HashMap<Int, Long>()
    val requests = ArrayList<Long>()
    var clockSet: Long? = null
    var onRangeRead: () -> Unit = {}

    override suspend fun writeControl(bytes: ByteArray) {
        val type = bytes.u8(0)
        val address = bytes.u32(1)
        requests += address
        if (type == 2) return
        val data = when {
            address == 0x4003_0005L -> {
                onRangeRead()
                le16(start) + le16(end)
            }
            address == 0x4003_000EL -> bytes(0x09, 0xFF, 0xEA, 0x0E) + le32(0x1234_FFFF) + bytes(count, if (recording) 0x20 else 0)
            address and 0xFFFF_0000L == 0x4002_0000L -> {
                val index = (address and 0xFFFF).toInt()
                refuse[index]?.let {
                    notifications.send(RingNotification.Control(reply(it, 0)))
                    return
                }
                collections[index] ?: error("no collection $index")
            }
            else -> error("unexpected request at 0x${address.toString(16)}")
        }
        val chunks = data.toList().chunked(174).map { RingNotification.Data(it.toByteArray()) }
        val reply = reply(0, data.size.toLong())
        if (replyFirst) notifications.send(RingNotification.Control(reply))
        chunks.forEach { notifications.send(it) }
        if (!replyFirst) {
            notifications.send(RingNotification.Control(reply.copyOfRange(0, 7)))
            notifications.send(RingNotification.Control(reply.copyOfRange(7, 12)))
        }
    }

    override suspend fun writeData(bytes: ByteArray) {
        clockSet = bytes.u32(0)
        notifications.send(RingNotification.Control(reply(0, 4)))
    }

    private fun reply(status: Long, length: Long) = le32(status) + le32(0) + le32(length)
}

private val oneSecond = packBits("1".repeat(10_000))

/** A part of the recording starting at [startIndex]: 10,000 silent samples per [seconds] at 10 kHz. */
fun ringPart(startIndex: Int, final: Boolean, seconds: Int = 1, clock: Long = 1_800_000_000): ByteArray {
    val (bits, stream) = oneSecond
    return collection(
        record(0x35, le32(0xFFFF_FFFFL)),
        partRecord(startIndex.toLong(), !final || seconds > 1, final),
        compressedRecord(0x84, bits * seconds, 10_000, ByteArray(stream.size * seconds) { 0xFF.toByte() }),
        record(0x02, le32(clock)),
    )
}

class MemoryPosition(var next: Int? = null, var count: Int? = null) : SyncPosition {
    override fun nextIndex() = next

    override fun setNextIndex(index: Int) {
        next = index
    }

    override fun lastCount() = count

    override fun setLastCount(count: Int) {
        this.count = count
    }
}
