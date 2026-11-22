package com.nagi.dosecircle.data.repository

import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseNetworkException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.UserProfileChangeRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class AuthRepository(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
) {

    val currentUser: FirebaseUser?
        get() = auth.currentUser

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
            val authResult = auth.createUserWithEmailAndPassword(trimmedEmail, password).await()
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
            Result.failure(Exception(getReadableErrorMessage(e), e))
        }
    }

    suspend fun login(email: String, password: String): Result<FirebaseUser> {
        val trimmedEmail = email.trim()

        if (trimmedEmail.isEmpty() || password.isEmpty()) {
            return Result.failure(IllegalArgumentException("Email and password cannot be empty."))
        }

        return try {
            val authResult = auth.signInWithEmailAndPassword(trimmedEmail, password).await()
            val user = authResult.user ?: throw IllegalStateException("Login succeeded but user details are unavailable.")
            Result.success(user)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(Exception(getReadableErrorMessage(e), e))
        }
    }

    fun logout(): Result<Unit> {
        return try {
            auth.signOut()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(Exception(getReadableErrorMessage(e), e))
        }
    }

    suspend fun sendPasswordReset(email: String): Result<Unit> {
        val trimmedEmail = email.trim()

        if (trimmedEmail.isEmpty()) {
            return Result.failure(IllegalArgumentException("Email address cannot be empty."))
        }

        return try {
            auth.sendPasswordResetEmail(trimmedEmail).await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(Exception(getReadableErrorMessage(e), e))
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
                    else -> "Invalid email or password. Please verify your credentials."
                }
            }
            is FirebaseAuthUserCollisionException -> {
                "An account with this email address already exists."
            }
            is FirebaseAuthWeakPasswordException -> {
                "The password is too weak. Please use at least 6 characters."
            }
            is FirebaseNetworkException -> {
                "Network connection error. Please check your internet connection."
            }
            is FirebaseAuthException -> {
                when (e.errorCode) {
                    "ERROR_OPERATION_NOT_ALLOWED" -> "Email and password authentication is currently disabled."
                    "ERROR_TOO_MANY_REQUESTS" -> "Too many unsuccessful attempts. Please try again later."
                    else -> "Authentication failed. Please check your credentials and try again."
                }
            }
            is IllegalArgumentException -> e.message ?: "Invalid input provided."
            else -> "An unexpected error occurred. Please try again."
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
