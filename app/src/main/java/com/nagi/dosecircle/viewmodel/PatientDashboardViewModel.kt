package com.nagi.dosecircle.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nagi.dosecircle.DoseCircleApp
import com.nagi.dosecircle.data.repository.PatientDataResult
import com.nagi.dosecircle.data.repository.PatientDoseEvent
import com.nagi.dosecircle.data.repository.PatientMedication
import com.nagi.dosecircle.data.repository.PatientViewRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.util.Calendar

data class PatientDashboardUiState(
    val patientName: String = "",
    val takenToday: Int = 0,
    val skippedToday: Int = 0,
    val missedToday: Int = 0,
    val pendingToday: Int = 0,
    val adherencePercent: Int = 0,
    val missedDoses: List<PatientDoseEvent> = emptyList(),
    val recentDoses: List<PatientDoseEvent> = emptyList(),
    val medications: List<PatientMedication> = emptyList(),
    val accessDeniedMessage: String? = null,
    val isLoading: Boolean = true
)

class PatientDashboardViewModel(
    private val patientUid: String,
    private val repository: PatientViewRepository
) : ViewModel() {

    val uiState: StateFlow<PatientDashboardUiState> = combine(
        repository.observePatient(patientUid),
        repository.observeMedications(patientUid),
        repository.observeRecentDoses(patientUid)
    ) { patientResult, medsResult, dosesResult ->
        val denied = listOf(patientResult, medsResult, dosesResult)
            .filterIsInstance<PatientDataResult.AccessDenied>()
            .firstOrNull()

        if (denied != null) {
            return@combine PatientDashboardUiState(
                accessDeniedMessage = denied.message,
                isLoading = false
            )
        }

        val patientName = (patientResult as? PatientDataResult.Success)?.data ?: ""
        val medications = (medsResult as? PatientDataResult.Success)?.data ?: emptyList()
        val recentDoses = (dosesResult as? PatientDataResult.Success)?.data ?: emptyList()

        val (startOfDay, endOfDay) = todayBounds()
        val todayDoses = recentDoses.filter { it.scheduledTime in startOfDay..endOfDay }

        val takenToday = todayDoses.count { it.status.equals("TAKEN", ignoreCase = true) }
        val skippedToday = todayDoses.count { it.status.equals("SKIPPED", ignoreCase = true) }
        val missedToday = todayDoses.count { it.status.equals("MISSED", ignoreCase = true) }
        val pendingToday = todayDoses.count { it.status.equals("PENDING", ignoreCase = true) }

        val totalTaken7d = recentDoses.count { it.status.equals("TAKEN", ignoreCase = true) }
        val totalSkipped7d = recentDoses.count { it.status.equals("SKIPPED", ignoreCase = true) }
        val totalMissed7d = recentDoses.count { it.status.equals("MISSED", ignoreCase = true) }
        val denominator = totalTaken7d + totalSkipped7d + totalMissed7d
        val adherencePercent = if (denominator > 0) {
            (totalTaken7d * 100) / denominator
        } else {
            0
        }

        val missedDoses = recentDoses.filter { it.status.equals("MISSED", ignoreCase = true) }

        PatientDashboardUiState(
            patientName = patientName,
            takenToday = takenToday,
            skippedToday = skippedToday,
            missedToday = missedToday,
            pendingToday = pendingToday,
            adherencePercent = adherencePercent,
            missedDoses = missedDoses,
            recentDoses = recentDoses,
            medications = medications,
            accessDeniedMessage = null,
            isLoading = false
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = PatientDashboardUiState()
    )

    private fun todayBounds(): Pair<Long, Long> {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        val start = cal.timeInMillis
        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59)
        cal.set(Calendar.MILLISECOND, 999)
        val end = cal.timeInMillis
        return start to end
    }

    companion object {
        fun provideFactory(
            app: DoseCircleApp,
            patientUid: String
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(PatientDashboardViewModel::class.java)) {
                        return PatientDashboardViewModel(
                            patientUid = patientUid,
                            repository = app.patientViewRepository
                        ) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                }
            }
    }
}
