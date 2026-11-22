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

    suspend fun scheduleForSchedule(scheduleId: Long) {
        if (!PermissionHelper.canScheduleExactAlarms(context)) {
            Log.w(TAG, "Exact alarms permission not granted. Skipping schedule: $scheduleId")
            return
        }

        val schedule = medicationRepository.getScheduleById(scheduleId) ?: return
        if (!schedule.isActive) {
            cancelForSchedule(scheduleId)
            return
        }

        val times = medicationRepository.getTimesForScheduleDirect(scheduleId)
        val selectedDays = schedule.daysOfWeek
            .split(",")
            .mapNotNull { it.trim().toIntOrNull() }
            .toSet()

        if (selectedDays.isEmpty()) return

        for (time in times) {
            val nextTriggerMillis = calculateNextTriggerTime(
                schedule = schedule,
                selectedDays = selectedDays,
                hour = time.hour,
                minute = time.minute
            )

            if (nextTriggerMillis != null) {
                scheduleExactAlarm(
                    scheduleTime = time,
                    medicationId = schedule.medicationId,
                    personId = schedule.personId,
                    triggerAtMillis = nextTriggerMillis
                )
            } else {
                cancelAlarmForScheduleTime(time.id)
            }
        }
    }

    suspend fun cancelForSchedule(scheduleId: Long) {
        val times = medicationRepository.getTimesForScheduleDirect(scheduleId)
        for (time in times) {
            cancelAlarmForScheduleTime(time.id)
        }
    }

    suspend fun rescheduleAll() {
        if (!PermissionHelper.canScheduleExactAlarms(context)) {
            Log.w(TAG, "Exact alarms permission not granted. Skipping rescheduleAll.")
            return
        }

        val activeSchedules = medicationRepository.getAllActiveSchedules()
        for (schedule in activeSchedules) {
            scheduleForSchedule(schedule.id)
        }
    }

    private fun scheduleExactAlarm(
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
