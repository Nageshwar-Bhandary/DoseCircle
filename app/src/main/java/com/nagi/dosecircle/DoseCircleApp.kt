package com.nagi.dosecircle

import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.nagi.dosecircle.data.database.AppDatabase
import com.nagi.dosecircle.data.repository.AuthRepository
import com.nagi.dosecircle.data.repository.DoseEventRepository
import com.nagi.dosecircle.data.repository.FirestoreSyncRepository
import com.nagi.dosecircle.data.repository.MedicationRepository
import com.nagi.dosecircle.data.repository.PersonRepository
import com.nagi.dosecircle.notifications.alarm.AlarmScheduler
import com.nagi.dosecircle.notifications.worker.MissedSweepWorker
import com.nagi.dosecircle.session.SessionManager
import com.nagi.dosecircle.util.NotificationHelper
import java.util.concurrent.TimeUnit

class DoseCircleApp : Application() {

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createNotificationChannels(this)
        scheduleMissedSweepWorker()
        // Initialize sessionManager so it starts listening to auth state
        sessionManager
    }

    private fun scheduleMissedSweepWorker() {
        val sweepWorkRequest =
            PeriodicWorkRequestBuilder<MissedSweepWorker>(15, TimeUnit.MINUTES)
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

    val authRepository: AuthRepository by lazy {
        AuthRepository()
    }

    val firestoreSyncRepository: FirestoreSyncRepository by lazy {
        FirestoreSyncRepository(database)
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

    val sessionManager: SessionManager by lazy {
        SessionManager(
            personRepository = personRepository,
            alarmScheduler = alarmScheduler
        )
    }
}