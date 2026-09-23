package com.coomi.lifetrace.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TrackDao {
    @Insert
    suspend fun insert(point: TrackPoint): Long

    @Query("SELECT * FROM tracks WHERE timestamp BETWEEN :from AND :to ORDER BY timestamp ASC")
    suspend fun between(from: Long, to: Long): List<TrackPoint>

    @Query("SELECT * FROM tracks WHERE timestamp BETWEEN :from AND :to ORDER BY timestamp ASC")
    fun betweenFlow(from: Long, to: Long): Flow<List<TrackPoint>>

    @Query("SELECT * FROM tracks ORDER BY timestamp ASC")
    suspend fun all(): List<TrackPoint>

    @Query("SELECT MIN(timestamp) FROM tracks")
    suspend fun minTimestamp(): Long?

    @Query("SELECT COUNT(*) FROM tracks")
    suspend fun count(): Int

    @Query("DELETE FROM tracks WHERE timestamp BETWEEN :from AND :to")
    suspend fun deleteBetween(from: Long, to: Long)

    @Query("DELETE FROM tracks")
    suspend fun clear()

    @Query("SELECT * FROM tracks WHERE timestamp >= :from ORDER BY timestamp ASC")
    suspend fun since(from: Long): List<TrackPoint>
}
