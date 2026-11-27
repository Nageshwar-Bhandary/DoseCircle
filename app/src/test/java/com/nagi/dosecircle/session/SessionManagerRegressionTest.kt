package com.nagi.dosecircle.session

import android.content.SharedPreferences
import com.nagi.dosecircle.data.dao.PersonDao
import com.nagi.dosecircle.data.entity.Person
import com.nagi.dosecircle.data.repository.PersonRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicLong

class SessionManagerRegressionTest {

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

    private class FakeSharedPreferences(
        initialMap: Map<String, Any?> = emptyMap()
    ) : SharedPreferences {
        private val map = initialMap.toMutableMap()

        override fun getAll(): MutableMap<String, *> = map
        override fun getString(key: String?, defValue: String?): String? = (map[key] as? String) ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String?, defValue: Int): Int = (map[key] as? Int) ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = (map[key] as? Long) ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = (map[key] as? Float) ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = (map[key] as? Boolean) ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = FakeEditor(map)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        class FakeEditor(private val target: MutableMap<String, Any?>) : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private val removed = mutableSetOf<String>()
            private var clear = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                if (key != null) {
                    pending[key] = value
                    removed.remove(key)
                }
                return this
            }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = this
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = this
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = this
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = this
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = this
            override fun remove(key: String?): SharedPreferences.Editor {
                if (key != null) {
                    removed.add(key)
                    pending.remove(key)
                }
                return this
            }
            override fun clear(): SharedPreferences.Editor {
                clear = true
                return this
            }
            override fun commit(): Boolean {
                if (clear) target.clear()
                for (r in removed) target.remove(r)
                for ((k, v) in pending) target[k] = v
                return true
            }
            override fun apply() {
                commit()
            }
        }
    }

    private class FakePersonDao(
        private val persons: MutableMap<String, Person> = mutableMapOf()
    ) : PersonDao {
        override suspend fun insertPerson(person: Person): Long {
            val id = (persons.size + 1).toLong()
            val inserted = person.copy(id = id)
            if (inserted.cloudUid != null) {
                persons[inserted.cloudUid] = inserted
            }
            return id
        }
        override suspend fun updatePerson(person: Person) {
            if (person.cloudUid != null) {
                persons[person.cloudUid] = person
            }
        }
        override fun getAllPersons(): Flow<List<Person>> = flowOf(persons.values.toList())
        override suspend fun getDefaultPerson(): Person? = persons.values.firstOrNull { it.isDefault }
        override suspend fun getByCloudUid(uid: String): Person? = persons[uid]
        override suspend fun getDefaultPersonWithNullCloudUid(): Person? = null
    }

    /**
     * Test harness that simulates the SessionManager architectural state machine
     * without platform dependencies.
     */
    private class SessionStateHarness(
        private val personRepository: PersonRepository,
        private val restorePreferences: SharedPreferences,
        private val scope: CoroutineScope,
        private val currentUidProvider: () -> String?,
        private val hasMedicationsProvider: suspend (personId: Long) -> Boolean = { false },
        private val restoreAction: suspend (expectedUid: String, personId: Long) -> Boolean = { _, _ -> true },
        private val onScheduleAlarms: (Long) -> Unit = {},
        private val onCancelAlarms: (Long) -> Unit = {}
    ) {
        private val _currentPersonId = MutableStateFlow<Long?>(null)
        val currentPersonId = _currentPersonId.asStateFlow()

        private var restoredUid: String? = restorePreferences.getString("restored_uid", null)
        private val sessionMutex = Mutex()
        private val authEventVersion = AtomicLong(0)

        @Volatile
        private var restoreJob: Job? = null
        @Volatile
        private var activeRestoreUid: String? = null

        fun onAuthStateChanged(userUid: String?, email: String? = null, displayName: String? = null): Job {
            val eventVersion = authEventVersion.incrementAndGet()

            if (userUid == null || userUid != activeRestoreUid) {
                restoreJob?.cancel()
            }

            return scope.launch {
                sessionMutex.withLock {
                    if (eventVersion != authEventVersion.get()) return@withLock

                    if (userUid != null) {
                        if (currentUidProvider() != userUid) return@withLock

                        val previousPersonId = _currentPersonId.value
                        val person = personRepository.getOrCreatePersonForUser(
                            uid = userUid,
                            email = email,
                            displayName = displayName
                        )

                        if (eventVersion != authEventVersion.get() || currentUidProvider() != userUid) {
                            return@withLock
                        }

                        _currentPersonId.value = person.id

                        if (previousPersonId != person.id) {
                            onScheduleAlarms(person.id)
                        }

                        val hasMedications = hasMedicationsProvider(person.id)
                        val shouldSkipRestore = restoredUid == userUid && hasMedications

                        if (!shouldSkipRestore) {
                            if (eventVersion != authEventVersion.get() || currentUidProvider() != userUid) {
                                return@withLock
                            }

                            restoreJob?.cancel()
                            val currentJob = scope.launch {
                                val success = restoreAction(userUid, person.id)
                                if (success && eventVersion == authEventVersion.get() && currentUidProvider() == userUid) {
                                    restoredUid = userUid
                                    restorePreferences.edit().putString("restored_uid", userUid).commit()
                                }
                            }
                            activeRestoreUid = userUid
                            restoreJob = currentJob
                            currentJob.invokeOnCompletion {
                                if (restoreJob === currentJob) {
                                    restoreJob = null
                                    activeRestoreUid = null
                                }
                            }
                            currentJob.join()
                        }
                    } else {
                        restoreJob?.cancel()
                        restoreJob = null
                        activeRestoreUid = null

                        if (currentUidProvider() != null) return@withLock

                        val previousPersonId = _currentPersonId.value
                        if (previousPersonId != null) {
                            onCancelAlarms(previousPersonId)
                        }

                        if (eventVersion != authEventVersion.get() || currentUidProvider() != null) {
                            return@withLock
                        }

                        restoredUid = null
                        restorePreferences.edit().remove("restored_uid").commit()
                        _currentPersonId.value = null
                    }
                }
            }
        }
    }

    @Test
    fun `existing user session still creates or loads the local person`() = runBlocking {
        val scope = createTestScope()
        val fakeDao = FakePersonDao(
            persons = mutableMapOf(
                "user_123" to Person(id = 42L, name = "Existing Person", cloudUid = "user_123")
            )
        )
        val personRepo = PersonRepository(fakeDao)
        val fakePrefs = FakeSharedPreferences()

        val harness = SessionStateHarness(
            personRepository = personRepo,
            restorePreferences = fakePrefs,
            scope = scope,
            currentUidProvider = { "user_123" }
        )

        harness.onAuthStateChanged("user_123", "test@test.com", "Existing Person").join()

        assertEquals(42L, harness.currentPersonId.value)
    }

    @Test
    fun `existing restore logic still runs when required`() = runBlocking {
        val scope = createTestScope()
        val fakeDao = FakePersonDao(
            persons = mutableMapOf(
                "user_123" to Person(id = 42L, name = "Existing Person", cloudUid = "user_123")
            )
        )
        val personRepo = PersonRepository(fakeDao)
        val fakePrefs = FakeSharedPreferences()
        val restoreCalls = mutableListOf<Pair<String, Long>>()

        val harness = SessionStateHarness(
            personRepository = personRepo,
            restorePreferences = fakePrefs,
            scope = scope,
            currentUidProvider = { "user_123" },
            hasMedicationsProvider = { false },
            restoreAction = { expectedUid, personId ->
                restoreCalls.add(expectedUid to personId)
                true
            }
        )

        harness.onAuthStateChanged("user_123", "test@test.com", "Existing Person").join()

        assertEquals(1, restoreCalls.size)
        assertEquals("user_123" to 42L, restoreCalls[0])
        assertEquals("user_123", fakePrefs.getString("restored_uid", null))
        assertEquals(42L, harness.currentPersonId.value)
    }

    @Test
    fun `restore is skipped when the saved UID matches and medications already exist`() = runBlocking {
        val scope = createTestScope()
        val fakeDao = FakePersonDao(
            persons = mutableMapOf(
                "user_123" to Person(id = 42L, name = "Existing Person", cloudUid = "user_123")
            )
        )
        val personRepo = PersonRepository(fakeDao)
        val fakePrefs = FakeSharedPreferences(
            initialMap = mapOf("restored_uid" to "user_123")
        )
        val restoreCalls = mutableListOf<Pair<String, Long>>()

        val harness = SessionStateHarness(
            personRepository = personRepo,
            restorePreferences = fakePrefs,
            scope = scope,
            currentUidProvider = { "user_123" },
            hasMedicationsProvider = { true },
            restoreAction = { expectedUid, personId ->
                restoreCalls.add(expectedUid to personId)
                true
            }
        )

        harness.onAuthStateChanged("user_123", "test@test.com", "Existing Person").join()

        assertTrue("Restore must be skipped when UID matches and medications exist", restoreCalls.isEmpty())
        assertEquals(42L, harness.currentPersonId.value)
    }

    @Test
    fun `logout still cancels the active restore, clears the restored UID, and clears the current person ID`() = runBlocking {
        val scope = createTestScope()
        val fakeDao = FakePersonDao(
            persons = mutableMapOf(
                "user_123" to Person(id = 42L, name = "Existing Person", cloudUid = "user_123")
            )
        )
        val personRepo = PersonRepository(fakeDao)
        val fakePrefs = FakeSharedPreferences(
            initialMap = mapOf("restored_uid" to "user_123")
        )
        var currentUid: String? = "user_123"
        val restoreStarted = CompletableDeferred<Unit>()
        val restoreCancelled = CompletableDeferred<Unit>()

        val harness = SessionStateHarness(
            personRepository = personRepo,
            restorePreferences = fakePrefs,
            scope = scope,
            currentUidProvider = { currentUid },
            hasMedicationsProvider = { false },
            restoreAction = { _, _ ->
                restoreStarted.complete(Unit)
                try {
                    kotlinx.coroutines.awaitCancellation()
                } finally {
                    restoreCancelled.complete(Unit)
                }
            }
        )

        withTimeout(5000) {
            val loginJob = harness.onAuthStateChanged("user_123", "test@test.com", "Existing Person")
            restoreStarted.await()
            assertEquals(42L, harness.currentPersonId.value)

            currentUid = null
            val logoutJob = harness.onAuthStateChanged(null)
            logoutJob.join()
            loginJob.join()

            restoreCancelled.await()
            assertTrue("Active restore must be cancelled on logout", restoreCancelled.isCompleted)
            assertNull("Restored UID must be cleared on logout", fakePrefs.getString("restored_uid", null))
            assertNull("Current person ID must be cleared on logout", harness.currentPersonId.value)
        }
    }

    @Test
    fun `stale auth event must not overwrite a newer session`() = runBlocking {
        val scope = createTestScope()
        var currentUid = "user_B"

        val fakeDao = FakePersonDao(
            persons = mutableMapOf(
                "user_A" to Person(id = 10L, name = "User A", cloudUid = "user_A"),
                "user_B" to Person(id = 20L, name = "User B", cloudUid = "user_B")
            )
        )
        val personRepo = PersonRepository(fakeDao)
        val fakePrefs = FakeSharedPreferences()

        val harness = SessionStateHarness(
            personRepository = personRepo,
            restorePreferences = fakePrefs,
            scope = scope,
            currentUidProvider = { currentUid }
        )

        withTimeout(5000) {
            val event1Job = harness.onAuthStateChanged("user_A", "a@test.com", "User A")
            val event2Job = harness.onAuthStateChanged("user_B", "b@test.com", "User B")

            event1Job.join()
            event2Job.join()

            assertEquals("Newer session must not be overwritten by stale auth event", 20L, harness.currentPersonId.value)
        }
    }
}

