package com.nagi.dosecircle.viewmodel

import android.app.NotificationManager
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nagi.dosecircle.DoseCircleApp
import com.nagi.dosecircle.data.entity.DoseEvent
import com.nagi.dosecircle.data.entity.DoseStatus
import com.nagi.dosecircle.data.entity.Medication
import com.nagi.dosecircle.data.entity.Person
import com.nagi.dosecircle.data.entity.Schedule
import com.nagi.dosecircle.data.entity.ScheduleTime
import com.nagi.dosecircle.data.repository.DoseEventRepository
import com.nagi.dosecircle.data.repository.DoseEventSyncRepository
import com.nagi.dosecircle.data.repository.FirestoreSyncRepository
import com.nagi.dosecircle.data.repository.MedicationRepository
import com.nagi.dosecircle.data.repository.PersonRepository
import com.nagi.dosecircle.util.NotificationHelper
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

data class TodayDoseItem(
    val doseEvent: DoseEvent,
    val medicationName: String,
    val dosage: String,
    val timeFormatted: String
)

data class UpcomingDoseItem(
    val scheduleTimeId: Long,
    val medicationId: Long,
    val medicationName: String,
    val dosage: String,
    val timeFormatted: String,
    val scheduledTimeMillis: Long
)

data class DashboardUiState(
    val todayDoses: List<TodayDoseItem> = emptyList(),
    val upcomingDoses: List<UpcomingDoseItem> = emptyList(),
    val takenCount: Int = 0,
    val skippedCount: Int = 0,
    val missedCount: Int = 0,
    val pendingCount: Int = 0
)

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModel(
    private val context: Context,
    private val currentPersonIdFlow: StateFlow<Long?>,
    private val medicationRepository: MedicationRepository,
    private val doseEventRepository: DoseEventRepository,
    private val doseEventSyncRepository: DoseEventSyncRepository? = null,
    private val firestoreSyncRepository: FirestoreSyncRepository? = null
) : ViewModel() {

    val uiState: StateFlow<DashboardUiState> = currentPersonIdFlow
        .filterNotNull()
        .flatMapLatest { personId ->
            val zoneId = ZoneId.systemDefault()
            val today = LocalDate.now(zoneId)
            val startOfDay = today.atStartOfDay(zoneId).toInstant().toEpochMilli()
            val endOfDay = today.atTime(LocalTime.MAX).atZone(zoneId).toInstant().toEpochMilli()

            val doseEventsFlow = doseEventRepository.getDoseEventsForPersonBetween(personId, startOfDay, endOfDay)
            val medicationsFlow = medicationRepository.getMedicationsForPerson(personId)

            combine(doseEventsFlow, medicationsFlow) { doseEvents, medications ->
                val medMap = medications.associateBy { it.id }

                val todayDoses = doseEvents.map { event ->
                    val med = medMap[event.medicationId]
                    val timeString = formatMillisToTime(event.scheduledTime, zoneId)
                    TodayDoseItem(
                        doseEvent = event,
                        medicationName = med?.name ?: "Unknown",
                        dosage = med?.dosage ?: "",
                        timeFormatted = timeString
                    )
                }

                // Compute counts
                var taken = 0
                var skipped = 0
                var missed = 0
                var pending = 0
                for (event in doseEvents) {
                    when (event.status) {
                        DoseStatus.TAKEN -> taken++
                        DoseStatus.SKIPPED -> skipped++
                        DoseStatus.MISSED -> missed++
                        DoseStatus.PENDING -> pending++
                    }
                }

                // Upcoming remaining ScheduleTimes today
                val upcoming = computeUpcomingDoses(personId, medMap, doseEvents, today, zoneId)

                DashboardUiState(
                    todayDoses = todayDoses,
                    upcomingDoses = upcoming,
                    takenCount = taken,
                    skippedCount = skipped,
                    missedCount = missed,
                    pendingCount = pending
                )
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = DashboardUiState()
        )

    private suspend fun computeUpcomingDoses(
        personId: Long,
        medMap: Map<Long, Medication>,
        todayEvents: List<DoseEvent>,
        today: LocalDate,
        zoneId: ZoneId
    ): List<UpcomingDoseItem> {
        val now = LocalDateTime.now(zoneId)
        val dayOfWeek = today.dayOfWeek.value // 1 = Mon ... 7 = Sun
        val activeSchedules = medicationRepository.getAllActiveSchedules().filter { it.personId == personId }

        val upcomingList = mutableListOf<UpcomingDoseItem>()

        for (schedule in activeSchedules) {
            val startLocalDate = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(schedule.startDate), zoneId).toLocalDate()
            val endLocalDate = schedule.endDate?.let {
                LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(it), zoneId).toLocalDate()
            }

            if (today.isBefore(startLocalDate)) continue
            if (endLocalDate != null && today.isAfter(endLocalDate)) continue

            val scheduleDays = schedule.daysOfWeek.split(",").mapNotNull { it.trim().toIntOrNull() }.toSet()
            if (!scheduleDays.contains(dayOfWeek)) continue

            val med = medMap[schedule.medicationId] ?: continue
            val times = medicationRepository.getTimesForScheduleDirect(schedule.id)

            for (time in times) {
                val scheduledDateTime = LocalDateTime.of(today, LocalTime.of(time.hour, time.minute))
                if (scheduledDateTime.isAfter(now)) {
                    val slotMillis = scheduledDateTime.atZone(zoneId).toInstant().toEpochMilli()

                    // Check if a DoseEvent already exists today for this medication at this slot (within 60s tolerance)
                    val eventExists = todayEvents.any { event ->
                        event.medicationId == schedule.medicationId && Math.abs(event.scheduledTime - slotMillis) < 60_000
                    }

                    if (!eventExists) {
                        upcomingList.add(
                            UpcomingDoseItem(
                                scheduleTimeId = time.id,
                                medicationId = schedule.medicationId,
                                medicationName = med.name,
                                dosage = med.dosage,
                                timeFormatted = String.format("%02d:%02d", time.hour, time.minute),
                                scheduledTimeMillis = slotMillis
                            )
                        )
                    }
                }
            }
        }

        return upcomingList.sortedBy { it.scheduledTimeMillis }
    }

    fun markDoseTaken(doseEventId: Long) {
        viewModelScope.launch {
            val event = doseEventRepository.getDoseEventById(doseEventId)
            if (event != null && event.status == DoseStatus.PENDING) {
                val nowMillis = System.currentTimeMillis()
                doseEventRepository.updateDoseStatus(doseEventId, DoseStatus.TAKEN, nowMillis)
                doseEventSyncRepository?.uploadDoseEvent(doseEventId)
                cancelNotification(doseEventId)

                // Deduct stock
                val medId = event.medicationId
                val stockBefore = medicationRepository.getMedicationById(medId)?.stockCount ?: 0
                medicationRepository.decreaseStock(medId)
                val medAfter = medicationRepository.getMedicationById(medId)
                if (medAfter != null) {
                    val app = context.applicationContext as? com.nagi.dosecircle.DoseCircleApp
                    // Sync updated stock to Firestore
                    app?.firestoreSyncRepository?.uploadMedication(medId)

                    // Notify only on threshold crossing or reaching zero
                    val threshold = medAfter.lowStockThreshold
                    if (threshold > 0 &&
                        (medAfter.stockCount == 0 ||
                                (stockBefore > threshold && medAfter.stockCount <= threshold))
                    ) {
                        com.nagi.dosecircle.util.NotificationHelper.showLowStockAlert(context, medAfter)
                    }
                }
            }
        }
    }

    fun markDoseSkipped(doseEventId: Long) {
        viewModelScope.launch {
            val event = doseEventRepository.getDoseEventById(doseEventId)
            if (event != null && event.status == DoseStatus.PENDING) {
                val nowMillis = System.currentTimeMillis()
                doseEventRepository.updateDoseStatus(doseEventId, DoseStatus.SKIPPED, nowMillis)
                doseEventSyncRepository?.uploadDoseEvent(doseEventId)
                cancelNotification(doseEventId)
            }
        }
    }

    private fun cancelNotification(doseEventId: Long) {
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.cancel(doseEventId.toInt())
    }

    private fun formatMillisToTime(millis: Long, zoneId: ZoneId): String {
        val dt = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(millis), zoneId)
        return String.format("%02d:%02d", dt.hour, dt.minute)
    }

    companion object {
        fun provideFactory(app: DoseCircleApp): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(DashboardViewModel::class.java)) {
                        return DashboardViewModel(
                            context = app,
                            currentPersonIdFlow = app.sessionManager.currentPersonId,
                            medicationRepository = app.medicationRepository,
                            doseEventRepository = app.doseEventRepository,
                            doseEventSyncRepository = app.doseEventSyncRepository,
                            firestoreSyncRepository = app.firestoreSyncRepository
                        ) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                }
            }
    }
}
