package me.kumavis.wishring

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import java.io.File
import me.kumavis.wishring.store.RecordingStore

class WishRingApp : Application() {
    lateinit var prefs: Prefs
        private set
    lateinit var store: RecordingStore
        private set

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        store = RecordingStore(File(filesDir, "recordings"))
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, getString(R.string.notification_channel_service), NotificationManager.IMPORTANCE_LOW),
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_EVENTS, getString(R.string.notification_channel_events), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    companion object {
        const val CHANNEL_SERVICE = "service"
        const val CHANNEL_EVENTS = "events"
    }
}
