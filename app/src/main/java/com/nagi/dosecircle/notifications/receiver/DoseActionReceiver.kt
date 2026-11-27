package com.nagi.dosecircle.notifications.receiver

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.WorkManager
import com.nagi.dosecircle.DoseCircleApp
import com.nagi.dosecircle.data.entity.DoseStatus
import com.nagi.dosecircle.notifications.worker.FollowUpReminderWorker
import com.nagi.dosecircle.util.Constants
import com.nagi.dosecircle.util.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class DoseActionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "DoseActionReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Constants.ACTION_TAKEN && action != Constants.ACTION_SKIP) return

        val doseEventId = intent.getLongExtra("doseEventId", -1L)
        if (doseEventId == -1L) {
            Log.w(TAG, "Invalid doseEventId in action: $action")
            return
        }

        val pendingResult = goAsync()
        val app = context.applicationContext as DoseCircleApp

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val doseEvent = app.doseEventRepository.getDoseEventById(doseEventId)
                if (doseEvent != null && doseEvent.status == DoseStatus.PENDING) {
                    val newStatus = if (action == Constants.ACTION_TAKEN) {
                        DoseStatus.TAKEN
                    } else {
                        DoseStatus.SKIPPED
                    }
                    val nowMillis = System.currentTimeMillis()
                    val rowsUpdated = app.doseEventRepository.updateDoseStatus(doseEventId, newStatus, nowMillis)

                    if (rowsUpdated == 1) {
                        Log.d(TAG, "DoseEvent $doseEventId updated to $newStatus at $nowMillis (rowsUpdated=1)")

                        // Cancel the corresponding scheduled follow-up work
                        WorkManager.getInstance(context)
                            .cancelAllWorkByTag(FollowUpReminderWorker.getWorkTag(doseEventId))

                        app.doseEventSyncRepository.uploadDoseEvent(doseEventId)

                        // Deduct stock only when TAKEN
                        if (newStatus == DoseStatus.TAKEN) {
                            val medId = doseEvent.medicationId
                            val stockBefore = app.medicationRepository.getMedicationById(medId)?.stockCount ?: 0
                            app.medicationRepository.decreaseStock(medId)
                            val medAfter = app.medicationRepository.getMedicationById(medId)
                            if (medAfter != null) {
                                app.firestoreSyncRepository.uploadMedication(medId)

                                val threshold = medAfter.lowStockThreshold
                                if (threshold > 0 &&
                                    (medAfter.stockCount == 0 ||
                                            (stockBefore > threshold && medAfter.stockCount <= threshold))
                                ) {
                                    NotificationHelper.showLowStockAlert(context, medAfter)
                                }
                            }
                        }
                    } else {
                        Log.d(TAG, "DoseEvent $doseEventId was not updated (rowsUpdated=$rowsUpdated), skipping stock deduction and sync")
                    }
                } else {
                    Log.d(TAG, "DoseEvent $doseEventId is not PENDING (current=${doseEvent?.status}), ignoring action $action")
                }

                // Cancel the dose notification
                val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                notificationManager.cancel(doseEventId.toInt())
            } catch (e: Exception) {
                Log.e(TAG, "Error handling action $action for doseEvent $doseEventId", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
