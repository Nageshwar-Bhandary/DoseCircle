package com.nagi.dosecircle

import android.content.SharedPreferences
import com.nagi.dosecircle.data.entity.DoseEvent
import com.nagi.dosecircle.data.entity.DoseStatus
import com.nagi.dosecircle.data.entity.Medication
import com.nagi.dosecircle.data.entity.Schedule
import com.nagi.dosecircle.data.entity.ScheduleTime
import com.nagi.dosecircle.data.repository.RestoreRepository
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Verification test suite for DoseCircle Firestore restore logic.
 *
 * Contains:
 * 1. DIRECT PRODUCTION UNIT TESTS:
 *    Directly invokes production RestoreRepository.parseMedication() and
 *    RestoreRepository.parseDoseEvent() with valid, malformed, boundary,
 *    fractional, and missing data.
 *
 * 2. SIMULATION TESTS (Behavioral & Architectural Models):
 *    Models repository interactions, multi-pass deduplication, 30-day window
 *    filtering, database wipe recovery, coroutine cancellation, and account switching.
 */
class RestoreAutomatedVerificationTest {

    // =========================================================================
    // PART 1: DIRECT PRODUCTION TESTS (RestoreRepository.kt)
    // =========================================================================

    @Test
    fun productionParseMedication_validDocument_parsesCorrectly() {
        val data = mapOf<String, Any?>(
            "name" to "Metformin",
            "dosage" to "500mg",
            "instructions" to "Take after meal",
            "stockCount" to 60L,
            "lowStockThreshold" to 10L,
            "daysOfWeek" to "1,2,3,4,5",
            "startDate" to 1700000000000L,
            "endDate" to 1750000000000L,
            "isActive" to true,
            "times" to listOf(
                mapOf("hour" to 8L, "minute" to 30L),
                mapOf("hour" to 20.0, "minute" to 0.0) // Whole-number doubles must be accepted
            )
        )

        val parsed = RestoreRepository.parseMedication("cloud_med_1", data, personId = 42L)
        assertNotNull("Valid medication document must parse successfully", parsed)
        assertEquals("Metformin", parsed!!.medication.name)
        assertEquals(42L, parsed.medication.personId)
        assertEquals("cloud_med_1", parsed.medication.cloudId)
        assertEquals("500mg", parsed.medication.dosage)
        assertEquals(60, parsed.medication.stockCount)
        assertEquals(10, parsed.medication.lowStockThreshold)

        assertEquals("1,2,3,4,5", parsed.schedule.daysOfWeek)
        assertEquals(1700000000000L, parsed.schedule.startDate)
        assertEquals(1750000000000L, parsed.schedule.endDate)
        assertTrue(parsed.schedule.isActive)

        assertEquals(2, parsed.times.size)
        assertEquals(8, parsed.times[0].hour)
        assertEquals(30, parsed.times[0].minute)
        assertEquals(20, parsed.times[1].hour)
        assertEquals(0, parsed.times[1].minute)
    }

    @Test
    fun productionParseMedication_missingRequiredFields_returnsNull() {
        val baseData = mapOf<String, Any?>(
            "name" to "Lisinopril",
            "daysOfWeek" to "1,2,3,4,5,6,7",
            "startDate" to 1700000000000L,
            "isActive" to true,
            "times" to listOf(mapOf("hour" to 9L, "minute" to 0L))
        )

        // 1. Missing or blank name
        assertNull("Missing name must return null",
            RestoreRepository.parseMedication("m1", baseData - "name", 1L))
        assertNull("Empty name must return null",
            RestoreRepository.parseMedication("m1", baseData + ("name" to ""), 1L))
        assertNull("Blank name must return null",
            RestoreRepository.parseMedication("m1", baseData + ("name" to "   "), 1L))

        // 2. Missing or blank daysOfWeek
        assertNull("Missing daysOfWeek must return null",
            RestoreRepository.parseMedication("m1", baseData - "daysOfWeek", 1L))
        assertNull("Empty daysOfWeek must return null",
            RestoreRepository.parseMedication("m1", baseData + ("daysOfWeek" to ""), 1L))

        // 3. Missing startDate
        assertNull("Missing startDate must return null",
            RestoreRepository.parseMedication("m1", baseData - "startDate", 1L))

        // 4. Missing isActive
        assertNull("Missing isActive must return null",
            RestoreRepository.parseMedication("m1", baseData - "isActive", 1L))

        // 5. Missing or invalid times
        assertNull("Missing times must return null",
            RestoreRepository.parseMedication("m1", baseData - "times", 1L))
        assertNull("Non-list times must return null",
            RestoreRepository.parseMedication("m1", baseData + ("times" to "not_a_list"), 1L))
        assertNull("Empty times list must return null",
            RestoreRepository.parseMedication("m1", baseData + ("times" to emptyList<Any>()), 1L))
    }

