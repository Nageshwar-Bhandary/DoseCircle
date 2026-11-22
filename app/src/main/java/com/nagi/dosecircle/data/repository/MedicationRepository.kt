package com.nagi.dosecircle.data.repository

import androidx.room.withTransaction
import com.nagi.dosecircle.data.database.AppDatabase
import com.nagi.dosecircle.data.entity.Medication
import com.nagi.dosecircle.data.entity.Schedule
import com.nagi.dosecircle.data.entity.ScheduleTime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull

data class ScheduleWithTimes(
    val schedule: Schedule,
    val times: List<ScheduleTime>
)

class MedicationRepository(private val database: AppDatabase) {

    private val medicationDao = database.medicationDao()
    private val scheduleDao = database.scheduleDao()
    private val scheduleTimeDao = database.scheduleTimeDao()

    fun getMedicationsForPerson(personId: Long): Flow<List<Medication>> {
        return medicationDao.getAllMedicationsForPerson(personId)
    }

    suspend fun getMedicationById(id: Long): Medication? {
        return medicationDao.getMedicationById(id)
    }

    suspend fun saveMedicationWithSchedule(
        medication: Medication,
        schedule: Schedule,
        times: List<ScheduleTime>
    ): Long {
        return database.withTransaction {
            val medicationId = medicationDao.insertMedication(medication)
            val scheduleToInsert = schedule.copy(medicationId = medicationId)
            val scheduleId = scheduleDao.insertSchedule(scheduleToInsert)
            val timesToInsert = times.map { time ->
                time.copy(scheduleId = scheduleId)
            }
            if (timesToInsert.isNotEmpty()) {
                scheduleTimeDao.insertScheduleTimes(timesToInsert)
            }
            medicationId
        }
    }

    suspend fun updateMedicationWithSchedule(
        medication: Medication,
        schedule: Schedule,
        times: List<ScheduleTime>,
        oldPhotoUri: String? = null
    ) {
        database.withTransaction {
            if (oldPhotoUri != null && oldPhotoUri != medication.photoUri) {
                com.nagi.dosecircle.util.ImageStorageHelper.deleteFile(oldPhotoUri)
            }
            medicationDao.updateMedication(medication)
            val scheduleId = if (schedule.id != 0L) {
                scheduleDao.updateSchedule(schedule)
                schedule.id
            } else {
                scheduleDao.insertSchedule(schedule.copy(medicationId = medication.id))
            }
            scheduleTimeDao.deleteTimesForSchedule(scheduleId)
            val timesToInsert = times.map { time ->
                time.copy(id = 0, scheduleId = scheduleId)
            }
            if (timesToInsert.isNotEmpty()) {
                scheduleTimeDao.insertScheduleTimes(timesToInsert)
            }
        }
    }

    suspend fun updateMedication(medication: Medication) {
        medicationDao.updateMedication(medication)
    }

    suspend fun deleteMedication(medication: Medication) {
        database.withTransaction {
            medication.photoUri?.let { path ->
                com.nagi.dosecircle.util.ImageStorageHelper.deleteFile(path)
            }
            medicationDao.deleteMedication(medication)
        }
    }

    fun getSchedulesForMedication(medicationId: Long): Flow<List<Schedule>> {
        return scheduleDao.getSchedulesForMedication(medicationId)
    }

    fun getTimesForSchedule(scheduleId: Long): Flow<List<ScheduleTime>> {
        return scheduleTimeDao.getTimesForSchedule(scheduleId)
    }
}
