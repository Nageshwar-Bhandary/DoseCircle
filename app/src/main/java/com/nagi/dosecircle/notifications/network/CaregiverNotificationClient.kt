package com.nagi.dosecircle.notifications.network

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.URL
import javax.net.ssl.HttpsURLConnection

open class NotificationNetworkException(message: String) : Exception(message)
class RetryableNotificationException(val statusCode: Int, message: String) : NotificationNetworkException("Retryable error ($statusCode): $message")
class NonRetryableNotificationException(val statusCode: Int, message: String) : NotificationNetworkException("Non-retryable error ($statusCode): $message")

/**
 * Client for triggering the DoseCircle caregiver notification Worker.
 *
 * Dispatches an authenticated request to the Cloudflare Worker when a dose is missed.
 * Catches all network/server failures safely without blocking or disrupting local dose operations.
 */
class CaregiverNotificationClient(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val workerUrl: String = DEFAULT_WORKER_URL
) {
    companion object {
        private const val TAG = "CaregiverNotifyClient"
        const val DEFAULT_WORKER_URL = "https://dosecircle-notify.dosecircle-notify.workers.dev/notify"

        fun isRetryable(throwable: Throwable?): Boolean {
            return when (throwable) {
                is RetryableNotificationException -> true
                is NonRetryableNotificationException -> false
                is IOException -> true
                is IllegalArgumentException -> false
                else -> false
            }
        }
    }

    suspend fun notifyMissedDose(doseCloudId: String): Result<String> = withContext(Dispatchers.IO) {
        if (doseCloudId.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("doseCloudId cannot be blank"))
        }

        val user = auth.currentUser
            ?: return@withContext Result.failure(RetryableNotificationException(0, "No authenticated user currently"))

        val idToken = try {
            user.getIdToken(false).await()?.token
        } catch (e: Exception) {
            Log.w(TAG, "Failed to retrieve ID token: ${e.message}")
            return@withContext Result.failure(RetryableNotificationException(0, "Failed to retrieve token: ${e.message}"))
        }

        if (idToken.isNullOrBlank()) {
            return@withContext Result.failure(RetryableNotificationException(0, "Retrieved ID token is null or blank"))
        }

        var connection: HttpsURLConnection? = null
        try {
            val url = URL(workerUrl)
            connection = (url.openConnection() as HttpsURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 10000
                readTimeout = 10000
                setRequestProperty("Authorization", "Bearer $idToken")
                setRequestProperty("Content-Type", "application/json")
            }

            val body = "{\"doseCloudId\":\"$doseCloudId\"}"
            OutputStreamWriter(connection.outputStream, "UTF-8").use { writer ->
                writer.write(body)
                writer.flush()
            }

            val statusCode = connection.responseCode
            val stream = if (statusCode in 200..299) connection.inputStream else connection.errorStream
            val responseBody = stream?.let {
                BufferedReader(InputStreamReader(it, "UTF-8")).use { reader -> reader.readText() }
            } ?: ""

            if (statusCode in 200..299) {
                Log.i(TAG, "Notification accepted by Worker: HTTP $statusCode - $responseBody")
                Result.success(responseBody)
            } else {
                Log.w(TAG, "Notification request rejected by Worker: HTTP $statusCode - $responseBody")
                val isRetryableHttp = statusCode in 500..599 || statusCode == 429 || statusCode == 408
                if (isRetryableHttp) {
                    Result.failure(RetryableNotificationException(statusCode, responseBody))
                } else {
                    Result.failure(NonRetryableNotificationException(statusCode, responseBody))
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "Network error calling Worker notify: ${e.message}")
            Result.failure(e)
        } catch (e: Exception) {
            Log.w(TAG, "Unexpected error calling Worker notify: ${e.message}")
            Result.failure(e)
        } finally {
            connection?.disconnect()
        }
    }
}