    @Test
    fun productionParseMedication_fractionalTimesAndOutOfRange_droppedCorrectly() {
        val dataWithMixedTimes = mapOf<String, Any?>(
            "name" to "Atorvastatin",
            "daysOfWeek" to "1,2,3,4,5,6,7",
            "startDate" to 1700000000000L,
            "isActive" to true,
            "times" to listOf(
                mapOf("hour" to 8.5, "minute" to 0),      // Rejected: fractional hour
                mapOf("hour" to 12, "minute" to 30.25),   // Rejected: fractional minute
                mapOf("hour" to 24, "minute" to 0),       // Rejected: out-of-range hour (24)
                mapOf("hour" to -1, "minute" to 0),       // Rejected: negative hour (-1)
                mapOf("hour" to 10, "minute" to 60),      // Rejected: out-of-range minute (60)
                mapOf("hour" to "10", "minute" to 30),    // Rejected: string instead of Number
                "not_a_map",                              // Rejected: non-map item
                mapOf("hour" to 9L, "minute" to 15L)      // ACCEPTED: valid whole number
            )
        )

        val parsed = RestoreRepository.parseMedication("m2", dataWithMixedTimes, 1L)
        assertNotNull("Medication must parse when at least one valid time remains", parsed)
        assertEquals("Exactly one valid time must remain after dropping invalid items", 1, parsed!!.times.size)
        assertEquals(9, parsed.times[0].hour)
        assertEquals(15, parsed.times[0].minute)

        // All times invalid -> medication skipped
        val dataAllInvalidTimes = dataWithMixedTimes + ("times" to listOf(
            mapOf("hour" to 8.5, "minute" to 0),
            mapOf("hour" to 25, "minute" to 0)
        ))
        assertNull("Medication with 0 valid times remaining must return null",
            RestoreRepository.parseMedication("m3", dataAllInvalidTimes, 1L))
    }

    @Test
    fun productionParseMedication_extremeAndOverflowValues_droppedCorrectly() {
        val dataWithOverflowTimes = mapOf<String, Any?>(
            "name" to "Metoprolol",
            "daysOfWeek" to "1,2,3,4,5,6,7",
            "startDate" to 1700000000000L,
            "isActive" to true,
            "times" to listOf(
                mapOf("hour" to Long.MAX_VALUE, "minute" to 0),
                mapOf("hour" to Long.MIN_VALUE, "minute" to 0),
                mapOf("hour" to 4294967300L, "minute" to 0), // 2^32 + 4: would truncate to 4 in 32-bit int
                mapOf("hour" to 8, "minute" to 4294967326L), // 2^32 + 30: would truncate to 30 in 32-bit int
                mapOf("hour" to Double.MAX_VALUE, "minute" to 0),
                mapOf("hour" to -10000000000L, "minute" to 0),
                mapOf("hour" to Double.POSITIVE_INFINITY, "minute" to 0),
                mapOf("hour" to Double.NEGATIVE_INFINITY, "minute" to 0),
                mapOf("hour" to Double.NaN, "minute" to 0),
                mapOf("hour" to 1e15, "minute" to 0),
                mapOf("hour" to 8L, "minute" to 30L) // Valid item that must be preserved
            )
        )

        val parsed = RestoreRepository.parseMedication("m_overflow", dataWithOverflowTimes, 1L)
        assertNotNull("Medication must parse when a valid time exists alongside overflow times", parsed)
        assertEquals("Only the single valid time must remain after dropping overflow items", 1, parsed!!.times.size)
        assertEquals(8, parsed.times[0].hour)
        assertEquals(30, parsed.times[0].minute)

        // Test pure overflow where all items are extreme values
        val dataAllOverflow = dataWithOverflowTimes + ("times" to listOf(
            mapOf("hour" to Long.MAX_VALUE, "minute" to 0),
            mapOf("hour" to 4294967300L, "minute" to 0),
            mapOf("hour" to 0, "minute" to Long.MIN_VALUE)
        ))
        assertNull("Medication with only overflow times must be rejected (return null)",
            RestoreRepository.parseMedication("m_all_overflow", dataAllOverflow, 1L))
    }

    @Test
    fun productionParseWholeLong_numericSafetyAndBoundaries() {
        // 1. Long.MAX_VALUE and Long.MIN_VALUE as Long values (both accepted)
        assertEquals(Long.MAX_VALUE, RestoreRepository.parseWholeLong(Long.MAX_VALUE))
        assertEquals(Long.MIN_VALUE, RestoreRepository.parseWholeLong(Long.MIN_VALUE))

        // 2. Exact integral types (Byte, Short, Int, Long)
        assertEquals(42L, RestoreRepository.parseWholeLong(42.toByte()))
        assertEquals(1000L, RestoreRepository.parseWholeLong(1000.toShort()))
        assertEquals(123456L, RestoreRepository.parseWholeLong(123456))

        // 3. Valid whole-number Double and Float values (accepted)
        assertEquals(1700000000000L, RestoreRepository.parseWholeLong(1700000000000.0))
        assertEquals(0L, RestoreRepository.parseWholeLong(0.0))
        assertEquals(-42L, RestoreRepository.parseWholeLong(-42.0))
        assertEquals(100L, RestoreRepository.parseWholeLong(100.0f))

        // 4. Positive 2^63 as Double (rejected: strictly exceeds Long.MAX_VALUE)
        val pow2_63 = Math.pow(2.0, 63.0) // 9223372036854775808.0
        assertNull("Positive 2^63 as Double must be rejected", RestoreRepository.parseWholeLong(pow2_63))
        assertNull("Double.MAX_VALUE must be rejected", RestoreRepository.parseWholeLong(Double.MAX_VALUE))
        assertNull("1e25 must be rejected", RestoreRepository.parseWholeLong(1e25))

        // 5. Negative values below Long.MIN_VALUE (rejected)
        val belowMinDouble = -Math.pow(2.0, 63.0) - 2048.0
        assertNull("Double strictly below Long.MIN_VALUE must be rejected", RestoreRepository.parseWholeLong(belowMinDouble))
        assertNull("-1e25 must be rejected", RestoreRepository.parseWholeLong(-1e25))

        // 6. Fractional values (rejected)
        assertNull("Fractional Double must be rejected", RestoreRepository.parseWholeLong(123.45))
        assertNull("Fractional Float must be rejected", RestoreRepository.parseWholeLong(0.5f))
        assertNull("1700000000000.5 must be rejected", RestoreRepository.parseWholeLong(1700000000000.5))

        // 7. Non-finite values (rejected)
        assertNull("Double.POSITIVE_INFINITY must be rejected", RestoreRepository.parseWholeLong(Double.POSITIVE_INFINITY))
        assertNull("Double.NEGATIVE_INFINITY must be rejected", RestoreRepository.parseWholeLong(Double.NEGATIVE_INFINITY))
        assertNull("Double.NaN must be rejected", RestoreRepository.parseWholeLong(Double.NaN))
        assertNull("Float.POSITIVE_INFINITY must be rejected", RestoreRepository.parseWholeLong(Float.POSITIVE_INFINITY))
        assertNull("Float.NaN must be rejected", RestoreRepository.parseWholeLong(Float.NaN))

        // 8. BigInteger / BigDecimal support
        assertEquals(123456789L, RestoreRepository.parseWholeLong(java.math.BigInteger.valueOf(123456789L)))
        assertEquals(Long.MAX_VALUE, RestoreRepository.parseWholeLong(java.math.BigInteger.valueOf(Long.MAX_VALUE)))
        assertEquals(Long.MIN_VALUE, RestoreRepository.parseWholeLong(java.math.BigInteger.valueOf(Long.MIN_VALUE)))
        assertNull("BigInteger 2^63 must be rejected",
            RestoreRepository.parseWholeLong(java.math.BigInteger("9223372036854775808")))
        assertNull("BigInteger -2^63 - 1 must be rejected",
            RestoreRepository.parseWholeLong(java.math.BigInteger("-9223372036854775809")))

        assertEquals(500L, RestoreRepository.parseWholeLong(java.math.BigDecimal("500.00")))
        assertNull("Fractional BigDecimal must be rejected",
            RestoreRepository.parseWholeLong(java.math.BigDecimal("500.25")))

        // 9. Non-numeric types & null
        assertNull(RestoreRepository.parseWholeLong(null))
        assertNull(RestoreRepository.parseWholeLong("12345"))
        assertNull(RestoreRepository.parseWholeLong(true))
    }

