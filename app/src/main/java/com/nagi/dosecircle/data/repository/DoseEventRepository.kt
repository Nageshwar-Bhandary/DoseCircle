package com.nagi.dosecircle.data.repository

import com.nagi.dosecircle.data.dao.DoseEventDao
import com.nagi.dosecircle.data.entity.DoseEvent
import com.nagi.dosecircle.data.entity.DoseStatus
import kotlinx.coroutines.flow.Flow

class DoseEventRepository(private val doseEventDao: DoseEventDao) {

    suspend fun insertDoseEvent(doseEvent: DoseEvent): Long {
        return doseEventDao.insertDoseEvent(doseEvent)
    }

    suspend fun updateDoseStatus(id: Long, status: DoseStatus, actionTime: Long?) {
        doseEventDao.updateDoseStatus(id, status, actionTime)
    }

    suspend fun updateCloudId(id: Long, cloudId: String) {
        doseEventDao.updateCloudId(id, cloudId)
    }

    fun getDoseEventsForPersonBetween(personId: Long, startTime: Long, endTime: Long): Flow<List<DoseEvent>> {
        return doseEventDao.getDoseEventsForPersonBetween(personId, startTime, endTime)
    }

    suspend fun getPendingDosesBefore(timestamp: Long): List<DoseEvent> {
        return doseEventDao.getPendingDosesBefore(timestamp)
    }

    suspend fun getDoseEventById(id: Long): DoseEvent? {
        return doseEventDao.getDoseEventById(id)
    }
}
