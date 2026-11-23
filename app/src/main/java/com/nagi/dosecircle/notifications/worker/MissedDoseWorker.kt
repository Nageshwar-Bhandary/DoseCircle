package com.nagi.dosecircle.notifications.worker

import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.nagi.dosecircle.DoseCircleApp
import com.nagi.dosecircle.data.entity.DoseStatus

class MissedDoseWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "MissedDoseWorker"
        const val KEY_DOSE_EVENT_ID = "doseEventId"
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
            if (doseEvent != null && doseEvent.status == DoseStatus.PENDING) {
                val nowMillis = System.currentTimeMillis()
                app.doseEventRepository.updateDoseStatus(doseEventId, DoseStatus.MISSED, nowMillis)
                app.doseEventSyncRepository.uploadDoseEvent(doseEventId)
                Log.d(TAG, "DoseEvent $doseEventId marked as MISSED at $nowMillis")

                val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.cancel(doseEventId.toInt())
            } else {
                Log.d(TAG, "DoseEvent $doseEventId is not PENDING (${doseEvent?.status}), skipping")
            }
            return Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Error executing MissedDoseWorker for doseEventId $doseEventId", e)
            return Result.retry()
        }
    }
}
