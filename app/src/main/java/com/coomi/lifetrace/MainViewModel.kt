package com.coomi.lifetrace

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.coomi.lifetrace.data.AppDatabase
import com.coomi.lifetrace.data.TrackPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Calendar

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.get(app)
    private val dao = db.trackDao()

    private val _points = MutableStateFlow<List<TrackPoint>>(emptyList())
    val points: StateFlow<List<TrackPoint>> = _points

    private val _tracking = MutableStateFlow(false)
    val tracking: StateFlow<Boolean> = _tracking

    private val _totalCount = MutableStateFlow(0)
    val totalCount: StateFlow<Int> = _totalCount

    private val _stats = MutableStateFlow<TrackStats>(TrackStats())
    val stats: StateFlow<TrackStats> = _stats

    fun load(range: QueryRange) {
        viewModelScope.launch {
            val (from, to) = range.bounds()
            val data = if (range == QueryRange.LIFE)
                dao.all() else dao.between(from, to)
            _points.value = data
            _stats.value = computeStats(data)
        }
    }

    fun refreshCount() {
        viewModelScope.launch { _totalCount.value = dao.count() }
    }

    fun loadTrackingState() {
        val prefs = getApplication<Application>().getSharedPreferences("lifetrace", Context.MODE_PRIVATE)
        _tracking.value = prefs.getBoolean("tracking_enabled", false)
    }

    fun setTracking(enabled: Boolean) {
        val prefs = getApplication<Application>().getSharedPreferences("lifetrace", Context.MODE_PRIVATE)
        prefs.edit().putBoolean("tracking_enabled", enabled).apply()
        _tracking.value = enabled
    }
}

sealed class QueryRange(val label: String) {
    object DAY : QueryRange("今天")
    object YESTERDAY : QueryRange("昨天")
    object WEEK : QueryRange("本周")
    object MONTH : QueryRange("本月")
    object YEAR : QueryRange("今年")
    object LIFE : QueryRange("一生")
    object CUSTOM : QueryRange("自定义")

    fun bounds(): Pair<Long, Long> {
        val cal = Calendar.getInstance()
        return when (this) {
            DAY -> {
                cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
                val s = cal.timeInMillis
                cal.add(Calendar.DAY_OF_MONTH, 1)
                s to cal.timeInMillis
            }
            YESTERDAY -> {
                cal.add(Calendar.DAY_OF_MONTH, -1)
                cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
                val s = cal.timeInMillis
                cal.add(Calendar.DAY_OF_MONTH, 1)
                s to cal.timeInMillis
            }
            WEEK -> {
                cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
                cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
                val s = cal.timeInMillis
                cal.add(Calendar.DAY_OF_MONTH, 7)
                s to cal.timeInMillis
            }
            MONTH -> {
                cal.set(Calendar.DAY_OF_MONTH, 1)
                cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
                val s = cal.timeInMillis
                cal.add(Calendar.MONTH, 1)
                s to cal.timeInMillis
            }
            YEAR -> {
                cal.set(Calendar.DAY_OF_YEAR, 1)
                cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
                val s = cal.timeInMillis
                cal.add(Calendar.YEAR, 1)
                s to cal.timeInMillis
            }
            LIFE -> 0L to Long.MAX_VALUE
            CUSTOM -> {
                cal.set(Calendar.HOUR_OF_DAY, 0); cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
                val s = cal.timeInMillis
                cal.add(Calendar.DAY_OF_MONTH, 1)
                s to cal.timeInMillis
            }
        }
    }
}

/** 一段轨迹的统计信息 */
data class TrackStats(
    val distanceM: Double = 0.0,     // 总距离（米）
    val avgSpeedKmh: Double = 0.0,   // 平均速度（km/h，有速度样本时）
    val maxSpeedKmh: Double = 0.0,   // 最高速度（km/h）
    val durationMin: Long = 0        // 起止时长（分钟）
)

/** 计算轨迹点列表的统计（按相邻点测地距离累加距离，使用速度值求平均/最高） */
private fun computeStats(points: List<com.coomi.lifetrace.data.TrackPoint>): TrackStats {
    if (points.size < 2) return TrackStats()
    var dist = 0.0
    for (i in 1 until points.size) {
        dist += haversine(points[i - 1], points[i])
    }
    val avg = points.map { it.speed.toDouble() }.filter { it > 0 }.average()
    val max = points.map { it.speed.toDouble() }.maxOrNull() ?: 0.0
    // speed 单位 m/s → km/h
    val durMin = (points.last().timestamp - points.first().timestamp) / 60000.0
    return TrackStats(
        distanceM = dist,
        avgSpeedKmh = if (avg > 0) avg * 3.6 else 0.0,
        maxSpeedKmh = max * 3.6,
        durationMin = durMin.toLong()
    )
}

private fun haversine(a: com.coomi.lifetrace.data.TrackPoint, b: com.coomi.lifetrace.data.TrackPoint): Double {
    val r = 6371000.0
    val dLat = Math.toRadians(b.latitude - a.latitude)
    val dLon = Math.toRadians(b.longitude - a.longitude)
    val la1 = Math.toRadians(a.latitude)
    val la2 = Math.toRadians(b.latitude)
    val h = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
            Math.cos(la1) * Math.cos(la2) *
            Math.sin(dLon / 2) * Math.sin(dLon / 2)
    return 2 * r * Math.atan2(Math.sqrt(h), Math.sqrt(1 - h))
}
