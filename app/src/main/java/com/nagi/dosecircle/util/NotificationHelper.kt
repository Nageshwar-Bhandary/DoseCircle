package com.nagi.dosecircle.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.nagi.dosecircle.MainActivity
import com.nagi.dosecircle.data.entity.Medication

object NotificationHelper {

    const val CHANNEL_REFILL_ALERTS = "refill_alerts"

    fun createNotificationChannels(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Dose reminders channel
            val doseChannel = NotificationChannel(
                Constants.CHANNEL_DOSE_REMINDERS,
                "Dose Reminders",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Notifications and reminders for scheduled medication doses"
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(doseChannel)

            // Refill alerts channel
            val refillChannel = NotificationChannel(
                CHANNEL_REFILL_ALERTS,
                "Refill Alerts",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Low stock and refill alerts for medications"
            }
            notificationManager.createNotificationChannel(refillChannel)
        }
    }

    fun showLowStockAlert(context: Context, medication: Medication) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            medication.id.toInt(),
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val contentText = if (medication.stockCount == 0) {
            "${medication.name} is out of stock"
        } else {
            "Low stock: ${medication.name}, ${medication.stockCount} left"
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_REFILL_ALERTS)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Refill Alert")
            .setContentText(contentText)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        // Use a unique notification id per medication (offset to avoid clash with dose events)
        notificationManager.notify(("refill_${medication.id}").hashCode(), notification)
    }
}
