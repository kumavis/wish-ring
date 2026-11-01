package me.kumavis.wishring.sync

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.kumavis.wishring.protocol.AssemblyEvent
import me.kumavis.wishring.protocol.RecordingAssembler
import me.kumavis.wishring.protocol.RingAdvertisement
import me.kumavis.wishring.protocol.RingCollection
import me.kumavis.wishring.protocol.RingExchange
import me.kumavis.wishring.protocol.RingOutcome
import me.kumavis.wishring.protocol.RingProtocolException
import me.kumavis.wishring.protocol.RingRange
import me.kumavis.wishring.protocol.RingRequest
import me.kumavis.wishring.protocol.nextCollectionIndex
import me.kumavis.wishring.protocol.parseRingCollection

class RingRefusedException(val request: RingRequest, val status: Long) :
    Exception("the ring refused $request with status 0x${status.toString(16)}")

class RingTimeoutException(val request: RingRequest) : Exception("no answer to $request")

enum class SessionResult { SYNCED, NOT_STORED, STILL_RECORDING }

/** Whether a ring advertising [advertisement] has something to sync, given the count its last sync ended at. */
fun ringHasNews(advertisement: RingAdvertisement, lastCount: Int?): Boolean =
    advertisement.pairable &&
        (lastCount == null || advertisement.collectionCount != lastCount || advertisement.needsServicing || advertisement.recording)

/**
 * One sync over an open [connection]. Sets the clock if the ring asked for it, then reads every collection
 * from the saved position to the end of the stored range and hands each finished recording to [sink],
 * re-reading the range while the ring reports that its button is still held. The position moves past a
 * recording once it is stored, and past collections the parser or the ring refuses, so a broken page is
 * never retried forever; an unfinished recording is read again next time, since the ring keeps everything.
 */
class RingSession(
    private val connection: RingConnection,
    private val position: SyncPosition,
    private val sink: RecordingSink,
    private val nowUnixSeconds: () -> Long,
    private val maxRecordingSamples: Int = 150 * 16_000,
    private val exchangeTimeoutMillis: Long = 10_000,
    private val pollMillis: Long = 50,
    private val maxDurationMillis: Long = 10 * 60_000,
    private val log: (String) -> Unit = {},
) {
    suspend fun run(advertisement: RingAdvertisement): SessionResult {
        val startedAt = System.nanoTime()
        if (advertisement.needsServicing) {
            val seconds = nowUnixSeconds()
            log("setting the ring's clock to $seconds")
            exchange(RingRequest.SetClock(seconds))
        }
        val assembler = RecordingAssembler(maxRecordingSamples)
        var next: Int? = null
        while (true) {
            // Read the advertisement before the range so the count saved at the end is one this round read up to.
            val current = RingAdvertisement.fromAdStructure(exchange(RingRequest.ReadAdvertisement))
            val range = RingRange.parse(exchange(RingRequest.ReadRange))
            val wanted = next ?: position.nextIndex()
            val resumeAt = range.resumeFrom(wanted)
            if (resumeAt != wanted) {
                log("range ${range.start}..${range.end} no longer holds $wanted; restarting at $resumeAt")
                assembler.reset()
                position.setNextIndex(resumeAt)
            }
            next = resumeAt
            log("range ${range.start}..${range.end}, resuming at $resumeAt, count ${current.collectionCount}, recording=${current.recording}")
            while (next != range.end) {
                val index = next!!
                next = nextCollectionIndex(index)
                val bytes = try {
                    exchange(RingRequest.ReadCollection(index))
                } catch (e: RingRefusedException) {
                    // The refusal is about this page; move past it rather than stopping here every time.
                    position.setNextIndex(next)
                    throw e
                }
                val collection: RingCollection? = try {
                    parseRingCollection(bytes)
                } catch (e: RingProtocolException) {
                    log("skipping collection $index: ${e.message}")
                    null
                }
                if (collection == null) {
                    if (assembler.pendingFirstIndex == null) position.setNextIndex(next)
                    continue
                }
                for (event in assembler.add(index, collection)) {
                    when (event) {
                        is AssemblyEvent.Dropped -> log("dropped collections ${event.indexes}: ${event.reason}")
                        is AssemblyEvent.Finished -> {
                            val recording = event.recording
                            // Store and advance together, so a cancelled sync never imports the same recording twice.
                            val stored = withContext(NonCancellable) {
                                sink.store(recording).also { if (it) position.setNextIndex(nextCollectionIndex(recording.lastIndex)) }
                            }
                            if (!stored) return SessionResult.NOT_STORED
                        }
                    }
                }
                position.setNextIndex(assembler.pendingFirstIndex ?: next)
            }
            if (!current.recording) {
                position.setLastCount(current.collectionCount)
                return SessionResult.SYNCED
            }
            if ((System.nanoTime() - startedAt) / 1_000_000 >= maxDurationMillis) return SessionResult.STILL_RECORDING
            delay(pollMillis)
        }
    }

    private suspend fun exchange(request: RingRequest): ByteArray {
        if (connection.notifications.tryReceive().isSuccess) {
            throw RingProtocolException("bytes from the ring between requests")
        }
        val exchange = RingExchange(request)
        val outcome = withTimeoutOrNull(exchangeTimeoutMillis) {
            connection.writeControl(request.encode())
            if (request.isWrite) connection.writeData(request.payload)
            var outcome: RingOutcome? = null
            while (outcome == null) {
                outcome = when (val notification = connection.notifications.receive()) {
                    is RingNotification.Control -> exchange.onControl(notification.bytes)
                    is RingNotification.Data -> exchange.onData(notification.bytes)
                }
            }
            outcome
        } ?: throw RingTimeoutException(request)
        return when (outcome) {
            is RingOutcome.Done -> outcome.data
            is RingOutcome.Refused -> throw RingRefusedException(request, outcome.status)
        }
    }
}
