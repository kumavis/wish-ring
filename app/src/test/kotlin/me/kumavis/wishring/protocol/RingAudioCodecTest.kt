package me.kumavis.wishring.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RingAudioCodecTest {
    private fun decode(config: Int, bits: String): IntArray {
        val (count, stream) = packBits(bits)
        return decodeRingAudio(config, count, stream, 0, stream.size)
    }

    @Test
    fun descriptionExamplesWithThresholdFour() {
        // H = 4, L = 4: "1" -> 0, "010" -> +1, "011" -> -1, "0010" -> +2, "00011" -> -3, "0000" + 12 bits -> escape.
        // Each correction changes the running delta, which accumulates into the word.
        val samples = decode(0x44, "1 010 1 011 1")
        // deltas: 0, 1, 1, 0, 0 -> words: 0, 1, 2, 2, 2 -> shifted by 4 bits
        assertArrayEquals(intArrayOf(0, 16, 32, 32, 32), samples)
    }

    @Test
    fun negativeAndEscapedCorrections() {
        val samples = decode(0x44, "0010 00011 0000 000000000111")
        // corrections: +2, -3, +7 -> deltas: 2, -1 (4095), 6 -> words: 2, 1, 7
        assertArrayEquals(intArrayOf(2 shl 4, 1 shl 4, 7 shl 4), samples)
    }

    @Test
    fun stopsCleanlyAtBitCountAndIgnoresPadding() {
        val (count, stream) = packBits("1 1 1")
        val padded = stream + bytes(0xFF, 0xFF)
        assertEquals(3, decodeRingAudio(0x84, count, padded, 0, padded.size).size)
    }

    @Test
    fun zeroBitsDecodeToNothing() {
        assertEquals(0, decodeRingAudio(0x84, 0, ByteArray(0), 0, 0).size)
    }

    @Test
    fun bitstreamEndingInsideACodeIsRefused() {
        assertThrows(RingProtocolException::class.java) { decode(0x84, "00") }
    }

    @Test
    fun roundTripsThroughTheTestEncoder() {
        val config = 0x84
        var state = 12345L
        var word = 0
        val samples = IntArray(5000) {
            state = state * 6364136223846793005L + 1442695040888963407L
            val roll = (state ushr 33).toInt()
            word = if (roll % 11 == 0) roll and 0xFFFF else (word + (roll % 61) - 30) and 0xFFFF
            word and 0xFFF0
        }
        val (count, stream) = encodeRingAudio(config, samples)
        val decoded = decodeRingAudio(config, count, stream, 0, stream.size)
        assertArrayEquals(samples, decoded)
    }
}
