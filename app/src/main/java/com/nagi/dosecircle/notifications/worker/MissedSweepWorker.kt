package com.nagi.dosecircle.notifications.worker

import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.nagi.dosecircle.DoseCircleApp
import com.nagi.dosecircle.data.entity.DoseStatus
import com.nagi.dosecircle.util.Constants
import java.util.concurrent.TimeUnit

class MissedSweepWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "MissedSweepWorker"
        const val UNIQUE_WORK_NAME = "MissedSweepWorker"
    }

    override suspend fun doWork(): Result {
        val app = applicationContext as DoseCircleApp
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        return try {
            val cutoffTime = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(Constants.MISSED_GRACE_MINUTES)
            val pendingDoses = app.doseEventRepository.getPendingDosesBefore(cutoffTime)

            Log.d(TAG, "Running MissedSweepWorker. Found ${pendingDoses.size} pending doses before $cutoffTime")

            val nowMillis = System.currentTimeMillis()
            for (dose in pendingDoses) {
                app.doseEventRepository.updateDoseStatus(dose.id, DoseStatus.MISSED, nowMillis)
                app.doseEventSyncRepository.uploadDoseEvent(dose.id)
                notificationManager.cancel(dose.id.toInt())
                Log.d(TAG, "Sweep marked DoseEvent ${dose.id} as MISSED")
            }

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error running MissedSweepWorker", e)
            Result.retry()
        }
    }
}
