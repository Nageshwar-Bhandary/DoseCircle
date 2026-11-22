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
import com.nagi.dosecircle.util.ImageStorageHelper
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
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
    val isEditMode: Boolean = false,
    val medicationId: Long? = null,
    val name: String = "",
    val dosage: String = "",
    val instructions: String = "",
    val photoUri: String? = null,
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
    private val medicationRepository: MedicationRepository,
    private val medicationId: Long? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        AddMedicationUiState(
            isEditMode = medicationId != null,
            medicationId = medicationId
        )
    )
    val uiState: StateFlow<AddMedicationUiState> = _uiState.asStateFlow()

    private val _saveSuccessEvent = MutableSharedFlow<Unit>()
    val saveSuccessEvent: SharedFlow<Unit> = _saveSuccessEvent.asSharedFlow()

    private var initialPhotoUri: String? = null
    private var existingSchedule: Schedule? = null

    init {
        if (medicationId != null) {
            loadExistingMedication(medicationId)
        }
    }

    private fun loadExistingMedication(medId: Long) {
        viewModelScope.launch {
            val med = medicationRepository.getMedicationById(medId) ?: return@launch
            initialPhotoUri = med.photoUri
            val schedules = medicationRepository.getSchedulesForMedication(medId).firstOrNull() ?: emptyList()
            val primarySchedule = schedules.firstOrNull()
            existingSchedule = primarySchedule

            val scheduleTimes = if (primarySchedule != null) {
                medicationRepository.getTimesForSchedule(primarySchedule.id).firstOrNull() ?: emptyList()
            } else {
                emptyList()
            }

            val loadedDays = primarySchedule?.daysOfWeek
                ?.split(",")
                ?.mapNotNull { it.trim().toIntOrNull() }
                ?.toSet()
                ?: setOf(1, 2, 3, 4, 5, 6, 7)

            val loadedTimes = scheduleTimes.map {
                TimeItem(hour = it.hour, minute = it.minute)
            }.sortedWith(compareBy({ it.hour }, { it.minute }))

            _uiState.update { current ->
                current.copy(
                    name = med.name,
                    dosage = med.dosage,
                    instructions = med.instructions,
                    photoUri = med.photoUri,
                    stockCount = med.stockCount.toString(),
                    lowStockThreshold = med.lowStockThreshold.toString(),
                    selectedDays = if (loadedDays.isNotEmpty()) loadedDays else setOf(1, 2, 3, 4, 5, 6, 7),
                    times = loadedTimes
                )
            }
        }
    }

    fun onNameChange(name: String) {
        _uiState.update { it.copy(name = name, nameError = null) }
    }

    fun onDosageChange(dosage: String) {
        _uiState.update { it.copy(dosage = dosage) }
    }

    fun onInstructionsChange(instructions: String) {
        _uiState.update { it.copy(instructions = instructions) }
    }

    fun onPhotoUriChange(savedFilePath: String) {
        _uiState.update { current ->
            // If user already picked a temporary replacement photo during this session that wasn't initialPhotoUri, clean it
            if (current.photoUri != null && current.photoUri != initialPhotoUri && current.photoUri != savedFilePath) {
                ImageStorageHelper.deleteFile(current.photoUri)
            }
            current.copy(photoUri = savedFilePath)
        }
    }

    fun onRemovePhoto() {
        _uiState.update { current ->
            if (current.photoUri != null && current.photoUri != initialPhotoUri) {
                ImageStorageHelper.deleteFile(current.photoUri)
            }
            current.copy(photoUri = null)
        }
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

            val sortedDaysString = state.selectedDays.sorted().joinToString(",")

            if (state.isEditMode && medicationId != null) {
                val med = Medication(
                    id = medicationId,
                    personId = defaultPerson.id,
                    name = trimmedName,
                    dosage = state.dosage.trim(),
                    instructions = state.instructions.trim(),
                    photoUri = state.photoUri,
                    stockCount = state.stockCount.toIntOrNull() ?: 0,
                    lowStockThreshold = state.lowStockThreshold.toIntOrNull() ?: 0
                )

                val sched = existingSchedule?.copy(
                    daysOfWeek = sortedDaysString
                ) ?: Schedule(
                    personId = defaultPerson.id,
                    medicationId = medicationId,
                    startDate = startOfDayMillis,
                    endDate = null,
                    daysOfWeek = sortedDaysString,
                    isActive = true
                )

                val scheduleTimes = state.times.map {
                    ScheduleTime(
                        scheduleId = sched.id,
                        hour = it.hour,
                        minute = it.minute
                    )
                }

                medicationRepository.updateMedicationWithSchedule(
                    medication = med,
                    schedule = sched,
                    times = scheduleTimes,
                    oldPhotoUri = initialPhotoUri
                )
            } else {
                val medication = Medication(
                    personId = defaultPerson.id,
                    name = trimmedName,
                    dosage = state.dosage.trim(),
                    instructions = state.instructions.trim(),
                    photoUri = state.photoUri,
                    stockCount = state.stockCount.toIntOrNull() ?: 0,
                    lowStockThreshold = state.lowStockThreshold.toIntOrNull() ?: 0
                )

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
            }

            _uiState.update { it.copy(isSaving = false) }
            _saveSuccessEvent.emit(Unit)
        }
    }

    override fun onCleared() {
        super.onCleared()
        // If the user picked a photo but navigated away without saving in Add mode:
        val state = _uiState.value
        if (!state.isEditMode && state.photoUri != null) {
            ImageStorageHelper.deleteFile(state.photoUri)
        }
    }

    companion object {
        fun provideFactory(app: DoseCircleApp, medicationId: Long? = null): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(AddMedicationViewModel::class.java)) {
                        return AddMedicationViewModel(
                            personRepository = app.personRepository,
                            medicationRepository = app.medicationRepository,
                            medicationId = medicationId
                        ) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                }
            }
    }
}
