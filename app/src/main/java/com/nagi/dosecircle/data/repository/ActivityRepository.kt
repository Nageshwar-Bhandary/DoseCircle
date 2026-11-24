package com.nagi.dosecircle.data.repository

import com.nagi.dosecircle.data.dao.DailyActivityDao
import com.nagi.dosecircle.data.entity.DailyActivity
import kotlinx.coroutines.flow.Flow

class ActivityRepository(private val dailyActivityDao: DailyActivityDao) {

    suspend fun getOrInsertTodayActivity(personId: Long, date: String, defaultGoal: Int = 6000): DailyActivity {
        return dailyActivityDao.getOrInsertActivity(personId, date, defaultGoal)
    }

    suspend fun updateSteps(id: Long, steps: Int) {
        dailyActivityDao.updateSteps(id, steps)
    }

    fun getActivitiesBetween(personId: Long, startDate: String, endDate: String): Flow<List<DailyActivity>> {
        return dailyActivityDao.getActivitiesBetween(personId, startDate, endDate)
    }
}
