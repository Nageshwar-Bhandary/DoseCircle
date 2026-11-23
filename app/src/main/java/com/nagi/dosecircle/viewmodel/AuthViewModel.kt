package com.nagi.dosecircle.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.google.firebase.auth.FirebaseUser
import com.nagi.dosecircle.DoseCircleApp
import com.nagi.dosecircle.auth.AuthState
import com.nagi.dosecircle.data.repository.AuthRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Sealed interface representing the state of a password reset email request.
 */
sealed interface PasswordResetState {
    data object Idle : PasswordResetState
    data object Loading : PasswordResetState
    data object Sent : PasswordResetState
    data class Error(val message: String) : PasswordResetState
}

/**
 * ViewModel managing authentication state and Google Sign-In logic.
 *
 * It does not retain any Activity, View, or UI Context, keeping the ViewModel leak-free.
 * Firebase interaction is delegated entirely to [AuthRepository].
 */
class AuthViewModel(
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _authState = MutableStateFlow<AuthState>(AuthState.Idle)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    val currentUser: FirebaseUser?
        get() = authRepository.currentUser

    private val _passwordResetState = MutableStateFlow<PasswordResetState>(PasswordResetState.Idle)
    val passwordResetState: StateFlow<PasswordResetState> = _passwordResetState.asStateFlow()

    private var authJob: Job? = null
    private var passwordResetJob: Job? = null

    /**
     * Authenticates with Firebase using a Google ID token retrieved via Credential Manager.
     *
     * Duplicate sign-in attempts while a request is active are safely ignored.
     */
    fun signInWithGoogle(idToken: String) {
        if (authJob?.isActive == true || _authState.value is AuthState.Loading) {
            return
        }

        authJob = viewModelScope.launch {
            _authState.value = AuthState.Loading
            try {
                val result = authRepository.signInWithGoogle(idToken)
                result.fold(
                    onSuccess = { user ->
                        _authState.value = AuthState.Success(user)
                    },
                    onFailure = { error ->
                        _authState.value = AuthState.Error(
                            error.message ?: "Authentication failed. Please try again."
                        )
                    }
                )
            } catch (e: CancellationException) {
                _authState.value = AuthState.Idle
                throw e
            } catch (e: Exception) {
                _authState.value = AuthState.Error(
                    e.message ?: "An unexpected error occurred. Please try again."
                )
            }
        }
    }

    /**
     * Authenticates an existing user with Firebase using email and password.
     *
     * Duplicate authentication attempts while an operation is active are safely ignored.
     */
    fun login(email: String, password: String) {
        if (authJob?.isActive == true || _authState.value is AuthState.Loading) {
            return
        }

        authJob = viewModelScope.launch {
            _authState.value = AuthState.Loading
            try {
                val result = authRepository.login(email, password)
                result.fold(
                    onSuccess = { user ->
                        _authState.value = AuthState.Success(user)
                    },
                    onFailure = { error ->
                        _authState.value = AuthState.Error(
                            error.message ?: "Login failed. Please try again."
                        )
                    }
                )
            } catch (e: CancellationException) {
                _authState.value = AuthState.Idle
                throw e
            } catch (e: Exception) {
                _authState.value = AuthState.Error(
                    e.message ?: "An unexpected error occurred. Please try again."
                )
            }
        }
    }

    /**
     * Registers a new user with Firebase using email, password, and optional display name.
     *
     * Duplicate authentication attempts while an operation is active are safely ignored.
     */
    fun signUp(email: String, password: String, displayName: String = "") {
        if (authJob?.isActive == true || _authState.value is AuthState.Loading) {
            return
        }

        authJob = viewModelScope.launch {
            _authState.value = AuthState.Loading
            try {
                val result = authRepository.signUp(email, password, displayName)
                result.fold(
                    onSuccess = { user ->
                        _authState.value = AuthState.Success(user)
                    },
                    onFailure = { error ->
                        _authState.value = AuthState.Error(
                            error.message ?: "Registration failed. Please try again."
                        )
                    }
                )
            } catch (e: CancellationException) {
                _authState.value = AuthState.Idle
                throw e
            } catch (e: Exception) {
                _authState.value = AuthState.Error(
                    e.message ?: "An unexpected error occurred. Please try again."
                )
            }
        }
    }

    /**
     * Sends a password reset email using Firebase Authentication.
     *
     * Does not affect [authState] or [AuthState.Success] to prevent unintended navigation.
     */
    fun sendPasswordReset(email: String) {
        if (passwordResetJob?.isActive == true || _passwordResetState.value is PasswordResetState.Loading) {
            return
        }

        passwordResetJob = viewModelScope.launch {
            _passwordResetState.value = PasswordResetState.Loading
            try {
                val result = authRepository.sendPasswordReset(email)
                result.fold(
                    onSuccess = {
                        _passwordResetState.value = PasswordResetState.Sent
                    },
                    onFailure = { error ->
                        _passwordResetState.value = PasswordResetState.Error(
                            error.message ?: "Failed to send password reset email. Please try again."
                        )
                    }
                )
            } catch (e: CancellationException) {
                _passwordResetState.value = PasswordResetState.Idle
                throw e
            } catch (e: Exception) {
                _passwordResetState.value = PasswordResetState.Error(
                    e.message ?: "An unexpected error occurred. Please try again."
                )
            }
        }
    }

    /**
     * Resets authentication state back to [AuthState.Idle].
     */
    fun resetState() {
        _authState.value = AuthState.Idle
    }

    /**
     * Resets password reset state back to [PasswordResetState.Idle].
     */
    fun resetPasswordResetState() {
        _passwordResetState.value = PasswordResetState.Idle
    }

    companion object {
        fun provideFactory(app: DoseCircleApp): ViewModelProvider.Factory =
            provideFactory(app.authRepository)

        fun provideFactory(authRepository: AuthRepository): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(AuthViewModel::class.java)) {
                        return AuthViewModel(authRepository) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                }
            }
    }
}
