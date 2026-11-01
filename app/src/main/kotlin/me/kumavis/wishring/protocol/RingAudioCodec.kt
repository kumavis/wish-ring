package me.kumavis.wishring.protocol

/**
 * Decodes the ring's compressed audio (record tag 0x51) into unsigned 16-bit samples.
 *
 * [config]: high nibble H is the escape threshold, low nibble L the number of low bits dropped from every
 * sample. [bitCount] bits of [stream] starting at [from] follow, most significant bit of each byte first.
 * Every code is a correction to the running difference between samples: a single 1 bit for no change, a
 * unary magnitude (zeros, terminated by a 1) followed by a sign bit, or H-1 zeros followed by a raw
 * (16 - L)-bit value. The state starts at zero for every collection.
 */
fun decodeRingAudio(config: Int, bitCount: Long, stream: ByteArray, from: Int, to: Int): IntArray {
    if (bitCount < 0 || bitCount > (to - from).toLong() * 8) {
        throw RingProtocolException("$bitCount audio bits do not fit in ${to - from} bytes")
    }
    val escapeAt = (config ushr 4) and 0xF
    val dropped = config and 0xF
    val width = 16 - dropped
    val modulus = 1 shl width
    val mask = modulus - 1
    val reader = BitReader(stream, from, bitCount)
    // Every code is at least one bit, so the sample count is bounded by the bit count.
    val out = IntArray(bitCount.toInt())
    var count = 0
    var delta = 0
    var word = 0
    while (reader.remaining > 0) {
        val correction: Int
        if (reader.bit() == 1) {
            correction = 0
        } else {
            var n = 0
            var escaped = false
            while (true) {
                if (escapeAt <= n + 1) {
                    escaped = true
                    break
                }
                n++
                if (reader.bit() == 1) break
            }
            correction = when {
                escaped -> reader.bits(width)
                reader.bit() == 1 -> (modulus - n) and mask
                else -> n and mask
            }
        }
        delta = (delta + correction) and mask
        word = (word + delta) and mask
        out[count++] = word shl dropped
    }
    return out.copyOf(count)
}

private class BitReader(private val bytes: ByteArray, private val from: Int, private val bitCount: Long) {
    private var position = 0L

    val remaining: Long get() = bitCount - position

    fun bit(): Int {
        if (position >= bitCount) throw RingProtocolException("the audio bitstream ends inside a code")
        val byte = bytes[from + (position ushr 3).toInt()].toInt()
        val shift = 7 - (position and 7).toInt()
        position++
        return (byte ushr shift) and 1
    }

    /** [count] bits as one value, first bit read most significant. */
    fun bits(count: Int): Int {
        var value = 0
        repeat(count) { value = (value shl 1) or bit() }
        return value
    }
}