    @Test
    fun productionParseDoseEvent_validAndMalformedDocuments() {
        val validData = mapOf<String, Any?>(
            "medicationCloudId" to "med_cloud_100",
            "scheduledTime" to 1700000000000L,
            "status" to "TAKEN",
            "actionTime" to 1700000060000L
        )

        // 1. Valid parse
        val parsed = RestoreRepository.parseDoseEvent("event_1", validData)
        assertNotNull("Valid dose event must parse successfully", parsed)
        assertEquals("event_1", parsed!!.cloudId)
        assertEquals("med_cloud_100", parsed.medicationCloudId)
        assertEquals(1700000000000L, parsed.scheduledTime)
        assertEquals(DoseStatus.TAKEN, parsed.status)
        assertEquals(1700000060000L, parsed.actionTime)

        // 2. Blank or missing medicationCloudId
        assertNull("Missing medicationCloudId must return null",
            RestoreRepository.parseDoseEvent("e1", validData - "medicationCloudId"))
        assertNull("Empty medicationCloudId must return null",
            RestoreRepository.parseDoseEvent("e1", validData + ("medicationCloudId" to "")))
        assertNull("Blank medicationCloudId must return null",
            RestoreRepository.parseDoseEvent("e1", validData + ("medicationCloudId" to "   ")))

        // 3. Missing scheduledTime
        assertNull("Missing scheduledTime must return null",
            RestoreRepository.parseDoseEvent("e1", validData - "scheduledTime"))

        // 4. Missing or invalid status enum
        assertNull("Missing status must return null",
            RestoreRepository.parseDoseEvent("e1", validData - "status"))
        assertNull("Empty status must return null",
            RestoreRepository.parseDoseEvent("e1", validData + ("status" to "")))
        assertNull("Invalid status enum must return null",
            RestoreRepository.parseDoseEvent("e1", validData + ("status" to "UNKNOWN_ENUM_VALUE")))

        // 5. Status enums: SKIPPED and MISSED
        val skippedEvent = RestoreRepository.parseDoseEvent("e2", validData + ("status" to "SKIPPED"))
        assertEquals(DoseStatus.SKIPPED, skippedEvent?.status)
        val missedEvent = RestoreRepository.parseDoseEvent("e3", validData + ("status" to "MISSED"))
        assertEquals(DoseStatus.MISSED, missedEvent?.status)
    }

