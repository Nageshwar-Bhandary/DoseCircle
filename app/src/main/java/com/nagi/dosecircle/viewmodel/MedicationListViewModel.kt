package com.nagi.dosecircle.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nagi.dosecircle.DoseCircleApp
import com.nagi.dosecircle.data.entity.Medication
import com.nagi.dosecircle.data.repository.MedicationRepository
import com.nagi.dosecircle.notifications.alarm.AlarmScheduler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class MedicationListViewModel(
    private val currentPersonIdFlow: StateFlow<Long?>,
    private val medicationRepository: MedicationRepository,
    private val alarmScheduler: AlarmScheduler? = null
) : ViewModel() {

    val medications: StateFlow<List<Medication>> = currentPersonIdFlow
        .filterNotNull()
        .flatMapLatest { personId ->
            medicationRepository.getMedicationsForPerson(personId)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun deleteMedication(medication: Medication) {
        viewModelScope.launch {
            val schedules = medicationRepository.getSchedulesForMedication(medication.id).firstOrNull() ?: emptyList()
            for (schedule in schedules) {
                alarmScheduler?.cancelForSchedule(schedule.id)
            }
            medicationRepository.deleteMedication(medication)
        }
    }

    companion object {
        fun provideFactory(app: DoseCircleApp): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(MedicationListViewModel::class.java)) {
                        return MedicationListViewModel(
                            currentPersonIdFlow = app.sessionManager.currentPersonId,
                            medicationRepository = app.medicationRepository,
                            alarmScheduler = app.alarmScheduler
                        ) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                }
            }
    }
}
