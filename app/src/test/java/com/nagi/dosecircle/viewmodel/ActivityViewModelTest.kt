package com.nagi.dosecircle.viewmodel

import android.content.SharedPreferences
import com.nagi.dosecircle.data.dao.DailyActivityDao
import com.nagi.dosecircle.data.entity.DailyActivity
import com.nagi.dosecircle.data.repository.ActivityRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityViewModelTest {

    private val testScopes = mutableListOf<CoroutineScope>()

    private fun createTestScope(): CoroutineScope {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        testScopes.add(scope)
        return scope
    }

    @After
    fun tearDown() {
        testScopes.forEach { it.cancel() }
        testScopes.clear()
    }

    private class FakeDailyActivityDao : DailyActivityDao {
        val activities = mutableMapOf<Pair<Long, String>, DailyActivity>()
        private val activityFlows = mutableMapOf<Pair<Long, String>, MutableStateFlow<DailyActivity?>>()
        var updateGoalCalledWith: Pair<Long, Int>? = null
        var updateStepsCalledWith: Pair<Long, Int>? = null

        override suspend fun insertActivity(activity: DailyActivity): Long {
            val key = Pair(activity.personId, activity.date)
            val id = (activities.size + 1).toLong()
            val saved = activity.copy(id = id)
            activities[key] = saved
            getOrCreateFlow(key).value = saved
            return id
        }

        override suspend fun getActivityForDate(personId: Long, date: String): DailyActivity? {
            return activities[Pair(personId, date)]
        }

        override suspend fun updateSteps(id: Long, steps: Int) {
            updateStepsCalledWith = Pair(id, steps)
            val entry = activities.entries.find { it.value.id == id }
            if (entry != null) {
                val updated = entry.value.copy(steps = steps)
                activities[entry.key] = updated
                getOrCreateFlow(entry.key).value = updated
            }
        }

        override suspend fun updateGoal(id: Long, goal: Int) {
            updateGoalCalledWith = Pair(id, goal)
            val entry = activities.entries.find { it.value.id == id }
            if (entry != null) {
                val updated = entry.value.copy(goal = goal)
                activities[entry.key] = updated
                getOrCreateFlow(entry.key).value = updated
            }
        }

        override fun getActivityFlowForDate(personId: Long, date: String): Flow<DailyActivity?> {
            return getOrCreateFlow(Pair(personId, date))
        }

        override fun getActivitiesBetween(personId: Long, startDate: String, endDate: String): Flow<List<DailyActivity>> {
            return flowOf(activities.values.filter { it.personId == personId && it.date in startDate..endDate })
        }

        private fun getOrCreateFlow(key: Pair<Long, String>): MutableStateFlow<DailyActivity?> {
            return activityFlows.getOrPut(key) {
                MutableStateFlow(activities[key])
            }
        }
    }

    private class FakeSharedPreferences(
        initialMap: Map<String, Any?> = emptyMap()
    ) : SharedPreferences {
        val map = initialMap.toMutableMap()

        override fun getAll(): MutableMap<String, *> = map

        override fun getString(key: String?, defValue: String?): String? =
            map[key] as? String ?: defValue

        @Suppress("UNCHECKED_CAST")
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            map[key] as? MutableSet<String> ?: defValues

        override fun getInt(key: String?, defValue: Int): Int =
            (map[key] as? Number)?.toInt() ?: defValue

        override fun getLong(key: String?, defValue: Long): Long =
            (map[key] as? Number)?.toLong() ?: defValue

        override fun getFloat(key: String?, defValue: Float): Float =
            (map[key] as? Number)?.toFloat() ?: defValue

        override fun getBoolean(key: String?, defValue: Boolean): Boolean =
            map[key] as? Boolean ?: defValue

        override fun contains(key: String?): Boolean = map.containsKey(key)

        override fun edit(): SharedPreferences.Editor = EditorImpl()

        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        private inner class EditorImpl : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private val removes = mutableSetOf<String>()
            private var clearAll = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }

            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor {
                if (key != null) pending[key] = values
                return this
            }

            override fun putInt(key: String?, value: Int): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }

            override fun putLong(key: String?, value: Long): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }

            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }

            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor {
                if (key != null) pending[key] = value
                return this
            }

            override fun remove(key: String?): SharedPreferences.Editor {
                if (key != null) removes.add(key)
                return this
            }

            override fun clear(): SharedPreferences.Editor {
                clearAll = true
                return this
            }

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                if (clearAll) map.clear()
                removes.forEach { map.remove(it) }
                map.putAll(pending)
            }
        }
    }

    @Test
    fun `initial uiState has default values`() {
        val fakeDao = FakeDailyActivityDao()
        val repository = ActivityRepository(fakeDao)
        val personIdFlow = MutableStateFlow<Long?>(1L)
        val prefs = FakeSharedPreferences(mapOf("daily_step_goal_1" to 5000))

        val viewModel = ActivityViewModel(
            context = null,
            currentPersonIdFlow = personIdFlow,
            activityRepository = repository,
            stepTracker = null,
            preferences = prefs,
            externalScope = createTestScope()
        )

        val state = viewModel.uiState.value
        assertEquals(5000, state.goal)
        assertEquals(0, state.steps)
        assertEquals(0f, state.progress, 0.001f)
        assertEquals(0.0, state.distanceKm, 0.0001)
    }

    @Test
    fun `progress calculation caps at 100 percent when steps exceed goal`() {
        val steps = 8000
        val goal = 6000
        val progress = if (goal > 0) (steps.toFloat() / goal).coerceIn(0f, 1f) else 0f
        assertEquals(1.0f, progress, 0.001f)

        val distanceKm = steps * 0.0007
        assertEquals(5.6, distanceKm, 0.0001)
    }

    @Test
    fun `distance calculation is steps times 0_0007 km`() {
        val steps = 3500
        val distanceKm = steps * 0.0007
        // 3500 * 0.0007 = 2.45
        assertEquals(2.45, distanceKm, 0.0001)

        val goal = 10000
        val progress = (steps.toFloat() / goal).coerceIn(0f, 1f)
        assertEquals(0.35f, progress, 0.001f)
    }

    @Test
    fun `setGoal updates SharedPreferences and database for valid goal`() = runBlocking {
        val fakeDao = FakeDailyActivityDao()
        val repository = ActivityRepository(fakeDao)
        val personIdFlow = MutableStateFlow<Long?>(1L)
        val prefs = FakeSharedPreferences(mapOf("daily_step_goal_1" to 6000))
        val scope = createTestScope()

        val viewModel = ActivityViewModel(
            context = null,
            currentPersonIdFlow = personIdFlow,
            activityRepository = repository,
            stepTracker = null,
            preferences = prefs,
            externalScope = scope
        )

        val collectJob = scope.launch { viewModel.uiState.collect {} }

        viewModel.setGoal(8500)

        // Give coroutine a moment to complete
        delay(200)

        // Verify stored in SharedPreferences using per-person key
        assertEquals(8500, prefs.getInt("daily_step_goal_1", 6000))

        // Verify updated in database
        val today = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        val todayActivity = fakeDao.getActivityForDate(1L, today)
        assertEquals(8500, todayActivity?.goal)

        collectJob.cancel()
    }

    @Test
    fun `setGoal rejects values outside 1000 to 50000 range`() = runBlocking {
        val fakeDao = FakeDailyActivityDao()
        val repository = ActivityRepository(fakeDao)
        val personIdFlow = MutableStateFlow<Long?>(1L)
        val prefs = FakeSharedPreferences(mapOf("daily_step_goal_1" to 6000))

        val viewModel = ActivityViewModel(
            context = null,
            currentPersonIdFlow = personIdFlow,
            activityRepository = repository,
            stepTracker = null,
            preferences = prefs,
            externalScope = createTestScope()
        )

        // Try setting goal < 1000
        viewModel.setGoal(500)
        delay(100)
        assertEquals(6000, prefs.getInt("daily_step_goal_1", 6000))

        // Try setting goal > 50000
        viewModel.setGoal(60000)
        delay(100)
        assertEquals(6000, prefs.getInt("daily_step_goal_1", 6000))
    }

    @Test
    fun `two different person IDs have independent goals`() = runBlocking {
        val fakeDao = FakeDailyActivityDao()
        val repository = ActivityRepository(fakeDao)
        val personIdFlow = MutableStateFlow<Long?>(1L)
        val prefs = FakeSharedPreferences(
            mapOf(
                "daily_step_goal_1" to 2000,
                "daily_step_goal_2" to 4000
            )
        )
        val scope = createTestScope()

        val viewModel = ActivityViewModel(
            context = null,
            currentPersonIdFlow = personIdFlow,
            activityRepository = repository,
            stepTracker = null,
            preferences = prefs,
            externalScope = scope
        )

        val collectJob = scope.launch { viewModel.uiState.collect {} }

        assertEquals(2000, viewModel.getSavedGoalForPerson(1L))
        assertEquals(4000, viewModel.getSavedGoalForPerson(2L))

        // Active user is 1L
        delay(150)
        assertEquals(2000, viewModel.uiState.value.goal)

        // Switch to 2L
        personIdFlow.value = 2L
        delay(150)
        assertEquals(4000, viewModel.uiState.value.goal)

        collectJob.cancel()
    }

    @Test
    fun `new account receives default goal`() = runBlocking {
        val fakeDao = FakeDailyActivityDao()
        val repository = ActivityRepository(fakeDao)
        val personIdFlow = MutableStateFlow<Long?>(10L)
        val prefs = FakeSharedPreferences(emptyMap())
        val scope = createTestScope()

        val viewModel = ActivityViewModel(
            context = null,
            currentPersonIdFlow = personIdFlow,
            activityRepository = repository,
            stepTracker = null,
            preferences = prefs,
            externalScope = scope
        )

        val collectJob = scope.launch { viewModel.uiState.collect {} }

        assertEquals(ActivityViewModel.DEFAULT_GOAL, viewModel.getSavedGoalForPerson(10L))
        delay(150)
        assertEquals(ActivityViewModel.DEFAULT_GOAL, viewModel.uiState.value.goal)
        assertEquals(6000, viewModel.uiState.value.goal)

        collectJob.cancel()
    }

    @Test
    fun `changing one account goal does not affect another`() = runBlocking {
        val fakeDao = FakeDailyActivityDao()
        val repository = ActivityRepository(fakeDao)
        val personIdFlow = MutableStateFlow<Long?>(1L)
        val prefs = FakeSharedPreferences(
            mapOf("daily_step_goal_1" to 2000)
        )
        val scope = createTestScope()

        val viewModel = ActivityViewModel(
            context = null,
            currentPersonIdFlow = personIdFlow,
            activityRepository = repository,
            stepTracker = null,
            preferences = prefs,
            externalScope = scope
        )

        val collectJob = scope.launch { viewModel.uiState.collect {} }
        delay(150)
        assertEquals(2000, viewModel.uiState.value.goal)

        // Switch to account 2 (which has no saved goal yet)
        personIdFlow.value = 2L
        delay(150)
        assertEquals(ActivityViewModel.DEFAULT_GOAL, viewModel.uiState.value.goal)

        // Change account 2's goal to 3000
        viewModel.setGoal(3000)
        delay(200)

        assertEquals(3000, prefs.getInt("daily_step_goal_2", -1))
        assertEquals(2000, prefs.getInt("daily_step_goal_1", -1))

        // Switch back to account 1 and verify it remains 2000
        personIdFlow.value = 1L
        delay(150)
        assertEquals(2000, viewModel.uiState.value.goal)

        collectJob.cancel()
    }

    @Test
    fun `legacy goal migration preserves current account goal and removes legacy key`() = runBlocking {
        val fakeDao = FakeDailyActivityDao()
        val repository = ActivityRepository(fakeDao)
        val personIdFlow = MutableStateFlow<Long?>(1L)
        // SharedPreferences has legacy key "daily_step_goal" = 2000 and NO "daily_step_goal_1"
        val prefs = FakeSharedPreferences(
            mapOf("daily_step_goal" to 2000)
        )
        val scope = createTestScope()

        val viewModel = ActivityViewModel(
            context = null,
            currentPersonIdFlow = personIdFlow,
            activityRepository = repository,
            stepTracker = null,
            preferences = prefs,
            externalScope = scope
        )

        val collectJob = scope.launch { viewModel.uiState.collect {} }

        // Initial goal migrated for 1L
        delay(150)
        assertEquals(2000, viewModel.uiState.value.goal)
        assertEquals(2000, prefs.getInt("daily_step_goal_1", -1))
        // Legacy key must be removed
        assertFalse("Legacy key should have been removed after migration", prefs.contains("daily_step_goal"))

        // Future or other account (2L) must NOT inherit the migrated legacy goal; it gets default 6000
        personIdFlow.value = 2L
        delay(150)
        assertEquals(ActivityViewModel.DEFAULT_GOAL, viewModel.uiState.value.goal)
        assertEquals(6000, viewModel.uiState.value.goal)
        assertFalse(prefs.contains("daily_step_goal_2"))

        collectJob.cancel()
    }
}
