package me.kumavis.wishring.protocol

import kotlin.math.abs

/** A whole recording: signed 16-bit mono [samples] at [sampleRate] Hz with the DC offset removed. */
class RingRecording(
    val firstIndex: Int,
    val lastIndex: Int,
    val partCount: Int,
    val capturedAtUnixSeconds: Long?,
    val sampleRate: Int,
    val samples: ShortArray,
    val batteryMillivolts: Int?,
    val pressCount: Long?,
) {
    val durationMillis: Long get() = if (sampleRate == 0) 0 else samples.size * 1000L / sampleRate
}

enum class DropReason { INCOMPLETE, MIXED_SAMPLE_RATES, TOO_LONG }

sealed class AssemblyEvent {
    class Finished(val recording: RingRecording) : AssemblyEvent()

    class Dropped(val indexes: List<Int>, val reason: DropReason) : AssemblyEvent()
}

/**
 * Groups collections, fed in the order they were read with their collection index, into recordings. A
 * recording's parts sit at consecutive indexes and share a start index; the final part closes it. A group
 * the next collection does not continue is dropped as incomplete. A group longer than [maxSamples] is
 * dropped (a real recording is at most two minutes) along with its remaining parts. Collections without a
 * part record stand alone.
 */
class RecordingAssembler(private val maxSamples: Int) {
    private val open = ArrayList<Pair<Int, RingCollection>>()
    private var openSamples = 0L
    private var droppingStart: Long? = null

    /** The index of the first collection still waiting for its final part, or null. */
    val pendingFirstIndex: Int? get() = open.firstOrNull()?.first

    fun reset() {
        open.clear()
        openSamples = 0
        droppingStart = null
    }

    fun add(index: Int, collection: RingCollection): List<AssemblyEvent> {
        val events = ArrayList<AssemblyEvent>(2)
        val start = collection.startIndex ?: index.toLong()
        if (droppingStart != null) {
            if (start == droppingStart) {
                if (collection.final) droppingStart = null
                return events
            }
            droppingStart = null
        }
        val last = open.lastOrNull()
        if (last != null && (index != nextCollectionIndex(last.first) || start != (last.second.startIndex ?: last.first.toLong()))) {
            events += AssemblyEvent.Dropped(open.map { it.first }, DropReason.INCOMPLETE)
            open.clear()
            openSamples = 0
        }
        open += index to collection
        openSamples += collection.samples.size
        if (openSamples > maxSamples) {
            events += AssemblyEvent.Dropped(open.map { it.first }, DropReason.TOO_LONG)
            droppingStart = if (collection.final) null else start
            open.clear()
            openSamples = 0
            return events
        }
        if (!collection.final) return events
        val parts = open.toList()
        open.clear()
        openSamples = 0
        val rate = parts.first().second.sampleRate
        if (parts.any { abs(it.second.sampleRate - rate) * 100 > rate }) {
            events += AssemblyEvent.Dropped(parts.map { it.first }, DropReason.MIXED_SAMPLE_RATES)
            return events
        }
        events += AssemblyEvent.Finished(join(parts, rate))
        return events
    }

    private fun join(parts: List<Pair<Int, RingCollection>>, rate: Int): RingRecording {
        val total = parts.sumOf { it.second.samples.size }
        var sum = 0L
        for ((_, part) in parts) for (sample in part.samples) sum += sample
        val mean = if (total == 0) 0 else (sum / total).toInt()
        val out = ShortArray(total)
        var i = 0
        for ((_, part) in parts) {
            for (sample in part.samples) {
                out[i++] = (sample - mean).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
        }
        val first = parts.first().second
        return RingRecording(
            firstIndex = parts.first().first,
            lastIndex = parts.last().first,
            partCount = parts.size,
            capturedAtUnixSeconds = first.capturedAtUnixSeconds,
            sampleRate = rate,
            samples = out,
            batteryMillivolts = parts.last().second.batteryMillivolts,
            pressCount = parts.last().second.pressCount,
        )
    }
}
