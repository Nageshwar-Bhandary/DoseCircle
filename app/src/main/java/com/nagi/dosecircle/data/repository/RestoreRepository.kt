package com.nagi.dosecircle.data.repository

import android.util.Log
import androidx.room.withTransaction
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.nagi.dosecircle.data.database.AppDatabase
import com.nagi.dosecircle.data.entity.DoseEvent
import com.nagi.dosecircle.data.entity.DoseStatus
import com.nagi.dosecircle.data.entity.Medication
import com.nagi.dosecircle.data.entity.Schedule
import com.nagi.dosecircle.data.entity.ScheduleTime
import com.nagi.dosecircle.notifications.alarm.AlarmScheduler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

data class ParsedMedication(
    val medication: Medication,
    val schedule: Schedule,
    val times: List<ScheduleTime>
)

data class ParsedDoseEvent(
    val cloudId: String,
    val medicationCloudId: String,
    val scheduledTime: Long,
    val status: DoseStatus,
    val actionTime: Long?
)

/**
 * Downloads and restores medications and dose events from Firestore under users/{uid}.
 * Preserves cloudIds, resolves relationships, avoids duplicate insertions,
 * avoids triggering sync uploads, and stops if the signed-in account changes.
 */
class RestoreRepository(
    private val database: AppDatabase,
    private val medicationRepository: MedicationRepository,
    private val doseEventRepository: DoseEventRepository,
    private val alarmScheduler: AlarmScheduler,
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore? = null,
    private val currentUidProvider: () -> String? = { auth.currentUser?.uid }
) {

    companion object {
        private const val TAG = "RestoreRepository"
        private const val THIRTY_DAYS_DAYS = 30L

        private fun logW(tag: String, msg: String) {
            try {
                Log.w(tag, msg)
            } catch (_: RuntimeException) {
            }
        }

        private fun logD(tag: String, msg: String) {
            try {
                Log.d(tag, msg)
            } catch (_: RuntimeException) {
            }
        }

        /**
         * Validates and parses a Firestore number as a whole-number Long.
         * Preserves exact integral values (Byte, Short, Int, Long, BigInteger).
         * Rejects fractional values, non-finite values (NaN, +/-Infinity),
         * non-numeric types, and values strictly outside [Long.MIN_VALUE..Long.MAX_VALUE].
         */
        internal fun parseWholeLong(value: Any?): Long? {
            if (value == null || value !is Number) return null
            return when (value) {
                is Long -> value
                is Int -> value.toLong()
                is Short -> value.toLong()
                is Byte -> value.toLong()
                is java.math.BigInteger -> {
                    val minLong = java.math.BigInteger.valueOf(Long.MIN_VALUE)
                    val maxLong = java.math.BigInteger.valueOf(Long.MAX_VALUE)
                    if (value in minLong..maxLong) value.toLong() else null
                }
                is java.math.BigDecimal -> {
                    try {
                        val exact = value.toBigIntegerExact()
                        val minLong = java.math.BigInteger.valueOf(Long.MIN_VALUE)
                        val maxLong = java.math.BigInteger.valueOf(Long.MAX_VALUE)
                        if (exact in minLong..maxLong) exact.toLong() else null
                    } catch (_: ArithmeticException) {
                        null
                    }
                }
                is Double, is Float -> {
                    val d = value.toDouble()
                    if (!d.isFinite() || d != Math.floor(d)) return null
                    // Long.MIN_VALUE is -2^63, exactly -9223372036854775808.0 in Double.
                    // Long.MAX_VALUE is 2^63 - 1. Any Double >= 2^63 is strictly greater than Long.MAX_VALUE.
                    if (d < -9223372036854775808.0 || d >= 9223372036854775808.0) return null
                    val l = d.toLong()
                    if (l.toDouble() != d) return null
                    l
                }
                else -> {
                    val d = value.toDouble()
                    if (!d.isFinite() || d != Math.floor(d)) return null
                    if (d < -9223372036854775808.0 || d >= 9223372036854775808.0) return null
                    val l = d.toLong()
                    if (l.toDouble() != d) return null
                    l
                }
            }
        }

        /**
         * Validates and parses a Firestore number as a non-negative whole-number Int.
         * Rejects negative values, fractional values, non-finite values (NaN, +/-Infinity),
         * non-numeric types, and values exceeding Int.MAX_VALUE.
         */
        internal fun parseNonNegativeInt(value: Any?): Int? {
            return parseBoundedInt(value, 0, Int.MAX_VALUE)
        }

        /**
         * Validates and parses a Firestore number as a whole-number Int within [min..max].
         */
        internal fun parseBoundedInt(value: Any?, min: Int, max: Int): Int? {
            val longVal = parseWholeLong(value) ?: return null
            if (longVal < min || longVal > max) return null
            return longVal.toInt()
        }

        /**
         * Validates daysOfWeek as a comma-separated list of integers in range 1..7 (Monday=1..Sunday=7).
         * Rejects empty tokens, duplicate days, invalid ranges, and non-numeric characters.
         * Returns the validated trimmed string or null if invalid.
         */
        internal fun validateDaysOfWeek(daysOfWeek: String?): String? {
            if (daysOfWeek.isNullOrBlank()) return null
            val tokens = daysOfWeek.split(",")
            if (tokens.isEmpty()) return null
            val seenDays = mutableSetOf<Int>()
            for (token in tokens) {
                val trimmed = token.trim()
                if (trimmed.isEmpty()) return null
                val day = trimmed.toIntOrNull() ?: return null
                if (day !in 1..7) return null
                if (!seenDays.add(day)) {
                    // Duplicate day
                    return null
                }
            }
            return seenDays.sorted().joinToString(",")
        }

        /**
         * Validates and parses raw Firestore medication document fields into domain entities.
         * Returns null if any required field is missing or invalid.
         */
        internal fun parseMedication(
            cloudId: String,
            data: Map<String, Any?>,
            personId: Long
        ): ParsedMedication? {
            val name = data["name"] as? String
            if (name.isNullOrBlank()) {
                logW(TAG, "Medication document has missing, empty, or blank name; skipping.")
                return null
            }

            val dosage = data["dosage"] as? String ?: ""
            val instructions = data["instructions"] as? String ?: ""

            // Validate stockCount: non-negative Int. If absent or null, default to 0.
            // If present and non-null, it must be a valid non-negative whole-number Int.
            val stockCount = if (data["stockCount"] == null) {
                0
            } else {
                parseNonNegativeInt(data["stockCount"]) ?: run {
                    logW(TAG, "Medication document has invalid stockCount; skipping.")
                    return null
                }
            }

            // Validate lowStockThreshold: non-negative Int. If absent or null, default to 0.
            // If present and non-null, it must be a valid non-negative whole-number Int.
            val lowStockThreshold = if (data["lowStockThreshold"] == null) {
                0
            } else {
                parseNonNegativeInt(data["lowStockThreshold"]) ?: run {
                    logW(TAG, "Medication document has invalid lowStockThreshold; skipping.")
                    return null
                }
            }

            val rawDaysOfWeek = data["daysOfWeek"] as? String
            val daysOfWeek = validateDaysOfWeek(rawDaysOfWeek)
            if (daysOfWeek == null) {
                logW(TAG, "Medication document has invalid or missing daysOfWeek; skipping.")
                return null
            }

            val rawStartDate = data["startDate"]
            if (rawStartDate == null) {
                logW(TAG, "Medication document has missing startDate; skipping.")
                return null
            }
            val startDate = parseWholeLong(rawStartDate)
            if (startDate == null) {
                logW(TAG, "Medication document has invalid startDate; skipping.")
                return null
            }

            val isActive = data["isActive"] as? Boolean
            if (isActive == null) {
                logW(TAG, "Medication document has missing isActive; skipping.")
                return null
            }

            // Nullable endDate: absent or null accepted; malformed non-null rejected.
            val rawEndDate = data["endDate"]
            val endDate: Long? = if (rawEndDate == null) {
                null
            } else {
                parseWholeLong(rawEndDate) ?: run {
                    logW(TAG, "Medication document has invalid non-null endDate; skipping.")
                    return null
                }
            }

            if (endDate != null && endDate < startDate) {
                logW(TAG, "Medication document has endDate earlier than startDate; skipping.")
                return null
            }

            val rawTimes = data["times"]
            if (rawTimes !is List<*>) {
                logW(TAG, "Medication document missing or invalid times list; skipping.")
                return null
            }

            if (rawTimes.isEmpty()) {
                logW(TAG, "Medication document has empty times list; skipping.")
                return null
            }

            val timesList = mutableListOf<ScheduleTime>()

            for (item in rawTimes) {
                if (item !is Map<*, *>) {
                    logW(TAG, "Medication document has non-map time entry; dropping item.")
                    continue
                }

                val hour = parseBoundedInt(item["hour"], 0, 23)
                val minute = parseBoundedInt(item["minute"], 0, 59)

                if (hour == null || minute == null) {
                    logW(TAG, "Medication document time entry has invalid hour or minute; dropping item.")
                    continue
                }

                timesList.add(
                    ScheduleTime(
                        scheduleId = 0L,
                        hour = hour,
                        minute = minute
                    )
                )
            }

            if (timesList.isEmpty()) {
                logW(TAG, "Medication document has no valid times remaining; skipping.")
                return null
            }

            val medication = Medication(
                personId = personId,
                name = name,
                dosage = dosage,
                instructions = instructions,
                photoUri = null,
                stockCount = stockCount,
                lowStockThreshold = lowStockThreshold,
                cloudId = cloudId
            )

            val schedule = Schedule(
                personId = personId,
                medicationId = 0L,
                startDate = startDate,
                endDate = endDate,
                daysOfWeek = daysOfWeek,
                isActive = isActive
            )

            return ParsedMedication(
                medication = medication,
                schedule = schedule,
                times = timesList
            )
        }

        /**
         * Validates and parses raw Firestore dose event document fields into domain data.
         * Returns null if any required field is missing or invalid.
         */
        internal fun parseDoseEvent(
            cloudId: String,
            data: Map<String, Any?>
        ): ParsedDoseEvent? {
            val medicationCloudId =
                data["medicationCloudId"] as? String ?: ""

            if (medicationCloudId.isBlank()) {
                logD(TAG, "Dose event document has empty medicationCloudId; skipping.")
                return null
            }

            val rawScheduledTime = data["scheduledTime"]
            if (rawScheduledTime == null) {
                logW(TAG, "Dose event document has missing scheduledTime; skipping.")
                return null
            }
            val scheduledTime = parseWholeLong(rawScheduledTime)
            if (scheduledTime == null || scheduledTime < 0L) {
                logW(TAG, "Dose event document has invalid scheduledTime; skipping.")
                return null
            }

            val statusString = data["status"] as? String
            if (statusString.isNullOrBlank()) {
                logW(TAG, "Dose event document has missing or empty status; skipping.")
                return null
            }

            val status = try {
                DoseStatus.valueOf(statusString)
            } catch (e: IllegalArgumentException) {
                logW(TAG, "Dose event document has invalid status; skipping.")
                return null
            }

            // Nullable actionTime: absent or null accepted; malformed non-null rejected.
            val rawActionTime = data["actionTime"]
            val actionTime: Long? = if (rawActionTime == null) {
                null
            } else {
                val parsed = parseWholeLong(rawActionTime)
                if (parsed == null || parsed < 0L) {
                    logW(TAG, "Dose event document has invalid non-null actionTime; skipping.")
                    return null
                }
                parsed
            }

            return ParsedDoseEvent(
                cloudId = cloudId,
                medicationCloudId = medicationCloudId,
                scheduledTime = scheduledTime,
                status = status,
                actionTime = actionTime
            )
        }
    }

    /**
     * Mutex ensuring only one restore write phase runs at a time.
     * Network requests are performed outside of this mutex and outside of Room transactions.
     */
    private val restoreWriteMutex = Mutex()

    private fun getCurrentUid(): String? = currentUidProvider()

    /**
     * Stops restoration if the currently authenticated user differs from
     * the expected user whose Firestore data is being restored, or if the coroutine was cancelled.
     */
    private fun ensureSameUser(expectedUid: String) {
        val currentUid = getCurrentUid()
        if (currentUid.isNullOrBlank() || currentUid != expectedUid) {
            throw CancellationException(
                "User changed or logged out during restore; stopping restore."
            )
        }
    }

    /**
     * Restores medications and recent (last 30 days) dose events for the specified expectedUid.
     * Safe to run repeatedly. Handles network/offline errors gracefully.
     *
     * @return true if both collections were successfully retrieved and processed.
     */
    suspend fun restoreForCurrentUser(expectedUid: String, personId: Long): Boolean {
        if (expectedUid.isBlank()) {
            logD(TAG, "Expected UID is blank; skipping restore.")
            return false
        }

        val currentUid = getCurrentUid()
        if (currentUid.isNullOrBlank() || currentUid != expectedUid) {
            logW(TAG, "Current authenticated user does not match expected user; skipping restore.")
            return false
        }

        return try {
            coroutineContext.ensureActive()
            ensureSameUser(expectedUid)
            logD(TAG, "Starting restore.")

            val medsSuccess = restoreMedications(expectedUid, personId)

            coroutineContext.ensureActive()
            ensureSameUser(expectedUid)
            val eventsSuccess = restoreDoseEvents(expectedUid, personId)

            coroutineContext.ensureActive()
            ensureSameUser(expectedUid)

            // If any document failed to parse or persist, return false
            if (!medsSuccess || !eventsSuccess) {
                logW(TAG, "Restore had partial document failures; returning false.")
                return false
            }

            // Protect alarm scheduling: recheck authenticated UID and cancellation
            coroutineContext.ensureActive()
            ensureSameUser(expectedUid)
            try {
                alarmScheduler.rescheduleAll()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logW(TAG, "Failed to reschedule alarms after restore.")
                return false
            }

            coroutineContext.ensureActive()
            ensureSameUser(expectedUid)

            logD(TAG, "Restore completed successfully.")
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logW(
                TAG,
                "Restore failed or incomplete (network or database error)."
            )
            false
        }
    }

    private suspend fun restoreMedications(expectedUid: String, personId: Long): Boolean {
        coroutineContext.ensureActive()
        ensureSameUser(expectedUid)

        val fs = firestore ?: FirebaseFirestore.getInstance()

        // Network request performed outside Mutex and outside Room transaction
        val medicationsSnapshot = fs
            .collection("users")
            .document(expectedUid)
            .collection("medications")
            .get()
            .await()

        coroutineContext.ensureActive()
        ensureSameUser(expectedUid)

        var allSucceeded = true

        // Mutex ensures only one restore write phase runs at a time
        restoreWriteMutex.withLock {
            for (doc in medicationsSnapshot.documents) {
                // Check cancellation between documents
                coroutineContext.ensureActive()
                ensureSameUser(expectedUid)

                val cloudId = doc.id
                if (cloudId.isBlank()) {
                    allSucceeded = false
                    continue
                }

                try {
                    val parsed = parseMedication(cloudId, doc.data ?: emptyMap(), personId)
                    if (parsed == null) {
                        allSucceeded = false
                        continue
                    }
                    val medication = parsed.medication
                    val schedule = parsed.schedule
                    val timesList = parsed.times

                    // Validate UID inside Room transaction immediately before database writes.
                    // Abort transaction without writing if UID no longer matches.
                    database.withTransaction {
                        val existingMedication =
                            medicationRepository.getMedicationByCloudId(personId, cloudId)
                        if (existingMedication != null) {
                            logD(
                                TAG,
                                "Medication document already exists locally; skipping."
                            )
                            return@withTransaction
                        }

                        if (getCurrentUid() != expectedUid) {
                            throw CancellationException("UID changed inside medication transaction; aborting write.")
                        }

                        // Local-only write; does not trigger Firestore uploads.
                        medicationRepository.saveMedicationWithSchedule(
                            medication,
                            schedule,
                            timesList
                        )

                        logD(TAG, "Restored medication record successfully.")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    allSucceeded = false
                    logW(
                        TAG,
                        "Failed to restore medication document; skipping."
                    )
                }
            }
        }
        return allSucceeded
    }

    private suspend fun restoreDoseEvents(expectedUid: String, personId: Long): Boolean {
        coroutineContext.ensureActive()
        ensureSameUser(expectedUid)

        val nowTime = System.currentTimeMillis()
        val cutoffTime =
            nowTime - TimeUnit.DAYS.toMillis(THIRTY_DAYS_DAYS)

        val fs = firestore ?: FirebaseFirestore.getInstance()

        // Network request performed outside Mutex and outside Room transaction
        val doseEventsSnapshot = fs
            .collection("users")
            .document(expectedUid)
            .collection("doseEvents")
            .whereGreaterThanOrEqualTo("scheduledTime", cutoffTime)
            .whereLessThanOrEqualTo("scheduledTime", nowTime)
            .get()
            .await()

        coroutineContext.ensureActive()
        ensureSameUser(expectedUid)

        var allSucceeded = true

        // Mutex ensures only one restore write phase runs at a time
        restoreWriteMutex.withLock {
            for (doc in doseEventsSnapshot.documents) {
                // Check cancellation between documents
                coroutineContext.ensureActive()
                ensureSameUser(expectedUid)

                val cloudId = doc.id
                if (cloudId.isBlank()) {
                    allSucceeded = false
                    continue
                }

                try {
                    val parsed = parseDoseEvent(cloudId, doc.data ?: emptyMap())
                    if (parsed == null) {
                        allSucceeded = false
                        continue
                    }

                    // Validate UID inside Room transaction immediately before database writes.
                    // Abort transaction without writing if UID no longer matches.
                    var docSucceeded = true
                    database.withTransaction {
                        val existingEvent =
                            doseEventRepository.getDoseEventByCloudId(personId, cloudId)
                        if (existingEvent != null) {
                            logD(
                                TAG,
                                "Dose event document already exists locally; skipping."
                            )
                            return@withTransaction
                        }

                        val localMedication =
                            medicationRepository.getMedicationByCloudId(
                                personId,
                                parsed.medicationCloudId
                            )
                        if (localMedication == null) {
                            logD(
                                TAG,
                                "Could not resolve local medication reference for dose event; skipping."
                            )
                            // A missing medication reference for a dose event should count as a skipped/failed document.
                            docSucceeded = false
                            return@withTransaction
                        }

                        if (getCurrentUid() != expectedUid) {
                            throw CancellationException("UID changed inside dose event transaction; aborting write.")
                        }

                        val doseEvent = DoseEvent(
                            personId = personId,
                            medicationId = localMedication.id,
                            scheduledTime = parsed.scheduledTime,
                            status = parsed.status,
                            actionTime = parsed.actionTime,
                            cloudId = cloudId
                        )

                        // Local-only insert; does not trigger Firestore uploads or notifications.
                        doseEventRepository.insertDoseEvent(doseEvent)

                        logD(
                            TAG,
                            "Restored dose event successfully."
                        )
                    }
                    if (!docSucceeded) {
                        allSucceeded = false
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    allSucceeded = false
                    logW(
                        TAG,
                        "Failed to restore dose event document; skipping."
                    )
                }
            }
        }
        return allSucceeded
    }
}
