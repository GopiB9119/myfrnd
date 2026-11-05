package com.wellness.overlay3d.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.wellness.overlay3d.service.OverlayService

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            if (Settings.canDrawOverlays(context)) {
                OverlayService.startWithInitialDemo(context)
            }
        }
    }
}