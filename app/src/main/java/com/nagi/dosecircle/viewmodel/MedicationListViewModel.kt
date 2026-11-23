package com.nagi.dosecircle.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nagi.dosecircle.DoseCircleApp
import com.nagi.dosecircle.data.entity.Medication
import com.nagi.dosecircle.data.entity.Person
import com.nagi.dosecircle.data.repository.MedicationRepository
import com.nagi.dosecircle.data.repository.PersonRepository
import com.nagi.dosecircle.notifications.alarm.AlarmScheduler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
class MedicationListViewModel(
    private val personRepository: PersonRepository,
    private val medicationRepository: MedicationRepository,
    private val alarmScheduler: AlarmScheduler? = null
) : ViewModel() {

    private val _currentPerson = MutableStateFlow<Person?>(null)

    val medications: StateFlow<List<Medication>> = _currentPerson
        .filterNotNull()
        .flatMapLatest { person ->
            medicationRepository.getMedicationsForPerson(person.id)
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    init {
        viewModelScope.launch {
            val defaultPerson = personRepository.ensureDefaultPerson()
            _currentPerson.value = defaultPerson
        }
    }

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
                            personRepository = app.personRepository,
                            medicationRepository = app.medicationRepository,
                            alarmScheduler = app.alarmScheduler
                        ) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                }
            }
    }
}
