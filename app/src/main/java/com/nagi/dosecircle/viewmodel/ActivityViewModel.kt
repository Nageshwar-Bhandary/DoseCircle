package com.nagi.dosecircle.viewmodel

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.nagi.dosecircle.DoseCircleApp
import com.nagi.dosecircle.data.repository.ActivityRepository
import com.nagi.dosecircle.steps.StepTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter

data class ActivityUiState(
    val steps: Int = 0,
    val goal: Int = 6000,
    val progress: Float = 0f,
    val distanceKm: Double = 0.0,
    val isSensorAvailable: Boolean = true,
    val isLoading: Boolean = false
)

@OptIn(ExperimentalCoroutinesApi::class)
class ActivityViewModel(
    private val context: Context? = null,
    private val currentPersonIdFlow: StateFlow<Long?>,
    private val activityRepository: ActivityRepository,
    private val stepTracker: StepTracker? = null,
    private val preferences: SharedPreferences? = null,
    private val externalScope: CoroutineScope? = null
) : ViewModel() {

    private val scope: CoroutineScope
        get() = externalScope ?: viewModelScope

    private val prefs: SharedPreferences = preferences
        ?: context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        ?: error("Either Context or SharedPreferences must be provided")

    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    private val isSensorAvailable = stepTracker?.hasStepSensor() ?: true

    private fun getPerPersonGoalKey(personId: Long): String = "${KEY_STEP_GOAL_PREFIX}$personId"

    /**
     * Retrieves the saved goal for [personId].
     * If this user has no per-person preference yet, checks for a legacy shared preference
     * (`daily_step_goal`), safely migrates it to this user's key only, removes the legacy key,
     * and returns the goal. Otherwise, returns [DEFAULT_GOAL].
     */
    fun getSavedGoalForPerson(personId: Long): Int {
        val personKey = getPerPersonGoalKey(personId)
        if (prefs.contains(personKey)) {
            return prefs.getInt(personKey, DEFAULT_GOAL)
        }
        if (prefs.contains(KEY_LEGACY_STEP_GOAL)) {
            val legacyGoal = prefs.getInt(KEY_LEGACY_STEP_GOAL, DEFAULT_GOAL)
            prefs.edit()
                .putInt(personKey, legacyGoal)
                .remove(KEY_LEGACY_STEP_GOAL)
                .apply()
            return legacyGoal
        }
        return DEFAULT_GOAL
    }

    val uiState: StateFlow<ActivityUiState> = currentPersonIdFlow
        .filterNotNull()
        .flatMapLatest { personId ->
            val today = LocalDate.now().format(dateFormatter)
            val savedGoal = getSavedGoalForPerson(personId)

            // Ensure today's row exists in the database
            activityRepository.getOrInsertTodayActivity(personId, today, savedGoal)

            activityRepository.getActivityFlowForDate(personId, today).map { activity ->
                val currentSavedGoal = getSavedGoalForPerson(personId)
                val steps = activity?.steps ?: 0
                val goal = activity?.goal ?: currentSavedGoal
                val progress = if (goal > 0) {
                    (steps.toFloat() / goal).coerceIn(0f, 1f)
                } else {
                    0f
                }
                val distanceKm = steps * 0.0007

                ActivityUiState(
                    steps = steps,
                    goal = goal,
                    progress = progress,
                    distanceKm = distanceKm,
                    isSensorAvailable = isSensorAvailable,
                    isLoading = false
                )
            }
        }
        .stateIn(
            scope = scope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ActivityUiState(
                goal = currentPersonIdFlow.value?.let { getSavedGoalForPerson(it) } ?: DEFAULT_GOAL,
                isSensorAvailable = isSensorAvailable
            )
        )

    fun refreshSteps() {
        val personId = currentPersonIdFlow.value ?: return
        val today = LocalDate.now().format(dateFormatter)
        val savedGoal = getSavedGoalForPerson(personId)
        scope.launch {
            activityRepository.getOrInsertTodayActivity(personId, today, savedGoal)
            stepTracker?.startForegroundTracking(personId)
            stepTracker?.trackStepsOneShot(personId)
        }
    }

    fun setGoal(newGoal: Int) {
        if (newGoal < 1000 || newGoal > 50000) return
        val personId = currentPersonIdFlow.value ?: return

        // Store in SharedPreferences using per-person key, removing any legacy shared key
        val personKey = getPerPersonGoalKey(personId)
        prefs.edit()
            .putInt(personKey, newGoal)
            .remove(KEY_LEGACY_STEP_GOAL)
            .apply()

        val today = LocalDate.now().format(dateFormatter)

        scope.launch {
            val activity = activityRepository.getOrInsertTodayActivity(personId, today, newGoal)
            activityRepository.updateGoal(activity.id, newGoal)
        }
    }

    companion object {
        private const val PREFS_NAME = "activity_prefs"
        private const val KEY_LEGACY_STEP_GOAL = "daily_step_goal"
        private const val KEY_STEP_GOAL_PREFIX = "daily_step_goal_"
        const val DEFAULT_GOAL = 6000

        fun provideFactory(app: DoseCircleApp): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(ActivityViewModel::class.java)) {
                        return ActivityViewModel(
                            context = app,
                            currentPersonIdFlow = app.sessionManager.currentPersonId,
                            activityRepository = app.activityRepository,
                            stepTracker = app.stepTracker
                        ) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                }
            }
    }
}
