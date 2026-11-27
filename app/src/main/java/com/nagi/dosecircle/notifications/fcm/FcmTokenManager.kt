package com.nagi.dosecircle.notifications.fcm

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Coordinated manager for FCM token lifecycle operations.
 *
 * Prevents race conditions between token sync on login, token refresh, and logout:
 * - Uses a shared [tokenMutex] across all token write and deletion operations.
 * - Logout waits for any active Firestore write to complete before deleting the fcmToken field.
 * - Increments [tokenGeneration] on every operation and before logout deletion to invalidate in-flight saves.
 * - Maintains [loggedOutUids] guard: once logout starts for a UID, token writes and refreshes
 *   for that UID are rejected until a genuine new session is initiated via [syncTokenForUser].
 * - Re-verifies generation token, active UID, and loggedOut status immediately before any write under [tokenMutex].
 * - Uses an application-level [CoroutineScope] instead of an unmanaged service scope.
 */
open class FcmTokenManager(
    private val appScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    private val currentUidProvider: () -> String? = {
        try {
            FirebaseAuth.getInstance().currentUser?.uid
        } catch (_: Exception) {
            null
        }
    },
    private val tokenProvider: suspend () -> String? = {
        try {
            FirebaseMessaging.getInstance().token.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch FCM token: ${e.message}")
            null
        }
    },
    private val firestoreWriter: suspend (uid: String, token: String) -> Unit = { uid, token ->
        FirebaseFirestore.getInstance()
            .collection("users")
            .document(uid)
            .set(mapOf("fcmToken" to token), SetOptions.merge())
            .await()
    },
    private val firestoreDeleter: suspend (uid: String) -> Unit = { uid ->
        FirebaseFirestore.getInstance()
            .collection("users")
            .document(uid)
            .update("fcmToken", FieldValue.delete())
            .await()
    }
) {
    // Convenience constructor for direct Firebase SDK instances
    constructor(
        appScope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
        firebaseAuth: FirebaseAuth,
        firestore: FirebaseFirestore,
        firebaseMessaging: FirebaseMessaging
    ) : this(
        appScope = appScope,
        currentUidProvider = { firebaseAuth.currentUser?.uid },
        tokenProvider = { firebaseMessaging.token.await() },
        firestoreWriter = { uid, token ->
            firestore.collection("users")
                .document(uid)
                .set(mapOf("fcmToken" to token), SetOptions.merge())
                .await()
        },
        firestoreDeleter = { uid ->
            firestore.collection("users")
                .document(uid)
                .update("fcmToken", FieldValue.delete())
                .await()
        }
    )

    private val tokenMutex = Mutex()
    private val tokenGeneration = AtomicLong(0)
    private val loggedOutUids = ConcurrentHashMap.newKeySet<String>()
    private val logoutInProgressUids = ConcurrentHashMap.newKeySet<String>()
    private val signOutCompletedUids = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var activeSyncJob: Job? = null

    internal fun isLoggedOut(uid: String): Boolean = loggedOutUids.contains(uid)
    internal fun isLogoutInProgress(uid: String): Boolean = logoutInProgressUids.contains(uid)
    internal fun isSignOutCompleted(uid: String): Boolean = signOutCompletedUids.contains(uid)
    internal fun isLogoutActive(uid: String): Boolean =
        logoutInProgressUids.contains(uid) || (loggedOutUids.contains(uid) && !signOutCompletedUids.contains(uid))

    /**
     * Signals that Firebase Auth sign-out has completed for the given UID.
     *
     * This marks the completion of the sign-out phase following token deletion,
     * allowing subsequent genuine logins for [uid] to sync their token and clear the logged-out guard.
     */
    fun onSignOutCompleted(uid: String) {
        signOutCompletedUids.add(uid)
        logD(TAG, "Sign-out completed for $uid")
    }

    /**
     * Signals that Firebase Auth sign-out failed or was cancelled for the given UID.
     *
     * If [uid] is still the currently authenticated user according to [currentUidProvider],
     * removes [uid] from [loggedOutUids] and [signOutCompletedUids] so that token syncing and
     * refreshes for the ongoing authenticated session continue to work.
     * Does not clear another user's guard if the authenticated user has changed or is null.
     */
    fun onSignOutFailed(uid: String) {
        if (currentUidProvider() == uid) {
            loggedOutUids.remove(uid)
            signOutCompletedUids.remove(uid)
            logD(TAG, "Sign-out failed or cancelled for $uid while still authenticated. Restored token sync guards.")
        } else {
            logD(TAG, "Sign-out failed or cancelled for $uid, but current user is different or null. Guard retained.")
        }
    }

    /**
     * Synchronizes the FCM token for the specified user upon successful login.
     *
     * Serialization guarantees:
     * 1. If logout is currently in progress or awaiting sign-out for [uid], rejects immediately
     *    without removing the logout guard or writing to Firestore.
     * 2. Acquires a generation token and cancels any prior sync job.
     * 3. Checks that Firebase Auth still belongs to [uid] before fetching the token.
     * 4. Acquires [tokenMutex] to serialize with other token writes and logout deletion.
     * 5. Immediately before writing under the lock, verifies generation has not changed,
     *    Firebase Auth still belongs to [uid], and logout is not active.
     * 6. Writes to users/{uid}.fcmToken using Firestore merge semantics and releases [loggedOutUids]
     *    only after the write succeeds.
     */
    open fun syncTokenForUser(uid: String): Job {
        if (isLogoutActive(uid)) {
            logD(TAG, "Logout in progress or awaiting sign-out for $uid. Rejecting syncTokenForUser.")
            val cancelledJob = Job()
            cancelledJob.cancel()
            return cancelledJob
        }

        val currentGen = tokenGeneration.incrementAndGet()
        activeSyncJob?.cancel()

        val job = appScope.launch {
            try {
                if (currentUidProvider() != uid) {
                    logD(TAG, "Auth UID changed before fetching token ($uid). Aborting sync.")
                    return@launch
                }

                if (isLogoutActive(uid)) {
                    logD(TAG, "Logout in progress or awaiting sign-out for $uid before token fetch. Aborting sync.")
                    return@launch
                }

                val token = try {
                    tokenProvider()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logW(TAG, "Failed to fetch FCM token: ${e.message}")
                    return@launch
                }

                if (token.isNullOrBlank()) {
                    logD(TAG, "FCM token is null or blank. Aborting sync for $uid.")
                    return@launch
                }

                tokenMutex.withLock {
                    if (isLogoutActive(uid)) {
                        logD(TAG, "Logout in progress or awaiting sign-out for $uid under lock. Aborting sync.")
                        return@withLock
                    }

                    if (tokenGeneration.get() != currentGen) {
                        logD(TAG, "Token generation changed ($currentGen != ${tokenGeneration.get()}). Aborting token save for $uid.")
                        return@withLock
                    }

                    val authUid = currentUidProvider()
                    if (authUid != uid) {
                        logD(TAG, "Auth UID changed immediately before write ($authUid != $uid). Aborting save.")
                        return@withLock
                    }

                    firestoreWriter(uid, token)
                    loggedOutUids.remove(uid)
                    signOutCompletedUids.remove(uid)
                    logD(TAG, "Successfully synced FCM token and released guard for user: $uid")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logW(TAG, "Unexpected error in syncTokenForUser for $uid: ${e.message}")
            }
        }

        activeSyncJob = job
        return job
    }

    /**
     * Updates the FCM token when a new token is generated by Firebase.
     *
     * Serialization guarantees:
     * 1. Verifies that a user is currently authenticated and logout is not active (neither in progress nor awaiting sign-out).
     * 2. Acquires [tokenMutex] to serialize with other token operations.
     * 3. Immediately before writing under the lock, verifies generation has not changed,
     *    the authenticated user still matches the initial UID, and logout is not active.
     * 4. Writes to users/{uid}.fcmToken using Firestore merge semantics.
     * 5. Clears [loggedOutUids] and [signOutCompletedUids] only after a successful Firestore token write.
     */
    fun onNewToken(token: String): Job? {
        if (token.isBlank()) return null

        val initialUid = currentUidProvider() ?: run {
            logD(TAG, "onNewToken received but no user is currently logged in. Discarding.")
            return null
        }

        if (isLogoutActive(initialUid)) {
            logD(TAG, "Logout is in progress or sign-out is pending for $initialUid. Discarding onNewToken.")
            return null
        }

        val currentGen = tokenGeneration.incrementAndGet()
        activeSyncJob?.cancel()

        val job = appScope.launch {
            try {
                tokenMutex.withLock {
                    if (tokenGeneration.get() != currentGen) {
                        logD(TAG, "Token generation changed ($currentGen != ${tokenGeneration.get()}). Aborting onNewToken save.")
                        return@withLock
                    }

                    val authUid = currentUidProvider()
                    if (authUid != initialUid) {
                        logD(TAG, "Auth user changed ($authUid != $initialUid). Aborting onNewToken save.")
                        return@withLock
                    }

                    if (isLogoutActive(initialUid)) {
                        logD(TAG, "Logout is in progress or sign-out is pending for $initialUid under lock. Aborting onNewToken save.")
                        return@withLock
                    }

                    firestoreWriter(initialUid, token)
                    loggedOutUids.remove(initialUid)
                    signOutCompletedUids.remove(initialUid)
                    logD(TAG, "Successfully updated refreshed FCM token and released guard for user: $initialUid")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logW(TAG, "Unexpected error in onNewToken: ${e.message}")
            }
        }

        activeSyncJob = job
        return job
    }

    /**
     * Coordinates token deletion during logout.
     *
     * Serialization guarantees:
     * 1. Immediately marks [uid] in [loggedOutUids] and [logoutInProgressUids] so any concurrent
     *    or subsequent [onNewToken] and [syncTokenForUser] calls for this UID are rejected
     *    before and after auth.signOut().
     * 2. Increments [tokenGeneration] immediately so any pending write awaiting token or mutex will abort.
     * 3. Acquires [tokenMutex]. If an earlier write is currently executing in Firestore,
     *    this waits for that write to finish before deleting.
     * 4. Deletes only the fcmToken field via FieldValue.delete().
     * 5. Catches Firestore exceptions (e.g. offline / not found) while rethrowing CancellationException,
     *    ensuring the logout flow is never blocked from completing auth.signOut().
     * 6. In finally block, removes [uid] from [logoutInProgressUids] while keeping it in [loggedOutUids]
     *    until a genuine new login occurs.
     */
    suspend fun deleteTokenOnLogout(uid: String) {
        loggedOutUids.add(uid)
        logoutInProgressUids.add(uid)
        signOutCompletedUids.remove(uid)
        tokenGeneration.incrementAndGet()

        try {
            tokenMutex.withLock {
                try {
                    firestoreDeleter(uid)
                    logD(TAG, "Successfully deleted fcmToken field for user: $uid")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logW(TAG, "Could not delete fcmToken for user $uid from Firestore: ${e.message}")
                }
            }
        } finally {
            logoutInProgressUids.remove(uid)
        }
    }

    companion object {
        private const val TAG = "FcmTokenManager"

        private fun logD(tag: String, msg: String) {
            try {
                Log.d(tag, msg)
            } catch (_: Throwable) {
                // In local JVM unit tests, android.util.Log is not mocked
            }
        }

        private fun logW(tag: String, msg: String) {
            try {
                Log.w(tag, msg)
            } catch (_: Throwable) {
                // In local JVM unit tests, android.util.Log is not mocked
            }
        }
    }
}
