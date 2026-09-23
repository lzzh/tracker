package com.coomi.lifetrace.tracking

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.os.Build
import android.os.IBinder
import android.os.Looper
import com.coomi.lifetrace.MainActivity
import com.coomi.lifetrace.R
import com.coomi.lifetrace.data.AppDatabase
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 后台定位服务（Foreground Service）。持续高精度记录位置并写入数据库。
 */
class LocationTrackingService : Service() {

    private lateinit var fusedClient: FusedLocationProviderClient
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var callback: LocationCallback? = null
    private lateinit var pauseController: PauseController
    private var isPaused = false

    companion object {
        const val CHANNEL_ID = "location_tracking"
        const val NOTIFICATION_ID = 1001

        fun start(context: Context) {
            val intent = Intent(context, LocationTrackingService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LocationTrackingService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForegroundWithNotification()
        fusedClient = LocationServices.getFusedLocationProviderClient(this)
        pauseController = PauseController(this)
        startLocationUpdates()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        callback?.let { fusedClient.removeLocationUpdates(it) }
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "轨迹记录", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "持续记录你的位置轨迹"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun startForegroundWithNotification() {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("一生足迹")
            .setContentText("正在记录你的位置轨迹…")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun startLocationUpdates() {
        // 高精度：正常记录时用
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000L)
            .setMinUpdateIntervalMillis(3000L)
            .setMaxUpdateDelayMillis(10000L)
            .build()
        val cb = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc: Location = result.lastLocation ?: return
                // 智能暂停判定：静止 / 进指定范围 / 连上指定WiFi → 省电暂停记录
                val shouldPause = pauseController.shouldPause(loc)
                if (shouldPause) {
                    if (!isPaused) enterPause()
                } else {
                    if (isPaused) exitPause()
                    persist(loc)
                }
            }
        }
        callback = cb
        try {
            fusedClient.requestLocationUpdates(request, cb, Looper.getMainLooper())
        } catch (e: SecurityException) {
            // 无权限时静默，等用户授权
        }
    }

    /** 进入省电暂停：下调定位优先级到低频（省电），不落点，通知提示已暂停 */
    private fun enterPause() {
        isPaused = true
        // 不 reset()：保留静止基线（stillCount/lastLat/lastLon），
        // 避免暂停后首个低频样本被误判为"移动"而立即恢复 → 造成"暂停-恢复"振荡。
        try {
            val lowReq = LocationRequest.Builder(Priority.PRIORITY_LOW_POWER, 600000L)
                .setMinUpdateIntervalMillis(600000L)
                .build()
            callback?.let { fusedClient.requestLocationUpdates(lowReq, it, Looper.getMainLooper()) }
        } catch (e: SecurityException) { }
        updateNotification("已暂停记录（静止/区域/Wi-Fi）")
    }

    /** 退出暂停：恢复高精度记录（此时已检测到用户移动，重置静止计数合理） */
    private fun exitPause() {
        isPaused = false
        pauseController.reset()
        try {
            val highReq = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000L)
                .setMinUpdateIntervalMillis(3000L)
                .setMaxUpdateDelayMillis(10000L)
                .build()
            callback?.let { fusedClient.requestLocationUpdates(highReq, it, Looper.getMainLooper()) }
        } catch (e: SecurityException) { }
        updateNotification("正在记录你的位置轨迹…")
    }

    private fun updateNotification(text: String) {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("一生足迹")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) { }
    }

    private fun persist(loc: Location) {
        val db = AppDatabase.get(applicationContext)
        scope.launch {
            val pt = com.coomi.lifetrace.data.TrackPoint(
                timestamp = loc.time,
                latitude = loc.latitude,
                longitude = loc.longitude,
                altitude = loc.altitude,
                accuracy = loc.accuracy,
                speed = loc.speed,
                bearing = loc.bearing
            )
            db.trackDao().insert(pt)
        }
    }
}
