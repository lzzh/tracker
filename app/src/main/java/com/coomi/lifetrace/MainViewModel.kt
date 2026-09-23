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

    fun load(range: QueryRange) {
        viewModelScope.launch {
            val (from, to) = range.bounds()
            _points.value = if (range == QueryRange.LIFE)
                dao.all() else dao.between(from, to)
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
    object WEEK : QueryRange("本周")
    object MONTH : QueryRange("本月")
    object YEAR : QueryRange("今年")
    object LIFE : QueryRange("一生")

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
        }
    }
}
