package me.kumavis.wishring.protocol

/** The largest collection the ring stores: one flash page. */
const val MAX_COLLECTION_BYTES = 4096

const val REQUEST_BYTES = 13

private const val TYPE_WRITE = 2
private const val TYPE_READ = 3

private const val ADDRESS_CLOCK = 0x4003_0001L
private const val ADDRESS_RANGE = 0x4003_0005L
private const val ADDRESS_ADVERTISEMENT = 0x4003_000EL
private const val ADDRESS_COLLECTIONS = 0x4002_0000L

/**
 * A request on the control channel: a type, an address, an offset (always zero here) and a length. Only
 * the four requests a sync needs exist; there is deliberately no erase and no firmware write.
 * [replyLimit] bounds the data bytes a reply may carry. A write's [payload] follows on the data channel.
 */
sealed class RingRequest(
    private val type: Int,
    val address: Long,
    val length: Long,
    val replyLimit: Int,
    val payload: ByteArray = ByteArray(0),
) {
    val isWrite: Boolean get() = type == TYPE_WRITE

    /** The stored range: u16 start, u16 end. */
    object ReadRange : RingRequest(TYPE_READ, ADDRESS_RANGE, 4, 4) {
        override fun toString() = "ReadRange"
    }

    /** The whole collection at [index] (length 0 means the whole object). */
    class ReadCollection(val index: Int) : RingRequest(TYPE_READ, ADDRESS_COLLECTIONS or index.toLong(), 0, MAX_COLLECTION_BYTES) {
        init {
            require(index in 0..0xFFFF) { "collection index $index is not 16-bit" }
        }

        override fun toString() = "ReadCollection($index)"
    }

    /** The current advertisement as one AD structure. */
    object ReadAdvertisement : RingRequest(TYPE_READ, ADDRESS_ADVERTISEMENT, 10, 10) {
        override fun toString() = "ReadAdvertisement"
    }

    /** Sets the ring's clock to [unixSeconds]; sent when the advertisement asks for servicing. */
    class SetClock(val unixSeconds: Long) : RingRequest(TYPE_WRITE, ADDRESS_CLOCK, 4, 0, u32Bytes(unixSeconds)) {
        init {
            require(unixSeconds in 0..U32_MASK) { "the ring's clock is unsigned 32-bit seconds" }
        }

        override fun toString() = "SetClock($unixSeconds)"
    }

    fun encode(): ByteArray = ByteArray(REQUEST_BYTES).also {
        it[0] = type.toByte()
        it.putU32(1, address)
        it.putU32(5, 0)
        it.putU32(9, length)
    }
}
