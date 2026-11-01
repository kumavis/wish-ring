package me.kumavis.wishring.protocol

private const val INDEX_MASK = 0xFFFF

fun nextCollectionIndex(index: Int): Int = (index + 1) and INDEX_MASK

/**
 * The collections the ring holds: indexes from [start] up to but not including [end], modulo 65536. The
 * ring never deletes after a sync, so the phone remembers where it got to.
 */
data class RingRange(val start: Int, val end: Int) {
    val size: Int get() = (end - start) and INDEX_MASK

    /**
     * Where a sync continues: [saved] (the first index not yet stored) when it lies inside the range or at
     * its end, otherwise [start], which happens after the ring wrapped past it or was erased.
     */
    fun resumeFrom(saved: Int?): Int {
        if (saved == null) return start
        return if (((saved - start) and INDEX_MASK) <= size) saved else start
    }

    companion object {
        fun parse(data: ByteArray): RingRange {
            if (data.size != 4) throw RingProtocolException("a stored range of ${data.size} bytes")
            return RingRange(data.u16(0), data.u16(2))
        }
    }
}
