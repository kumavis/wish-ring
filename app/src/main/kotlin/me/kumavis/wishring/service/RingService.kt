package me.kumavis.wishring.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.ParcelUuid
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.kumavis.wishring.R
import me.kumavis.wishring.WishRingApp
import me.kumavis.wishring.ble.LinkFailure
import me.kumavis.wishring.ble.RingGatt
import me.kumavis.wishring.ble.RingLinkException
import me.kumavis.wishring.protocol.RING_COMPANY_ID
import me.kumavis.wishring.protocol.RING_SERVICE_UUID
import me.kumavis.wishring.protocol.RingAdvertisement
import me.kumavis.wishring.protocol.RingRecording
import me.kumavis.wishring.sync.RingRefusedException
import me.kumavis.wishring.sync.RingSession
import me.kumavis.wishring.sync.RingTimeoutException
import me.kumavis.wishring.sync.SessionResult
import me.kumavis.wishring.sync.ringHasNews
import me.kumavis.wishring.ui.MainActivity

private const val TAG = "RingService"

/**
 * Keeps a filtered low-power scan running for the paired ring and syncs when its advertisement says there
 * is something new. A foreground service of type connectedDevice, so the scan survives the screen turning
 * off and the app leaving the foreground.
 */
@SuppressLint("MissingPermission")
class RingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var scanner: BluetoothLeScanner? = null
    private var scanning = false
    private var syncJob: Job? = null
    @Volatile private var forceNextSync = false
    private var holdUntil = 0L
    private var holdMillis = 0L
    private var consecutiveGattErrors = 0

    private val app get() = application as WishRingApp

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = onAdvertisement(result)

        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach { onAdvertisement(it) }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "scan failed: $errorCode")
            scanning = false
            RingState.update { it.copy(scanning = false, lastError = "scan failed ($errorCode)") }
            scope.launch {
                delay(10_000)
                startScan()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.notification_waiting)), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        RingState.update { it.copy(serviceRunning = true, lastError = null) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SYNC_NOW -> {
                forceNextSync = true
                holdUntil = 0
                RingState.update { it.copy(lastError = null) }
            }
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
        }
        if (app.prefs.pairedAddress == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        startScan()
        return START_STICKY
    }

    override fun onDestroy() {
        stopScan()
        syncJob?.cancel()
        scope.cancel()
        RingState.update { it.copy(serviceRunning = false, scanning = false, syncing = false) }
        super.onDestroy()
    }

    private fun hasPermission(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun startScan() {
        if (scanning) return
        if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN) || !hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            RingState.update { it.copy(lastError = getString(R.string.permissions_missing)) }
            return
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapter == null || !adapter.isEnabled) {
            RingState.update { it.copy(lastError = "Bluetooth is off") }
            scope.launch {
                delay(15_000)
                startScan()
            }
            return
        }
        val leScanner = adapter.bluetoothLeScanner ?: return
        scanner = leScanner
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(RING_SERVICE_UUID)).build())
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()
        try {
            leScanner.startScan(filters, settings, scanCallback)
            scanning = true
            RingState.update { it.copy(scanning = true) }
            Log.i(TAG, "scanning for ${app.prefs.pairedAddress}")
        } catch (e: Exception) {
            Log.w(TAG, "startScan failed", e)
            RingState.update { it.copy(scanning = false, lastError = "scan start failed: ${e.message}") }
        }
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        try {
            scanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            Log.w(TAG, "stopScan failed", e)
        }
        RingState.update { it.copy(scanning = false) }
    }

    private fun onAdvertisement(result: ScanResult) {
        val paired = app.prefs.pairedAddress ?: return
        val device = result.device
        if (!device.address.equals(paired, ignoreCase = true)) return
        val advertisement = RingAdvertisement.fromManufacturerData(result.scanRecord?.getManufacturerSpecificData(RING_COMPANY_ID)) ?: return
        RingState.update { it.copy(lastSeenAtMillis = System.currentTimeMillis(), lastAdvertisement = advertisement, lastRssi = result.rssi) }
        if (syncJob?.isActive == true) return
        if (SystemClock.elapsedRealtime() < holdUntil && !forceNextSync) return
        val position = app.prefs.positionFor(paired)
        if (!forceNextSync && !ringHasNews(advertisement, position.lastCount())) return
        if (!advertisement.pairable) {
            RingState.update { it.copy(lastError = "the ring is in its ${advertisement.image.name.lowercase()} image") }
            return
        }
        forceNextSync = false
        syncJob = scope.launch { sync(device, advertisement) }
    }

    private suspend fun sync(device: BluetoothDevice, advertisement: RingAdvertisement) {
        val address = device.address
        val position = app.prefs.positionFor(address)
        val iteration = app.prefs.iteration
        var received = 0
        RingState.update { it.copy(syncing = true, lastError = null, needsPairing = false) }
        updateNotification(getString(R.string.notification_syncing))
        var link: RingGatt? = null
        try {
            link = RingGatt.open(this, device)
            consecutiveGattErrors = 0
            val session = RingSession(
                connection = link,
                position = position,
                sink = { recording -> store(address, iteration, recording).also { if (it) received++ } },
                nowUnixSeconds = { System.currentTimeMillis() / 1000 },
                log = { Log.d(TAG, it) },
            )
            val result = session.run(advertisement)
            Log.i(TAG, "session ended: $result, $received recording(s)")
            holdMillis = 0
            holdUntil = if (result == SessionResult.NOT_STORED) SystemClock.elapsedRealtime() + 60_000 else SystemClock.elapsedRealtime() + 2_000
            RingState.update { it.copy(lastSyncAtMillis = System.currentTimeMillis(), lastSyncReceived = received) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onSyncFailure(e)
        } finally {
            link?.hangUp()
            if (received > 0) notifyEvent(EVENT_RECEIVED_ID, getString(R.string.notification_received, received))
            RingState.update { it.copy(syncing = false) }
            updateNotification(getString(R.string.notification_waiting))
        }
    }

    private fun onSyncFailure(e: Exception) {
        Log.w(TAG, "sync failed", e)
        val failure = (e as? RingLinkException)?.failure
        val message = when (e) {
            is RingLinkException -> "link: ${e.message}"
            is RingRefusedException, is RingTimeoutException -> e.message ?: e.toString()
            else -> "${e.javaClass.simpleName}: ${e.message}"
        }
        when (failure) {
            LinkFailure.PAIR_AGAIN -> {
                RingState.update { it.copy(lastError = message, needsPairing = true) }
                notifyEvent(EVENT_PAIR_ID, getString(R.string.notification_pair_again))
                holdUntil = SystemClock.elapsedRealtime() + 5 * 60_000
            }
            LinkFailure.RETRY -> {
                consecutiveGattErrors++
                RingState.update { it.copy(lastError = message) }
                holdUntil = SystemClock.elapsedRealtime() + if (consecutiveGattErrors >= 5) 60_000 else 1_000L * consecutiveGattErrors
                if (consecutiveGattErrors >= 5) consecutiveGattErrors = 0
            }
            else -> {
                holdMillis = if (holdMillis == 0L) 30_000 else minOf(holdMillis * 2, 15 * 60_000)
                holdUntil = SystemClock.elapsedRealtime() + holdMillis
                RingState.update { it.copy(lastError = message) }
            }
        }
    }

    private fun store(address: String, iteration: Int, recording: RingRecording): Boolean {
        val now = System.currentTimeMillis() / 1000
        val fromRing = recording.capturedAtUnixSeconds
        val plausible = fromRing != null && fromRing > MIN_PLAUSIBLE_CLOCK && fromRing <= now + 60
        val capturedAt = if (plausible) fromRing!! else now
        return try {
            val stored = app.store.add(address, iteration, recording, capturedAt, plausible, now)
            if (stored == null) Log.i(TAG, "already had ${recording.firstIndex} in iteration $iteration")
            true
        } catch (e: Exception) {
            Log.e(TAG, "could not store recording ${recording.firstIndex}", e)
            RingState.update { it.copy(lastError = "store: ${e.message}") }
            false
        }
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return Notification.Builder(this, WishRingApp.CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_ring)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun notifyEvent(id: Int, text: String) {
        if (!hasPermission(Manifest.permission.POST_NOTIFICATIONS)) return
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(this, WishRingApp.CHANNEL_EVENTS)
            .setSmallIcon(R.drawable.ic_stat_ring)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(id, notification)
    }

    companion object {
        const val ACTION_SYNC_NOW = "me.kumavis.wishring.SYNC_NOW"
        const val ACTION_STOP = "me.kumavis.wishring.STOP"
        private const val NOTIFICATION_ID = 1
        private const val EVENT_PAIR_ID = 2
        private const val EVENT_RECEIVED_ID = 3

        /** 2025-01-01: a ring clock before this was never set by a phone. */
        private const val MIN_PLAUSIBLE_CLOCK = 1_735_689_600L

        fun start(context: Context, action: String? = null) {
            val intent = Intent(context, RingService::class.java).setAction(action)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RingService::class.java))
        }
    }
}
