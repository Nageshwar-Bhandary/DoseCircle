package com.nagi.dosecircle

import android.app.Application
import com.nagi.dosecircle.data.database.AppDatabase
import com.nagi.dosecircle.data.repository.DoseEventRepository
import com.nagi.dosecircle.data.repository.MedicationRepository
import com.nagi.dosecircle.data.repository.PersonRepository
import com.nagi.dosecircle.notifications.alarm.AlarmScheduler
import com.nagi.dosecircle.notifications.worker.MissedSweepWorker
import com.nagi.dosecircle.util.NotificationHelper
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class DoseCircleApp : Application() {

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createNotificationChannels(this)
        scheduleMissedSweepWorker()
    }

    private fun scheduleMissedSweepWorker() {
        val sweepWorkRequest = PeriodicWorkRequestBuilder<MissedSweepWorker>(15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            MissedSweepWorker.UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            sweepWorkRequest
        )
    }

    val database: AppDatabase by lazy {
        AppDatabase.getDatabase(this)
    }

    val personRepository: PersonRepository by lazy {
        PersonRepository(database.personDao())
    }

    val medicationRepository: MedicationRepository by lazy {
        MedicationRepository(database)
    }

    val doseEventRepository: DoseEventRepository by lazy {
        DoseEventRepository(database.doseEventDao())
    }

    val alarmScheduler: AlarmScheduler by lazy {
        AlarmScheduler(
            context = this,
            personRepository = personRepository,
            medicationRepository = medicationRepository
        )
    }
}
