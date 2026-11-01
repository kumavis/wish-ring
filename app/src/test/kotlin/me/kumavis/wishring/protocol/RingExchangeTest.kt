package me.kumavis.wishring.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class RingExchangeTest {
    private fun reply(status: Long, length: Long) = le32(status) + le32(0) + le32(length)

    @Test
    fun requestsEncodeAsThirteenBytes() {
        assertArrayEquals(bytes(3, 0x05, 0x00, 0x03, 0x40, 0, 0, 0, 0, 4, 0, 0, 0), RingRequest.ReadRange.encode())
        assertArrayEquals(bytes(3, 0x2A, 0x00, 0x02, 0x40, 0, 0, 0, 0, 0, 0, 0, 0), RingRequest.ReadCollection(42).encode())
        assertArrayEquals(bytes(3, 0x0E, 0x00, 0x03, 0x40, 0, 0, 0, 0, 10, 0, 0, 0), RingRequest.ReadAdvertisement.encode())
        val clock = RingRequest.SetClock(0x6500_0000L)
        assertArrayEquals(bytes(2, 0x01, 0x00, 0x03, 0x40, 0, 0, 0, 0, 4, 0, 0, 0), clock.encode())
        assertArrayEquals(bytes(0, 0, 0, 0x65), clock.payload)
    }

    @Test
    fun dataBeforeASplitReply() {
        val x = RingExchange(RingRequest.ReadRange)
        assertNull(x.onData(bytes(1, 0)))
        assertNull(x.onData(bytes(5, 0)))
        assertNull(x.onControl(reply(0, 4).copyOfRange(0, 5)))
        val outcome = x.onControl(reply(0, 4).copyOfRange(5, 12))
        assertTrue(outcome is RingOutcome.Done)
        assertArrayEquals(bytes(1, 0, 5, 0), (outcome as RingOutcome.Done).data)
    }

    @Test
    fun replyBeforeData() {
        val x = RingExchange(RingRequest.ReadRange)
        assertNull(x.onControl(reply(0, 4)))
        assertNull(x.onData(bytes(1, 0, 5)))
        assertTrue(x.onData(bytes(0)) is RingOutcome.Done)
    }

    @Test
    fun refusalEndsTheExchange() {
        val x = RingExchange(RingRequest.ReadCollection(1))
        assertEquals(RingOutcome.Refused(0x43), x.onControl(reply(0x43, 0)))
        assertThrows(RingProtocolException::class.java) { x.onData(bytes(1)) }
    }

    @Test
    fun aWriteCompletesOnItsReply() {
        val x = RingExchange(RingRequest.SetClock(1))
        assertTrue(x.onControl(reply(0, 4)) is RingOutcome.Done)
    }

    @Test
    fun moreDataThanTheRequestAllowsIsRefused() {
        val x = RingExchange(RingRequest.ReadRange)
        assertThrows(RingProtocolException::class.java) { x.onData(ByteArray(5)) }
    }

    @Test
    fun advertisementReadParses() {
        val read = bytes(0x09, 0xFF, 0xEA, 0x0E) + le32(0x1234_FFFF) + bytes(200, 0x60)
        val adv = RingAdvertisement.fromAdStructure(read)
        assertEquals(200, adv.collectionCount)
        assertTrue(adv.recording)
        assertTrue(adv.needsServicing)
        assertTrue(adv.pairable)
        assertEquals(RingImage.FAILSAFE, RingAdvertisement.fromManufacturerData(le32(0xDEAD_DEADL) + bytes(0, 0))!!.image)
        assertNull(RingAdvertisement.fromManufacturerData(bytes(1, 2, 3)))
    }

    @Test
    fun rangeResumesInsideOrAtTheEndElseRestarts() {
        val range = RingRange.parse(le16(65530) + le16(4))
        assertEquals(10, range.size)
        assertEquals(65530, range.resumeFrom(null))
        assertEquals(65535, range.resumeFrom(65535))
        assertEquals(2, range.resumeFrom(2))
        assertEquals(4, range.resumeFrom(4))
        assertEquals(65530, range.resumeFrom(5))
        assertEquals(65530, range.resumeFrom(100))
        assertEquals(0, nextCollectionIndex(65535))
    }
}
