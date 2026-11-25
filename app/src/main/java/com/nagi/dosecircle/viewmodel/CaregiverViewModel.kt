package com.nagi.dosecircle.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nagi.dosecircle.DoseCircleApp
import com.nagi.dosecircle.data.repository.CaregiverLink
import com.nagi.dosecircle.data.repository.CaregiverRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Date

data class CaregiverUiState(
    val activeInviteCode: String? = null,
    val inviteExpiresAt: Date? = null,
    val remainingSeconds: Long = 0,
    val isGeneratingCode: Boolean = false,
    val errorMessage: String? = null
)

class CaregiverViewModel(
    private val caregiverRepository: CaregiverRepository
) : ViewModel() {

    val links: StateFlow<List<CaregiverLink>> = caregiverRepository.observeLinks()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val _uiState = MutableStateFlow(CaregiverUiState())
    val uiState: StateFlow<CaregiverUiState> = _uiState.asStateFlow()

    private var countdownJob: Job? = null

    fun generateInviteCode() {
        if (_uiState.value.isGeneratingCode) return
        _uiState.update { it.copy(isGeneratingCode = true, errorMessage = null) }

        viewModelScope.launch {
            try {
                val result = caregiverRepository.createInviteCode()
                _uiState.update {
                    it.copy(
                        activeInviteCode = result.code,
                        inviteExpiresAt = result.expiresAt,
                        isGeneratingCode = false,
                        errorMessage = null
                    )
                }
                startCountdown(result.expiresAt)
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        isGeneratingCode = false,
                        errorMessage = e.localizedMessage ?: e.message ?: "Failed to generate invite code."
                    )
                }
            }
        }
    }

    private fun startCountdown(expiresAt: Date) {
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            while (true) {
                val now = System.currentTimeMillis()
                val diff = (expiresAt.time - now) / 1000
                if (diff <= 0) {
                    _uiState.update {
                        it.copy(
                            activeInviteCode = null,
                            inviteExpiresAt = null,
                            remainingSeconds = 0
                        )
                    }
                    break
                }
                _uiState.update { it.copy(remainingSeconds = diff) }
                delay(1000)
            }
        }
    }

    fun approveLink(link: CaregiverLink) {
        viewModelScope.launch {
            try {
                caregiverRepository.approve(link)
            } catch (_: Exception) {
            }
        }
    }

    fun rejectLink(link: CaregiverLink) {
        viewModelScope.launch {
            try {
                caregiverRepository.reject(link)
            } catch (_: Exception) {
            }
        }
    }

    fun removeCaregiver(link: CaregiverLink) {
        viewModelScope.launch {
            try {
                caregiverRepository.removeCaregiver(link)
            } catch (_: Exception) {
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        countdownJob?.cancel()
    }

    companion object {
        fun provideFactory(app: DoseCircleApp): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(CaregiverViewModel::class.java)) {
                        return CaregiverViewModel(
                            caregiverRepository = app.caregiverRepository
                        ) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                }
            }
    }
}
