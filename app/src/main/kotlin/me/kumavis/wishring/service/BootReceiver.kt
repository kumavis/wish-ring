package me.kumavis.wishring.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import me.kumavis.wishring.WishRingApp

/** Brings the sync service back after a reboot while a ring is paired. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val app = context.applicationContext as WishRingApp
        if (app.prefs.pairedAddress != null && app.prefs.serviceEnabled) RingService.start(context)
    }
}
