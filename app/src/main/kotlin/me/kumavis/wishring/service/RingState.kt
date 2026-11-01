package me.kumavis.wishring.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import me.kumavis.wishring.protocol.RingAdvertisement

/** What the service is doing, for the UI. */
data class RingStatus(
    val serviceRunning: Boolean = false,
    val scanning: Boolean = false,
    val syncing: Boolean = false,
    val lastSeenAtMillis: Long? = null,
    val lastAdvertisement: RingAdvertisement? = null,
    val lastRssi: Int? = null,
    val lastSyncAtMillis: Long? = null,
    val lastSyncReceived: Int = 0,
    val lastError: String? = null,
    val needsPairing: Boolean = false,
)

object RingState {
    private val _status = MutableStateFlow(RingStatus())
    val status: StateFlow<RingStatus> = _status

    fun update(change: (RingStatus) -> RingStatus) {
        _status.value = change(_status.value)
    }
}
