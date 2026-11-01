package me.kumavis.wishring

import android.content.Context
import android.content.SharedPreferences
import me.kumavis.wishring.sync.SyncPosition

/** The paired ring and each ring's sync position, in SharedPreferences. */
class Prefs(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("wish_ring", Context.MODE_PRIVATE)

    var pairedAddress: String?
        get() = prefs.getString(KEY_PAIRED, null)
        set(value) = prefs.edit().putString(KEY_PAIRED, value).apply()

    /** Bumped on every pairing: the ring's collection counter restarts, so stored keys must not repeat. */
    var iteration: Int
        get() = prefs.getInt(KEY_ITERATION, 0)
        set(value) = prefs.edit().putInt(KEY_ITERATION, value).apply()

    var serviceEnabled: Boolean
        get() = prefs.getBoolean(KEY_SERVICE, true)
        set(value) = prefs.edit().putBoolean(KEY_SERVICE, value).apply()

    /** Records a fresh pairing with [address]: new iteration, no saved position. */
    fun paired(address: String) {
        val next = iteration + 1
        prefs.edit()
            .putString(KEY_PAIRED, address)
            .putInt(KEY_ITERATION, next)
            .remove(KEY_NEXT_INDEX + address)
            .remove(KEY_LAST_COUNT + address)
            .apply()
    }

    fun forget() {
        val address = pairedAddress ?: return
        prefs.edit()
            .remove(KEY_PAIRED)
            .remove(KEY_NEXT_INDEX + address)
            .remove(KEY_LAST_COUNT + address)
            .apply()
    }

    /** Forgets the saved position so the next sync imports everything still on the ring. */
    fun resetPosition() {
        val address = pairedAddress ?: return
        prefs.edit().remove(KEY_NEXT_INDEX + address).remove(KEY_LAST_COUNT + address).apply()
    }

    fun positionFor(address: String): SyncPosition = object : SyncPosition {
        override fun nextIndex(): Int? = if (prefs.contains(KEY_NEXT_INDEX + address)) prefs.getInt(KEY_NEXT_INDEX + address, 0) else null

        override fun setNextIndex(index: Int) = prefs.edit().putInt(KEY_NEXT_INDEX + address, index).commit().let {}

        override fun lastCount(): Int? = if (prefs.contains(KEY_LAST_COUNT + address)) prefs.getInt(KEY_LAST_COUNT + address, 0) else null

        override fun setLastCount(count: Int) = prefs.edit().putInt(KEY_LAST_COUNT + address, count).commit().let {}
    }

    private companion object {
        const val KEY_PAIRED = "paired_address"
        const val KEY_ITERATION = "iteration"
        const val KEY_SERVICE = "service_enabled"
        const val KEY_NEXT_INDEX = "next_index:"
        const val KEY_LAST_COUNT = "last_count:"
    }
}
