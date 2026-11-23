package com.nagi.dosecircle.data.repository

import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.nagi.dosecircle.data.database.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.UUID

class DoseEventSyncRepository(
    private val database: AppDatabase,
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
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

            } catch (e: Exception) {
                Log.e("DoseEventSync", "Upload failed for doseEventId=$doseEventId", e)
            }
        }
    }
}