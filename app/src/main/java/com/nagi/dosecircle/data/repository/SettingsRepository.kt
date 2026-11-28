package com.nagi.dosecircle.data.repository

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsRepository(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    )

    private val _seniorMode = MutableStateFlow(
        prefs.getBoolean(KEY_SENIOR_MODE, false)
    )
    val seniorMode: StateFlow<Boolean> = _seniorMode.asStateFlow()

    fun setSeniorMode(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_SENIOR_MODE, enabled).apply()
        _seniorMode.value = enabled
    }

    companion object {
        private const val PREFS_NAME = "dosecircle_settings_prefs"
        private const val KEY_SENIOR_MODE = "key_senior_mode"
    }
}
