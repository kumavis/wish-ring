package me.kumavis.wishring.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import me.kumavis.wishring.R
import me.kumavis.wishring.WishRingApp
import me.kumavis.wishring.protocol.RING_COMPANY_ID
import me.kumavis.wishring.protocol.RING_SERVICE_UUID
import me.kumavis.wishring.protocol.RingAdvertisement
import me.kumavis.wishring.service.RingService

private const val SCAN_MILLIS = 30_000L
private const val BOND_TIMEOUT_MILLIS = 60_000L

/** Finds rings that are awake, and bonds with the one the user taps. */
@SuppressLint("MissingPermission")
class PairActivity : Activity() {
    private class Found(val device: BluetoothDevice, var name: String?, var rssi: Int, var advertisement: RingAdvertisement) {
        override fun toString(): String {
            val image = if (advertisement.pairable) "" else "  [${advertisement.image.name.lowercase()}]"
            return "${name ?: "Pebble Index"}  ${device.address}  ${rssi} dBm$image"
        }
    }

    private val app get() = application as WishRingApp
    private val handler = Handler(Looper.getMainLooper())
    private val found = ArrayList<Found>()
    private lateinit var adapter: ArrayAdapter<Found>
    private lateinit var status: TextView
    private var scanner: BluetoothLeScanner? = null
    private var scanning = false
    private var bonding: BluetoothDevice? = null
    private var receiverRegistered = false

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val advertisement = RingAdvertisement.fromManufacturerData(result.scanRecord?.getManufacturerSpecificData(RING_COMPANY_ID)) ?: return
            val existing = found.firstOrNull { it.device.address == result.device.address }
            if (existing == null) {
                found += Found(result.device, result.scanRecord?.deviceName ?: result.device.name, result.rssi, advertisement)
            } else {
                existing.rssi = result.rssi
                existing.advertisement = advertisement
                result.scanRecord?.deviceName?.let { existing.name = it }
            }
            adapter.notifyDataSetChanged()
            if (bonding == null) status.text = getString(R.string.pair_scanning)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            status.text = "Scan failed ($errorCode)"
        }
    }

    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
            val device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java) ?: return
            if (device.address != bonding?.address) return
            when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)) {
                BluetoothDevice.BOND_BONDED -> onBonded(device)
                BluetoothDevice.BOND_NONE -> {
                    val reason = intent.getIntExtra("android.bluetooth.device.extra.REASON", -1)
                    onBondFailed(reasonText(reason))
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_pair)
        padForSystemBars(findViewById(R.id.root))
        status = findViewById(R.id.pair_status)
        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, found)
        val list = findViewById<ListView>(R.id.pair_list)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ -> pairWith(found[position]) }
        registerReceiver(bondReceiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED), Context.RECEIVER_EXPORTED)
        receiverRegistered = true
    }

    override fun onResume() {
        super.onResume()
        if (bonding == null) startScan()
    }

    override fun onPause() {
        stopScan()
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (receiverRegistered) unregisterReceiver(bondReceiver)
        super.onDestroy()
    }

    private fun startScan() {
        if (scanning) return
        val adapterBt = getSystemService(BluetoothManager::class.java)?.adapter
        if (adapterBt == null || !adapterBt.isEnabled) {
            status.text = "Bluetooth is off"
            return
        }
        val leScanner = adapterBt.bluetoothLeScanner ?: return
        scanner = leScanner
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(RING_SERVICE_UUID)).build())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            leScanner.startScan(filters, settings, scanCallback)
            scanning = true
            status.text = getString(R.string.pair_scanning)
            handler.postDelayed({
                stopScan()
                if (found.isEmpty()) status.text = getString(R.string.pair_none)
            }, SCAN_MILLIS)
        } catch (e: Exception) {
            status.text = "Scan failed: ${e.message}"
        }
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        handler.removeCallbacksAndMessages(null)
        try {
            scanner?.stopScan(scanCallback)
        } catch (_: Exception) {
        }
    }

    private fun pairWith(entry: Found) {
        if (bonding != null) return
        if (!entry.advertisement.pairable) {
            Toast.makeText(this, getString(R.string.pair_not_primary, entry.advertisement.image.name.lowercase()), Toast.LENGTH_LONG).show()
            return
        }
        stopScan()
        val device = entry.device
        bonding = device
        status.text = getString(R.string.pair_bonding, device.address)
        if (device.bondState == BluetoothDevice.BOND_BONDED) {
            onBonded(device)
            return
        }
        if (!device.createBond()) {
            onBondFailed("createBond refused")
            return
        }
        handler.postDelayed({ if (bonding != null) onBondFailed("timed out") }, BOND_TIMEOUT_MILLIS)
    }

    private fun onBonded(device: BluetoothDevice) {
        handler.removeCallbacksAndMessages(null)
        bonding = null
        app.prefs.paired(device.address)
        app.prefs.serviceEnabled = true
        status.text = getString(R.string.pair_done, device.address)
        Toast.makeText(this, getString(R.string.pair_done, device.address), Toast.LENGTH_SHORT).show()
        RingService.start(this, RingService.ACTION_SYNC_NOW)
        finish()
    }

    private fun onBondFailed(reason: String) {
        handler.removeCallbacksAndMessages(null)
        bonding = null
        status.text = getString(R.string.pair_failed, reason)
        startScan()
    }

    /** Android's unbond reasons; a ring that refuses is usually still bonded to another phone. */
    private fun reasonText(reason: Int): String = when (reason) {
        1 -> "authentication failed: the ring may be paired to another phone (hard-reset it first)"
        2 -> "the ring rejected pairing: it may be paired to another phone (hard-reset it first)"
        3 -> "cancelled"
        4 -> "the ring did not answer"
        9 -> "pairing was removed"
        else -> "reason $reason"
    }
}
