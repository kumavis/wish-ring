package me.kumavis.wishring.store

import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import me.kumavis.wishring.protocol.RingRecording
import org.json.JSONArray
import org.json.JSONObject

/** One recording on the phone. [id] is `ring-iteration-firstIndex`, unique even after the ring's counter resets. */
data class Recording(
    val id: String,
    val ringAddress: String,
    val iteration: Int,
    val firstIndex: Int,
    val lastIndex: Int,
    val partCount: Int,
    val capturedAtEpochSeconds: Long,
    val clockFromRing: Boolean,
    val receivedAtEpochSeconds: Long,
    val durationMillis: Long,
    val sampleRate: Int,
    val fileName: String,
    val batteryMillivolts: Int?,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("ring", ringAddress)
        .put("iteration", iteration)
        .put("firstIndex", firstIndex)
        .put("lastIndex", lastIndex)
        .put("parts", partCount)
        .put("capturedAt", capturedAtEpochSeconds)
        .put("clockFromRing", clockFromRing)
        .put("receivedAt", receivedAtEpochSeconds)
        .put("durationMillis", durationMillis)
        .put("sampleRate", sampleRate)
        .put("file", fileName)
        .put("batteryMillivolts", batteryMillivolts ?: JSONObject.NULL)

    companion object {
        fun fromJson(o: JSONObject): Recording = Recording(
            id = o.getString("id"),
            ringAddress = o.getString("ring"),
            iteration = o.getInt("iteration"),
            firstIndex = o.getInt("firstIndex"),
            lastIndex = o.getInt("lastIndex"),
            partCount = o.optInt("parts", 1),
            capturedAtEpochSeconds = o.getLong("capturedAt"),
            clockFromRing = o.optBoolean("clockFromRing", false),
            receivedAtEpochSeconds = o.getLong("receivedAt"),
            durationMillis = o.getLong("durationMillis"),
            sampleRate = o.getInt("sampleRate"),
            fileName = o.getString("file"),
            batteryMillivolts = if (o.isNull("batteryMillivolts")) null else o.getInt("batteryMillivolts"),
        )
    }
}

/**
 * Recordings as WAV files in [directory] with a `catalog.json` beside them. Every change rewrites the
 * catalog through a temporary file, and [changes] ticks so the UI reloads.
 */
class RecordingStore(val directory: File) {
    private val catalogFile = File(directory, CATALOG)
    private val lock = Any()
    private var items: LinkedHashMap<String, Recording>? = null
    private val _changes = MutableStateFlow(0L)
    val changes: StateFlow<Long> = _changes

    fun all(): List<Recording> = synchronized(lock) {
        load().values.sortedWith(compareByDescending<Recording> { it.capturedAtEpochSeconds }.thenByDescending { it.receivedAtEpochSeconds })
    }

    fun get(id: String): Recording? = synchronized(lock) { load()[id] }

    fun file(recording: Recording): File = File(directory, recording.fileName)

    /**
     * Stores [recording] from [ringAddress] in pairing [iteration]; returns null when the same key is already
     * stored. [capturedAt] is the time to file it under; [clockFromRing] says whether it came from the ring.
     */
    fun add(
        ringAddress: String,
        iteration: Int,
        recording: RingRecording,
        capturedAt: Long,
        clockFromRing: Boolean,
        receivedAt: Long,
    ): Recording? = synchronized(lock) {
        val map = load()
        val id = "${ringAddress.replace(":", "").lowercase()}-$iteration-${recording.firstIndex}"
        if (map.containsKey(id)) return null
        val fileName = "$id.wav"
        val target = File(directory, fileName)
        val tmp = File(directory, "$fileName.tmp")
        tmp.writeBytes(pcm16Wav(recording.samples, recording.sampleRate))
        if (!tmp.renameTo(target)) throw java.io.IOException("could not move ${tmp.name} into place")
        val entry = Recording(
            id = id,
            ringAddress = ringAddress,
            iteration = iteration,
            firstIndex = recording.firstIndex,
            lastIndex = recording.lastIndex,
            partCount = recording.partCount,
            capturedAtEpochSeconds = capturedAt,
            clockFromRing = clockFromRing,
            receivedAtEpochSeconds = receivedAt,
            durationMillis = recording.durationMillis,
            sampleRate = recording.sampleRate,
            fileName = fileName,
            batteryMillivolts = recording.batteryMillivolts,
        )
        map[id] = entry
        save(map)
        entry
    }

    fun delete(id: String): Unit = synchronized(lock) {
        val map = load()
        val entry = map.remove(id) ?: return
        File(directory, entry.fileName).delete()
        save(map)
    }

    private fun load(): LinkedHashMap<String, Recording> {
        items?.let { return it }
        directory.mkdirs()
        val map = LinkedHashMap<String, Recording>()
        if (catalogFile.exists()) {
            try {
                val root = JSONObject(catalogFile.readText())
                val array = root.getJSONArray("recordings")
                for (i in 0 until array.length()) {
                    val entry = Recording.fromJson(array.getJSONObject(i))
                    map[entry.id] = entry
                }
            } catch (e: Exception) {
                // A damaged catalog loses the index, not the audio: the WAV files stay on disk.
                android.util.Log.w("RecordingStore", "catalog unreadable, starting empty", e)
            }
        }
        items = map
        return map
    }

    private fun save(map: Map<String, Recording>) {
        val root = JSONObject()
            .put("version", 1)
            .put("recordings", JSONArray().also { array -> map.values.forEach { array.put(it.toJson()) } })
        val tmp = File(directory, "$CATALOG.tmp")
        tmp.writeText(root.toString(2))
        if (catalogFile.exists()) catalogFile.delete()
        if (!tmp.renameTo(catalogFile)) throw java.io.IOException("could not replace $CATALOG")
        _changes.value = _changes.value + 1
    }

    companion object {
        const val CATALOG = "catalog.json"
    }
}
