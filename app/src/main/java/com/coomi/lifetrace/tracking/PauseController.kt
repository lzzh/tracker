package com.coomi.lifetrace.tracking

import android.content.Context
import android.location.Location
import android.net.wifi.WifiManager
import com.coomi.lifetrace.data.PlaceStore
import kotlin.math.abs

/**
 * 智能暂停判定器：判断当前是否应暂停记录以省电。
 *
 * 三种规则（可独立启用，任一命中即暂停）：
 *  1) 静止停留：连续多条定位点位移都很小（速度≈0、累积位移小于阈值）→ 判定人未移动。
 *  2) 地点范围：进入设定的中心点+半径范围内 → 暂停。
 *  3) WiFi：连上指定 SSID 的 WiFi → 暂停。
 */
class PauseController(private val context: Context) {

    private val prefs = context.getSharedPreferences("lifetrace", Context.MODE_PRIVATE)
    private val placeStore = PlaceStore(context.applicationContext)

    // ---- 规则 1：静止停留 ----
    private var lastLat = 0.0
    private var lastLon = 0.0
    private var lastTime = 0L
    private var stillCount = 0
    /** 判定停留所需连续静止点数 */
    private val STILL_REQUIRED = 6
    /** 两点间视为"静止"的位移阈值（米） */
    private val STILL_DIST_M = 5.0

    // ---- 规则 2：常去地点范围（多个） ----
    private fun zoneEnabled() = prefs.getBoolean("pause_zone_enabled", false)
    private fun zones() = placeStore.all()

    // ---- 规则 3：WiFi ----
    private fun wifiEnabled() = prefs.getBoolean("pause_wifi_enabled", false)
    private fun wifiTarget() = prefs.getString("pause_wifi_ssid", "") ?: ""

    /**
     * 判断当前定位点是否应触发暂停。
     * 返回 true = 应当暂停记录（省电）。
     */
    fun shouldPause(loc: Location): Boolean {
        // 规则 3：WiFi 命中
        if (wifiEnabled() && wifiTarget().isNotBlank() && isOnTargetWifi()) {
            return true
        }
        // 规则 2：进入任意常去地点范围
        if (zoneEnabled()) {
            for (z in zones()) {
                val d = distanceMeters(loc.latitude, loc.longitude, z.lat, z.lon)
                if (d <= z.radius) return true
            }
        }
        // 规则 1：静止停留
        if (isStill(loc)) {
            return true
        }
        return false
    }

    /** 静止检测：连续多条定位位移很小 */
    private fun isStill(loc: Location): Boolean {
        if (lastTime != 0L) {
            val dt = (loc.time - lastTime) / 1000.0
            val dist = distanceMeters(lastLat, lastLon, loc.latitude, loc.longitude)
            // 速度很小（<0.3 m/s 即约 1km/h 以下），且位移小于阈值
            if (dt > 0 && dist < STILL_DIST_M && loc.speed < 0.3f) {
                stillCount++
            } else if (dist >= STILL_DIST_M || loc.speed >= 0.3f) {
                stillCount = 0
            }
        }
        lastLat = loc.latitude
        lastLon = loc.longitude
        lastTime = loc.time
        return stillCount >= STILL_REQUIRED
    }

    private fun isOnTargetWifi(): Boolean {
        return try {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val info = wifi.connectionInfo ?: return false
            val ssid = info.ssid?.trim('"') ?: ""
            ssid == wifiTarget()
        } catch (e: Exception) {
            false
        }
    }

    /** SQLite 内置 distance 公式（球面距离，米） */
    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return r * c
    }

    fun reset() {
        lastLat = 0.0; lastLon = 0.0; lastTime = 0L; stillCount = 0
    }
}
