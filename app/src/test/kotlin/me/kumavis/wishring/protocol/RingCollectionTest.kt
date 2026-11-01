package me.kumavis.wishring.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RingCollectionTest {
    private val silence = packBits("1".repeat(1000))

    private fun page(vararg extra: ByteArray, final: Boolean = true, start: Long = 7, clock: Long = 1_800_000_000, offset: Long = 0xFFFF_FFFFL) = collection(
        record(0x35, le32(offset)),
        partRecord(start, !final, final),
        record(0x53, le32(0) + le32(1)),
        compressedRecord(0x84, silence.first, 9997, silence.second),
        record(0x02, le32(clock)),
        record(0x25, bytes(0x0b, 0x00, 0x03, 0x4b)),
        record(0x01, bytes(1, 2, 3, 4, 5, 6)),
        record(0x54, le32(42)),
        record(0x37, le16(2987)),
        *extra,
    )

    @Test
    fun parsesEveryRecordTheRingSends() {
        val c = parseRingCollection(page())
        assertEquals(1000, c.samples.size)
        assertEquals(9997, c.sampleRate)
        assertEquals(7L, c.startIndex)
        assertFalse(c.multiPart)
        assertTrue(c.final)
        assertEquals(1_800_000_000L, c.capturedAtUnixSeconds)
        assertEquals(42L, c.lifetimeCount)
        assertEquals(2987, c.batteryMillivolts)
        assertEquals(1L, c.pressCount)
        assertArrayEquals(bytes(0x0b, 0x00, 0x03, 0x4b), c.platformVersion)
    }

    @Test
    fun timestampCombinesClockAndOffsetBeforeTheClockIsSet() {
        val c = parseRingCollection(page(clock = 100, offset = 1_799_999_899))
        assertEquals(1_800_000_000L, c.capturedAtUnixSeconds)
        assertNull(parseRingCollection(collection(partRecord(1, false, true))).capturedAtUnixSeconds)
    }

    @Test
    fun declaredLengthMustMatch() {
        val good = page()
        val short = good.copyOf(good.size - 1)
        assertThrows(RingProtocolException::class.java) { parseRingCollection(short) }
    }

    @Test
    fun unknownRecordsAreSkipped() {
        val c = parseRingCollection(page(record(0x77, bytes(9, 9, 9))))
        assertEquals(1000, c.samples.size)
    }

    @Test
    fun pcmRecordIsMovedToTheUnsignedScale() {
        val c = parseRingCollection(collection(partRecord(3, false, true), record(0x50, le32(8000) + le16(0) + le16(0x8000) + le16(0x7FFF))))
        assertArrayEquals(intArrayOf(32768, 0, 65535), c.samples)
        assertEquals(8000, c.sampleRate)
    }

    @Test
    fun aCollectionWithoutAudioHasNoSamples() {
        val c = parseRingCollection(collection(partRecord(3, false, true)))
        assertEquals(0, c.samples.size)
        assertEquals(0L, c.durationMillis)
    }

    @Test
    fun aTapIsAShortCollection() {
        val tap = packBits("1".repeat(200))
        val c = parseRingCollection(collection(partRecord(9, false, true), compressedRecord(0x84, tap.first, 9997, tap.second)))
        assertTrue(c.durationMillis < 1000)
    }
}
