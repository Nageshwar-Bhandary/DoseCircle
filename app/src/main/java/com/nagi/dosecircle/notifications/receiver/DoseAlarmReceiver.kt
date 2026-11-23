package com.nagi.dosecircle.notifications.receiver

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.core.app.NotificationCompat
import com.nagi.dosecircle.DoseCircleApp
import com.nagi.dosecircle.MainActivity
import com.nagi.dosecircle.R
import com.nagi.dosecircle.data.entity.DoseEvent
import com.nagi.dosecircle.data.entity.DoseStatus
import com.nagi.dosecircle.notifications.worker.MissedDoseWorker
import com.nagi.dosecircle.util.Constants
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.ZoneId

class DoseAlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "DoseAlarmReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Constants.ACTION_DOSE_ALARM) return

        val scheduleTimeId = intent.getLongExtra("scheduleTimeId", -1L)
        val medicationId = intent.getLongExtra("medicationId", -1L)
        val personId = intent.getLongExtra("personId", -1L)

        if (scheduleTimeId == -1L || medicationId == -1L || personId == -1L) {
            Log.w(TAG, "Invalid alarm extras: scheduleTimeId=$scheduleTimeId, medicationId=$medicationId, personId=$personId")
            return
        }

        val pendingResult = goAsync()
        val app = context.applicationContext as DoseCircleApp

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // (1) Load medication and schedule
                val medication = app.medicationRepository.getMedicationById(medicationId)
                val scheduleTime = app.medicationRepository.getScheduleTimeById(scheduleTimeId)
                val schedule = if (scheduleTime != null) {
                    app.medicationRepository.getScheduleById(scheduleTime.scheduleId)
                } else null

                // (2) Skip safely if medication no longer exists or schedule is inactive
                if (medication == null || schedule == null || !schedule.isActive) {
                    Log.d(TAG, "Skipping alarm: medication=$medication, schedule=$schedule")
                    return@launch
                }

                // (3) Insert DoseEvent with status PENDING and scheduledTime = now
                val nowMillis = System.currentTimeMillis()
                val doseEvent = DoseEvent(
                    personId = personId,
                    medicationId = medicationId,
                    scheduledTime = nowMillis,
                    status = DoseStatus.PENDING,
                    actionTime = null
                )
                val doseEventId = app.doseEventRepository.insertDoseEvent(doseEvent)
                app.doseEventSyncRepository.uploadDoseEvent(doseEventId)

                // Enqueue MissedDoseWorker with initial delay of MISSED_GRACE_MINUTES
                val missedWorkRequest = OneTimeWorkRequestBuilder<MissedDoseWorker>()
                    .setInitialDelay(Constants.MISSED_GRACE_MINUTES, TimeUnit.MINUTES)
                    .setInputData(workDataOf(MissedDoseWorker.KEY_DOSE_EVENT_ID to doseEventId))
                    .build()
                WorkManager.getInstance(context).enqueue(missedWorkRequest)

                // (4) Show notification on dose_reminders channel
                showDoseNotification(context, doseEventId, medication.name, medication.dosage)

                // (5) Reschedule the next occurrence of that same ScheduleTime through AlarmScheduler
                val nextTrigger = app.alarmScheduler.scheduleNextForScheduleTime(scheduleTimeId)
                if (nextTrigger != null) {
                    val nextDateTime = LocalDateTime.ofInstant(
                        java.time.Instant.ofEpochMilli(nextTrigger),
                        ZoneId.systemDefault()
                    )
                    Log.d(TAG, "Next trigger time for scheduleTimeId=$scheduleTimeId is $nextTrigger ($nextDateTime)")
                } else {
                    Log.d(TAG, "No further occurrences found for scheduleTimeId=$scheduleTimeId")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling dose alarm", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun showDoseNotification(
        context: Context,
        doseEventId: Long,
        medicationName: String,
        dosage: String
    ) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Content intent: tapping notification body opens MainActivity
        val contentIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("doseEventId", doseEventId)
        }
        val contentPendingIntent = PendingIntent.getActivity(
            context,
            doseEventId.toInt(),
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action "Taken"
        val takenIntent = Intent(context, DoseActionReceiver::class.java).apply {
            action = Constants.ACTION_TAKEN
            putExtra("doseEventId", doseEventId)
        }
        val takenRequestCode = (doseEventId * 31 + 1).toInt()
        val takenPendingIntent = PendingIntent.getBroadcast(
            context,
            takenRequestCode,
            takenIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Action "Skip"
        val skipIntent = Intent(context, DoseActionReceiver::class.java).apply {
            action = Constants.ACTION_SKIP
            putExtra("doseEventId", doseEventId)
        }
        val skipRequestCode = (doseEventId * 31 + 2).toInt()
        val skipPendingIntent = PendingIntent.getBroadcast(
            context,
            skipRequestCode,
            skipIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val extras = Bundle().apply {
            putLong("doseEventId", doseEventId)
        }

        val notification = NotificationCompat.Builder(context, Constants.CHANNEL_DOSE_REMINDERS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Time to take your medicine")
            .setContentText("$medicationName ($dosage)")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(contentPendingIntent)
            .setAutoCancel(true)
            .addAction(0, "Taken", takenPendingIntent)
            .addAction(0, "Skip", skipPendingIntent)
            .setExtras(extras)
            .build()

        notificationManager.notify(doseEventId.toInt(), notification)
    }
}
