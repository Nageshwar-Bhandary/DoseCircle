package com.nagi.dosecircle.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nagi.dosecircle.DoseCircleApp
import com.nagi.dosecircle.data.entity.Medication
import com.nagi.dosecircle.data.repository.FirestoreSyncRepository
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
    private val alarmScheduler: AlarmScheduler? = null,
    private val firestoreSyncRepository: FirestoreSyncRepository? = null
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
            // Fire-and-forget Firestore delete; silently skipped if offline or logged out
            firestoreSyncRepository?.deleteMedication(medication.cloudId)
        }
    }

    fun refillMedication(medication: Medication, quantity: Int) {
        if (quantity <= 0) return
        viewModelScope.launch {
            medicationRepository.increaseStock(medication.id, quantity)
            firestoreSyncRepository?.uploadMedication(medication.id)
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
                            alarmScheduler = app.alarmScheduler,
                            firestoreSyncRepository = app.firestoreSyncRepository
                        ) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                }
            }
    }
}
