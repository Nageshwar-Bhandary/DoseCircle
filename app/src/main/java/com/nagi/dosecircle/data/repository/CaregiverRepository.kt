package com.nagi.dosecircle.data.repository

import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import java.security.SecureRandom
import java.util.Date

data class CaregiverLink(
    val id: String = "",
    val patientUid: String = "",
    val caregiverUid: String = "",
    val code: String = "",
    val status: String = "pending", // "pending" or "active"
    val caregiverName: String = "",
    val caregiverEmail: String = "",
    val patientName: String = "",
    val patientEmail: String = ""
)

data class InviteCodeResult(
    val code: String,
    val expiresAt: Date
)

class CaregiverRepository(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
) {
    companion object {
        private const val TAG = "CaregiverRepository"
        // Uppercase letters and digits, excluding 0, O, 1, I
        private const val CODE_CHARS = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
        private const val CODE_LENGTH = 8
        private const val EXPIRY_MINUTES = 10L
        private val random = SecureRandom()
    }

    private fun generateRandomCode(): String {
        val sb = StringBuilder(CODE_LENGTH)
        for (i in 0 until CODE_LENGTH) {
            val idx = random.nextInt(CODE_CHARS.length)
            sb.append(CODE_CHARS[idx])
        }
        return sb.toString()
    }

    /**
     * Generates a random 8-character code (uppercase letters and digits, no 0/O/1/I)
     * and writes inviteCodes/{code} directly with patientUid, createdAt, and expiresAt (10 minutes ahead).
     * If write is denied due to an existing document collision, retries with a new code up to 3 times.
     * Does not swallow other exceptions or treat general errors as collisions.
     */
    suspend fun createInviteCode(): InviteCodeResult {
        val uid = auth.currentUser?.uid
            ?: throw IllegalStateException("User not authenticated.")

        var attempts = 0
        var lastException: Exception? = null

        while (attempts < 3) {
            attempts++
            val code = generateRandomCode()
            val now = Timestamp.now()
            val expiryMillis = System.currentTimeMillis() + (EXPIRY_MINUTES * 60 * 1000)
            val expiresAt = Timestamp(Date(expiryMillis))

            val patientName = auth.currentUser?.displayName ?: ""

            val codeData = mapOf(
                "patientUid" to uid,
                "patientName" to patientName,
                "createdAt" to now,
                "expiresAt" to expiresAt
            )

            try {
                // Attempt to create the document directly without pre-creation read check
                val docRef = firestore.collection("inviteCodes").document(code)
                docRef.set(codeData).await()
                return InviteCodeResult(code, expiresAt.toDate())
            } catch (e: FirebaseFirestoreException) {
                lastException = e
                Log.w(TAG, "Attempt $attempts to create invite code $code failed with code=${e.code}: ${e.message}")
                if (e.code == FirebaseFirestoreException.Code.ALREADY_EXISTS) {
                    // Genuine collision, retry with a new code
                    continue
                } else {
                    // Not a collision (e.g. PERMISSION_DENIED, UNAVAILABLE, etc.), do not retry as collision
                    throw e
                }
            } catch (e: Exception) {
                lastException = e
                Log.e(TAG, "Attempt $attempts to create invite code $code failed with unexpected exception", e)
                throw e
            }
        }

        throw lastException ?: IllegalStateException("Failed to generate invite code after 3 attempts.")
    }

    /**
     * Snapshot listener on links where patientUid == current uid, exposed as a Flow.
     * Safely handles being offline or logged out without crashing.
     */
    fun observeLinks(): Flow<List<CaregiverLink>> = callbackFlow {
        val uid = auth.currentUser?.uid
        if (uid == null) {
            trySend(emptyList())
            close()
            return@callbackFlow
        }

        val listenerRegistration = firestore.collection("links")
            .whereEqualTo("patientUid", uid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    // Offline or permission issue - emit empty or keep current
                    trySend(emptyList())
                    return@addSnapshotListener
                }

                if (snapshot != null) {
                    val links = snapshot.documents.mapNotNull { doc ->
                        try {
                            CaregiverLink(
                                id = doc.id,
                                patientUid = doc.getString("patientUid") ?: "",
                                caregiverUid = doc.getString("caregiverUid") ?: "",
                                code = doc.getString("code") ?: "",
                                status = doc.getString("status") ?: "pending",
                                caregiverName = doc.getString("caregiverName")
                                    ?: doc.getString("name") ?: "",
                                caregiverEmail = doc.getString("caregiverEmail")
                                    ?: doc.getString("email") ?: "",
                                patientName = doc.getString("patientName") ?: "",
                                patientEmail = doc.getString("patientEmail") ?: ""
                            )
                        } catch (_: Exception) {
                            null
                        }
                    }
                    trySend(links)
                }
            }

        awaitClose {
            listenerRegistration.remove()
        }
    }

    /**
     * Updates only the status field from pending to active, then deletes matching inviteCodes/{code}.
     */
    suspend fun approve(link: CaregiverLink): Boolean {
        return try {
            firestore.collection("links")
                .document(link.id)
                .update("status", "active")
                .await()

            if (link.code.isNotEmpty()) {
                try {
                    firestore.collection("inviteCodes")
                        .document(link.code)
                        .delete()
                        .await()
                } catch (_: Exception) {
                    // Ignore deletion error if already deleted
                }
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Deletes the link document.
     */
    suspend fun reject(link: CaregiverLink): Boolean {
        return try {
            firestore.collection("links")
                .document(link.id)
                .delete()
                .await()
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Deletes the link document.
     */
    suspend fun removeCaregiver(link: CaregiverLink): Boolean {
        return try {
            firestore.collection("links")
                .document(link.id)
                .delete()
                .await()
            true
        } catch (_: Exception) {
            false
        }
    }
}
