package com.nagi.dosecircle.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nagi.dosecircle.DoseCircleApp
import com.nagi.dosecircle.data.model.CaregiverLink
import com.nagi.dosecircle.data.repository.CaregiverLinkRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CareForViewModel(
    private val repository: CaregiverLinkRepository,
    coroutineScope: CoroutineScope? = null
) : ViewModel() {

    private val scope: CoroutineScope = coroutineScope ?: viewModelScope

    private val _inviteCode = MutableStateFlow("")
    val inviteCode: StateFlow<String> = _inviteCode.asStateFlow()

    private val _isSubmitting = MutableStateFlow(false)
    val isSubmitting: StateFlow<Boolean> = _isSubmitting.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _successMessage = MutableStateFlow<String?>(null)
    val successMessage: StateFlow<String?> = _successMessage.asStateFlow()

    private val _linkToCancelOrLeave = MutableStateFlow<CaregiverLink?>(null)
    val linkToCancelOrLeave: StateFlow<CaregiverLink?> = _linkToCancelOrLeave.asStateFlow()

    val links: StateFlow<List<CaregiverLink>> = repository.observeMyLinks()
        .catch {
            // Handle listener failure safely without crashing
            emit(emptyList())
        }
        .stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    fun onInviteCodeChanged(input: String) {
        // Automatically uppercase input and limit it to 8 characters
        val formatted = input.uppercase().filter { it.isLetterOrDigit() }.take(8)
        _inviteCode.value = formatted
        _errorMessage.value = null
        _successMessage.value = null
    }

    fun submitInviteCode(): Job {
        val code = _inviteCode.value.trim()
        if (code.length != 8) {
            _errorMessage.value = "Invite code must be 8 characters"
            return Job().apply { complete() }
        }

        return scope.launch {
            _isSubmitting.value = true
            _errorMessage.value = null
            _successMessage.value = null

            val result = repository.requestLink(code)
            _isSubmitting.value = false

            result.onSuccess {
                _successMessage.value = "Link request sent successfully"
                _inviteCode.value = ""
            }.onFailure { e ->
                _errorMessage.value = e.message ?: "Failed to submit code"
            }
        }
    }

    fun requestCancelOrLeave(link: CaregiverLink) {
        _linkToCancelOrLeave.value = link
    }

    fun dismissConfirmDialog() {
        _linkToCancelOrLeave.value = null
    }

    fun confirmCancelOrLeave(): Job? {
        val link = _linkToCancelOrLeave.value ?: return null
        _linkToCancelOrLeave.value = null

        return scope.launch {
            _errorMessage.value = null
            val result = repository.cancelOrLeave(link)
            result.onFailure { e ->
                _errorMessage.value = e.message ?: "Failed to remove link"
            }
        }
    }

    fun clearMessages() {
        _errorMessage.value = null
        _successMessage.value = null
    }

    companion object {
        fun provideFactory(app: DoseCircleApp): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(CareForViewModel::class.java)) {
                        return CareForViewModel(
                            repository = app.caregiverLinkRepository
                        ) as T
                    }
                    throw IllegalArgumentException(
                        "Unknown ViewModel class: ${modelClass.name}"
                    )
                }
            }
    }
}
