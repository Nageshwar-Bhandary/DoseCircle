package com.nagi.dosecircle.data.repository

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.nagi.dosecircle.data.database.AppDatabase
import com.nagi.dosecircle.data.entity.DoseStatus
import com.nagi.dosecircle.notifications.network.CaregiverNotificationClient
import com.nagi.dosecircle.notifications.worker.MissedDoseNotificationRetryWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.UUID

class DoseEventSyncRepository(
    private val database: AppDatabase,
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val caregiverNotificationClient: CaregiverNotificationClient = CaregiverNotificationClient(auth),
    private val context: Context? = null
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val doseEventDao = database.doseEventDao()
    private val medicationDao = database.medicationDao()

    fun uploadDoseEvent(doseEventId: Long) {
        scope.launch {
            try {
                val uid = auth.currentUser?.uid
                if (uid == null) {
                    Log.w("DoseEventSync", "User not logged in. Upload skipped.")
                    return@launch
                }

                var doseEvent = doseEventDao.getDoseEventById(doseEventId)

                if (doseEvent == null) {
                    Log.w("DoseEventSync", "Dose event not found: $doseEventId")
                    return@launch
                }

                val cloudId = if (!doseEvent.cloudId.isNullOrEmpty()) {
                    doseEvent.cloudId
                } else {
                    val newId = UUID.randomUUID().toString()
                    doseEventDao.updateCloudId(doseEventId, newId)
                    doseEvent = doseEvent.copy(cloudId = newId)
                    newId
                }

                val medication =
                    medicationDao.getMedicationById(doseEvent.medicationId)

                val data = hashMapOf<String, Any?>(
                    "medicationName" to (medication?.name ?: ""),
                    "dosage" to (medication?.dosage ?: ""),
                    "medicationCloudId" to (medication?.cloudId ?: ""),
                    "scheduledTime" to doseEvent.scheduledTime,
                    "status" to doseEvent.status.name,
                    "actionTime" to doseEvent.actionTime
                )

                firestore
                    .collection("users")
                    .document(uid)
                    .collection("doseEvents")
                    .document(cloudId!!)
                    .set(data, SetOptions.merge())
                    .await()

                // Trigger caregiver notification only when a dose is MISSED.
                // TAKEN, SKIPPED, or PENDING doses do not notify caregivers.
                if (doseEvent.status == DoseStatus.MISSED) {
                    try {
                        val result = caregiverNotificationClient.notifyMissedDose(cloudId)
                        if (result.isFailure) {
                            val error = result.exceptionOrNull()
                            Log.w("DoseEventSync", "Initial caregiver notification dispatch failed for $cloudId: ${error?.message}")
                            if (context != null && CaregiverNotificationClient.isRetryable(error)) {
                                MissedDoseNotificationRetryWorker.enqueue(context, cloudId, doseEventId)
                            }
                        }
                    } catch (e: Exception) {
                        Log.w("DoseEventSync", "Unexpected error in caregiver notification dispatch for $cloudId: ${e.message}")
                        if (context != null && CaregiverNotificationClient.isRetryable(e)) {
                            MissedDoseNotificationRetryWorker.enqueue(context, cloudId, doseEventId)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("DoseEventSync", "Upload failed for doseEventId=$doseEventId", e)
            }
        }
    }
}
