package me.kumavis.wishring.store

/** A 16-bit PCM mono WAV file holding [samples] at [sampleRate] Hz. */
fun pcm16Wav(samples: ShortArray, sampleRate: Int): ByteArray {
    val dataBytes = samples.size * 2
    val out = ByteArray(44 + dataBytes)
    fun ascii(at: Int, text: String) = text.forEachIndexed { i, c -> out[at + i] = c.code.toByte() }
    fun le16(at: Int, value: Int) {
        out[at] = value.toByte()
        out[at + 1] = (value shr 8).toByte()
    }
    fun le32(at: Int, value: Int) {
        le16(at, value and 0xFFFF)
        le16(at + 2, (value ushr 16) and 0xFFFF)
    }
    ascii(0, "RIFF")
    le32(4, 36 + dataBytes)
    ascii(8, "WAVE")
    ascii(12, "fmt ")
    le32(16, 16)
    le16(20, 1) // PCM
    le16(22, 1) // mono
    le32(24, sampleRate)
    le32(28, sampleRate * 2)
    le16(32, 2)
    le16(34, 16)
    ascii(36, "data")
    le32(40, dataBytes)
    var at = 44
    for (sample in samples) {
        out[at++] = sample.toByte()
        out[at++] = (sample.toInt() shr 8).toByte()
    }
    return out
}