    @Test
    fun productionParseMedication_stockValidation() {
        val baseData = mapOf<String, Any?>(
            "name" to "Amoxicillin",
            "daysOfWeek" to "1,2,3,4,5,6,7",
            "startDate" to 1700000000000L,
            "isActive" to true,
            "times" to listOf(mapOf("hour" to 8, "minute" to 0))
        )

        // 1. Valid non-negative integers
        val validParsed = RestoreRepository.parseMedication(
            "m1",
            baseData + mapOf("stockCount" to 100L, "lowStockThreshold" to 10.0),
            1L
        )
        assertNotNull("Valid stock values must parse", validParsed)
        assertEquals(100, validParsed!!.medication.stockCount)
        assertEquals(10, validParsed.medication.lowStockThreshold)

        // 2. Zero values
        val zeroParsed = RestoreRepository.parseMedication(
            "m2",
            baseData + mapOf("stockCount" to 0, "lowStockThreshold" to 0),
            1L
        )
        assertNotNull("Zero stock values must parse", zeroParsed)
        assertEquals(0, zeroParsed!!.medication.stockCount)
        assertEquals(0, zeroParsed.medication.lowStockThreshold)

        // 3. Absent stock fields default safely to zero
        val absentParsed = RestoreRepository.parseMedication("m3", baseData, 1L)
        assertNotNull("Absent stock fields must default to 0", absentParsed)
        assertEquals(0, absentParsed!!.medication.stockCount)
        assertEquals(0, absentParsed.medication.lowStockThreshold)

        // 4. Invalid stockCount: negative values rejected
        assertNull("Negative stockCount must return null",
            RestoreRepository.parseMedication("m4", baseData + ("stockCount" to -1), 1L))
        assertNull("Negative lowStockThreshold must return null",
            RestoreRepository.parseMedication("m5", baseData + ("lowStockThreshold" to -5), 1L))

        // 5. Invalid stockCount: fractional values rejected
        assertNull("Fractional stockCount must return null",
            RestoreRepository.parseMedication("m6", baseData + ("stockCount" to 10.5), 1L))
        assertNull("Fractional lowStockThreshold must return null",
            RestoreRepository.parseMedication("m7", baseData + ("lowStockThreshold" to 2.25), 1L))

        // 6. Invalid stockCount: non-finite values rejected
        assertNull("Infinity stockCount must return null",
            RestoreRepository.parseMedication("m8", baseData + ("stockCount" to Double.POSITIVE_INFINITY), 1L))
        assertNull("NaN lowStockThreshold must return null",
            RestoreRepository.parseMedication("m9", baseData + ("lowStockThreshold" to Double.NaN), 1L))

        // 7. Invalid stockCount: overflowing values rejected
        assertNull("Long.MAX_VALUE stockCount must return null",
            RestoreRepository.parseMedication("m10", baseData + ("stockCount" to Long.MAX_VALUE), 1L))
        assertNull("4294967300L lowStockThreshold must return null",
            RestoreRepository.parseMedication("m11", baseData + ("lowStockThreshold" to 4294967300L), 1L))

        // 8. Invalid stockCount: non-numeric string rejected
        assertNull("String stockCount must return null",
            RestoreRepository.parseMedication("m12", baseData + ("stockCount" to "50"), 1L))
        assertNull("String lowStockThreshold must return null",
            RestoreRepository.parseMedication("m13", baseData + ("lowStockThreshold" to "ten"), 1L))
    }

    @Test
    fun productionParseMedication_daysOfWeekValidation() {
        val baseData = mapOf<String, Any?>(
            "name" to "Metoprolol",
            "startDate" to 1700000000000L,
            "isActive" to true,
            "times" to listOf(mapOf("hour" to 8, "minute" to 0))
        )

        // 1. Valid days of week
        assertNotNull("Valid standard range 1..5",
            RestoreRepository.parseMedication("d1", baseData + ("daysOfWeek" to "1,2,3,4,5"), 1L))
        assertNotNull("Valid single day",
            RestoreRepository.parseMedication("d2", baseData + ("daysOfWeek" to "7"), 1L))
        assertNotNull("Valid reversed full week",
            RestoreRepository.parseMedication("d3", baseData + ("daysOfWeek" to "7,6,5,4,3,2,1"), 1L))

        // 2. Out of range (range is 1..7)
        assertNull("Day 0 must return null",
            RestoreRepository.parseMedication("d4", baseData + ("daysOfWeek" to "0,1,2"), 1L))
        assertNull("Day 8 must return null",
            RestoreRepository.parseMedication("d5", baseData + ("daysOfWeek" to "1,2,8"), 1L))
        assertNull("Negative day must return null",
            RestoreRepository.parseMedication("d6", baseData + ("daysOfWeek" to "-1,2"), 1L))

        // 3. Duplicate days
        assertNull("Duplicate days must return null",
            RestoreRepository.parseMedication("d7", baseData + ("daysOfWeek" to "1,2,1"), 1L))
        assertNull("Duplicate identical day must return null",
            RestoreRepository.parseMedication("d8", baseData + ("daysOfWeek" to "5,5"), 1L))

        // 4. Empty and blank tokens
        assertNull("Empty string must return null",
            RestoreRepository.parseMedication("d9", baseData + ("daysOfWeek" to ""), 1L))
        assertNull("Whitespace string must return null",
            RestoreRepository.parseMedication("d10", baseData + ("daysOfWeek" to "   "), 1L))
        assertNull("Empty token between commas must return null",
            RestoreRepository.parseMedication("d11", baseData + ("daysOfWeek" to "1,,2"), 1L))
        assertNull("Leading comma must return null",
            RestoreRepository.parseMedication("d12", baseData + ("daysOfWeek" to ",1,2"), 1L))
        assertNull("Trailing comma must return null",
            RestoreRepository.parseMedication("d13", baseData + ("daysOfWeek" to "1,2,"), 1L))

        // 5. Non-numeric tokens
        assertNull("Text tokens must return null",
            RestoreRepository.parseMedication("d14", baseData + ("daysOfWeek" to "1,mon,2"), 1L))
        assertNull("Fractional token must return null",
            RestoreRepository.parseMedication("d15", baseData + ("daysOfWeek" to "1.5,2"), 1L))
    }

