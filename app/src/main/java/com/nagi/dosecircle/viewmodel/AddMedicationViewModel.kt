package com.nagi.dosecircle.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nagi.dosecircle.DoseCircleApp
import com.nagi.dosecircle.data.entity.Medication
import com.nagi.dosecircle.data.entity.Schedule
import com.nagi.dosecircle.data.entity.ScheduleTime
import com.nagi.dosecircle.data.repository.MedicationRepository
import com.nagi.dosecircle.data.repository.PersonRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

data class TimeItem(
    val hour: Int,
    val minute: Int
) {
    val formatted: String
        get() = String.format("%02d:%02d", hour, minute)
}

data class AddMedicationUiState(
    val name: String = "",
    val dosage: String = "",
    val instructions: String = "",
    val stockCount: String = "0",
    val lowStockThreshold: String = "0",
    val selectedDays: Set<Int> = setOf(1, 2, 3, 4, 5, 6, 7), // 1 = Monday ... 7 = Sunday
    val times: List<TimeItem> = emptyList(),
    val nameError: String? = null,
    val daysError: String? = null,
    val timesError: String? = null,
    val isSaving: Boolean = false
)

class AddMedicationViewModel(
    private val personRepository: PersonRepository,
    private val medicationRepository: MedicationRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AddMedicationUiState())
    val uiState: StateFlow<AddMedicationUiState> = _uiState.asStateFlow()

    private val _saveSuccessEvent = MutableSharedFlow<Unit>()
    val saveSuccessEvent: SharedFlow<Unit> = _saveSuccessEvent.asSharedFlow()

    fun onNameChange(name: String) {
        _uiState.update { it.copy(name = name, nameError = null) }
    }

    fun onDosageChange(dosage: String) {
        _uiState.update { it.copy(dosage = dosage) }
    }

    fun onInstructionsChange(instructions: String) {
        _uiState.update { it.copy(instructions = instructions) }
    }

    fun onStockCountChange(stock: String) {
        if (stock.isEmpty() || stock.all { it.isDigit() }) {
            _uiState.update { it.copy(stockCount = stock) }
        }
    }

    fun onLowStockThresholdChange(threshold: String) {
        if (threshold.isEmpty() || threshold.all { it.isDigit() }) {
            _uiState.update { it.copy(lowStockThreshold = threshold) }
        }
    }

    fun toggleDay(day: Int) {
        _uiState.update { current ->
            val updatedDays = if (current.selectedDays.contains(day)) {
                current.selectedDays - day
            } else {
                current.selectedDays + day
            }
            current.copy(selectedDays = updatedDays, daysError = null)
        }
    }

    fun addTime(hour: Int, minute: Int) {
        _uiState.update { current ->
            val newTime = TimeItem(hour, minute)
            if (current.times.none { it.hour == hour && it.minute == minute }) {
                current.copy(
                    times = (current.times + newTime).sortedWith(compareBy({ it.hour }, { it.minute })),
                    timesError = null
                )
            } else {
                current
            }
        }
    }

    fun removeTime(time: TimeItem) {
        _uiState.update { current ->
            current.copy(times = current.times.filterNot { it.hour == time.hour && it.minute == time.minute })
        }
    }

    fun save() {
        val state = _uiState.value
        val trimmedName = state.name.trim()

        var hasError = false
        var nameErr: String? = null
        var daysErr: String? = null
        var timesErr: String? = null

        if (trimmedName.isEmpty()) {
            nameErr = "Medication name is required."
            hasError = true
        }
        if (state.selectedDays.isEmpty()) {
            daysErr = "Select at least one day."
            hasError = true
        }
        if (state.times.isEmpty()) {
            timesErr = "Add at least one reminder time."
            hasError = true
        }

        if (hasError) {
            _uiState.update {
                it.copy(
                    nameError = nameErr,
                    daysError = daysErr,
                    timesError = timesErr
                )
            }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            val defaultPerson = personRepository.ensureDefaultPerson()
            val startOfDayMillis = LocalDate.now()
                .atStartOfDay(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()

            val medication = Medication(
                personId = defaultPerson.id,
                name = trimmedName,
                dosage = state.dosage.trim(),
                instructions = state.instructions.trim(),
                stockCount = state.stockCount.toIntOrNull() ?: 0,
                lowStockThreshold = state.lowStockThreshold.toIntOrNull() ?: 0
            )

            val sortedDaysString = state.selectedDays.sorted().joinToString(",")
            val schedule = Schedule(
                personId = defaultPerson.id,
                medicationId = 0,
                startDate = startOfDayMillis,
                endDate = null,
                daysOfWeek = sortedDaysString,
                isActive = true
            )

            val scheduleTimes = state.times.map {
                ScheduleTime(
                    scheduleId = 0,
                    hour = it.hour,
                    minute = it.minute
                )
            }

            medicationRepository.saveMedicationWithSchedule(
                medication = medication,
                schedule = schedule,
                times = scheduleTimes
            )

            _uiState.update { it.copy(isSaving = false) }
            _saveSuccessEvent.emit(Unit)
        }
    }

    companion object {
        fun provideFactory(app: DoseCircleApp): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(AddMedicationViewModel::class.java)) {
                        return AddMedicationViewModel(
                            personRepository = app.personRepository,
                            medicationRepository = app.medicationRepository
                        ) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                }
            }
    }
}
