package com.nagi.dosecircle.notifications.receiver

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.nagi.dosecircle.DoseCircleApp
import com.nagi.dosecircle.data.entity.DoseStatus
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
                    val medId = doseEvent.medicationId
                    val currentMed = app.medicationRepository.getMedicationById(medId)

                    // Safety: If action is TAKEN but medication is out of stock (stockCount <= 0),
                    // do not mark TAKEN and do not deduct stock.
                    if (action == Constants.ACTION_TAKEN && (currentMed == null || currentMed.stockCount <= 0)) {
                        Log.w(TAG, "Cannot take dose for medId=$medId because stock is 0 or null. Ignoring Taken action.")
                        // Cancel the notification so user is not stuck
                        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                        notificationManager.cancel(doseEventId.toInt())
                        return@launch
                    }

                    val newStatus = if (action == Constants.ACTION_TAKEN) {
                        DoseStatus.TAKEN
                    } else {
                        DoseStatus.SKIPPED
                    }
                    val nowMillis = System.currentTimeMillis()
                    app.doseEventRepository.updateDoseStatus(doseEventId, newStatus, nowMillis)
                    app.doseEventSyncRepository.uploadDoseEvent(doseEventId)
                    Log.d(TAG, "DoseEvent $doseEventId updated to $newStatus at $nowMillis")

                    // Deduct stock only when TAKEN
                    if (newStatus == DoseStatus.TAKEN) {
                        val stockBefore = currentMed?.stockCount ?: 0
                        app.medicationRepository.decreaseStock(medId)
                        val medAfter = app.medicationRepository.getMedicationById(medId)
                        if (medAfter != null) {
                            app.firestoreSyncRepository.uploadMedication(medId)

                            // If stock reached 0, cancel all scheduled alarms for this medication
                            if (medAfter.stockCount == 0) {
                                app.alarmScheduler.cancelAlarmsForMedication(medId)
                            }

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
