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
            val stableNotificationId = remoteMessage.data["stableNotificationId"]
                ?: remoteMessage.data["notificationId"]

            Log.i(TAG, "Device received FCM message: messageId=${remoteMessage.messageId}, stableNotificationId=$stableNotificationId")

            // Persistent duplicate suppression: check if message was already successfully displayed
            if (!stableNotificationId.isNullOrBlank() && isAlreadyDelivered(this, stableNotificationId)) {
                Log.i(TAG, "Duplicate FCM message suppressed for stableNotificationId: $stableNotificationId")
                return
            }

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

            // Post notification first; persist deduplication record only after successful posting
            val posted = showNotification(title, displayBody, stableNotificationId ?: remoteMessage.messageId)
            if (posted && !stableNotificationId.isNullOrBlank()) {
                recordDelivered(this, stableNotificationId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing incoming FCM message: ${e.message}", e)
        }
    }

    private fun showNotification(title: String, body: String, identifier: String?): Boolean {
        // Ensure notification channel exists
        NotificationHelper.createNotificationChannels(this)

        // Android 13+ (API 33) runtime notification permission check
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val permissionStatus = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            )
            if (permissionStatus != PackageManager.PERMISSION_GRANTED) {
                Log.w(TAG, "POST_NOTIFICATIONS permission not granted. Skipping notification display.")
                return false
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
            return false
        }

        val notificationId = identifier?.hashCode() ?: ((System.currentTimeMillis() % Int.MAX_VALUE).toInt())
        return try {
            notificationManager.notify(notificationId, notification)
            Log.i(TAG, "Notification displayed on channel ${NotificationHelper.CHANNEL_CAREGIVER_ALERTS} with notificationId=$notificationId")
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "SecurityException while notifying: ${e.message}")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to display notification: ${e.message}", e)
            false
        }
    }

    companion object {
        private const val TAG = "DoseCircleMessaging"
        private const val PREFS_DELIVERIES = "caregiver_delivered_notifications"
        private const val DEDUPLICATION_WINDOW_MS = 24L * 60L * 60L * 1000L // 24 hours
        private const val CLEANUP_CUTOFF_MS = 48L * 60L * 60L * 1000L // 48 hours

        @Synchronized
        internal fun isAlreadyDelivered(context: Context, stableId: String): Boolean {
            val prefs = context.getSharedPreferences(PREFS_DELIVERIES, Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            val lastReceived = prefs.getLong(stableId, 0L)
            return lastReceived > 0L && (now - lastReceived) < DEDUPLICATION_WINDOW_MS
        }

        @Synchronized
        internal fun recordDelivered(context: Context, stableId: String) {
            val prefs = context.getSharedPreferences(PREFS_DELIVERIES, Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            val editor = prefs.edit()
            editor.putLong(stableId, now)

            val allEntries = prefs.all
            if (allEntries.size > 50) {
                val cutoff = now - CLEANUP_CUTOFF_MS
                for ((key, value) in allEntries) {
                    if (value is Long && value < cutoff) {
                        editor.remove(key)
                    }
                }
            }
            editor.apply()
        }

        @Synchronized
        internal fun isDuplicateNotification(context: Context, stableId: String): Boolean {
            if (isAlreadyDelivered(context, stableId)) {
                return true
            }
            recordDelivered(context, stableId)
            return false
        }
    }
}

