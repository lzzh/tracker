package com.coomi.lifetrace.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 一条轨迹点记录 */
@Entity(
    tableName = "tracks",
    indices = [Index(value = ["timestamp"])]   // 时间范围查询必备，避免全表扫描
)
data class TrackPoint(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,   // epoch millis
    val latitude: Double,
    val longitude: Double,
    val altitude: Double = 0.0,
    val accuracy: Float = 0f,
    val speed: Float = 0f,
    val bearing: Float = 0f
)
