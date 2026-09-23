package com.coomi.lifetrace.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/** 一条轨迹点记录 */
@Entity(tableName = "tracks")
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
