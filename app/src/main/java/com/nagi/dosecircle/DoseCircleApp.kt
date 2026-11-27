
package com.nagi.dosecircle

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessaging
import com.nagi.dosecircle.data.database.AppDatabase
import com.nagi.dosecircle.data.repository.ActivityRepository
import com.nagi.dosecircle.data.repository.AuthRepository
import com.nagi.dosecircle.data.repository.CaregiverRepository
import com.nagi.dosecircle.data.repository.CaregiverLinkRepository
import com.nagi.dosecircle.data.repository.DoseEventRepository
import com.nagi.dosecircle.data.repository.DoseEventSyncRepository
import com.nagi.dosecircle.data.repository.FirestoreSyncRepository
import com.nagi.dosecircle.data.repository.MedicationRepository
import com.nagi.dosecircle.data.repository.PersonRepository
import com.nagi.dosecircle.data.repository.RestoreRepository
import com.nagi.dosecircle.notifications.alarm.AlarmScheduler
import com.nagi.dosecircle.notifications.fcm.FcmTokenManager
import com.nagi.dosecircle.notifications.worker.MissedSweepWorker
import com.nagi.dosecircle.session.SessionManager
import com.nagi.dosecircle.steps.StepSyncWorker
import com.nagi.dosecircle.steps.StepTracker
import com.nagi.dosecircle.util.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class DoseCircleApp : Application() {

    private var foregroundActivityCount = 0

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createNotificationChannels(this)
        scheduleMissedSweepWorker()
        scheduleStepSyncWorker()

        // Initialize sessionManager so it starts listening to auth state.
        sessionManager

        setupForegroundStepTracking()
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

    private fun scheduleStepSyncWorker() {
        val stepSyncWorkRequest =
            PeriodicWorkRequestBuilder<StepSyncWorker>(15, TimeUnit.MINUTES)
                .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            StepSyncWorker.UNIQUE_WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            stepSyncWorkRequest
        )
    }

    private fun setupForegroundStepTracking() {
        // Observe currentPersonId to start/stop tracking when user changes.
        CoroutineScope(Dispatchers.Main).launch {
            sessionManager.currentPersonId.collect { personId ->
                if (personId != null && foregroundActivityCount > 0) {
                    stepTracker.startForegroundTracking(personId)
                } else if (personId == null) {
                    stepTracker.stopForegroundTracking()
                }
            }
        }

        // Track app foreground/background transitions.
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                foregroundActivityCount++
                if (foregroundActivityCount == 1) {
                    val personId = sessionManager.currentPersonId.value
                    if (personId != null) {
                        stepTracker.startForegroundTracking(personId)
                    }
                }
            }

            override fun onActivityStopped(activity: Activity) {
                foregroundActivityCount--
                if (foregroundActivityCount <= 0) {
                    foregroundActivityCount = 0
                    stepTracker.stopForegroundTracking()
                }
            }

            override fun onActivityCreated(
                activity: Activity,
                savedInstanceState: Bundle?
            ) {}

            override fun onActivityResumed(activity: Activity) {}

            override fun onActivityPaused(activity: Activity) {}

            override fun onActivitySaveInstanceState(
                activity: Activity,
                outState: Bundle
            ) {}

            override fun onActivityDestroyed(activity: Activity) {}
        })
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

    val fcmTokenManager: FcmTokenManager by lazy {
        FcmTokenManager(
            appScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            firebaseAuth = FirebaseAuth.getInstance(),
            firestore = FirebaseFirestore.getInstance(),
            firebaseMessaging = FirebaseMessaging.getInstance()
        )
    }

    val authRepository: AuthRepository by lazy {
        AuthRepository(
            auth = FirebaseAuth.getInstance(),
            fcmTokenManager = fcmTokenManager
        )
    }

    val firestoreSyncRepository: FirestoreSyncRepository by lazy {
        FirestoreSyncRepository(database)
    }

    // Patient-side caregiver repository.
    val caregiverRepository: CaregiverRepository by lazy {
        CaregiverRepository()
    }

    val doseEventRepository: DoseEventRepository by lazy {
        DoseEventRepository(database.doseEventDao())
    }

    val doseEventSyncRepository: DoseEventSyncRepository by lazy {
        DoseEventSyncRepository(database)
    }

    // Caregiver-side linking repository.
    val caregiverLinkRepository: CaregiverLinkRepository by lazy {
        CaregiverLinkRepository()
    }

    val activityRepository: ActivityRepository by lazy {
        ActivityRepository(database.dailyActivityDao())
    }

    val stepTracker: StepTracker by lazy {
        StepTracker(this, activityRepository)
    }

    val alarmScheduler: AlarmScheduler by lazy {
        AlarmScheduler(
            context = this,
            personRepository = personRepository,
            medicationRepository = medicationRepository
        )
    }

    val restoreRepository: RestoreRepository by lazy {
        RestoreRepository(
            database = database,
            medicationRepository = medicationRepository,
            doseEventRepository = doseEventRepository,
            alarmScheduler = alarmScheduler,
            auth = FirebaseAuth.getInstance()
        )
    }

    val restoreSessionPreferences by lazy {
        getSharedPreferences(
            "restore_session_prefs",
            Context.MODE_PRIVATE
        )
    }

    val sessionManager: SessionManager by lazy {
        SessionManager(
            personRepository = personRepository,
            alarmScheduler = alarmScheduler,
            restoreRepository = restoreRepository,
            restorePreferences = restoreSessionPreferences,
            medicationRepository = medicationRepository,
            fcmTokenManager = fcmTokenManager
        )
    }
}
