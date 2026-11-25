package com.nagi.dosecircle.session

import android.content.SharedPreferences
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.nagi.dosecircle.data.repository.MedicationRepository
import com.nagi.dosecircle.data.repository.PersonRepository
import com.nagi.dosecircle.data.repository.RestoreRepository
import com.nagi.dosecircle.notifications.alarm.AlarmScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

class SessionManager(
    private val personRepository: PersonRepository,
    private val alarmScheduler: AlarmScheduler,
    private val restoreRepository: RestoreRepository? = null,
    private val restorePreferences: SharedPreferences,
    private val medicationRepository: MedicationRepository? = null,
    private val firebaseAuth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) {
    private val _currentPersonId = MutableStateFlow<Long?>(null)
    val currentPersonId: StateFlow<Long?> = _currentPersonId.asStateFlow()

    private var restoredUid: String? =
        restorePreferences.getString(KEY_RESTORED_UID, null)

    private val sessionMutex = Mutex()
    private val authEventVersion = AtomicLong(0)

    @Volatile
    private var restoreJob: Job? = null

    @Volatile
    private var activeRestoreUid: String? = null

    private val authStateListener = FirebaseAuth.AuthStateListener { auth ->
        val user = auth.currentUser
        val eventVersion = authEventVersion.incrementAndGet()

        // Cancel active restore immediately if the user logged out or authenticated UID changed
        if (user == null || user.uid != activeRestoreUid) {
            restoreJob?.cancel()
        }

        scope.launch {
            sessionMutex.withLock {
                // Ignore an outdated auth callback.
                if (eventVersion != authEventVersion.get()) {
                    return@withLock
                }

                if (user != null) {
                    if (firebaseAuth.currentUser?.uid != user.uid) {
                        return@withLock
                    }

                    val previousPersonId = _currentPersonId.value

                    val person = personRepository.getOrCreatePersonForUser(
                        uid = user.uid,
                        email = user.email,
                        displayName = user.displayName
                    )

                    // The user may have changed while the person was being loaded.
                    if (
                        eventVersion != authEventVersion.get() ||
                        firebaseAuth.currentUser?.uid != user.uid
                    ) {
                        return@withLock
                    }

                    _currentPersonId.value = person.id

                    if (previousPersonId != person.id) {
                        alarmScheduler.scheduleAlarmsForPerson(person.id)
                    }

                    // Skip restoration only when the saved UID matches the current UID
                    // AND the local person has at least one medication. Otherwise, restore again.
                    val hasMedications =
                        medicationRepository?.getMedicationsForPerson(person.id)?.firstOrNull()?.isNotEmpty() == true
                    val shouldSkipRestore = restoredUid == user.uid && hasMedications

                    if (!shouldSkipRestore) {
                        // Recheck eventVersion and Firebase UID immediately after the hasMedications suspension point and before creating the restore job
                        if (
                            eventVersion != authEventVersion.get() ||
                            firebaseAuth.currentUser?.uid != user.uid
                        ) {
                            return@withLock
                        }

                        // Cancel any previous restore job before starting a new one
                        restoreJob?.cancel()

                        val currentJob = scope.launch(start = CoroutineStart.LAZY) {
                            try {
                                // Recheck both at the beginning of the lazy restore coroutine
                                if (
                                    eventVersion != authEventVersion.get() ||
                                    firebaseAuth.currentUser?.uid != user.uid
                                ) {
                                    return@launch
                                }

                                val success =
                                    restoreRepository?.restoreForCurrentUser(
                                        expectedUid = user.uid,
                                        personId = person.id
                                    ) ?: false

                                if (
                                    success &&
                                    eventVersion == authEventVersion.get() &&
                                    firebaseAuth.currentUser?.uid == user.uid
                                ) {
                                    restoredUid = user.uid

                                    val persisted = restorePreferences?.edit()
                                        ?.putString(KEY_RESTORED_UID, user.uid)
                                        ?.commit() ?: false

                                    if (!persisted) {
                                        Log.w(
                                            TAG,
                                            "Could not persist restored UID; restore may run again after process restart"
                                        )
                                    }
                                }
                            } catch (e: CancellationException) {
                                Log.d(TAG, "Restore job was cancelled.")
                                throw e
                            }
                        }

                        // Register Job and active UID before the coroutine body can execute
                        activeRestoreUid = user.uid
                        restoreJob = currentJob

                        // Ensure cleanup occurs even if cancelled before starting or during execution
                        currentJob.invokeOnCompletion {
                            if (restoreJob === currentJob) {
                                restoreJob = null
                                activeRestoreUid = null
                            }
                        }

                        try {
                            currentJob.start()
                            currentJob.join()
                        } finally {
                            if (restoreJob === currentJob) {
                                restoreJob = null
                                activeRestoreUid = null
                            }
                        }
                    }
                } else {
                    // User logged out: cancel active restore immediately
                    restoreJob?.cancel()
                    restoreJob = null
                    activeRestoreUid = null

                    // Ignore an outdated logout callback.
                    if (firebaseAuth.currentUser != null) {
                        return@withLock
                    }

                    val previousPersonId = _currentPersonId.value

                    if (previousPersonId != null) {
                        alarmScheduler.cancelAlarmsForPerson(previousPersonId)
                    }

                    // Check again before clearing the active session.
                    if (
                        eventVersion != authEventVersion.get() ||
                        firebaseAuth.currentUser != null
                    ) {
                        return@withLock
                    }

                    restoredUid = null

                    val cleared = restorePreferences.edit()
                        .remove(KEY_RESTORED_UID)
                        .commit()

                    if (!cleared) {
                        Log.w(TAG, "Could not clear persisted restored UID")
                    }

                    _currentPersonId.value = null
                }
            }
        }
    }

    init {
        firebaseAuth.addAuthStateListener(authStateListener)
    }

    private companion object {
        const val TAG = "SessionManager"
        const val KEY_RESTORED_UID = "restored_uid"
    }
}
