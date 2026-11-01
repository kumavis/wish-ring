package me.kumavis.wishring.ui

import android.view.View
import android.view.WindowInsets

/**
 * Keeps [root]'s content clear of the status bar, navigation bar and display cutout. Apps targeting
 * Android 15+ draw edge to edge, so without this the top of the screen sits under the status bar.
 */
fun padForSystemBars(root: View, basePaddingDp: Int = 12) {
    val base = (basePaddingDp * root.resources.displayMetrics.density).toInt()
    root.setOnApplyWindowInsetsListener { view, insets ->
        val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        view.setPadding(base + bars.left, base + bars.top, base + bars.right, base + bars.bottom)
        insets
    }
    root.requestApplyInsets()
}
