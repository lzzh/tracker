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
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 5000L)
            .setMinUpdateIntervalMillis(3000L)
            .setMaxUpdateDelayMillis(10000L)
            .build()
        val cb = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val loc: Location = result.lastLocation ?: return
                persist(loc)
            }
        }
        callback = cb
        // 若后台间隔过大，也允许低速更新兜底
        val lr = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 30000L)
            .build()
        try {
            fusedClient.requestLocationUpdates(request, cb, Looper.getMainLooper())
        } catch (e: SecurityException) {
            // 无权限时静默，等用户授权
        }
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
