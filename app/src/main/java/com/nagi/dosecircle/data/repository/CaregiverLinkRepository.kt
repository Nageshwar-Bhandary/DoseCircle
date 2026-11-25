package com.nagi.dosecircle.data.repository

import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Source
import com.nagi.dosecircle.data.model.CaregiverLink
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.util.Date

data class CaregiverUserInfo(
    val uid: String,
    val displayName: String?,
    val email: String?
)

open class CaregiverLinkRepository(
    private val auth: FirebaseAuth? = null,
    private val firestore: FirebaseFirestore? = null,
    private val currentUidProvider: () -> String? = {
        try {
            (auth ?: FirebaseAuth.getInstance()).currentUser?.uid
        } catch (_: Exception) {
            null
        }
    },
    private val currentUserInfoProvider: () -> CaregiverUserInfo? = {
        try {
            val user = (auth ?: FirebaseAuth.getInstance()).currentUser
            if (user != null) CaregiverUserInfo(user.uid, user.displayName, user.email) else null
        } catch (_: Exception) {
            null
        }
    }
) {

    private fun getAuth(): FirebaseAuth = auth ?: FirebaseAuth.getInstance()
    private fun getFirestore(): FirebaseFirestore = firestore ?: FirebaseFirestore.getInstance()

    /**
     * Submits an invite code from the caregiver to request a link with a patient.
     *
     * Validates:
     * 1. Code format (must be 8 chars).
     * 2. User must be signed in.
     * 3. The invite code document `inviteCodes/{code}` exists and is not expired (read via Source.SERVER).
     * 4. The patientUid does not match the caregiver's own UID.
     * 5. The link `links/{patientUid}_{currentUid}` does not already exist (verified atomically in a transaction).
     *
     * Creates `links/{patientUid}_{currentUid}` with status "pending" inside a Firestore transaction.
     */
    open suspend fun requestLink(code: String): Result<Unit> {
        val trimmedCode = code.trim().uppercase()
        if (trimmedCode.length != 8) {
            return Result.failure(IllegalArgumentException("Invite code must be 8 characters"))
        }

        val currentUid = currentUidProvider()
            ?: return Result.failure(IllegalStateException("Please sign in first"))
        val currentUserInfo = currentUserInfoProvider()

        // 1. Read invite code using Source.SERVER to prevent validating stale local cache
        val inviteSnapshot = try {
            getFirestore().collection("inviteCodes")
                .document(trimmedCode)
                .get(Source.SERVER)
                .await()
        } catch (e: Exception) {
            if (isNetworkOrUnavailable(e)) {
                return Result.failure(Exception("Network error. Please check your connection."))
            }
            return Result.failure(Exception("Invalid or expired code"))
        }

        if (!inviteSnapshot.exists()) {
            return Result.failure(Exception("Invalid or expired code"))
        }

        val expiresAt = inviteSnapshot.getTimestamp("expiresAt")
        if (expiresAt == null || expiresAt.toDate().before(Date())) {
            return Result.failure(Exception("Invalid or expired code"))
        }

        val patientUid = inviteSnapshot.getString("patientUid")
        if (patientUid.isNullOrBlank()) {
            return Result.failure(Exception("Invalid or expired code"))
        }

        // 2. Prevent self-linking
        if (patientUid == currentUid) {
            return Result.failure(Exception("You can't link to yourself"))
        }

        // 3. Atomically check and create link inside a Firestore transaction to prevent duplicate overwrite
        val linkDocId = "${patientUid}_${currentUid}"
        val linkDocRef = getFirestore().collection("links").document(linkDocId)

        val patientName = inviteSnapshot.getString("patientName")
        val caregiverName = currentUserInfo?.displayName
        val caregiverEmail = currentUserInfo?.email

        val linkData = buildLinkData(
            patientUid = patientUid,
            caregiverUid = currentUid,
            code = trimmedCode,
            patientName = patientName,
            caregiverName = caregiverName,
            caregiverEmail = caregiverEmail
        )

        return try {
            getFirestore().runTransaction { transaction ->
                val existingSnapshot = transaction.get(linkDocRef)
                if (existingSnapshot.exists()) {
                    throw DuplicateLinkException("Already linked or request pending")
                }
                transaction.set(linkDocRef, linkData)
            }.await()
            Result.success(Unit)
        } catch (e: Exception) {
            val cause = (if (e is FirebaseFirestoreException) e.cause else null) ?: e
            if (cause is DuplicateLinkException || e.message?.contains("Already linked or request pending") == true) {
                Result.failure(Exception("Already linked or request pending"))
            } else if (isNetworkOrUnavailable(e)) {
                Result.failure(Exception("Network error. Please check your connection."))
            } else {
                Result.failure(Exception("Failed to send request. Please try again."))
            }
        }
    }

    /**
     * Observes all links where the current user is the caregiver.
     *
     * Lifecycle-aware and thread-safe:
     * - Listens to Firebase Auth state changes.
     * - Synchronizes mutable listener state (listenerGeneration, currentRegistration, lastAttachedUid).
     * - When signed in, attaches snapshot listener for that UID with a generation token.
     * - When signed out or switching accounts, increments generation token to immediately invalidate
     *   in-flight callbacks, detaches prior listener, and emits emptyList().
     * - Verifies listener generation and active Firebase Auth UID under lock before emitting snapshot results.
     * - Cleans up both AuthStateListener and ListenerRegistration on flow cancellation.
     */
    open fun observeMyLinks(): Flow<List<CaregiverLink>> = callbackFlow {
        val lock = Any()
        var currentRegistration: ListenerRegistration? = null
        var lastAttachedUid: String? = null
        var listenerGeneration = 0L

        val authInstance = try {
            getAuth()
        } catch (_: Exception) {
            null
        }

        if (authInstance == null) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }

        val authStateListener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            val oldRegistrationToCancel: ListenerRegistration?
            val queryToAttach: com.google.firebase.firestore.Query?
            val generationForListener: Long
            val uidForListener: String?

            synchronized(lock) {
                val user = firebaseAuth.currentUser
                val currentUid = user?.uid

                if (currentUid == null) {
                    // Logged out: invalidate listener generation, reset tracking, emit empty list
                    listenerGeneration++
                    oldRegistrationToCancel = currentRegistration
                    currentRegistration = null
                    lastAttachedUid = null
                    queryToAttach = null
                    generationForListener = listenerGeneration
                    uidForListener = null
                    trySend(emptyList())
                } else if (currentUid != lastAttachedUid) {
                    // Logged in or switched account:
                    // Invalidate prior generation so any in-flight callbacks from the old listener are discarded
                    listenerGeneration++
                    oldRegistrationToCancel = currentRegistration
                    currentRegistration = null
                    lastAttachedUid = currentUid
                    generationForListener = listenerGeneration
                    uidForListener = currentUid
                    queryToAttach = getFirestore().collection("links")
                        .whereEqualTo("caregiverUid", currentUid)
                    trySend(emptyList())
                } else {
                    oldRegistrationToCancel = null
                    queryToAttach = null
                    generationForListener = listenerGeneration
                    uidForListener = null
                }
            }

            // Remove old listener outside the lock to avoid holding lock during SDK operations
            oldRegistrationToCancel?.remove()

            // Attach new listener if an account change occurred
            if (queryToAttach != null && uidForListener != null) {
                val newRegistration = queryToAttach.addSnapshotListener { snapshot, error ->
                    // Guard against stale callbacks:
                    // 1. Fast check under lock: must match generation and active UID
                    val isCandidate = synchronized(lock) {
                        generationForListener == listenerGeneration &&
                            authInstance.currentUser?.uid == uidForListener
                    }
                    if (!isCandidate) {
                        return@addSnapshotListener
                    }

                    if (error != null) {
                        return@addSnapshotListener
                    }

                    if (snapshot != null) {
                        // Parse documents outside the lock without holding lock
                        val links = snapshot.documents.mapNotNull { doc ->
                            val patientUid = doc.getString("patientUid") ?: return@mapNotNull null
                            val caregiverUid = doc.getString("caregiverUid") ?: return@mapNotNull null
                            val code = doc.getString("code") ?: ""
                            val status = doc.getString("status") ?: "pending"
                            val patientName = doc.getString("patientName")
                            val caregiverName = doc.getString("caregiverName")
                            val caregiverEmail = doc.getString("caregiverEmail")
                            val createdAt = doc.getTimestamp("createdAt")

                            CaregiverLink(
                                id = doc.id,
                                patientUid = patientUid,
                                caregiverUid = caregiverUid,
                                code = code,
                                status = status,
                                patientName = patientName,
                                caregiverName = caregiverName,
                                caregiverEmail = caregiverEmail,
                                createdAt = createdAt
                            )
                        }

                        // Atomic check and emission under the same synchronized block
                        synchronized(lock) {
                            if (generationForListener == listenerGeneration &&
                                authInstance.currentUser?.uid == uidForListener
                            ) {
                                trySend(links)
                            }
                        }
                    }
                }

                // Safely store newRegistration or discard if account changed while registering
                var needsDiscard = false
                synchronized(lock) {
                    if (listenerGeneration == generationForListener) {
                        currentRegistration = newRegistration
                    } else {
                        needsDiscard = true
                    }
                }
                if (needsDiscard) {
                    newRegistration.remove()
                }
            }
        }

        authInstance.addAuthStateListener(authStateListener)

        awaitClose {
            val regToCancel = synchronized(lock) {
                listenerGeneration++
                lastAttachedUid = null
                val reg = currentRegistration
                currentRegistration = null
                reg
            }
            authInstance.removeAuthStateListener(authStateListener)
            regToCancel?.remove()
        }
    }

    /**
     * Cancels a pending request or leaves an active care link.
     */
    open suspend fun cancelOrLeave(link: CaregiverLink): Result<Unit> {
        val currentUid = currentUidProvider()
            ?: return Result.failure(IllegalStateException("Please sign in first"))

        if (link.caregiverUid != currentUid) {
            return Result.failure(SecurityException("Unauthorized to modify this link"))
        }

        val docId = if (link.id.isNotBlank()) link.id else "${link.patientUid}_${link.caregiverUid}"
        return try {
            getFirestore().collection("links").document(docId).delete().await()
            Result.success(Unit)
        } catch (e: Exception) {
            if (isNetworkOrUnavailable(e)) {
                Result.failure(Exception("Network error. Please check your connection."))
            } else {
                Result.failure(Exception("Failed to remove link. Please try again."))
            }
        }
    }

    private class DuplicateLinkException(message: String) : Exception(message)

    companion object {
        internal fun isNetworkOrUnavailable(e: Exception): Boolean {
            if (e is FirebaseFirestoreException) {
                return e.code == FirebaseFirestoreException.Code.UNAVAILABLE ||
                    e.code == FirebaseFirestoreException.Code.DEADLINE_EXCEEDED
            }
            val msg = e.message?.lowercase().orEmpty()
            return msg.contains("unavailable") || msg.contains("network") || msg.contains("offline")
        }

        internal fun buildLinkData(
            patientUid: String,
            caregiverUid: String,
            code: String,
            patientName: String?,
            caregiverName: String?,
            caregiverEmail: String?
        ): Map<String, Any?> {
            val linkData = mutableMapOf<String, Any?>(
                "patientUid" to patientUid,
                "caregiverUid" to caregiverUid,
                "code" to code,
                "status" to "pending",
                "createdAt" to FieldValue.serverTimestamp()
            )

            if (!patientName.isNullOrBlank()) {
                linkData["patientName"] = patientName
            }
            if (!caregiverName.isNullOrBlank()) {
                linkData["caregiverName"] = caregiverName
            }
            if (!caregiverEmail.isNullOrBlank()) {
                linkData["caregiverEmail"] = caregiverEmail
            }

            return linkData
        }

        internal fun validateInviteDocument(
            exists: Boolean,
            expiresAtSeconds: Long?,
            nowSeconds: Long,
            patientUid: String?,
            currentUid: String
        ): Result<String> {
            if (!exists || expiresAtSeconds == null || expiresAtSeconds <= nowSeconds || patientUid.isNullOrBlank()) {
                return Result.failure(Exception("Invalid or expired code"))
            }
            if (patientUid == currentUid) {
                return Result.failure(Exception("You can't link to yourself"))
            }
            return Result.success(patientUid)
        }
    }
}
