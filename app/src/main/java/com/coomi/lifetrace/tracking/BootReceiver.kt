package com.coomi.lifetrace.tracking

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** 开机后自动重启轨迹服务（若用户开启过记录） */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = context.getSharedPreferences("lifetrace", Context.MODE_PRIVATE)
            if (prefs.getBoolean("tracking_enabled", false)) {
                LocationTrackingService.start(context)
            }
        }
    }
}
