package com.nagi.dosecircle.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.nagi.dosecircle.data.entity.ScheduleTime
import kotlinx.coroutines.flow.Flow

@Dao
interface ScheduleTimeDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertScheduleTimes(scheduleTimes: List<ScheduleTime>)

    @Query("DELETE FROM schedule_times WHERE scheduleId = :scheduleId")
    suspend fun deleteTimesForSchedule(scheduleId: Long)

    @Query("SELECT * FROM schedule_times WHERE scheduleId = :scheduleId")
    fun getTimesForSchedule(scheduleId: Long): Flow<List<ScheduleTime>>

    @Query("SELECT * FROM schedule_times WHERE scheduleId = :scheduleId")
    suspend fun getTimesForScheduleDirect(scheduleId: Long): List<ScheduleTime>
}
