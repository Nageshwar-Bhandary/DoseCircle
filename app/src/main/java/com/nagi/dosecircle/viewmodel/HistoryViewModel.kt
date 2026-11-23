package com.nagi.dosecircle.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nagi.dosecircle.DoseCircleApp
import com.nagi.dosecircle.data.entity.DoseEvent
import com.nagi.dosecircle.data.entity.DoseStatus
import com.nagi.dosecircle.data.repository.DoseEventRepository
import com.nagi.dosecircle.data.repository.MedicationRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter

enum class HistoryRangeType {
    DAILY,
    WEEKLY,
    MONTHLY
}

data class HistoryDoseItem(
    val doseEvent: DoseEvent,
    val medicationName: String,
    val dosage: String,
    val scheduledTimeFormatted: String,
    val dateLocalDate: LocalDate
)

data class HistoryGroupedDate(
    val date: LocalDate,
    val dateHeader: String,
    val items: List<HistoryDoseItem>
)

data class HistoryUiState(
    val rangeType: HistoryRangeType = HistoryRangeType.DAILY,
    val selectedDate: LocalDate = LocalDate.now(),
    val selectedMonth: YearMonth = YearMonth.now(),
    val rangeLabel: String = "",
    val groupedEvents: List<HistoryGroupedDate> = emptyList(),
    val takenCount: Int = 0,
    val skippedCount: Int = 0,
    val missedCount: Int = 0,
    val adherencePercentage: Int = 0 // integer 0-100%
)

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModel(
    private val currentPersonIdFlow: StateFlow<Long?>,
    private val medicationRepository: MedicationRepository,
    private val doseEventRepository: DoseEventRepository
) : ViewModel() {

    private val _rangeType = MutableStateFlow(HistoryRangeType.DAILY)
    val rangeType: StateFlow<HistoryRangeType> = _rangeType.asStateFlow()

    private val _selectedDate = MutableStateFlow(LocalDate.now())
    val selectedDate: StateFlow<LocalDate> = _selectedDate.asStateFlow()

    private val _selectedMonth = MutableStateFlow(YearMonth.now())
    val selectedMonth: StateFlow<YearMonth> = _selectedMonth.asStateFlow()

    private val dateFormatter = DateTimeFormatter.ofPattern("MMM dd, yyyy")
    private val monthFormatter = DateTimeFormatter.ofPattern("MMMM yyyy")

    val uiState: StateFlow<HistoryUiState> = currentPersonIdFlow
        .filterNotNull()
        .flatMapLatest { personId ->
            combine(
                _rangeType,
                _selectedDate,
                _selectedMonth
            ) { type, sDate, sMonth ->
                Triple(type, sDate, sMonth)
            }.flatMapLatest { (type, sDate, sMonth) ->
                val zoneId = ZoneId.systemDefault()
                val (startTimeMillis, endTimeMillis, rangeLabel) = when (type) {
                    HistoryRangeType.DAILY -> {
                        val start = sDate.atStartOfDay(zoneId).toInstant().toEpochMilli()
                        val end = sDate.atTime(LocalTime.MAX).atZone(zoneId).toInstant().toEpochMilli()
                        val label = if (sDate == LocalDate.now(zoneId)) "Today" else sDate.format(dateFormatter)
                        Triple(start, end, label)
                    }
                    HistoryRangeType.WEEKLY -> {
                        val today = LocalDate.now(zoneId)
                        val startDay = today.minusDays(6)
                        val start = startDay.atStartOfDay(zoneId).toInstant().toEpochMilli()
                        val end = today.atTime(LocalTime.MAX).atZone(zoneId).toInstant().toEpochMilli()
                        val label = "${startDay.format(dateFormatter)} - ${today.format(dateFormatter)}"
                        Triple(start, end, label)
                    }
                    HistoryRangeType.MONTHLY -> {
                        val startDay = sMonth.atDay(1)
                        val endDay = sMonth.atEndOfMonth()
                        val start = startDay.atStartOfDay(zoneId).toInstant().toEpochMilli()
                        val end = endDay.atTime(LocalTime.MAX).atZone(zoneId).toInstant().toEpochMilli()
                        val label = sMonth.format(monthFormatter)
                        Triple(start, end, label)
                    }
                }

                val doseEventsFlow = doseEventRepository.getDoseEventsForPersonBetween(personId, startTimeMillis, endTimeMillis)
                val medicationsFlow = medicationRepository.getMedicationsForPerson(personId)

                combine(doseEventsFlow, medicationsFlow) { doseEvents, medications ->
                    val medMap = medications.associateBy { it.id }

                    var taken = 0
                    var skipped = 0
                    var missed = 0

                    val items = doseEvents.map { event ->
                        when (event.status) {
                            DoseStatus.TAKEN -> taken++
                            DoseStatus.SKIPPED -> skipped++
                            DoseStatus.MISSED -> missed++
                            DoseStatus.PENDING -> { /* Excluded from adherence summary counts */ }
                        }

                        val med = medMap[event.medicationId]
                        val dt = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(event.scheduledTime), zoneId)
                        val timeStr = String.format("%02d:%02d", dt.hour, dt.minute)

                        HistoryDoseItem(
                            doseEvent = event,
                            medicationName = med?.name ?: "Unknown",
                            dosage = med?.dosage ?: "",
                            scheduledTimeFormatted = timeStr,
                            dateLocalDate = dt.toLocalDate()
                        )
                    }

                    val totalLogged = taken + skipped + missed
                    val adherence = if (totalLogged > 0) {
                        (taken * 100) / totalLogged
                    } else {
                        0
                    }

                    // Group by date, ordered chronologically descending (newest dates first) or ascending
                    // Descending by date makes most sense for history, with items inside sorted by scheduledTime
                    val grouped = items.groupBy { it.dateLocalDate }
                        .toSortedMap(compareByDescending { it })
                        .map { (date, dayItems) ->
                            val header = when (date) {
                                LocalDate.now(zoneId) -> "Today, ${date.format(dateFormatter)}"
                                LocalDate.now(zoneId).minusDays(1) -> "Yesterday, ${date.format(dateFormatter)}"
                                else -> date.format(dateFormatter)
                            }
                            HistoryGroupedDate(
                                date = date,
                                dateHeader = header,
                                items = dayItems.sortedBy { it.doseEvent.scheduledTime }
                            )
                        }

                    HistoryUiState(
                        rangeType = type,
                        selectedDate = sDate,
                        selectedMonth = sMonth,
                        rangeLabel = rangeLabel,
                        groupedEvents = grouped,
                        takenCount = taken,
                        skippedCount = skipped,
                        missedCount = missed,
                        adherencePercentage = adherence
                    )
                }
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = HistoryUiState()
        )

    fun setRangeType(type: HistoryRangeType) {
        _rangeType.value = type
    }

    fun previousDay() {
        _selectedDate.value = _selectedDate.value.minusDays(1)
    }

    fun nextDay() {
        _selectedDate.value = _selectedDate.value.plusDays(1)
    }

    fun previousMonth() {
        _selectedMonth.value = _selectedMonth.value.minusMonths(1)
    }

    fun nextMonth() {
        _selectedMonth.value = _selectedMonth.value.plusMonths(1)
    }

    companion object {
        fun provideFactory(app: DoseCircleApp): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(HistoryViewModel::class.java)) {
                        return HistoryViewModel(
                            currentPersonIdFlow = app.sessionManager.currentPersonId,
                            medicationRepository = app.medicationRepository,
                            doseEventRepository = app.doseEventRepository
                        ) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                }
            }
    }
}
