package me.kumavis.wishring.protocol

private const val TAG_DEVICE_ID = 0x01
private const val TAG_CLOCK = 0x02
private const val TAG_PLATFORM = 0x25
private const val TAG_CLOCK_OFFSET = 0x35
private const val TAG_BATTERY = 0x37
private const val TAG_PCM = 0x50
private const val TAG_COMPRESSED = 0x51
private const val TAG_PART = 0x52
private const val TAG_PRESSES = 0x53
private const val TAG_LIFETIME_COUNT = 0x54

/** A sample rate above this is taken as a parse error rather than a real rate. */
const val MAX_SAMPLE_RATE = 192_000

/**
 * One collection read from the ring: one flash page holding a slice of audio plus the records that say which
 * recording it belongs to and when it was made. [samples] are unsigned 16-bit values at [sampleRate] Hz.
 * Collections sharing [startIndex] are the parts of one recording; the [final] one closes it.
 */
class RingCollection(
    val samples: IntArray,
    val sampleRate: Int,
    val startIndex: Long?,
    val multiPart: Boolean,
    val final: Boolean,
    val ringClock: Long?,
    val clockOffset: Long?,
    val pressBitmask: Long?,
    val pressCount: Long?,
    val lifetimeCount: Long?,
    val batteryMillivolts: Int?,
    val platformVersion: ByteArray?,
    val deviceId: ByteArray?,
) {
    /**
     * When the collection was made, as Unix seconds. Before the phone sets the ring's clock the clock
     * counts from zero and the offset holds the difference; after, the offset is all ones and the clock is
     * Unix time. The same formula covers both.
     */
    val capturedAtUnixSeconds: Long?
        get() = if (ringClock == null || clockOffset == null) null else (ringClock + clockOffset + 1) and U32_MASK

    val durationMillis: Long get() = if (sampleRate == 0) 0 else samples.size * 1000L / sampleRate
}

/**
 * Parses the answer to [RingRequest.ReadCollection]. The header is a length (24-bit little-endian when
 * byte 3 is zero, the only form seen on hardware; the two other forms the description mentions are
 * accepted too), then records of a tag, a length (32-bit for audio tags, 16-bit otherwise) and a payload.
 * Unknown records are skipped. Parsing is deterministic, so the same bytes fail the same way every time.
 */
fun parseRingCollection(data: ByteArray): RingCollection {
    if (data.size < 4) throw RingProtocolException("a collection of ${data.size} bytes")
    if (data.size > MAX_COLLECTION_BYTES) throw RingProtocolException("a collection of ${data.size} bytes, more than a page")
    val (recordsAt, declared) = when {
        data.u8(3) == 0 -> 4 to data.u24(0)
        data.u8(0) == 0xFF -> 3 to data.u16(1)
        else -> 3 to ((data.u8(0) shl 16) or (data.u8(1) shl 8) or data.u8(2))
    }
    if (declared < recordsAt || declared > data.size) {
        throw RingProtocolException("a collection of ${data.size} bytes declaring $declared")
    }

    var samples: IntArray? = null
    var sampleRate = 0
    var startIndex: Long? = null
    var multiPart = false
    var final = true
    var clock: Long? = null
    var offset: Long? = null
    var pressBitmask: Long? = null
    var pressCount: Long? = null
    var lifetime: Long? = null
    var battery: Int? = null
    var platform: ByteArray? = null
    var deviceId: ByteArray? = null

    var at = recordsAt
    while (at < declared) {
        val tag = data.u8(at)
        val wide = tag == TAG_PCM || tag == TAG_COMPRESSED
        val headerSize = if (wide) 5 else 3
        if (declared - at < headerSize) throw RingProtocolException("record 0x${tag.toString(16)} header runs past the end")
        val length = if (wide) data.u32(at + 1) else data.u16(at + 1).toLong()
        val start = at + headerSize
        if (length > declared - start) throw RingProtocolException("record 0x${tag.toString(16)} of $length bytes runs past the end")
        val end = start + length.toInt()
        when (tag) {
            TAG_COMPRESSED -> {
                if (length < 9) throw RingProtocolException("a compressed audio record of $length bytes")
                sampleRate = checkRate(data.u32(start + 5))
                val decoded = decodeRingAudio(data.u8(start), data.u32(start + 1), data, start + 9, end)
                samples = samples?.plus(decoded) ?: decoded
            }
            TAG_PCM -> {
                if (length < 4 || (length - 4) % 2 != 0L) throw RingProtocolException("a PCM record of $length bytes")
                sampleRate = checkRate(data.u32(start))
                // Signed samples on the wire, moved to the unsigned scale the compressed form decodes to.
                val decoded = IntArray(((length - 4) / 2).toInt()) { data.u16(start + 4 + 2 * it).toShort() + 32768 }
                samples = samples?.plus(decoded) ?: decoded
            }
            TAG_PART -> {
                need(tag, length, 6)
                startIndex = data.u32(start)
                multiPart = data.u8(start + 4) != 0
                final = data.u8(start + 5) != 0
            }
            TAG_CLOCK -> {
                need(tag, length, 4)
                clock = data.u32(start)
            }
            TAG_CLOCK_OFFSET -> {
                need(tag, length, 4)
                offset = data.u32(start)
            }
            TAG_PRESSES -> {
                need(tag, length, 8)
                pressBitmask = data.u32(start)
                pressCount = data.u32(start + 4)
            }
            TAG_LIFETIME_COUNT -> {
                need(tag, length, 4)
                lifetime = data.u32(start)
            }
            TAG_BATTERY -> {
                need(tag, length, 2)
                battery = data.u16(start)
            }
            TAG_PLATFORM -> platform = data.copyOfRange(start, end)
            TAG_DEVICE_ID -> deviceId = data.copyOfRange(start, end)
        }
        at = end
    }
    return RingCollection(
        samples = samples ?: IntArray(0),
        sampleRate = sampleRate,
        startIndex = startIndex,
        multiPart = multiPart,
        final = final,
        ringClock = clock,
        clockOffset = offset,
        pressBitmask = pressBitmask,
        pressCount = pressCount,
        lifetimeCount = lifetime,
        batteryMillivolts = battery,
        platformVersion = platform,
        deviceId = deviceId,
    )
}

private fun need(tag: Int, length: Long, least: Int) {
    if (length < least) throw RingProtocolException("record 0x${tag.toString(16)} of $length bytes")
}

private fun checkRate(rate: Long): Int {
    if (rate !in 1..MAX_SAMPLE_RATE.toLong()) throw RingProtocolException("a sample rate of $rate")
    return rate.toInt()
}
