package com.nagi.dosecircle.notifications.worker

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.nagi.dosecircle.DoseCircleApp
import com.nagi.dosecircle.data.entity.DoseStatus
import com.nagi.dosecircle.notifications.network.CaregiverNotificationClient
import java.util.concurrent.TimeUnit

/**
 * Background retry worker for dispatching missed dose notifications to caregivers via Cloudflare Worker.
 * Scheduled with exponential backoff and unique work naming when initial dispatch fails.
 */
class MissedDoseNotificationRetryWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "MissedNotifyRetry"
        const val KEY_DOSE_CLOUD_ID = "doseCloudId"
        const val KEY_DOSE_EVENT_ID = "doseEventId"
        const val MAX_RETRIES = 5

        fun enqueue(context: Context, doseCloudId: String, doseEventId: Long) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val workRequest = OneTimeWorkRequestBuilder<MissedDoseNotificationRetryWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    15,
                    TimeUnit.SECONDS
                )
                .setInputData(
                    workDataOf(
                        KEY_DOSE_CLOUD_ID to doseCloudId,
                        KEY_DOSE_EVENT_ID to doseEventId
                    )
                )
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                "retry_missed_notify_$doseCloudId",
                ExistingWorkPolicy.KEEP,
                workRequest
            )
            Log.d(TAG, "Enqueued retry work for doseCloudId=$doseCloudId, doseEventId=$doseEventId")
        }
    }

    override suspend fun doWork(): Result {
        val doseCloudId = inputData.getString(KEY_DOSE_CLOUD_ID)
        val doseEventId = inputData.getLong(KEY_DOSE_EVENT_ID, -1L)

        if (doseCloudId.isNullOrBlank()) {
            Log.w(TAG, "Missing doseCloudId, failing permanently")
            return Result.failure()
        }

        if (runAttemptCount >= MAX_RETRIES) {
            Log.w(TAG, "Exceeded MAX_RETRIES ($MAX_RETRIES) for $doseCloudId, terminating retry")
            return Result.failure()
        }

        val app = applicationContext as DoseCircleApp

        // Verify local dose status is still MISSED (if doseEventId is present)
        if (doseEventId != -1L) {
            val doseEvent = try {
                app.doseEventRepository.getDoseEventById(doseEventId)
            } catch (e: Exception) {
                null
            }
            if (doseEvent != null && doseEvent.status != DoseStatus.MISSED) {
                Log.i(TAG, "Dose $doseEventId is no longer MISSED (current: ${doseEvent.status}). Cancelling retry.")
                return Result.success()
            }
        }

        // Execute notification dispatch with fresh token
        val result = app.caregiverNotificationClient.notifyMissedDose(doseCloudId)
        return if (result.isSuccess) {
            Log.i(TAG, "Successfully delivered missed dose notification on retry for $doseCloudId")
            Result.success()
        } else {
            val error = result.exceptionOrNull()
            Log.w(TAG, "Retry attempt $runAttemptCount failed for $doseCloudId: ${error?.message}")
            if (CaregiverNotificationClient.isRetryable(error)) {
                Result.retry()
            } else {
                Log.w(TAG, "Non-retryable response for $doseCloudId, failing permanently")
                Result.failure()
            }
        }
    }
}

