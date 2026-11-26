package com.nagi.dosecircle.data.repository

import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.math.BigDecimal

sealed class PatientDataResult<out T> {
    data class Success<T>(val data: T) : PatientDataResult<T>()
    data class AccessDenied(val message: String = "You no longer have access.") : PatientDataResult<Nothing>()
}

data class PatientMedication(
    val id: String,
    val name: String,
    val dosage: String,
    val times: List<Pair<Int, Int>>,
    val stockCount: Int,
    val lowStockThreshold: Int
) {
    val isLowStock: Boolean
        get() = stockCount <= lowStockThreshold
}

data class PatientDoseEvent(
    val id: String,
    val medicationName: String,
    val dosage: String,
    val scheduledTime: Long,
    val status: String,
    val actionTime: Long?
)

class PatientViewRepository(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
) {
    companion object {
        private const val SEVEN_DAYS_MILLIS = 7L * 24L * 60L * 60L * 1000L
        private const val ACCESS_DENIED_MESSAGE = "You no longer have access."

        private fun parseExactInt(raw: Any?): Int? {
            val num = raw as? Number ?: return null
            return when (num) {
                is Int -> num
                is Short -> num.toInt()
                is Byte -> num.toInt()
                is Long -> if (num in Int.MIN_VALUE..Int.MAX_VALUE) num.toInt() else null
                is Float, is Double -> {
                    val d = num.toDouble()
                    if (!d.isFinite() || d != Math.floor(d) || d < Int.MIN_VALUE || d > Int.MAX_VALUE) {
                        null
                    } else {
                        d.toInt()
                    }
                }
                else -> {
                    val d = num.toDouble()
                    if (!d.isFinite() || d != Math.floor(d) || d < Int.MIN_VALUE || d > Int.MAX_VALUE) {
                        null
                    } else {
                        d.toInt()
                    }
                }
            }
        }

        private val MIN_LONG_BIG_DECIMAL = BigDecimal(Long.MIN_VALUE.toString())
        private val MAX_LONG_BIG_DECIMAL = BigDecimal(Long.MAX_VALUE.toString())

        private fun parseExactLong(raw: Any?): Long? {
            val num = raw as? Number ?: return null
            return when (num) {
                is Long -> num
                is Int -> num.toLong()
                is Short -> num.toLong()
                is Byte -> num.toLong()
                is Float, is Double -> {
                    val d = num.toDouble()
                    if (!d.isFinite()) return null
                    try {
                        val bd = BigDecimal(d)
                        if (bd.stripTrailingZeros().scale() > 0) return null
                        if (bd < MIN_LONG_BIG_DECIMAL || bd > MAX_LONG_BIG_DECIMAL) return null
                        bd.longValueExact()
                    } catch (_: Exception) {
                        null
                    }
                }
                else -> {
                    try {
                        val bd = BigDecimal(num.toString())
                        if (bd.stripTrailingZeros().scale() > 0) return null
                        if (bd < MIN_LONG_BIG_DECIMAL || bd > MAX_LONG_BIG_DECIMAL) return null
                        bd.longValueExact()
                    } catch (_: Exception) {
                        null
                    }
                }
            }
        }
    }

    fun observePatient(patientUid: String): Flow<PatientDataResult<String>> = callbackFlow {
        val registration = firestore.collection("users")
            .document(patientUid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    if (error.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                        trySend(PatientDataResult.AccessDenied(ACCESS_DENIED_MESSAGE))
                    } else {
                        trySend(PatientDataResult.Success(""))
                    }
                    return@addSnapshotListener
                }

                val displayName = try {
                    snapshot?.getString("displayName") ?: ""
                } catch (_: Exception) {
                    ""
                }
                trySend(PatientDataResult.Success(displayName))
            }

        awaitClose { registration.remove() }
    }

    fun observeMedications(patientUid: String): Flow<PatientDataResult<List<PatientMedication>>> = callbackFlow {
        val registration = firestore.collection("users")
            .document(patientUid)
            .collection("medications")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    if (error.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                        trySend(PatientDataResult.AccessDenied(ACCESS_DENIED_MESSAGE))
                    } else {
                        trySend(PatientDataResult.Success(emptyList()))
                    }
                    return@addSnapshotListener
                }

                if (snapshot != null) {
                    val meds = snapshot.documents.mapNotNull { doc ->
                        try {
                            val name = doc.getString("name") ?: return@mapNotNull null
                            val dosage = doc.getString("dosage") ?: return@mapNotNull null
                            val stockCount = parseExactInt(doc.get("stockCount")) ?: return@mapNotNull null
                            val lowStockThreshold = parseExactInt(doc.get("lowStockThreshold")) ?: return@mapNotNull null

                            val rawTimes = doc.get("times") as? List<*> ?: return@mapNotNull null
                            val times = mutableListOf<Pair<Int, Int>>()
                            for (item in rawTimes) {
                                val map = item as? Map<*, *> ?: return@mapNotNull null
                                val hour = parseExactInt(map["hour"]) ?: return@mapNotNull null
                                val minute = parseExactInt(map["minute"]) ?: return@mapNotNull null
                                if (hour !in 0..23 || minute !in 0..59) return@mapNotNull null
                                times.add(hour to minute)
                            }

                            PatientMedication(
                                id = doc.id,
                                name = name,
                                dosage = dosage,
                                times = times,
                                stockCount = stockCount,
                                lowStockThreshold = lowStockThreshold
                            )
                        } catch (_: Exception) {
                            null
                        }
                    }
                    trySend(PatientDataResult.Success(meds))
                }
            }

        awaitClose { registration.remove() }
    }

    fun observeRecentDoses(patientUid: String): Flow<PatientDataResult<List<PatientDoseEvent>>> = callbackFlow {
        val sevenDaysAgo = System.currentTimeMillis() - SEVEN_DAYS_MILLIS

        val registration = firestore.collection("users")
            .document(patientUid)
            .collection("doseEvents")
            .whereGreaterThanOrEqualTo("scheduledTime", sevenDaysAgo)
            .orderBy("scheduledTime", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    if (error.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                        trySend(PatientDataResult.AccessDenied(ACCESS_DENIED_MESSAGE))
                    } else {
                        trySend(PatientDataResult.Success(emptyList()))
                    }
                    return@addSnapshotListener
                }

                if (snapshot != null) {
                    val doses = snapshot.documents.mapNotNull { doc ->
                        try {
                            val medicationName = doc.getString("medicationName") ?: return@mapNotNull null
                            val dosage = doc.getString("dosage") ?: return@mapNotNull null
                            val scheduledTime = parseExactLong(doc.get("scheduledTime"))
                                ?: return@mapNotNull null
                            val status = doc.getString("status") ?: return@mapNotNull null
                            val rawActionTime = doc.get("actionTime")
                            val actionTime = if (rawActionTime != null) {
                                parseExactLong(rawActionTime)
                            } else {
                                null
                            }

                            PatientDoseEvent(
                                id = doc.id,
                                medicationName = medicationName,
                                dosage = dosage,
                                scheduledTime = scheduledTime,
                                status = status,
                                actionTime = actionTime
                            )
                        } catch (_: Exception) {
                            null
                        }
                    }
                    trySend(PatientDataResult.Success(doses))
                }
            }

        awaitClose { registration.remove() }
    }
}