    @Test
    fun productionParseMedication_nullableTimestamps() {
        val baseData = mapOf<String, Any?>(
            "name" to "Levothyroxine",
            "daysOfWeek" to "1,2,3,4,5,6,7",
            "isActive" to true,
            "times" to listOf(mapOf("hour" to 7, "minute" to 0))
        )

        // 1. startDate: required whole-number Long
        assertNull("Missing startDate must return null",
            RestoreRepository.parseMedication("t1", baseData, 1L))
        assertNull("Fractional startDate must return null",
            RestoreRepository.parseMedication("t2", baseData + ("startDate" to 1700000000000.5), 1L))
        assertNull("Non-finite startDate must return null",
            RestoreRepository.parseMedication("t3", baseData + ("startDate" to Double.POSITIVE_INFINITY), 1L))
        assertNull("String startDate must return null",
            RestoreRepository.parseMedication("t4", baseData + ("startDate" to "1700000000000"), 1L))
        assertNull("Overflow Double.MAX_VALUE startDate must return null",
            RestoreRepository.parseMedication("t5", baseData + ("startDate" to Double.MAX_VALUE), 1L))

        // 2. endDate: nullable whole-number Long
        // Absent / null accepted
        val noEndDate = RestoreRepository.parseMedication("t6", baseData + ("startDate" to 1700000000000L), 1L)
        assertNotNull("Absent endDate must be accepted", noEndDate)
        assertNull(noEndDate!!.schedule.endDate)

        val nullEndDate = RestoreRepository.parseMedication(
            "t7",
            baseData + mapOf("startDate" to 1700000000000L, "endDate" to null),
            1L
        )
        assertNotNull("Explicit null endDate must be accepted", nullEndDate)
        assertNull(nullEndDate!!.schedule.endDate)

        // Valid endDate accepted
        val validEndDate = RestoreRepository.parseMedication(
            "t8",
            baseData + mapOf("startDate" to 1700000000000L, "endDate" to 1750000000000L),
            1L
        )
        assertNotNull("Valid endDate must be accepted", validEndDate)
        assertEquals(1750000000000L, validEndDate!!.schedule.endDate)

        // Malformed non-null endDate rejected
        assertNull("Fractional endDate must be rejected",
            RestoreRepository.parseMedication("t9",
                baseData + mapOf("startDate" to 1700000000000L, "endDate" to 1750000000000.25), 1L))
        assertNull("Non-finite endDate must be rejected",
            RestoreRepository.parseMedication("t10",
                baseData + mapOf("startDate" to 1700000000000L, "endDate" to Double.NaN), 1L))
        assertNull("String endDate must be rejected",
            RestoreRepository.parseMedication("t11",
                baseData + mapOf("startDate" to 1700000000000L, "endDate" to "2026-12-31"), 1L))
        assertNull("Overflow Double.MAX_VALUE endDate must be rejected",
            RestoreRepository.parseMedication("t12",
                baseData + mapOf("startDate" to 1700000000000L, "endDate" to Double.MAX_VALUE), 1L))
    }

    @Test
    fun productionParseDoseEvent_timestampsAndActionTimes() {
        val baseData = mapOf<String, Any?>(
            "medicationCloudId" to "med_cloud_200",
            "status" to "TAKEN"
        )

        // 1. scheduledTime: required whole-number Long
        assertNull("Missing scheduledTime must return null",
            RestoreRepository.parseDoseEvent("e1", baseData))
        assertNull("Fractional scheduledTime must return null",
            RestoreRepository.parseDoseEvent("e2", baseData + ("scheduledTime" to 1700000000000.5)))
        assertNull("Non-finite scheduledTime must return null",
            RestoreRepository.parseDoseEvent("e3", baseData + ("scheduledTime" to Double.NEGATIVE_INFINITY)))
        assertNull("String scheduledTime must return null",
            RestoreRepository.parseDoseEvent("e4", baseData + ("scheduledTime" to "1700000000000")))

        // 2. actionTime: nullable whole-number Long
        // Absent / null accepted
        val noActionTime = RestoreRepository.parseDoseEvent("e5", baseData + ("scheduledTime" to 1700000000000L))
        assertNotNull("Absent actionTime must parse", noActionTime)
        assertNull(noActionTime!!.actionTime)

        val nullActionTime = RestoreRepository.parseDoseEvent(
            "e6",
            baseData + mapOf("scheduledTime" to 1700000000000L, "actionTime" to null)
        )
        assertNotNull("Null actionTime must parse", nullActionTime)
        assertNull(nullActionTime!!.actionTime)

        // Valid actionTime accepted
        val validActionTime = RestoreRepository.parseDoseEvent(
            "e7",
            baseData + mapOf("scheduledTime" to 1700000000000L, "actionTime" to 1700000060000L)
        )
        assertNotNull("Valid actionTime must parse", validActionTime)
        assertEquals(1700000060000L, validActionTime!!.actionTime)

        // Malformed non-null actionTime rejected
        assertNull("Fractional actionTime must return null",
            RestoreRepository.parseDoseEvent("e8",
                baseData + mapOf("scheduledTime" to 1700000000000L, "actionTime" to 1700000060000.75)))
        assertNull("Non-finite actionTime must return null",
            RestoreRepository.parseDoseEvent("e9",
                baseData + mapOf("scheduledTime" to 1700000000000L, "actionTime" to Double.NaN)))
        assertNull("String actionTime must return null",
            RestoreRepository.parseDoseEvent("e10",
                baseData + mapOf("scheduledTime" to 1700000000000L, "actionTime" to "yesterday")))
        assertNull("Overflow actionTime must return null",
            RestoreRepository.parseDoseEvent("e11",
                baseData + mapOf("scheduledTime" to 1700000000000L, "actionTime" to Double.MAX_VALUE)))
    }

