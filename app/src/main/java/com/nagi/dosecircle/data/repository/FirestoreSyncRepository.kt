package com.nagi.dosecircle.data.repository

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

/**
 * Uploads medication data (and its schedule/times) to Firestore under
 * users/{uid}/medications/{cloudId}.
 *
 * Every public function launches work on an application-level SupervisorJob scope
 * so it is never cancelled when the calling ViewModel is cleared, and never
 * crashes the app when the user is offline or logged out.
 */
class FirestoreSyncRepository(
    private val database: AppDatabase,
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
) {
    // Application-level scope: survives ViewModel lifecycle
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val medicationDao = database.medicationDao()
    private val scheduleDao = database.scheduleDao()
    private val scheduleTimeDao = database.scheduleTimeDao()

    /**
     * Fire-and-forget upload of a medication to Firestore.
     * 1. If the medication has no cloudId yet, generate a UUID and persist it locally.
     * 2. Collect the medication's schedule and times.
     * 3. Write everything to users/{uid}/medications/{cloudId}.
     *
     * Does nothing if the user is logged out or the medication doesn't exist.
     * Silently swallows network/Firestore errors (offline-safe).
     */
    fun uploadMedication(medicationId: Long) {
        scope.launch {
            try {
                val uid = auth.currentUser?.uid ?: return@launch
                var medication = medicationDao.getMedicationById(medicationId) ?: return@launch

                // Ensure the medication has a stable cloud ID
                val cloudId = if (medication.cloudId != null) {
                    medication.cloudId
                } else {
                    val newId = UUID.randomUUID().toString()
                    medication = medication.copy(cloudId = newId)
                    medicationDao.updateMedication(medication)
                    newId
                }

                // Gather schedule + times
                val schedules = scheduleDao.getSchedulesDirect(medicationId)
                val primarySchedule = schedules.firstOrNull()
                val times = if (primarySchedule != null) {
                    scheduleTimeDao.getTimesForScheduleDirect(primarySchedule.id)
                } else {
                    emptyList()
                }

                val timesData = times.map { t ->
                    mapOf("hour" to t.hour, "minute" to t.minute)
                }

                val doc = buildMap<String, Any?> {
                    put("name", medication.name)
                    put("dosage", medication.dosage)
                    put("instructions", medication.instructions)
                    put("stockCount", medication.stockCount)
                    put("lowStockThreshold", medication.lowStockThreshold)
                    put("updatedAt", com.google.firebase.Timestamp.now())
                    if (primarySchedule != null) {
                        put("daysOfWeek", primarySchedule.daysOfWeek)
                        put("startDate", primarySchedule.startDate)
                        put("endDate", primarySchedule.endDate)
                        put("isActive", primarySchedule.isActive)
                    }
                    put("times", timesData)
                }

                firestore
                    .collection("users")
                    .document(uid)
                    .collection("medications")
                    .document(cloudId)
                    .set(doc, SetOptions.merge())
                    .await()
            } catch (_: Exception) {
                // Offline or any Firestore error — silently ignore
            }
        }
    }

    /**
     * Fire-and-forget delete of a medication document from Firestore by its cloudId.
     * Does nothing if the user is logged out or cloudId is null.
     * Silently swallows network/Firestore errors.
     */
    fun deleteMedication(cloudId: String?) {
        if (cloudId == null) return
        scope.launch {
            try {
                val uid = auth.currentUser?.uid ?: return@launch
                firestore
                    .collection("users")
                    .document(uid)
                    .collection("medications")
                    .document(cloudId)
                    .delete()
                    .await()
            } catch (_: Exception) {
                // Offline or any Firestore error — silently ignore
            }
        }
    }
}

