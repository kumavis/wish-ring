package me.kumavis.wishring.sync

import kotlinx.coroutines.channels.ReceiveChannel

/** A notification from the ring, tagged with the characteristic it arrived on. */
sealed class RingNotification {
    class Control(val bytes: ByteArray) : RingNotification()

    class Data(val bytes: ByteArray) : RingNotification()
}

/**
 * A connected ring with notifications enabled on its control and data characteristics. [notifications]
 * carries both channels in arrival order; writes go out without response. The Android link implements
 * this; tests use a fake ring.
 */
interface RingConnection {
    val notifications: ReceiveChannel<RingNotification>

    suspend fun writeControl(bytes: ByteArray)

    suspend fun writeData(bytes: ByteArray)
}

/** Where a ring's sync stands, kept across sessions and app restarts. */
interface SyncPosition {
    /** The first collection index not yet stored or given up on; null before the first sync. */
    fun nextIndex(): Int?

    fun setNextIndex(index: Int)

    /** The advertised collection count when the last completed sync ended. */
    fun lastCount(): Int?

    fun setLastCount(count: Int)
}

/** Takes a finished recording; false when it could not be stored, which stops the sync before it moves on. */
fun interface RecordingSink {
    suspend fun store(recording: me.kumavis.wishring.protocol.RingRecording): Boolean
}
