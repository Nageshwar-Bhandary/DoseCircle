package com.nagi.dosecircle.notifications.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.nagi.dosecircle.data.entity.Schedule
import com.nagi.dosecircle.data.entity.ScheduleTime
import com.nagi.dosecircle.data.repository.MedicationRepository
import com.nagi.dosecircle.data.repository.PersonRepository
import com.nagi.dosecircle.notifications.receiver.DoseAlarmReceiver
import com.nagi.dosecircle.util.Constants
import com.nagi.dosecircle.util.PermissionHelper
import kotlinx.coroutines.flow.firstOrNull
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

class AlarmScheduler(
    private val context: Context,
    private val personRepository: PersonRepository,
    private val medicationRepository: MedicationRepository
) {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    suspend fun scheduleForSchedule(scheduleId: Long): Int {
        val schedule = medicationRepository.getScheduleById(scheduleId) ?: return 0
        if (!schedule.isActive) {
            cancelForSchedule(scheduleId)
            return 0
        }

        val times = medicationRepository.getTimesForScheduleDirect(scheduleId)
        val selectedDays = schedule.daysOfWeek
            .split(",")
            .mapNotNull { it.trim().toIntOrNull() }
            .toSet()

        if (selectedDays.isEmpty()) return 0

        var scheduledCount = 0
        for (time in times) {
            val nextTriggerMillis = calculateNextTriggerTime(
                schedule = schedule,
                selectedDays = selectedDays,
                hour = time.hour,
                minute = time.minute
            )

            if (nextTriggerMillis != null) {
                scheduleAlarm(
                    scheduleTime = time,
                    medicationId = schedule.medicationId,
                    personId = schedule.personId,
                    triggerAtMillis = nextTriggerMillis
                )
                scheduledCount++
            } else {
                cancelAlarmForScheduleTime(time.id)
            }
        }
        return scheduledCount
    }

    suspend fun scheduleNextForScheduleTime(scheduleTimeId: Long): Long? {
        val scheduleTime = medicationRepository.getScheduleTimeById(scheduleTimeId) ?: return null
        val schedule = medicationRepository.getScheduleById(scheduleTime.scheduleId) ?: return null
        if (!schedule.isActive) {
            cancelAlarmForScheduleTime(scheduleTimeId)
            return null
        }

        val selectedDays = schedule.daysOfWeek
            .split(",")
            .mapNotNull { it.trim().toIntOrNull() }
            .toSet()

        if (selectedDays.isEmpty()) return null

        val nextTriggerMillis = calculateNextTriggerTime(
            schedule = schedule,
            selectedDays = selectedDays,
            hour = scheduleTime.hour,
            minute = scheduleTime.minute
        )

        if (nextTriggerMillis != null) {
            scheduleAlarm(
                scheduleTime = scheduleTime,
                medicationId = schedule.medicationId,
                personId = schedule.personId,
                triggerAtMillis = nextTriggerMillis
            )
            Log.d(
                TAG,
                "Rescheduled next alarm for scheduleTimeId=$scheduleTimeId at $nextTriggerMillis (${LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(nextTriggerMillis), ZoneId.systemDefault())})"
            )
        } else {
            cancelAlarmForScheduleTime(scheduleTime.id)
        }
        return nextTriggerMillis
    }

    suspend fun cancelForSchedule(scheduleId: Long) {
        val times = medicationRepository.getTimesForScheduleDirect(scheduleId)
        for (time in times) {
            cancelAlarmForScheduleTime(time.id)
        }
    }

    suspend fun cancelAlarmsForMedication(medicationId: Long) {
        val schedules = medicationRepository.getSchedulesForMedication(medicationId).firstOrNull() ?: emptyList()
        for (schedule in schedules) {
            cancelForSchedule(schedule.id)
        }
        Log.d(TAG, "cancelAlarmsForMedication completed for medicationId=$medicationId across ${schedules.size} schedules")
    }

    suspend fun scheduleAlarmsForMedication(medicationId: Long): Int {
        val schedules = medicationRepository.getSchedulesForMedication(medicationId).firstOrNull() ?: emptyList()
        var scheduledCount = 0
        for (schedule in schedules) {
            if (schedule.isActive) {
                scheduledCount += scheduleForSchedule(schedule.id)
            }
        }
        return scheduledCount
    }

    suspend fun cancelAlarmsForPerson(personId: Long) {
        val activeSchedules = medicationRepository.getAllActiveSchedules().filter { it.personId == personId }
        for (schedule in activeSchedules) {
            cancelForSchedule(schedule.id)
        }
        Log.d(TAG, "cancelAlarmsForPerson completed for personId=$personId across ${activeSchedules.size} schedules")
    }

    suspend fun scheduleAlarmsForPerson(personId: Long): Int {
        val activeSchedules = medicationRepository.getAllActiveSchedules().filter { it.personId == personId }
        var totalScheduled = 0
        for (schedule in activeSchedules) {
            totalScheduled += scheduleForSchedule(schedule.id)
        }
        Log.d(TAG, "scheduleAlarmsForPerson completed for personId=$personId. Scheduled $totalScheduled alarms across ${activeSchedules.size} schedules")
        return totalScheduled
    }

    suspend fun rescheduleAll(): Int {
        val activeSchedules = medicationRepository.getAllActiveSchedules()
        var totalScheduled = 0
        for (schedule in activeSchedules) {
            totalScheduled += scheduleForSchedule(schedule.id)
        }
        Log.d(TAG, "rescheduleAll completed. Total alarms scheduled: $totalScheduled across ${activeSchedules.size} active schedules")
        return totalScheduled
    }

    fun scheduleExactAlarm(
        scheduleTime: ScheduleTime,
        medicationId: Long,
        personId: Long,
        triggerAtMillis: Long
    ) {
        scheduleAlarm(scheduleTime, medicationId, personId, triggerAtMillis)
    }

    private fun scheduleAlarm(
        scheduleTime: ScheduleTime,
        medicationId: Long,
        personId: Long,
        triggerAtMillis: Long
    ) {
        if (alarmManager == null) return

        val intent = Intent(context, DoseAlarmReceiver::class.java).apply {
            action = Constants.ACTION_DOSE_ALARM
            putExtra("scheduleTimeId", scheduleTime.id)
            putExtra("medicationId", medicationId)
            putExtra("personId", personId)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            scheduleTime.id.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val canScheduleExact = PermissionHelper.canScheduleExactAlarms(context)
        if (canScheduleExact) {
            try {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
                Log.d(
                    TAG,
                    "Scheduled alarm for scheduleTimeId=${scheduleTime.id} at $triggerAtMillis (${LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(triggerAtMillis), ZoneId.systemDefault())})"
                )
            } catch (e: SecurityException) {
                Log.e(TAG, "SecurityException while scheduling exact alarm for timeId=${scheduleTime.id}", e)
                Log.w(
                    TAG,
                    "Falling back to inexact alarm for scheduleTimeId=${scheduleTime.id} at $triggerAtMillis using setAndAllowWhileIdle"
                )
                alarmManager.setAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    triggerAtMillis,
                    pendingIntent
                )
            }
        } else {
            Log.w(
                TAG,
                "Exact alarms permission not granted. Scheduling inexact alarm for scheduleTimeId=${scheduleTime.id} at $triggerAtMillis (${LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(triggerAtMillis), ZoneId.systemDefault())}) using setAndAllowWhileIdle"
            )
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                triggerAtMillis,
                pendingIntent
            )
        }
    }

    private fun cancelAlarmForScheduleTime(scheduleTimeId: Long) {
        if (alarmManager == null) return

        val intent = Intent(context, DoseAlarmReceiver::class.java).apply {
            action = Constants.ACTION_DOSE_ALARM
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            scheduleTimeId.toInt(),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )

        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
            Log.d(TAG, "Canceled alarm for scheduleTimeId=$scheduleTimeId")
        }
    }

    private fun calculateNextTriggerTime(
        schedule: Schedule,
        selectedDays: Set<Int>,
        hour: Int,
        minute: Int
    ): Long? {
        val zoneId = ZoneId.systemDefault()
        val now = LocalDateTime.now(zoneId)
        val startDate = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(schedule.startDate), zoneId).toLocalDate()
        val endDate = schedule.endDate?.let {
            LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(it), zoneId).toLocalDate()
        }

        // Search the next 14 days for a matching trigger
        for (i in 0..14) {
            val candidateDate = now.toLocalDate().plusDays(i.toLong())

            if (candidateDate.isBefore(startDate)) {
                continue
            }
            if (endDate != null && candidateDate.isAfter(endDate)) {
                return null
            }

            val dayOfWeek = candidateDate.dayOfWeek.value // 1 = Monday ... 7 = Sunday
            if (selectedDays.contains(dayOfWeek)) {
                val candidateDateTime = LocalDateTime.of(candidateDate, LocalTime.of(hour, minute, 0))
                if (candidateDateTime.isAfter(now)) {
                    return candidateDateTime.atZone(zoneId).toInstant().toEpochMilli()
                }
            }
        }
        return null
    }

    companion object {
        private const val TAG = "AlarmScheduler"
    }
}
