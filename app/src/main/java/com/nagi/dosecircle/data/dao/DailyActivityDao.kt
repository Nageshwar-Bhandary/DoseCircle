package com.nagi.dosecircle.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.nagi.dosecircle.data.entity.DailyActivity
import kotlinx.coroutines.flow.Flow

@Dao
interface DailyActivityDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertActivity(activity: DailyActivity): Long

    @Query("SELECT * FROM daily_activities WHERE personId = :personId AND date = :date LIMIT 1")
    suspend fun getActivityForDate(personId: Long, date: String): DailyActivity?

    @Query("UPDATE daily_activities SET steps = :steps WHERE id = :id")
    suspend fun updateSteps(id: Long, steps: Int)

    @Query("UPDATE daily_activities SET goal = :goal WHERE id = :id")
    suspend fun updateGoal(id: Long, goal: Int)

    @Query("SELECT * FROM daily_activities WHERE personId = :personId AND date = :date LIMIT 1")
    fun getActivityFlowForDate(personId: Long, date: String): Flow<DailyActivity?>

    @Transaction
    suspend fun getOrInsertActivity(personId: Long, date: String, defaultGoal: Int = 6000): DailyActivity {
        val existing = getActivityForDate(personId, date)
        if (existing != null) {
            return existing
        }
        val newActivity = DailyActivity(
            personId = personId,
            date = date,
            steps = 0,
            goal = defaultGoal
        )
        val newId = insertActivity(newActivity)
        return if (newId != -1L) {
            newActivity.copy(id = newId)
        } else {
            getActivityForDate(personId, date) ?: newActivity
        }
    }

    @Query("SELECT * FROM daily_activities WHERE personId = :personId AND date BETWEEN :startDate AND :endDate ORDER BY date ASC")
    fun getActivitiesBetween(personId: Long, startDate: String, endDate: String): Flow<List<DailyActivity>>
}
