package me.kumavis.wishring.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import me.kumavis.wishring.sync.RingConnection
import me.kumavis.wishring.sync.RingNotification

private const val TAG = "RingGatt"

private val CONTROL_UUID: UUID = UUID.fromString("c0ef558a-2058-fabf-a140-8d5acde50b39")
private val DATA_UUID: UUID = UUID.fromString("daad3d52-237c-90a7-b54b-8854a134d801")
private val SYSTEM_INPUT_UUID: UUID = UUID.fromString("1d1f4039-23f5-33b2-c24e-704351f20585")
private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

/** GATT status 133: a generic failure while connecting, worth a retry. */
const val GATT_ERROR = 133

/** Disconnect reason 137: the ring no longer holds the bond. */
const val GATT_BOND_LOST = 137
const val GATT_INSUFFICIENT_AUTHENTICATION = 5
const val GATT_INSUFFICIENT_ENCRYPTION = 15

enum class LinkFailure { RETRY, PAIR_AGAIN, OTHER }

fun linkFailureFor(status: Int?): LinkFailure = when (status) {
    GATT_ERROR -> LinkFailure.RETRY
    GATT_BOND_LOST, GATT_INSUFFICIENT_AUTHENTICATION, GATT_INSUFFICIENT_ENCRYPTION -> LinkFailure.PAIR_AGAIN
    else -> LinkFailure.OTHER
}

/** The link to the ring failed; [status] is what Android reported, when it reported one. */
class RingLinkException(val status: Int?, message: String, cause: Throwable? = null) : Exception("$message (status $status)", cause) {
    val failure: LinkFailure get() = linkFailureFor(status)
}

/**
 * A GATT connection to a bonded ring, built on the platform [BluetoothGatt] API. [open] connects,
 * discovers the three characteristics and enables notifications on control then data; [hangUp] asks the
 * ring to disconnect by subscribing to its system-input characteristic, as the official app does, and
 * closes the client either way.
 */
@SuppressLint("MissingPermission")
class RingGatt private constructor(private val device: BluetoothDevice) : RingConnection {
    private val channel = Channel<RingNotification>(capacity = 512)
    override val notifications: ReceiveChannel<RingNotification> get() = channel

    private val connected = CompletableDeferred<Int>()
    private val servicesDiscovered = CompletableDeferred<Int>()
    private val disconnected = CompletableDeferred<Int>()
    private var descriptorWritten = CompletableDeferred<Int>()
    private var characteristicWritten = CompletableDeferred<Int>()
    private val writeLock = Mutex()

    private lateinit var gatt: BluetoothGatt
    private lateinit var control: BluetoothGattCharacteristic
    private lateinit var data: BluetoothGattCharacteristic
    private lateinit var systemInput: BluetoothGattCharacteristic
    @Volatile private var mtu = 23
    @Volatile private var closed = false

