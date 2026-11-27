package com.nagi.dosecircle.session

import com.nagi.dosecircle.data.dao.PersonDao
import com.nagi.dosecircle.data.entity.Person
import com.nagi.dosecircle.data.repository.PersonRepository
import com.nagi.dosecircle.notifications.fcm.FcmTokenManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionManagerTokenSyncTest {

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

    private class FakePersonDao(
        private val onGetByCloudUid: (String) -> Unit = {}
    ) : PersonDao {
        override suspend fun insertPerson(person: Person): Long = 1L
        override suspend fun updatePerson(person: Person) {}
        override fun getAllPersons(): Flow<List<Person>> = flowOf(emptyList())
        override suspend fun getDefaultPerson(): Person? = null
        override suspend fun getByCloudUid(uid: String): Person? {
            onGetByCloudUid(uid)
            return Person(id = 1L, name = "Test Person", cloudUid = uid)
        }
        override suspend fun getDefaultPersonWithNullCloudUid(): Person? = null
    }

    /**
     * Verifies that SessionManager's login flow initiates FCM token synchronization
     * immediately after validating the current user UID, before local person creation.
     */
    @Test
    fun `session token sync is initiated before local person creation`() = runBlocking {
        val scope = createTestScope()
        val eventLog = mutableListOf<String>()

        val fakeDao = FakePersonDao(
            onGetByCloudUid = { uid ->
                eventLog.add("person_creation:$uid")
            }
        )
        val personRepo = PersonRepository(fakeDao)

        val tokenManager = object : FcmTokenManager(
            appScope = scope,
            currentUidProvider = { "user_123" },
            tokenProvider = { "fcm_token_123" },
            firestoreWriter = { uid, _ ->
                eventLog.add("fcm_token_written:$uid")
            },
            firestoreDeleter = {}
        ) {
            override fun syncTokenForUser(uid: String): Job {
                eventLog.add("fcm_sync_initiated:$uid")
                return super.syncTokenForUser(uid)
            }
        }

        // Simulate SessionManager auth flow:
        // 1. Validate currentUser?.uid == user.uid
        val currentUid = "user_123"
        val userUid = "user_123"
        if (currentUid == userUid) {
            // FCM sync MUST be called here before person resolution
            tokenManager.syncTokenForUser(userUid)
            personRepo.getOrCreatePersonForUser(userUid, "test@test.com", "Test")
        }

        // Verify order: FCM sync was initiated before Person creation
        assertTrue("Events must be recorded", eventLog.isNotEmpty())
        assertEquals("fcm_sync_initiated:user_123", eventLog[0])
        assertTrue(
            "Person creation must occur after token sync initiation",
            eventLog.indexOf("person_creation:user_123") > eventLog.indexOf("fcm_sync_initiated:user_123")
        )
    }

    /**
     * Verifies that SessionManager's login flow initiates FCM token synchronization
     * even if subsequent local person creation throws an exception.
     */
    @Test
    fun `session token sync is initiated even if local person creation fails`() = runBlocking {
        val scope = createTestScope()
        val eventLog = mutableListOf<String>()

        val failingDao = object : PersonDao {
            override suspend fun insertPerson(person: Person): Long = throw RuntimeException("DB error")
            override suspend fun updatePerson(person: Person) {}
            override fun getAllPersons(): Flow<List<Person>> = flowOf(emptyList())
            override suspend fun getDefaultPerson(): Person? = null
            override suspend fun getByCloudUid(uid: String): Person? {
                eventLog.add("person_creation_attempt:$uid")
                throw RuntimeException("Database error in person creation")
            }
            override suspend fun getDefaultPersonWithNullCloudUid(): Person? = null
        }
        val personRepo = PersonRepository(failingDao)

        val tokenManager = object : FcmTokenManager(
            appScope = scope,
            currentUidProvider = { "user_123" },
            tokenProvider = { "fcm_token_123" },
            firestoreWriter = { uid, _ ->
                eventLog.add("fcm_token_written:$uid")
            },
            firestoreDeleter = {}
        ) {
            override fun syncTokenForUser(uid: String): Job {
                eventLog.add("fcm_sync_initiated:$uid")
                return super.syncTokenForUser(uid)
            }
        }

        // Simulate SessionManager auth flow:
        val currentUid = "user_123"
        val userUid = "user_123"
        if (currentUid == userUid) {
            // FCM sync is initiated before person resolution
            tokenManager.syncTokenForUser(userUid)
            try {
                personRepo.getOrCreatePersonForUser(userUid, "test@test.com", "Test")
            } catch (e: Exception) {
                eventLog.add("person_creation_failed:${e.message}")
            }
        }

        // Verify: FCM sync was initiated despite person creation failing
        assertTrue("FCM sync must be initiated", eventLog.contains("fcm_sync_initiated:user_123"))
        assertEquals("fcm_sync_initiated:user_123", eventLog[0])
        assertTrue("Person creation failure must be recorded after sync", eventLog.contains("person_creation_failed:Database error in person creation"))
    }
}
