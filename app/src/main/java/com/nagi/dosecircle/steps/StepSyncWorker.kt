package com.nagi.dosecircle.steps

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.nagi.dosecircle.DoseCircleApp

class StepSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        const val UNIQUE_WORK_NAME = "StepSyncWorker"
    }

    override suspend fun doWork(): Result {
        val app = applicationContext as? DoseCircleApp ?: return Result.success()
        val personId = app.sessionManager.currentPersonId.value ?: return Result.success()

        return try {
            app.stepTracker.trackStepsOneShot(personId)
            Result.success()
        } catch (_: Exception) {
            Result.success() // Keep it safe and never crash or fail
        }
    }
}