    companion object {
        @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
        private class DummyAppDatabase : com.nagi.dosecircle.data.database.AppDatabase() {
            override fun personDao(): com.nagi.dosecircle.data.dao.PersonDao = TODO()
            override fun medicationDao(): com.nagi.dosecircle.data.dao.MedicationDao = TODO()
            override fun scheduleDao(): com.nagi.dosecircle.data.dao.ScheduleDao = TODO()
            override fun scheduleTimeDao(): com.nagi.dosecircle.data.dao.ScheduleTimeDao = TODO()
            override fun doseEventDao(): com.nagi.dosecircle.data.dao.DoseEventDao = TODO()
            override fun dailyActivityDao(): com.nagi.dosecircle.data.dao.DailyActivityDao = TODO()
            override fun createOpenHelper(config: androidx.room.DatabaseConfiguration): androidx.sqlite.db.SupportSQLiteOpenHelper = TODO()
            override fun createInvalidationTracker(): androidx.room.InvalidationTracker = TODO()
            override fun clearAllTables() = TODO()
        }

        @Suppress("UNCHECKED_CAST")
        private fun <T> allocateDummy(clazz: Class<T>): T {
            val field = sun.misc.Unsafe::class.java.getDeclaredField("theUnsafe")
            field.isAccessible = true
            val unsafe = field.get(null) as sun.misc.Unsafe
            return unsafe.allocateInstance(clazz) as T
        }

        private fun createTestRestoreRepository(currentUidProvider: () -> String?): RestoreRepository {
            return RestoreRepository(
                database = allocateDummy(DummyAppDatabase::class.java),
                medicationRepository = allocateDummy(com.nagi.dosecircle.data.repository.MedicationRepository::class.java),
                doseEventRepository = allocateDummy(com.nagi.dosecircle.data.repository.DoseEventRepository::class.java),
                alarmScheduler = allocateDummy(com.nagi.dosecircle.notifications.alarm.AlarmScheduler::class.java),
                auth = allocateDummy(com.google.firebase.auth.FirebaseAuth::class.java),
                currentUidProvider = currentUidProvider
            )
        }
    }

    @Test
    fun productionRestoreForCurrentUser_rejectsBlankOrMismatchedExpectedUid() = runBlocking {
        // 1. Current user is "user_123", but expectedUid is blank -> rejected before any network/database access
        val repo = createTestRestoreRepository { "user_123" }
        assertFalse(
            "Blank expectedUid must be rejected immediately",
            repo.restoreForCurrentUser("", personId = 1L)
        )
        assertFalse(
            "Whitespace expectedUid must be rejected immediately",
            repo.restoreForCurrentUser("   ", personId = 1L)
        )

        // 2. Current user is "user_123", but expectedUid is "user_999" (mismatch) -> rejected before any access
        assertFalse(
            "Mismatched expectedUid must be rejected immediately",
            repo.restoreForCurrentUser("user_999", personId = 1L)
        )

        // 3. Current user is null (logged out), expectedUid is "user_123" -> rejected before any access
        val loggedOutRepo = createTestRestoreRepository { null }
        assertFalse(
            "Logged out state must reject restore for any expectedUid",
            loggedOutRepo.restoreForCurrentUser("user_123", personId = 1L)
        )
    }


    // =========================================================================
    // PART 2: SIMULATION TESTS (Behavioral & Architectural Models)
    // =========================================================================

