package com.nagi.dosecircle.data.repository

import android.util.Log
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.AuthCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.UserProfileChangeRequest
import com.nagi.dosecircle.notifications.fcm.FcmTokenManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class AuthRepository(
    private val auth: FirebaseAuth? = null,
    private val fcmTokenManager: FcmTokenManager? = null,
    private val signOutAction: () -> Unit = { (auth ?: FirebaseAuth.getInstance()).signOut() },
    private val currentUidProvider: () -> String? = {
        try {
            (auth ?: FirebaseAuth.getInstance()).currentUser?.uid
        } catch (_: Exception) {
            null
        }
    }
) {
    private val authInstance: FirebaseAuth
        get() = auth ?: FirebaseAuth.getInstance()

    val currentUser: FirebaseUser?
        get() = try { authInstance.currentUser } catch (_: Exception) { null }

    suspend fun signUp(
        email: String,
        password: String,
        displayName: String
    ): Result<FirebaseUser> {
        val trimmedEmail = email.trim()
        val trimmedName = displayName.trim()

        if (trimmedEmail.isEmpty() || password.isEmpty()) {
            return Result.failure(IllegalArgumentException("Email and password cannot be empty."))
        }

        return try {
            val authResult = authInstance.createUserWithEmailAndPassword(trimmedEmail, password).await()
            val user = authResult.user ?: throw IllegalStateException("Account created but user details are unavailable.")

            if (trimmedName.isNotEmpty()) {
                val profileUpdates = UserProfileChangeRequest.Builder()
                    .setDisplayName(trimmedName)
                    .build()
                user.updateProfile(profileUpdates).await()
            }

            Result.success(user)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("AuthRepository", "Firebase signInWithCredential failed: [${(e as? FirebaseAuthException)?.errorCode ?: e::class.java.simpleName}] ${e.message}", e)
            Result.failure(Exception(getReadableErrorMessage(e), e))
        }
    }

    suspend fun login(email: String, password: String): Result<FirebaseUser> {
        val trimmedEmail = email.trim()

        if (trimmedEmail.isEmpty() || password.isEmpty()) {
            return Result.failure(IllegalArgumentException("Email and password cannot be empty."))
        }

        return try {
            val authResult = authInstance.signInWithEmailAndPassword(trimmedEmail, password).await()
            val user = authResult.user ?: throw IllegalStateException("Login succeeded but user details are unavailable.")
            Result.success(user)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("AuthRepository", "Firebase signInWithCredential failed: [${(e as? FirebaseAuthException)?.errorCode ?: e::class.java.simpleName}] ${e.message}", e)
            Result.failure(Exception(getReadableErrorMessage(e), e))
        }
    }

    suspend fun signInWithGoogle(idToken: String): Result<FirebaseUser> {
        val trimmedToken = idToken.trim()

        if (trimmedToken.isEmpty()) {
            return Result.failure(IllegalArgumentException("Google ID token cannot be empty."))
        }

        return try {
            val credential = GoogleAuthProvider.getCredential(trimmedToken, null)
            signInWithCredential(credential)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("AuthRepository", "Firebase signInWithCredential failed: [${(e as? FirebaseAuthException)?.errorCode ?: e::class.java.simpleName}] ${e.message}", e)
            Result.failure(Exception(getReadableErrorMessage(e), e))
        }
    }

    suspend fun signInWithCredential(credential: AuthCredential): Result<FirebaseUser> {
        return try {
            val authResult = authInstance.signInWithCredential(credential).await()
            val user = authResult.user ?: throw IllegalStateException("Google sign-in succeeded but user details are unavailable.")
            Result.success(user)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("AuthRepository", "Firebase signInWithCredential failed: [${(e as? FirebaseAuthException)?.errorCode ?: e::class.java.simpleName}] ${e.message}", e)
            Result.failure(Exception(getReadableErrorMessage(e), e))
        }
    }

    suspend fun logout(): Result<Unit> {
        val uid = currentUidProvider()
        if (uid != null && fcmTokenManager != null) {
            try {
                withTimeoutOrNull(2500L) {
                    fcmTokenManager.deleteTokenOnLogout(uid)
                }
            } catch (e: CancellationException) {
                fcmTokenManager.onSignOutFailed(uid)
                throw e
            } catch (e: Exception) {
                logW("AuthRepository", "Failed to delete FCM token on logout: ${e.message}")
            }
        }

        return try {
            signOutAction()
            if (uid != null) {
                fcmTokenManager?.onSignOutCompleted(uid)
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            if (uid != null) {
                fcmTokenManager?.onSignOutFailed(uid)
            }
            throw e
        } catch (e: Exception) {
            if (uid != null) {
                fcmTokenManager?.onSignOutFailed(uid)
            }
            Result.failure(Exception(getReadableErrorMessage(e), e))
        }
    }

    suspend fun sendPasswordReset(email: String): Result<Unit> {
        val trimmedEmail = email.trim()

        if (trimmedEmail.isEmpty()) {
            return Result.failure(IllegalArgumentException("Email address cannot be empty."))
        }

        return try {
            authInstance.sendPasswordResetEmail(trimmedEmail).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("AuthRepository", "Firebase signInWithCredential failed: [${(e as? FirebaseAuthException)?.errorCode ?: e::class.java.simpleName}] ${e.message}", e)
            Result.failure(Exception(getReadableErrorMessage(e), e))
        }
    }

    private fun logW(tag: String, msg: String) {
        try {
            Log.w(tag, msg)
        } catch (_: Throwable) {
            // In local JVM unit tests, android.util.Log is not mocked
        }
    }

    private fun getReadableErrorMessage(e: Throwable): String {
        return when (e) {
            is FirebaseAuthInvalidUserException -> {
                when (e.errorCode) {
                    "ERROR_USER_DISABLED" -> "This account has been disabled."
                    else -> "No account found with this email address."
                }
            }
            is FirebaseAuthInvalidCredentialsException -> {
                when (e.errorCode) {
                    "ERROR_INVALID_EMAIL" -> "Please enter a valid email address."
                    "ERROR_WRONG_PASSWORD" -> "Incorrect password. Please try again."
                    else -> "Invalid or expired credentials. Please try signing in again."
                }
            }
            is FirebaseAuthUserCollisionException -> {
                "An account with this email address already exists with a different sign-in method."
            }
            is FirebaseAuthWeakPasswordException -> {
                "The password is too weak. Please use at least 6 characters."
            }
            is FirebaseNetworkException -> {
                "Network connection error. Please check your internet connection."
            }
            is FirebaseAuthException -> {
                when (e.errorCode) {
                    "ERROR_OPERATION_NOT_ALLOWED" -> "This sign-in method is currently disabled."
                    "ERROR_TOO_MANY_REQUESTS" -> "Too many unsuccessful attempts. Please try again later."
                    else -> "Authentication failed. Please check your credentials and try again."
                }
            }
            is IllegalArgumentException -> e.message ?: "Invalid input provided."
            else -> {
                if (e.message?.contains("cancel", ignoreCase = true) == true) {
                    "Sign-in was cancelled."
                } else {
                    "An unexpected error occurred. Please try again."
                }
            }
        }
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
        addOnCompleteListener { task ->
            if (continuation.isActive) {
                if (task.isSuccessful) {
                    @Suppress("UNCHECKED_CAST")
                    continuation.resume(task.result as T)
                } else {
                    continuation.resumeWithException(task.exception ?: Exception("Task failed with unknown error."))
                }
            }
        }
    }
}
