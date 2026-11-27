package com.nagi.dosecircle.notifications.service

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.nagi.dosecircle.DoseCircleApp
import com.nagi.dosecircle.MainActivity
import com.nagi.dosecircle.notifications.fcm.FcmTokenManager
import com.nagi.dosecircle.util.NotificationHelper

class DoseCircleMessagingService : FirebaseMessagingService() {

    internal var tokenManagerProvider: () -> FcmTokenManager? = {
        (applicationContext as? DoseCircleApp)?.fcmTokenManager
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)

        val manager = tokenManagerProvider()
        if (manager != null) {
            manager.onNewToken(token)
        } else {
            Log.w(TAG, "FcmTokenManager unavailable onNewToken")
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        try {
            // Extract title and body from FCM data payload, falling back to notification payload
            val title = remoteMessage.data["title"]
                ?: remoteMessage.notification?.title
                ?: "DoseCircle Alert"

            val body = remoteMessage.data["body"]
                ?: remoteMessage.data["message"]
                ?: remoteMessage.notification?.body

            // If body is missing/blank and title is generic/empty, payload is incomplete
            if (body.isNullOrBlank() && (title.isBlank() || title == "DoseCircle Alert")) {
                Log.d(TAG, "FCM message received with incomplete payload. Skipping notification.")
                return
            }

            val displayBody = body ?: ""

            showNotification(title, displayBody, remoteMessage.messageId)
        } catch (e: Exception) {
            Log.e(TAG, "Error processing incoming FCM message: ${e.message}", e)
        }
    }

    private fun showNotification(title: String, body: String, messageId: String?) {
        // Ensure notification channel exists
        NotificationHelper.createNotificationChannels(this)

        // Android 13+ (API 33) runtime notification permission check
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permissionStatus = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            )
            if (permissionStatus != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "POST_NOTIFICATIONS permission not granted. Skipping notification.")
                return
            }
        }

        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, NotificationHelper.CHANNEL_CAREGIVER_ALERTS)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (notificationManager == null) {
            Log.w(TAG, "NotificationManager unavailable")
            return
        }

        val notificationId = messageId?.hashCode() ?: ((System.currentTimeMillis() % Int.MAX_VALUE).toInt())
        try {
            notificationManager.notify(notificationId, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException while notifying: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to display notification: ${e.message}", e)
        }
    }

    companion object {
        private const val TAG = "DoseCircleMessaging"
    }
}

