package me.kumavis.wishring.sync

import kotlinx.coroutines.test.runTest
import me.kumavis.wishring.protocol.RingRecording
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RingSessionTest {
    private val stored = ArrayList<RingRecording>()
    private var accept = true
    private val sink = RecordingSink { stored += it; accept }
    private val advertisement = me.kumavis.wishring.protocol.RingAdvertisement(0x1234_FFFF, 3, needsServicing = false, recording = false)

    private fun session(ring: FakeRing, position: MemoryPosition) =
        RingSession(ring, position, sink, nowUnixSeconds = { 1_800_000_123 }, exchangeTimeoutMillis = 1_000, pollMillis = 1)

    @Test
    fun syncsAMultiPartAndASingleRecording() = runTest {
        val ring = FakeRing().apply {
            start = 10
            end = 13
            count = 3
            collections[10] = ringPart(10, final = false)
            collections[11] = ringPart(10, final = true)
            collections[12] = ringPart(12, final = true, seconds = 2)
        }
        val position = MemoryPosition()
        assertEquals(SessionResult.SYNCED, session(ring, position).run(advertisement))
        assertEquals(2, stored.size)
        assertEquals(10, stored[0].firstIndex)
        assertEquals(11, stored[0].lastIndex)
        assertEquals(20_000, stored[0].samples.size)
        assertEquals(10_000, stored[0].sampleRate)
        assertEquals(1_800_000_000L, stored[0].capturedAtUnixSeconds)
        assertEquals(12, stored[1].firstIndex)
        assertEquals(13, position.next)
        assertEquals(3, position.count)
        assertNull(ring.clockSet)
    }

    @Test
    fun resumesFromTheSavedPositionAndSetsTheClockWhenAsked() = runTest {
        val ring = FakeRing().apply {
            start = 0
            end = 3
            collections[0] = ringPart(0, final = true)
            collections[1] = ringPart(1, final = true)
            collections[2] = ringPart(2, final = true)
        }
        val position = MemoryPosition(next = 2)
        session(ring, position).run(advertisement.copy(needsServicing = true))
        assertEquals(1_800_000_123L, ring.clockSet)
        assertEquals(listOf(2), stored.map { it.firstIndex })
        assertEquals(3, position.next)
    }

    @Test
    fun restartsWhenTheRingWrappedPastTheSavedPosition() = runTest {
        val ring = FakeRing().apply {
            start = 100
            end = 101
            collections[100] = ringPart(100, final = true)
        }
        val position = MemoryPosition(next = 5)
        session(ring, position).run(advertisement)
        assertEquals(listOf(100), stored.map { it.firstIndex })
        assertEquals(101, position.next)
    }

    @Test
    fun keepsReadingWhileTheRingIsStillRecording() = runTest {
        val ring = FakeRing().apply {
            start = 0
            end = 1
            recording = true
            collections[0] = ringPart(0, final = false)
            var rounds = 0
            onRangeRead = {
                rounds++
                if (rounds == 2) {
                    collections[1] = ringPart(0, final = true)
                    end = 2
                    recording = false
                }
            }
        }
        val position = MemoryPosition()
        assertEquals(SessionResult.SYNCED, session(ring, position).run(advertisement.copy(recording = true)))
        assertEquals(1, stored.size)
        assertEquals(2, stored[0].partCount)
        assertEquals(2, position.next)
    }

    @Test
    fun anUnfinishedRecordingKeepsThePositionAtItsFirstPart() = runTest {
        val ring = FakeRing().apply {
            start = 0
            end = 2
            collections[0] = ringPart(0, final = true)
            collections[1] = ringPart(1, final = false)
        }
        val position = MemoryPosition()
        session(ring, position).run(advertisement)
        assertEquals(listOf(0), stored.map { it.firstIndex })
        assertEquals(1, position.next)
    }

    @Test
    fun aRefusedCollectionIsSkippedNextTime() = runTest {
        val ring = FakeRing().apply {
            start = 0
            end = 2
            refuse[0] = 0x43
            collections[1] = ringPart(1, final = true)
        }
        val position = MemoryPosition()
        val failure = runCatching { session(ring, position).run(advertisement) }.exceptionOrNull()
        assertTrue(failure is RingRefusedException)
        assertEquals(1, position.next)
    }

    @Test
    fun aBrokenPageIsSkippedWithoutStoppingTheSync() = runTest {
        val ring = FakeRing().apply {
            start = 0
            end = 2
            collections[0] = ByteArray(10)
            collections[1] = ringPart(1, final = true)
        }
        val position = MemoryPosition()
        session(ring, position).run(advertisement)
        assertEquals(listOf(1), stored.map { it.firstIndex })
        assertEquals(2, position.next)
    }

    @Test
    fun stopsWithoutAdvancingWhenTheSinkRefuses() = runTest {
        accept = false
        val ring = FakeRing().apply {
            start = 0
            end = 1
            collections[0] = ringPart(0, final = true)
        }
        val position = MemoryPosition()
        assertEquals(SessionResult.NOT_STORED, session(ring, position).run(advertisement))
        assertEquals(0, position.next)
        assertNull(position.count)
    }

    @Test
    fun replyFirstOrderingAlsoWorks() = runTest {
        val ring = FakeRing().apply {
            replyFirst = true
            start = 0
            end = 1
            collections[0] = ringPart(0, final = true)
        }
        session(ring, MemoryPosition()).run(advertisement)
        assertEquals(1, stored.size)
    }

    @Test
    fun hasNewsLogic() {
        assertTrue(ringHasNews(advertisement, null))
        assertTrue(ringHasNews(advertisement, 2))
        assertTrue(!ringHasNews(advertisement, 3))
        assertTrue(ringHasNews(advertisement.copy(recording = true), 3))
        assertTrue(ringHasNews(advertisement.copy(needsServicing = true), 3))
        assertTrue(!ringHasNews(advertisement.copy(fingerprint = 0xDEAD_DEADL), 2))
    }
}
