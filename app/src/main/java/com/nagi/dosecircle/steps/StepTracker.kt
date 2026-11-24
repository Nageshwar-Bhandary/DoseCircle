package com.nagi.dosecircle.steps

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.nagi.dosecircle.data.repository.ActivityRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.format.DateTimeFormatter

class StepTracker(
    private val context: Context,
    private val activityRepository: ActivityRepository
) : SensorEventListener {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stepMutex = Mutex()

    @Volatile
    private var currentTrackingPersonId: Long? = null

    @Volatile
    private var isRegistered = false

    /**
     * Starts foreground tracking for [personId].
     * Registers the [Sensor.TYPE_STEP_COUNTER] listener if not already registered,
     * provided sensor and permissions are available.
     */
    fun startForegroundTracking(personId: Long) {
        currentTrackingPersonId = personId
        scope.launch {
            checkAndResetErroneousCount(personId)
        }
        registerSensorListener()
    }

    /**
     * Stops foreground tracking and unregisters the sensor listener.
     */
    fun stopForegroundTracking() {
        unregisterSensorListener()
    }

    /**
     * Called when ACTIVITY_RECOGNITION permission is granted at runtime.
     * Starts or re-binds tracking if a person is active.
     */
    fun onPermissionGranted(personId: Long) {
        currentTrackingPersonId = personId
        scope.launch {
            checkAndResetErroneousCount(personId)
        }
        registerSensorListener()
    }

    private fun hasPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACTIVITY_RECOGNITION
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
    }

    private fun registerSensorListener() {
        if (isRegistered) return
        val sm = sensorManager ?: return
        if (!hasPermission()) return

        val stepSensor = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return

        val success = sm.registerListener(
            this,
            stepSensor,
            SensorManager.SENSOR_DELAY_UI
        )
        if (success) {
            isRegistered = true
        }
    }

    private fun unregisterSensorListener() {
        if (!isRegistered) return
        val sm = sensorManager ?: return
        try {
            sm.unregisterListener(this)
        } catch (_: Exception) {
            // Safe fallback
        } finally {
            isRegistered = false
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_STEP_COUNTER && event.values.isNotEmpty()) {
            val rawValue = event.values[0]
            val personId = currentTrackingPersonId ?: return
            scope.launch {
                processSensorReading(personId, rawValue.toInt())
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No-op
    }

    /**
     * One-shot step reading for background worker (StepSyncWorker).
     * If the foreground listener is already active, this method simply checks
     * or flushes if available, otherwise registers a temporary listener.
     */
    suspend fun trackStepsOneShot(personId: Long) {
        val sm = sensorManager ?: return
        if (!hasPermission()) return
        val stepSensor = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return

        val deferred = CompletableDeferred<Float?>()
        val tempListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                if (event?.sensor?.type == Sensor.TYPE_STEP_COUNTER && event.values.isNotEmpty()) {
                    deferred.complete(event.values[0])
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                // No-op
            }
        }

        val registered = sm.registerListener(
            tempListener,
            stepSensor,
            SensorManager.SENSOR_DELAY_NORMAL
        )

        if (!registered) return

        try {
            val reading = withTimeoutOrNull(3000L) {
                deferred.await()
            }
            if (reading != null) {
                processSensorReading(personId, reading.toInt())
            }
        } catch (_: Exception) {
            // Ignore
        } finally {
            try {
                sm.unregisterListener(tempListener)
            } catch (_: Exception) {
                // Ignore
            }
        }
    }

    /**
     * One-time cleanup for erroneous test count (e.g. 31,854 or 38,557 steps recorded during baseline bugs)
     * for the given personId on today's record, and synchronizes the baseline in SharedPreferences.
     */
    private suspend fun checkAndResetErroneousCount(personId: Long) {
        stepMutex.withLock {
            try {
                val fixKey = KEY_ERRONEOUS_COUNT_FIXED_PREFIX + personId
                val alreadyFixed = prefs.getBoolean(fixKey, false)
                if (!alreadyFixed) {
                    val today = LocalDate.now().format(dateFormatter)
                    val activity = activityRepository.getOrInsertTodayActivity(personId, today)
                    if (activity.steps == 31854 || activity.steps == 38557) {
                        activityRepository.updateSteps(activity.id, 0)
                    }
                    // Synchronize baseline: remove stale baseline so next reading establishes a clean baseline
                    val prefKey = KEY_LAST_SENSOR_TOTAL_PREFIX + personId
                    prefs.edit().remove(prefKey).putBoolean(fixKey, true).apply()
                }
            } catch (_: Exception) {
                // Ignore
            }
        }
    }

    /**
     * Processes a sensor total reading for [personId].
     * Thread-safe via [stepMutex] to prevent WorkManager and foreground
     * listener from double-counting.
     */
    suspend fun processSensorReading(personId: Long, newTotal: Int) {
        stepMutex.withLock {
            try {
                // First, check if erroneous count cleanup needs to run
                val fixKey = KEY_ERRONEOUS_COUNT_FIXED_PREFIX + personId
                if (!prefs.getBoolean(fixKey, false)) {
                    val today = LocalDate.now().format(dateFormatter)
                    val activity = activityRepository.getOrInsertTodayActivity(personId, today)
                    if (activity.steps == 31854 || activity.steps == 38557) {
                        activityRepository.updateSteps(activity.id, 0)
                    }
                    val prefKey = KEY_LAST_SENSOR_TOTAL_PREFIX + personId
                    prefs.edit().remove(prefKey).putBoolean(fixKey, true).apply()
                }

                val prefKey = KEY_LAST_SENSOR_TOTAL_PREFIX + personId

                // Check if a baseline has ever been recorded for this person
                if (!prefs.contains(prefKey)) {
                    // First sensor reading ever for this person:
                    // Save the current sensor reading as baseline and add 0 steps to DailyActivity
                    prefs.edit().putInt(prefKey, newTotal).apply()
                    return
                }

                val lastTotal = prefs.getInt(prefKey, -1)
                if (lastTotal < 0) {
                    // Invalid/corrupted baseline: re-initialize baseline and add 0 steps
                    prefs.edit().putInt(prefKey, newTotal).apply()
                    return
                }

                // If the reading hasn't changed, nothing to add and nothing to update
                if (newTotal == lastTotal) {
                    return
                }

                val stepsToAdd = if (newTotal > lastTotal) {
                    // Subsequent reading: add the difference between newTotal and saved lastTotal
                    newTotal - lastTotal
                } else {
                    // Sensor counter reset/reboot: add 0 steps and save newTotal as the new baseline
                    0
                }

                // Save the new sensor total
                prefs.edit().putInt(prefKey, newTotal).apply()

                val today = LocalDate.now().format(dateFormatter)
                val todayActivity = activityRepository.getOrInsertTodayActivity(personId, today)
                if (stepsToAdd > 0) {
                    activityRepository.updateSteps(todayActivity.id, todayActivity.steps + stepsToAdd)
                }
            } catch (_: Exception) {
                // Never crash
            }
        }
    }

    companion object {
        private const val PREFS_NAME = "step_tracker_prefs"
        private const val KEY_LAST_SENSOR_TOTAL_PREFIX = "last_sensor_total_"
        private const val KEY_ERRONEOUS_COUNT_FIXED_PREFIX = "erroneous_count_fixed_v3_"
    }
}
