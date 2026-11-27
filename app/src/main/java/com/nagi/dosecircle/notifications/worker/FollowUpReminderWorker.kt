package com.nagi.dosecircle.notifications.worker

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.nagi.dosecircle.DoseCircleApp
import com.nagi.dosecircle.MainActivity
import com.nagi.dosecircle.R
import com.nagi.dosecircle.data.entity.DoseStatus
import com.nagi.dosecircle.notifications.receiver.DoseActionReceiver
import com.nagi.dosecircle.util.Constants

class FollowUpReminderWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "FollowUpReminderWorker"
        const val KEY_DOSE_EVENT_ID = "doseEventId"

        fun getWorkTag(doseEventId: Long): String = "followup_dose_$doseEventId"
    }

    override suspend fun doWork(): Result {
        val doseEventId = inputData.getLong(KEY_DOSE_EVENT_ID, -1L)
        if (doseEventId == -1L) {
            Log.w(TAG, "Missing or invalid doseEventId")
            return Result.failure()
        }

        val app = applicationContext as DoseCircleApp
        try {
            val doseEvent = app.doseEventRepository.getDoseEventById(doseEventId)
            if (doseEvent == null || doseEvent.status != DoseStatus.PENDING) {
                Log.d(TAG, "DoseEvent $doseEventId not found or not PENDING (${doseEvent?.status}), doing nothing")
                return Result.success()
            }

            val medication = app.medicationRepository.getMedicationById(doseEvent.medicationId)
            val medicineName = medication?.name ?: "your medicine"
            val dosage = medication?.dosage ?: ""

            // Recheck status immediately before posting notification
            val freshDoseEvent = app.doseEventRepository.getDoseEventById(doseEventId)
            if (freshDoseEvent == null || freshDoseEvent.status != DoseStatus.PENDING) {
                Log.d(TAG, "DoseEvent $doseEventId no longer PENDING on recheck (${freshDoseEvent?.status}), aborting notification")
                return Result.success()
            }

            showFollowUpNotification(applicationContext, doseEventId, medicineName, dosage)
            return Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error executing FollowUpReminderWorker for doseEventId $doseEventId", e)
            return Result.retry()
        }
    }

    private fun showFollowUpNotification(
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

        val contentText = if (dosage.isNotBlank()) {
            "Reminder: take $medicationName ($dosage)"
        } else {
            "Reminder: take $medicationName"
        }

        val notification = NotificationCompat.Builder(context, Constants.CHANNEL_DOSE_REMINDERS)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Time to take your medicine")
            .setContentText(contentText)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setContentIntent(contentPendingIntent)
            .setAutoCancel(true)
            .addAction(0, "Taken", takenPendingIntent)
            .addAction(0, "Skip", skipPendingIntent)
            .setExtras(extras)
            .build()

        notificationManager.notify(doseEventId.toInt(), notification)
    }
}
