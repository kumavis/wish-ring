package me.kumavis.wishring.protocol

fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

fun le16(value: Int): ByteArray = bytes(value, value ushr 8)

fun le32(value: Long): ByteArray = u32Bytes(value)

/** A record with the length width the parser expects for [tag]. */
fun record(tag: Int, payload: ByteArray): ByteArray {
    val length = if (tag == 0x50 || tag == 0x51) le32(payload.size.toLong()) else le16(payload.size)
    return bytes(tag) + length + payload
}

/** A collection as the ring sends it: a 24-bit little-endian length, a zero byte, then the records. */
fun collection(vararg records: ByteArray): ByteArray {
    val body = records.fold(ByteArray(0)) { acc, next -> acc + next }
    val size = body.size + 4
    return bytes(size and 0xFF, (size ushr 8) and 0xFF, (size ushr 16) and 0xFF, 0) + body
}

fun partRecord(startIndex: Long, multiPart: Boolean, final: Boolean): ByteArray =
    record(0x52, le32(startIndex) + bytes(if (multiPart) 1 else 0, if (final) 1 else 0))

fun compressedRecord(config: Int, bitCount: Long, rate: Long, stream: ByteArray): ByteArray =
    record(0x51, bytes(config) + le32(bitCount) + le32(rate) + stream)

/** Packs a string of 0s and 1s most significant bit first; returns the bit count and the bytes. */
fun packBits(bits: String): Pair<Long, ByteArray> {
    val clean = bits.filter { it == '0' || it == '1' }
    val out = ByteArray((clean.length + 7) / 8)
    clean.forEachIndexed { i, c -> if (c == '1') out[i / 8] = (out[i / 8].toInt() or (0x80 ushr (i % 8))).toByte() }
    return clean.length.toLong() to out
}

/**
 * Encodes unsigned samples the way the description says the ring's bitstream is laid out, for round trips
 * through the decoder. Written from the description's decode pseudocode, inverted.
 */
fun encodeRingAudio(config: Int, samples: IntArray): Pair<Long, ByteArray> {
    val escapeAt = (config ushr 4) and 0xF
    val dropped = config and 0xF
    val width = 16 - dropped
    val modulus = 1 shl width
    val mask = modulus - 1
    val bits = StringBuilder()
    var delta = 0
    var word = 0
    for (sample in samples) {
        val target = (sample ushr dropped) and mask
        val newDelta = (target - word) and mask
        val correction = (newDelta - delta) and mask
        val signed = if (correction >= modulus / 2) correction - modulus else correction
        val magnitude = kotlin.math.abs(signed)
        when {
            correction == 0 -> bits.append('1')
            magnitude in 1..(escapeAt - 1) ->
                bits.append('0').append("0".repeat(magnitude - 1)).append('1').append(if (signed < 0) '1' else '0')
            else -> bits.append('0').append("0".repeat(maxOf(escapeAt - 1, 0))).append(correction.toString(2).padStart(width, '0'))
        }
        delta = newDelta
        word = target
    }
    return packBits(bits.toString())
}