    @OptIn(ExperimentalCoroutinesApi::class)
    val disconnectStatus: Int? get() = if (disconnected.isCompleted) disconnected.getCompleted() else null

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            Log.d(TAG, "connection state $newState status $status")
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connected.complete(status)
            } else {
                connected.complete(if (status == BluetoothGatt.GATT_SUCCESS) GATT_ERROR else status)
                disconnected.complete(status)
                channel.close(RingLinkException(status, "the ring disconnected"))
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            servicesDiscovered.complete(status)
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            Log.d(TAG, "mtu $newMtu status $status")
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = newMtu
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            descriptorWritten.complete(status)
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            characteristicWritten.complete(status)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            val notification = when (characteristic.uuid) {
                CONTROL_UUID -> RingNotification.Control(value)
                DATA_UUID -> RingNotification.Data(value)
                else -> return
            }
            if (channel.trySend(notification).isFailure) {
                channel.close(RingLinkException(null, "more notifications than the session consumed"))
            }
        }
    }

    private suspend fun connect(context: Context, timeoutMillis: Long) {
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            ?: throw RingLinkException(null, "connectGatt returned null")
        val status = withTimeoutOrNull(timeoutMillis) { connected.await() }
            ?: throw RingLinkException(null, "connect timed out")
        if (status != BluetoothGatt.GATT_SUCCESS) throw RingLinkException(status, "connect failed")
        gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
        if (!gatt.discoverServices()) throw RingLinkException(null, "discoverServices refused")
        val discovery = withTimeoutOrNull(timeoutMillis) { servicesDiscovered.await() }
            ?: throw RingLinkException(null, "service discovery timed out")
        if (discovery != BluetoothGatt.GATT_SUCCESS) throw RingLinkException(discovery, "service discovery failed")
        val characteristics = gatt.services.flatMap { it.characteristics }
        control = characteristics.firstOrNull { it.uuid == CONTROL_UUID } ?: throw RingLinkException(null, "no control characteristic")
        data = characteristics.firstOrNull { it.uuid == DATA_UUID } ?: throw RingLinkException(null, "no data characteristic")
        systemInput = characteristics.firstOrNull { it.uuid == SYSTEM_INPUT_UUID } ?: throw RingLinkException(null, "no system-input characteristic")
        subscribe(control, timeoutMillis)
        subscribe(data, timeoutMillis)
    }

    private suspend fun subscribe(characteristic: BluetoothGattCharacteristic, timeoutMillis: Long) {
        val cccd = characteristic.getDescriptor(CCCD_UUID) ?: throw RingLinkException(null, "no CCCD on ${characteristic.uuid}")
        if (!gatt.setCharacteristicNotification(characteristic, true)) {
            throw RingLinkException(null, "setCharacteristicNotification refused for ${characteristic.uuid}")
        }
        writeLock.withLock {
            descriptorWritten = CompletableDeferred()
            val queued = gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            if (queued != BluetoothGatt.GATT_SUCCESS) throw RingLinkException(queued, "writeDescriptor refused for ${characteristic.uuid}")
            val status = withTimeoutOrNull(timeoutMillis) { descriptorWritten.await() }
                ?: throw RingLinkException(null, "CCCD write timed out for ${characteristic.uuid}")
            if (status != BluetoothGatt.GATT_SUCCESS) throw RingLinkException(status, "CCCD write failed for ${characteristic.uuid}")
        }
    }

    override suspend fun writeControl(bytes: ByteArray) = write(control, bytes)

    override suspend fun writeData(bytes: ByteArray) = write(data, bytes)

    private suspend fun write(characteristic: BluetoothGattCharacteristic, bytes: ByteArray) {
        if (closed) throw RingLinkException(disconnectStatus, "link closed")
        val chunk = (mtu - 3).coerceAtLeast(20)
        var at = 0
        while (at < bytes.size || (bytes.isEmpty() && at == 0)) {
            val end = minOf(bytes.size, at + chunk)
            val part = bytes.copyOfRange(at, end)
            writeLock.withLock {
                characteristicWritten = CompletableDeferred()
                val queued = gatt.writeCharacteristic(characteristic, part, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
                if (queued != BluetoothGatt.GATT_SUCCESS) throw RingLinkException(queued, "write refused on ${characteristic.uuid}")
                val status = withTimeoutOrNull(5_000) { characteristicWritten.await() }
                    ?: throw RingLinkException(disconnectStatus, "write not confirmed on ${characteristic.uuid}")
                if (status != BluetoothGatt.GATT_SUCCESS) throw RingLinkException(status, "write failed on ${characteristic.uuid}")
            }
            at = end
            if (bytes.isEmpty()) break
        }
    }

    /**
     * Asks the ring to drop the link by enabling notifications on its system-input characteristic, waits
     * briefly for the disconnect, falls back to a plain disconnect, then closes the client.
     */
    suspend fun hangUp() = withContext(NonCancellable) {
        if (closed) return@withContext
        closed = true
        try {
            if (!disconnected.isCompleted) {
                val cccd = systemInput.getDescriptor(CCCD_UUID)
                if (cccd != null && gatt.setCharacteristicNotification(systemInput, true)) {
                    gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                }
                val gone = withTimeoutOrNull(1_000) { disconnected.await() }
                if (gone == null) {
                    delay(200)
                    gatt.disconnect()
                    withTimeoutOrNull(1_000) { disconnected.await() }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "hang-up failed", e)
        } finally {
            try {
                gatt.close()
            } catch (e: Exception) {
                Log.w(TAG, "close failed", e)
            }
            channel.close()
        }
    }

    companion object {
        /**
         * Connects to [device], which must already be bonded, and returns the open link. Failures throw
         * [RingLinkException] with the status Android reported; the caller decides whether to retry.
         */
        suspend fun open(context: Context, device: BluetoothDevice, timeoutMillis: Long = 20_000): RingGatt {
            if (device.bondState != BluetoothDevice.BOND_BONDED) {
                throw RingLinkException(GATT_BOND_LOST, "the ring is not bonded")
            }
            val link = RingGatt(device)
            try {
                link.connect(context, timeoutMillis)
            } catch (e: Exception) {
                link.hangUp()
                throw e
            }
            return link
        }
    }
}