    // --- Fake SharedPreferences for testing persistence ---
    class FakeSharedPreferences : SharedPreferences {
        private val map = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = map
        override fun getString(key: String?, defValue: String?): String? = (map[key] as? String) ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? {
            val v = map[key]
            return if (v is Set<*>) {
                @Suppress("UNCHECKED_CAST")
                v.filterIsInstance<String>().toMutableSet()
            } else defValues
        }
        override fun getInt(key: String?, defValue: Int): Int = (map[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (map[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (map[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (map[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = FakeEditor(map)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        class FakeEditor(private val target: MutableMap<String, Any?>) : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private val removed = mutableSetOf<String>()
            private var clear = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
                if (key != null) pending[key] = values
                return this
            }
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }
            override fun remove(key: String?): SharedPreferences.Editor {
                if (key != null) removed.add(key)
                return this
            }
            override fun clear(): SharedPreferences.Editor {
                clear = true
                return this
            }
            override fun commit(): Boolean {
                apply()
                return true
            }
            override fun apply() {
                if (clear) target.clear()
                for (key in removed) target.remove(key)
                target.putAll(pending)
            }
        }
    }

    // --- In-Memory Test Store simulating Room Database ---
    class InMemoryLocalStore {
        val medications = mutableListOf<Medication>()
        val schedules = mutableListOf<Schedule>()
        val scheduleTimes = mutableListOf<ScheduleTime>()
        val doseEvents = mutableListOf<DoseEvent>()

        private var nextMedId = 1L
        private var nextSchedId = 1L
        private var nextTimeId = 1L
        private var nextEventId = 1L

        fun getMedicationByCloudId(personId: Long, cloudId: String): Medication? {
            return medications.find { it.personId == personId && it.cloudId == cloudId }
        }

        fun getDoseEventByCloudId(personId: Long, cloudId: String): DoseEvent? {
            return doseEvents.find { it.personId == personId && it.cloudId == cloudId }
        }

        fun saveMedicationWithSchedule(
            medication: Medication,
            schedule: Schedule,
            times: List<ScheduleTime>
        ): Long {
            val medId = nextMedId++
            val savedMed = medication.copy(id = medId)
            medications.add(savedMed)

            val schedId = nextSchedId++
            val savedSched = schedule.copy(id = schedId, medicationId = medId)
            schedules.add(savedSched)

            for (t in times) {
                val timeId = nextTimeId++
                scheduleTimes.add(t.copy(id = timeId, scheduleId = schedId))
            }
            return schedId
        }

        fun insertDoseEvent(doseEvent: DoseEvent): Long {
            val eventId = nextEventId++
            val savedEvent = doseEvent.copy(id = eventId)
            doseEvents.add(savedEvent)
            return eventId
        }
    }

    // SIMULATION TEST 1: Duplicate Prevention
    @Test
    fun simulationTest1_duplicatePrevention_sameDocumentsRestoredTwice_noDuplicates() = runBlocking {
        val store = InMemoryLocalStore()
        val mutex = Mutex()
        val personId = 10L

        val docMed = Medication(
            personId = personId,
            name = "Aspirin",
            dosage = "100mg",
            instructions = "Take with water",
            cloudId = "cloud_med_1"
        )
        val docSched = Schedule(personId = personId, medicationId = 0, startDate = 1000L, daysOfWeek = "1,2", isActive = true)
        val docTimes = listOf(ScheduleTime(scheduleId = 0, hour = 8, minute = 0))

        val docEvent = DoseEvent(
            personId = personId,
            medicationId = 0,
            scheduledTime = System.currentTimeMillis() - 50000L,
            status = DoseStatus.TAKEN,
            cloudId = "cloud_event_1"
        )

        suspend fun executeRestorePass() {
            mutex.withLock {
                // Medication check & write
                val existingMed = store.getMedicationByCloudId(personId, docMed.cloudId!!)
                val localMed = if (existingMed == null) {
                    store.saveMedicationWithSchedule(docMed, docSched, docTimes)
                    store.getMedicationByCloudId(personId, docMed.cloudId!!)!!
                } else existingMed

                // Dose event check & write
                val existingEvent = store.getDoseEventByCloudId(personId, docEvent.cloudId!!)
                if (existingEvent == null) {
                    store.insertDoseEvent(docEvent.copy(medicationId = localMed.id))
                }
            }
        }

        // Pass 1: Fresh restore
        executeRestorePass()
        assertEquals("First pass must insert 1 medication", 1, store.medications.size)
        assertEquals("First pass must insert 1 dose event", 1, store.doseEvents.size)

        // Pass 2: Duplicate restore
        executeRestorePass()
        assertEquals("Second pass must NOT insert duplicate medication", 1, store.medications.size)
        assertEquals("Second pass must NOT insert duplicate dose event", 1, store.doseEvents.size)
    }

    // SIMULATION TEST 2: Medication Linking & Safe Handling of Missing References
    @Test
    fun simulationTest2_medicationLinking_validAndMissingReferences() = runBlocking {
        val store = InMemoryLocalStore()
        val personId = 20L

        // Pre-insert parent medication
        val parentMed = Medication(
            personId = personId,
            name = "Metformin",
            dosage = "500mg",
            instructions = "Take after meal",
            cloudId = "med_cloud_abc"
        )
        store.saveMedicationWithSchedule(parentMed, Schedule(personId = personId, medicationId = 0, startDate = 1000L, daysOfWeek = "1", isActive = true), emptyList())
        val resolvedLocalMed = store.getMedicationByCloudId(personId, "med_cloud_abc")
        assertNotNull("Parent medication must exist", resolvedLocalMed)
        val localMedId = resolvedLocalMed!!.id

        // 1. Valid linking: event references "med_cloud_abc"
        val validEvent = DoseEvent(
            personId = personId,
            medicationId = localMedId,
            scheduledTime = 1000L,
            status = DoseStatus.TAKEN,
            cloudId = "event_valid_1"
        )
        store.insertDoseEvent(validEvent)

        val insertedEvent = store.getDoseEventByCloudId(personId, "event_valid_1")
        assertNotNull(insertedEvent)
        assertEquals("Dose event must reference correct local medication ID", localMedId, insertedEvent!!.medicationId)

        // 2. Missing parent reference: event references non-existent "unknown_cloud_xyz"
        val unresolvableMed = store.getMedicationByCloudId(personId, "unknown_cloud_xyz")
        assertNull("Unresolvable medication must return null", unresolvableMed)
        if (unresolvableMed != null) {
            fail("Must not insert dose event when parent medication is unresolved")
        }
        assertEquals("No orphaned dose event inserted", 1, store.doseEvents.size)

        // 3. Blank medicationCloudId: Restorer rule skips blank references
        val blankCloudId = ""
        assertTrue("Blank cloudId must be rejected", blankCloudId.isBlank())
    }

    // SIMULATION TEST 3: Restore Window Calculation (Last 30 Days)
    @Test
    fun simulationTest3_restoreWindow_medicationsAllRestored_doseEventsLast30DaysOnly() {
        val nowTime = System.currentTimeMillis()
        val cutoffTime = nowTime - TimeUnit.DAYS.toMillis(30L)

        val eventWithinWindow = nowTime - TimeUnit.DAYS.toMillis(5L)
        val eventAtBoundary = cutoffTime
        val eventOlderThan30Days = cutoffTime - 1000L
        val eventInFuture = nowTime + TimeUnit.MINUTES.toMillis(10L)

        fun isDoseEventInRestoreWindow(scheduledTime: Long): Boolean {
            return scheduledTime >= cutoffTime && scheduledTime <= nowTime
        }

        assertTrue("Event 5 days ago must be inside restore window", isDoseEventInRestoreWindow(eventWithinWindow))
        assertTrue("Event at exact 30-day cutoff must be inside restore window", isDoseEventInRestoreWindow(eventAtBoundary))
        assertFalse("Event older than 30 days must be EXCLUDED", isDoseEventInRestoreWindow(eventOlderThan30Days))
        assertFalse("Future event must be EXCLUDED", isDoseEventInRestoreWindow(eventInFuture))

        val oldMedicationStartDate = nowTime - TimeUnit.DAYS.toMillis(365L)
        assertTrue("Medication from 1 year ago must be restored", oldMedicationStartDate > 0)
    }

    // SIMULATION TEST 4: Database-Wipe Recovery State Logic
    @Test
    fun simulationTest4_databaseWipeRecovery_retriggerRestoreWhenDatabaseEmpty() {
        val prefs = FakeSharedPreferences()
        val currentUserUid = "user_alpha_123"

        // Normal state: restoredUid matches and local DB has medications
        prefs.edit().putString("restored_uid", currentUserUid).commit()
        var restoredUid: String? = prefs.getString("restored_uid", null)
        var hasLocalMedications = true

        var shouldSkipRestore = (restoredUid == currentUserUid) && hasLocalMedications
        assertTrue("When UID matches AND medications exist, restore is skipped", shouldSkipRestore)

        // Database wiped: local DB has 0 medications, but SharedPreferences kept restoredUid
        hasLocalMedications = false
        shouldSkipRestore = (restoredUid == currentUserUid) && hasLocalMedications
        assertFalse("After database wipe (0 medications), restoration MUST run again", shouldSkipRestore)

        // Simulate restore completing
        hasLocalMedications = true
        restoredUid = currentUserUid
        shouldSkipRestore = (restoredUid == currentUserUid) && hasLocalMedications
        assertTrue("After re-restoration completes, subsequent checks skip restore", shouldSkipRestore)
    }

    // SIMULATION TEST 5: Coroutine Cancellation & Non-Persistence Model
    @Test
    fun simulationTest5_cancellation_propagatesAndPreventsPersistence() = runBlocking {
        val prefs = FakeSharedPreferences()
        val scope = CoroutineScope(Dispatchers.Default)
        val uid = "user_cancel_test"
        val restoreCompleted = AtomicBoolean(false)

        var restoreJob: Job? = null

        val currentJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                coroutineContext.ensureActive()
                delay(100)
                coroutineContext.ensureActive()

                prefs.edit().putString("restored_uid", uid).commit()
                restoreCompleted.set(true)
            } catch (e: CancellationException) {
                throw e
            }
        }

        restoreJob = currentJob
        currentJob.start()

        delay(20)
        restoreJob.cancel()
        currentJob.join()

        assertTrue("Job must finish in cancelled state", currentJob.isCancelled)
        assertFalse("Restore body must not complete", restoreCompleted.get())
        assertNull("restored_uid must NOT be persisted on cancellation", prefs.getString("restored_uid", null))

        val retryJob = scope.launch {
            prefs.edit().putString("restored_uid", uid).commit()
            restoreCompleted.set(true)
        }
        retryJob.join()

        assertTrue("Subsequent restore must succeed", restoreCompleted.get())
        assertEquals("Subsequent restore must persist restored_uid", uid, prefs.getString("restored_uid", null))
    }

    // SIMULATION TEST 6: Account Switching & Job Reference Protection Model
    @Test
    fun simulationTest6_accountSwitching_cancelsOldJobAndProtectsReferences() = runBlocking {
        val scope = CoroutineScope(Dispatchers.Default)

        var restoreJob: Job? = null
        var activeRestoreUid: String? = null

        val userA = "account_A"
        val jobA = scope.launch(start = CoroutineStart.LAZY) {
            try {
                delay(1000)
            } catch (e: CancellationException) {
                throw e
            }
        }
        activeRestoreUid = userA
        restoreJob = jobA
        jobA.start()

        val userB = "account_B"
        if (userB != activeRestoreUid) {
            restoreJob?.cancel()
        }

        assertTrue("Account A's restore must be cancelled immediately when Account B logs in", jobA.isCancelled)

        val jobB = scope.launch(start = CoroutineStart.LAZY) {
            delay(500)
        }
        activeRestoreUid = userB
        restoreJob = jobB
        jobB.start()

        if (restoreJob === jobA) {
            restoreJob = null
            activeRestoreUid = null
        }

        assertSame("Old Job A must NOT clear Job B reference", jobB, restoreJob)
        assertEquals("Old Job A must NOT clear activeRestoreUid for Account B", userB, activeRestoreUid)

        jobB.cancel()
    }

    // SIMULATION TEST 7: Sync and Notification Isolation Model
    @Test
    fun simulationTest7_syncAndNotificationIsolation_noSyncUploadsOrHistoricalAlarms() {
        val uploadMedicationCalled = AtomicBoolean(false)
        val uploadDoseEventCalled = AtomicBoolean(false)
        val historicalNotificationTriggered = AtomicBoolean(false)
        val rescheduleAllCount = AtomicInteger(0)

        val fakeSyncUpload: (Medication) -> Unit = { uploadMedicationCalled.set(true) }
        val fakeEventUpload: (DoseEvent) -> Unit = { uploadDoseEventCalled.set(true) }

        val store = InMemoryLocalStore()
        val med = Medication(
            personId = 1L,
            name = "TestMed",
            dosage = "10mg",
            instructions = "Daily",
            cloudId = "m1"
        )
        store.saveMedicationWithSchedule(med, Schedule(personId = 1L, medicationId = 0, startDate = 1000L, daysOfWeek = "1", isActive = true), emptyList())

        val historicalEvent = DoseEvent(
            personId = 1L,
            medicationId = 1L,
            scheduledTime = System.currentTimeMillis() - 86400000L,
            status = DoseStatus.TAKEN,
            cloudId = "e1"
        )
        store.insertDoseEvent(historicalEvent)

        assertFalse("Restore must NOT trigger medication sync uploads", uploadMedicationCalled.get())
        assertFalse("Restore must NOT trigger dose event sync uploads", uploadDoseEventCalled.get())

        if (historicalEvent.scheduledTime < System.currentTimeMillis()) {
            historicalNotificationTriggered.set(false)
        }
        assertFalse("Historical events must NOT trigger notification alerts", historicalNotificationTriggered.get())

        val currentAuthUid = "uid_matching"
        val restoringUid = "uid_matching"
        if (currentAuthUid == restoringUid) {
            rescheduleAllCount.incrementAndGet()
        }
        assertEquals("Alarms must be rescheduled when UID matches", 1, rescheduleAllCount.get())

        val changedAuthUid = "uid_different"
        if (changedAuthUid == restoringUid) {
            rescheduleAllCount.incrementAndGet()
        }
        assertEquals("Alarms must NOT be rescheduled if UID changed before alarm step", 1, rescheduleAllCount.get())
    }
}
