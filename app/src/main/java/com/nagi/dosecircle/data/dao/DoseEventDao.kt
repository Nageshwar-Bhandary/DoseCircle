package com.nagi.dosecircle.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.nagi.dosecircle.data.entity.DoseEvent
import com.nagi.dosecircle.data.entity.DoseStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface DoseEventDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDoseEvent(doseEvent: DoseEvent): Long

    @Query("UPDATE dose_events SET status = :status, actionTime = :actionTime WHERE id = :id")
    suspend fun updateDoseStatus(id: Long, status: DoseStatus, actionTime: Long?)

    @Query("UPDATE dose_events SET cloudId = :cloudId WHERE id = :id")
    suspend fun updateCloudId(id: Long, cloudId: String)

    @Query("SELECT * FROM dose_events WHERE personId = :personId AND scheduledTime BETWEEN :startTime AND :endTime ORDER BY scheduledTime ASC")
    fun getDoseEventsForPersonBetween(personId: Long, startTime: Long, endTime: Long): Flow<List<DoseEvent>>

    @Query("SELECT * FROM dose_events WHERE status = 'PENDING' AND scheduledTime < :timestamp ORDER BY scheduledTime ASC")
    suspend fun getPendingDosesBefore(timestamp: Long): List<DoseEvent>

    @Query("SELECT * FROM dose_events WHERE id = :id LIMIT 1")
    suspend fun getDoseEventById(id: Long): DoseEvent?

    @Query("SELECT * FROM dose_events WHERE personId = :personId AND cloudId = :cloudId LIMIT 1")
    suspend fun getDoseEventByCloudId(personId: Long, cloudId: String): DoseEvent?
}
