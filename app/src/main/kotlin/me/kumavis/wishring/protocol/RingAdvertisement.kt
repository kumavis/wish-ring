package me.kumavis.wishring.protocol

import java.util.UUID

/** The service UUID the ring advertises; scan filters match on it. */
val RING_SERVICE_UUID: UUID = UUID.fromString("607b5c9b-3700-4e94-f44a-2df900bcb0c3")

/** The Bluetooth SIG company id in the ring's manufacturer-specific data. */
const val RING_COMPANY_ID = 0x0EEA

private const val FINGERPRINT_FAILSAFE = 0xDEAD_DEADL
private const val FINGERPRINT_FACTORY_TEST = 0xDEAD_BEEFL
private const val FLAG_RECORDING = 1 shl 5
private const val FLAG_NEEDS_SERVICING = 1 shl 6
private const val STATE_SIZE = 6

enum class RingImage { PRIMARY, FAILSAFE, FACTORY_TEST }

/**
 * What the ring broadcasts: a [fingerprint] that identifies the running image, the low 8 bits of its
 * collection counter (a change means something new to fetch), whether it wants its clock set and whether
 * its button is held right now.
 */
data class RingAdvertisement(
    val fingerprint: Long,
    val collectionCount: Int,
    val needsServicing: Boolean,
    val recording: Boolean,
) {
    val image: RingImage
        get() = when (fingerprint) {
            FINGERPRINT_FAILSAFE -> RingImage.FAILSAFE
            FINGERPRINT_FACTORY_TEST -> RingImage.FACTORY_TEST
            else -> RingImage.PRIMARY
        }

    /** Only a ring on its normal image can be bonded with and synced. */
    val pairable: Boolean get() = image == RingImage.PRIMARY

    companion object {
        /** Parses the manufacturer data with the company id already stripped, as Android hands it out. */
        fun fromManufacturerData(data: ByteArray?): RingAdvertisement? {
            if (data == null || data.size < STATE_SIZE) return null
            val flags = data.u8(5)
            return RingAdvertisement(
                fingerprint = data.u32(0),
                collectionCount = data.u8(4),
                needsServicing = flags and FLAG_NEEDS_SERVICING != 0,
                recording = flags and FLAG_RECORDING != 0,
            )
        }

        /**
         * Parses the ring's answer to [RingRequest.ReadAdvertisement]: one AD structure, `09 FF EA 0E`
         * followed by the six state bytes.
         */
        fun fromAdStructure(data: ByteArray): RingAdvertisement {
            val expected = 4 + STATE_SIZE
            if (data.size != expected || data.u8(0) != expected - 1 || data.u8(1) != 0xFF || data.u16(2) != RING_COMPANY_ID) {
                throw RingProtocolException("advertisement read is not the ring's manufacturer data: ${data.toHex()}")
            }
            return fromManufacturerData(data.copyOfRange(4, expected))!!
        }
    }
}
