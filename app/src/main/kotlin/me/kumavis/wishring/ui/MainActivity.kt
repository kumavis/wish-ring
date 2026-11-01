package me.kumavis.wishring.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.DocumentsContract
import android.provider.Settings
import android.text.format.DateFormat
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import java.util.Date
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.kumavis.wishring.R
import me.kumavis.wishring.WishRingApp
import me.kumavis.wishring.service.RingService
import me.kumavis.wishring.service.RingState
import me.kumavis.wishring.service.RingStatus
import me.kumavis.wishring.store.Recording
import me.kumavis.wishring.store.RecordingStore

private const val REQUEST_PERMISSIONS = 1
private const val REQUEST_EXPORT_TREE = 2

class MainActivity : Activity() {
    private val app get() = application as WishRingApp
    private var uiScope: CoroutineScope? = null
    private lateinit var statusRing: TextView
    private lateinit var statusService: TextView
    private lateinit var statusDetail: TextView
    private lateinit var empty: TextView
    private lateinit var list: ListView
    private lateinit var serviceButton: Button
    private val adapter = RecordingAdapter()
    private var player: MediaPlayer? = null
    private var playingId: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        padForSystemBars(findViewById(R.id.root))
        statusRing = findViewById(R.id.status_ring)
        statusService = findViewById(R.id.status_service)
        statusDetail = findViewById(R.id.status_detail)
        empty = findViewById(R.id.empty)
        list = findViewById(R.id.list)
        serviceButton = findViewById(R.id.button_service)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ -> togglePlayback(adapter.getItem(position)) }
        list.setOnItemLongClickListener { _, _, position, _ ->
            confirmDelete(adapter.getItem(position))
            true
        }
        findViewById<Button>(R.id.button_pair).setOnClickListener {
            if (ensurePermissions()) startActivity(Intent(this, PairActivity::class.java))
        }
        findViewById<Button>(R.id.button_sync).setOnClickListener {
            if (app.prefs.pairedAddress == null) {
                Toast.makeText(this, R.string.status_no_ring, Toast.LENGTH_SHORT).show()
            } else if (ensurePermissions()) {
                app.prefs.serviceEnabled = true
                RingService.start(this, RingService.ACTION_SYNC_NOW)
            }
        }
        serviceButton.setOnClickListener {
            if (RingState.status.value.serviceRunning) {
                app.prefs.serviceEnabled = false
                RingService.stop(this)
            } else if (app.prefs.pairedAddress != null && ensurePermissions()) {
                app.prefs.serviceEnabled = true
                RingService.start(this)
            }
        }
        findViewById<Button>(R.id.button_export).setOnClickListener {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQUEST_EXPORT_TREE)
        }
        findViewById<Button>(R.id.button_battery).setOnClickListener { requestBatteryExemption() }
        findViewById<Button>(R.id.button_forget).setOnClickListener { confirmForget() }
        ensurePermissions()
    }

    override fun onStart() {
        super.onStart()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        uiScope = scope
        scope.launch { RingState.status.collect { render(it) } }
        scope.launch { app.store.changes.collect { reload() } }
        if (app.prefs.pairedAddress != null && app.prefs.serviceEnabled && !RingState.status.value.serviceRunning && hasBluetoothPermissions()) {
            RingService.start(this)
        }
    }

    override fun onStop() {
        uiScope?.cancel()
        uiScope = null
        stopPlayback()
        super.onStop()
    }

    private fun hasBluetoothPermissions(): Boolean =
        checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun ensurePermissions(): Boolean {
        val wanted = listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isEmpty()) return true
        requestPermissions(wanted.toTypedArray(), REQUEST_PERMISSIONS)
        return false
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS && hasBluetoothPermissions() && app.prefs.pairedAddress != null && app.prefs.serviceEnabled) {
            RingService.start(this)
        }
        render(RingState.status.value)
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        val power = getSystemService(PowerManager::class.java)
        if (power.isIgnoringBatteryOptimizations(packageName)) {
            Toast.makeText(this, R.string.battery_already, Toast.LENGTH_SHORT).show()
            return
        }
        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
    }

    private fun confirmForget() {
        if (app.prefs.pairedAddress == null) return
        AlertDialog.Builder(this)
            .setTitle(R.string.forget_title)
            .setMessage(R.string.forget_message)
            .setPositiveButton(R.string.action_forget) { _, _ ->
                RingService.stop(this)
                app.prefs.forget()
                render(RingState.status.value)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDelete(recording: Recording) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_title)
            .setMessage(getString(R.string.delete_message, title(recording)))
            .setPositiveButton(R.string.delete) { _, _ ->
                if (playingId == recording.id) stopPlayback()
                uiScope?.launch { withContext(Dispatchers.IO) { app.store.delete(recording.id) } }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun togglePlayback(recording: Recording) {
        if (playingId == recording.id) {
            stopPlayback()
            return
        }
        stopPlayback()
        val file = app.store.file(recording)
        if (!file.exists()) {
            Toast.makeText(this, "missing ${recording.fileName}", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            player = MediaPlayer().apply {
                setDataSource(file.absolutePath)
                setOnCompletionListener { stopPlayback() }
                prepare()
                start()
            }
            playingId = recording.id
        } catch (e: Exception) {
            Toast.makeText(this, "playback failed: ${e.message}", Toast.LENGTH_SHORT).show()
            stopPlayback()
        }
        adapter.notifyDataSetChanged()
    }

    private fun stopPlayback() {
        player?.let {
            try {
                it.stop()
            } catch (_: Exception) {
            }
            it.release()
        }
        player = null
        playingId = null
        adapter.notifyDataSetChanged()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_EXPORT_TREE || resultCode != RESULT_OK) return
        val tree = data?.data ?: return
        contentResolver.takePersistableUriPermission(tree, Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        uiScope?.launch {
            val result = withContext(Dispatchers.IO) { runCatching { exportTo(tree, app.store) } }
            result.onSuccess { Toast.makeText(this@MainActivity, getString(R.string.export_done, it), Toast.LENGTH_LONG).show() }
                .onFailure { Toast.makeText(this@MainActivity, getString(R.string.export_failed, it.message), Toast.LENGTH_LONG).show() }
        }
    }

    /** Copies every WAV and the catalog into the folder the user picked; returns the file count. */
    private fun exportTo(tree: Uri, store: RecordingStore): Int {
        val dir = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        var count = 0
        fun copy(name: String, mime: String, bytes: ByteArray) {
            val target = DocumentsContract.createDocument(contentResolver, dir, mime, name) ?: throw java.io.IOException("could not create $name")
            contentResolver.openOutputStream(target, "w")!!.use { it.write(bytes) }
            count++
        }
        for (recording in store.all()) {
            val file = store.file(recording)
            if (file.exists()) copy(recording.fileName, "audio/wav", file.readBytes())
        }
        val catalog = java.io.File(store.directory, RecordingStore.CATALOG)
        if (catalog.exists()) copy(RecordingStore.CATALOG, "application/json", catalog.readBytes())
        return count
    }

    private fun reload() {
        uiScope?.launch {
            val items = withContext(Dispatchers.IO) { app.store.all() }
            adapter.items = items
            adapter.notifyDataSetChanged()
            empty.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
        }
    }

    private fun render(status: RingStatus) {
        val paired = app.prefs.pairedAddress
        statusRing.text = if (paired == null) getString(R.string.status_no_ring) else getString(R.string.status_paired, paired)
        statusService.text = when {
            paired == null -> ""
            !status.serviceRunning -> getString(R.string.status_service_off)
            status.syncing -> getString(R.string.notification_syncing)
            status.scanning -> getString(R.string.status_service_on)
            else -> getString(R.string.notification_waiting)
        }
        serviceButton.text = getString(if (status.serviceRunning) R.string.action_stop_service else R.string.action_start_service)
        val parts = ArrayList<String>()
        status.lastSeenAtMillis?.let { parts += "Last seen ${timeOf(it)}" + (status.lastRssi?.let { r -> " ($r dBm)" } ?: "") }
        status.lastAdvertisement?.let { parts += "count ${it.collectionCount}" + (if (it.recording) ", recording" else "") + (if (it.needsServicing) ", wants clock" else "") }
        status.lastSyncAtMillis?.let { parts += "Last sync ${timeOf(it)}, ${status.lastSyncReceived} new" }
        if (status.needsPairing) parts += getString(R.string.notification_pair_again)
        status.lastError?.let { parts += "Error: $it" }
        statusDetail.text = parts.joinToString("\n")
    }

    private fun timeOf(millis: Long): String = DateFormat.getTimeFormat(this).format(Date(millis))

    private fun title(recording: Recording): String {
        val date = Date(recording.capturedAtEpochSeconds * 1000)
        return "${DateFormat.getMediumDateFormat(this).format(date)} ${DateFormat.getTimeFormat(this).format(date)}"
    }

    private inner class RecordingAdapter : BaseAdapter() {
        var items: List<Recording> = emptyList()

        override fun getCount() = items.size

        override fun getItem(position: Int): Recording = items[position]

        override fun getItemId(position: Int) = items[position].id.hashCode().toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val view = convertView ?: layoutInflater.inflate(R.layout.item_recording, parent, false)
            val recording = items[position]
            val seconds = recording.durationMillis / 1000
            view.findViewById<TextView>(R.id.title).text = title(recording)
            view.findViewById<TextView>(R.id.subtitle).text = buildString {
                append("${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}")
                append(" · ${recording.sampleRate} Hz · #${recording.firstIndex}")
                if (recording.partCount > 1) append(" (${recording.partCount} parts)")
                if (!recording.clockFromRing) append(" · time from phone")
            }
            view.findViewById<TextView>(R.id.play_state).text = if (playingId == recording.id) "■" else "▶"
            return view
        }
    }
}
