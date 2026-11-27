package com.nagi.dosecircle.notifications.fcm

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class FcmTokenManagerTest {

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

    @Test
    fun `syncTokenForUser saves token when user is logged in`() = runBlocking {
        val scope = createTestScope()
        var currentUid: String? = "user_123"
        val writtenTokens = mutableListOf<Pair<String, String>>()

        val manager = FcmTokenManager(
            appScope = scope,
            currentUidProvider = { currentUid },
            tokenProvider = { "fcm_token_abc" },
            firestoreWriter = { uid, token -> writtenTokens.add(uid to token) },
            firestoreDeleter = { }
        )

        val job = manager.syncTokenForUser("user_123")
        job.join()

        assertEquals(1, writtenTokens.size)
        assertEquals("user_123" to "fcm_token_abc", writtenTokens[0])
    }

    @Test
    fun `syncTokenForUser aborts when user logs out before token write`() = runBlocking {
        val scope = createTestScope()
        var currentUid: String? = "user_123"
        val tokenFetchStarted = CompletableDeferred<Unit>()
        val allowTokenFetch = CompletableDeferred<Unit>()
        val writtenTokens = mutableListOf<Pair<String, String>>()

        val manager = FcmTokenManager(
            appScope = scope,
            currentUidProvider = { currentUid },
            tokenProvider = {
                tokenFetchStarted.complete(Unit)
                allowTokenFetch.await()
                "fcm_token_abc"
            },
            firestoreWriter = { uid, token -> writtenTokens.add(uid to token) },
            firestoreDeleter = { }
        )

        val job = manager.syncTokenForUser("user_123")
        tokenFetchStarted.await()

        // User logs out before token fetch completes
        currentUid = null
        allowTokenFetch.complete(Unit)
        job.join()

        // Token must not have been written to Firestore
        assertTrue("No token write should occur after user logs out", writtenTokens.isEmpty())
    }

    @Test
    fun `syncTokenForUser aborts when user switches before write`() = runBlocking {
        val scope = createTestScope()
        var currentUid: String? = "user_A"
        val tokenFetchStarted = CompletableDeferred<Unit>()
        val allowTokenFetch = CompletableDeferred<Unit>()
        val writtenTokens = mutableListOf<Pair<String, String>>()

        val manager = FcmTokenManager(
            appScope = scope,
            currentUidProvider = { currentUid },
            tokenProvider = {
                tokenFetchStarted.complete(Unit)
                allowTokenFetch.await()
                "fcm_token_user_A"
            },
            firestoreWriter = { uid, token -> writtenTokens.add(uid to token) },
            firestoreDeleter = { }
        )

        val job = manager.syncTokenForUser("user_A")
        tokenFetchStarted.await()

        // Account switches to user_B
        currentUid = "user_B"
        allowTokenFetch.complete(Unit)
        job.join()

        assertTrue("Old user token must not be written after user switch", writtenTokens.isEmpty())
    }

    @Test
    fun `deleteTokenOnLogout waits for active write and deletes token`() = runBlocking {
        val scope = createTestScope()
        var currentUid: String? = "user_123"
        val writeStarted = CompletableDeferred<Unit>()
        val allowWriteFinish = CompletableDeferred<Unit>()
        val eventLog = mutableListOf<String>()

        val manager = FcmTokenManager(
            appScope = scope,
            currentUidProvider = { currentUid },
            tokenProvider = { "token_123" },
            firestoreWriter = { uid, _ ->
                eventLog.add("write_start:$uid")
                writeStarted.complete(Unit)
                allowWriteFinish.await()
                eventLog.add("write_finish:$uid")
            },
            firestoreDeleter = { uid ->
                eventLog.add("delete:$uid")
            }
        )

        val syncJob = manager.syncTokenForUser("user_123")
        writeStarted.await()

        // Launch logout while write is holding the mutex
        val logoutJob = launch(Dispatchers.Default) {
            manager.deleteTokenOnLogout("user_123")
        }

        // Give logout coroutine a moment to attempt acquiring the mutex
        delay(50)
        // Allow write to complete
        allowWriteFinish.complete(Unit)

        syncJob.join()
        logoutJob.join()

        // Verification: delete happened AFTER write finished
        assertEquals(3, eventLog.size)
        assertEquals("write_start:user_123", eventLog[0])
        assertEquals("write_finish:user_123", eventLog[1])
        assertEquals("delete:user_123", eventLog[2])
    }

    @Test
    fun `in-flight sync job cannot write after logout deletion`() = runBlocking {
        val scope = createTestScope()
        var currentUid: String? = "user_123"
        val tokenFetchStarted = CompletableDeferred<Unit>()
        val allowTokenFetch = CompletableDeferred<Unit>()
        val writtenTokens = mutableListOf<Pair<String, String>>()
        val deletedUsers = mutableListOf<String>()

        val manager = FcmTokenManager(
            appScope = scope,
            currentUidProvider = { currentUid },
            tokenProvider = {
                tokenFetchStarted.complete(Unit)
                allowTokenFetch.await()
                "token_123"
            },
            firestoreWriter = { uid, token -> writtenTokens.add(uid to token) },
            firestoreDeleter = { uid -> deletedUsers.add(uid) }
        )

        // Start sync
        val syncJob = manager.syncTokenForUser("user_123")
        tokenFetchStarted.await()

        // Logout runs while sync is fetching token
        manager.deleteTokenOnLogout("user_123")
        currentUid = null

        // Now resume the token fetch
        allowTokenFetch.complete(Unit)
        syncJob.join()

        // Token must not have been written after logout
        assertTrue("Token must not reappear after logout", writtenTokens.isEmpty())
        assertEquals(1, deletedUsers.size)
        assertEquals("user_123", deletedUsers[0])
    }

    @Test
    fun `onNewToken ignores when user is logged out`() = runBlocking {
        val scope = createTestScope()
        val writtenTokens = mutableListOf<Pair<String, String>>()

        val manager = FcmTokenManager(
            appScope = scope,
            currentUidProvider = { null }, // Logged out
            tokenProvider = { null },
            firestoreWriter = { uid, token -> writtenTokens.add(uid to token) },
            firestoreDeleter = { }
        )

        val job = manager.onNewToken("new_token_123")
        job?.join()

        assertTrue("onNewToken should do nothing when logged out", writtenTokens.isEmpty())
    }

    @Test
    fun `onNewToken saves token when user is logged in`() = runBlocking {
        val scope = createTestScope()
        val writtenTokens = mutableListOf<Pair<String, String>>()

        val manager = FcmTokenManager(
            appScope = scope,
            currentUidProvider = { "user_456" },
            tokenProvider = { null },
            firestoreWriter = { uid, token -> writtenTokens.add(uid to token) },
            firestoreDeleter = { }
        )

        val job = manager.onNewToken("refreshed_token_456")
        job?.join()

        assertEquals(1, writtenTokens.size)
        assertEquals("user_456" to "refreshed_token_456", writtenTokens[0])
    }

    @Test
    fun `onNewToken called after logout deletion starts but before signOut cannot restore token`() = runBlocking {
        val scope = createTestScope()
        // Auth still returns "user_123" because auth.signOut() has not yet been executed by AuthRepository
        var currentUid: String? = "user_123"
        val writtenTokens = mutableListOf<Pair<String, String>>()
        val deletedUsers = mutableListOf<String>()

        val manager = FcmTokenManager(
            appScope = scope,
            currentUidProvider = { currentUid },
            tokenProvider = { "token_123" },
            firestoreWriter = { uid, token -> writtenTokens.add(uid to token) },
            firestoreDeleter = { uid -> deletedUsers.add(uid) }
        )

        // 1. Logout deletion starts and completes in Firestore
        manager.deleteTokenOnLogout("user_123")
        assertEquals(1, deletedUsers.size)
        assertEquals("user_123", deletedUsers[0])

        // 2. Before auth.signOut() is called (currentUid is still "user_123"), onNewToken is triggered
        val tokenJob = manager.onNewToken("refreshed_token_before_signout")
        tokenJob?.join()

        // 3. Token must NOT be written to Firestore
        assertTrue("onNewToken must be rejected after deleteTokenOnLogout starts, even before signOut", writtenTokens.isEmpty())
    }

    @Test
    fun `new login for same UID can sync token again after logout`() = runBlocking {
        val scope = createTestScope()
        var currentUid: String? = "user_123"
        val writtenTokens = mutableListOf<Pair<String, String>>()
        val deletedUsers = mutableListOf<String>()

        val manager = FcmTokenManager(
            appScope = scope,
            currentUidProvider = { currentUid },
            tokenProvider = { "token_123_new" },
            firestoreWriter = { uid, token -> writtenTokens.add(uid to token) },
            firestoreDeleter = { uid -> deletedUsers.add(uid) }
        )

        // 1. User logs out
        manager.deleteTokenOnLogout("user_123")
        // 2. Auth signOut completes
        manager.onSignOutCompleted("user_123")
        currentUid = null

        // 3. User logs in again with the same UID
        currentUid = "user_123"
        val newLoginSyncJob = manager.syncTokenForUser("user_123")
        newLoginSyncJob.join()

        // Token must be successfully synced
        assertEquals(1, writtenTokens.size)
        assertEquals("user_123" to "token_123_new", writtenTokens[0])

        // Subsequent onNewToken should now work normally
        val refreshTokenJob = manager.onNewToken("token_123_refreshed")
        refreshTokenJob?.join()

        assertEquals(2, writtenTokens.size)
        assertEquals("user_123" to "token_123_refreshed", writtenTokens[1])
    }

    @Test
    fun `race window 1 - while Firestore deletion is in progress sync and onNewToken are rejected`() = runBlocking {
        val scope = createTestScope()
        var currentUid: String? = "user_123"
        val deletionStarted = CompletableDeferred<Unit>()
        val allowDeletionFinish = CompletableDeferred<Unit>()
        val writtenTokens = mutableListOf<Pair<String, String>>()
        val deletedUsers = mutableListOf<String>()

        val manager = FcmTokenManager(
            appScope = scope,
            currentUidProvider = { currentUid },
            tokenProvider = { "token_123" },
            firestoreWriter = { uid, token -> writtenTokens.add(uid to token) },
            firestoreDeleter = { uid ->
                deletedUsers.add(uid)
                deletionStarted.complete(Unit)
                allowDeletionFinish.await()
            }
        )

        // 1. Start deleteTokenOnLogout
        val logoutJob = launch(Dispatchers.Default) {
            manager.deleteTokenOnLogout("user_123")
        }
        deletionStarted.await()

        // Verify state during Firestore deletion
        assertTrue("Logout should be marked in progress", manager.isLogoutInProgress("user_123"))
        assertTrue("User should be marked logged out", manager.isLoggedOut("user_123"))
        assertTrue("Logout should be active", manager.isLogoutActive("user_123"))

        // 2. Attempt syncTokenForUser while deletion is in progress
        val syncJob = manager.syncTokenForUser("user_123")
        syncJob.join()
        assertTrue("syncTokenForUser must be rejected while deletion is in progress", writtenTokens.isEmpty())
        assertTrue("Logout guard must remain active", manager.isLoggedOut("user_123"))

        // 3. Attempt onNewToken while deletion is in progress
        val tokenJob = manager.onNewToken("token_window1")
        tokenJob?.join()
        assertTrue("onNewToken must be rejected while deletion is in progress", writtenTokens.isEmpty())

        // 4. Finish Firestore deletion
        allowDeletionFinish.complete(Unit)
        logoutJob.join()

        assertEquals(1, deletedUsers.size)
        assertEquals("user_123", deletedUsers[0])
        assertFalse("Logout deletion is no longer in progress", manager.isLogoutInProgress("user_123"))
        assertTrue("User must still be marked logged out", manager.isLoggedOut("user_123"))
    }

    @Test
    fun `race window 2 - after Firestore deletion finishes but before auth signOut completes sync and onNewToken are rejected`() = runBlocking {
        val scope = createTestScope()
        // Auth still returns "user_123" because auth.signOut() has not yet been executed
        var currentUid: String? = "user_123"
        val writtenTokens = mutableListOf<Pair<String, String>>()
        val deletedUsers = mutableListOf<String>()

        val manager = FcmTokenManager(
            appScope = scope,
            currentUidProvider = { currentUid },
            tokenProvider = { "token_123" },
            firestoreWriter = { uid, token -> writtenTokens.add(uid to token) },
            firestoreDeleter = { uid -> deletedUsers.add(uid) }
        )

        // 1. deleteTokenOnLogout completes in Firestore
        manager.deleteTokenOnLogout("user_123")
        assertEquals(1, deletedUsers.size)
        assertEquals("user_123", deletedUsers[0])

        // Window 2 State:
        // - Firestore deletion has finished: isLogoutInProgress is false
        // - But auth.signOut() has not yet completed: onSignOutCompleted has NOT been called
        // - currentUid is still "user_123"
        assertFalse("Firestore deletion is not in progress", manager.isLogoutInProgress("user_123"))
        assertTrue("User is in loggedOutUids", manager.isLoggedOut("user_123"))
        assertFalse("Sign-out has not yet completed", manager.isSignOutCompleted("user_123"))
        assertTrue("Logout must be active during Window 2", manager.isLogoutActive("user_123"))

        // 2. syncTokenForUser must be rejected and must NOT remove the loggedOut guard or write token
        val syncJob = manager.syncTokenForUser("user_123")
        syncJob.join()
        assertTrue("syncTokenForUser must be rejected during Window 2", writtenTokens.isEmpty())
        assertTrue("loggedOut guard must remain active", manager.isLoggedOut("user_123"))

        // 3. onNewToken must also be rejected
        val tokenJob = manager.onNewToken("token_window2")
        tokenJob?.join()
        assertTrue("onNewToken must be rejected during Window 2", writtenTokens.isEmpty())
        assertTrue("loggedOut guard must still remain active", manager.isLoggedOut("user_123"))
    }

    @Test
    fun `genuine subsequent login for same UID syncs token and releases guard after signOut completes`() = runBlocking {
        val scope = createTestScope()
        var currentUid: String? = "user_123"
        val writtenTokens = mutableListOf<Pair<String, String>>()
        val deletedUsers = mutableListOf<String>()

        val manager = FcmTokenManager(
            appScope = scope,
            currentUidProvider = { currentUid },
            tokenProvider = { "token_123_genuine" },
            firestoreWriter = { uid, token -> writtenTokens.add(uid to token) },
            firestoreDeleter = { uid -> deletedUsers.add(uid) }
        )

        // 1. User logs out: Firestore token deleted
        manager.deleteTokenOnLogout("user_123")
        assertEquals(1, deletedUsers.size)

        // 2. Auth signOut completes -> AuthRepository notifies manager via onSignOutCompleted
        manager.onSignOutCompleted("user_123")
        currentUid = null

        assertTrue("Sign-out is marked completed", manager.isSignOutCompleted("user_123"))
        assertFalse("Logout is no longer active", manager.isLogoutActive("user_123"))
        assertTrue("loggedOut guard remains active until genuine new login", manager.isLoggedOut("user_123"))

        // 3. onNewToken while logged out is still rejected
        val preLoginTokenJob = manager.onNewToken("token_before_login")
        preLoginTokenJob?.join()
        assertTrue("onNewToken while logged out must be rejected", writtenTokens.isEmpty())

        // 4. Genuine subsequent login occurs for the same user
        currentUid = "user_123"
        val loginSyncJob = manager.syncTokenForUser("user_123")
        loginSyncJob.join()

        // Token must be saved and guard released
        assertEquals(1, writtenTokens.size)
        assertEquals("user_123" to "token_123_genuine", writtenTokens[0])
        assertFalse("Guard must be released after genuine login sync", manager.isLoggedOut("user_123"))
        assertFalse("signOutCompleted flag must be reset", manager.isSignOutCompleted("user_123"))

        // 5. Subsequent refreshed tokens are accepted normally
        val postLoginTokenJob = manager.onNewToken("token_refreshed_after_login")
        postLoginTokenJob?.join()

        assertEquals(2, writtenTokens.size)
        assertEquals("user_123" to "token_refreshed_after_login", writtenTokens[1])
    }

    @Test
    fun `login token sync failing followed by successful onNewToken after genuine subsequent login`() = runBlocking {
        val scope = createTestScope()
        var currentUid: String? = "user_123"
        val writtenTokens = mutableListOf<Pair<String, String>>()
        val deletedUsers = mutableListOf<String>()
        var tokenProviderShouldFail = false

        val manager = FcmTokenManager(
            appScope = scope,
            currentUidProvider = { currentUid },
            tokenProvider = {
                if (tokenProviderShouldFail) {
                    throw RuntimeException("FCM token fetch failed")
                }
                "token_123"
            },
            firestoreWriter = { uid, token -> writtenTokens.add(uid to token) },
            firestoreDeleter = { uid -> deletedUsers.add(uid) }
        )

        // 1. Initial login and logout
        manager.deleteTokenOnLogout("user_123")
        manager.onSignOutCompleted("user_123")
        currentUid = null

        assertTrue("User must be marked loggedOut after logout", manager.isLoggedOut("user_123"))

        // 2. Genuine subsequent login occurs, but token sync fails
        currentUid = "user_123"
        tokenProviderShouldFail = true
        val failedSyncJob = manager.syncTokenForUser("user_123")
        failedSyncJob.join()

        // Sync failed, no token written yet, guard is still not released
        assertTrue("No token should be written when sync fails", writtenTokens.isEmpty())
        assertTrue("loggedOut guard remains active after failed sync", manager.isLoggedOut("user_123"))

        // 3. onNewToken arrives for the genuinely logged in user
        val refreshTokenJob = manager.onNewToken("refreshed_token_after_failed_sync")
        refreshTokenJob?.join()

        // Verify token is now successfully written and guard released
        assertEquals(1, writtenTokens.size)
        assertEquals("user_123" to "refreshed_token_after_failed_sync", writtenTokens[0])
        assertFalse("Guard must be released after successful onNewToken write", manager.isLoggedOut("user_123"))
        assertFalse("signOutCompleted must be reset", manager.isSignOutCompleted("user_123"))
    }

    @Test
    fun `onSignOutFailed restores guard when current user still equals UID`() = runBlocking {
        val scope = createTestScope()
        var currentUid: String? = "user_123"
        val writtenTokens = mutableListOf<Pair<String, String>>()

        val manager = FcmTokenManager(
            appScope = scope,
            currentUidProvider = { currentUid },
            tokenProvider = { "token_123" },
            firestoreWriter = { uid, token -> writtenTokens.add(uid to token) },
            firestoreDeleter = { }
        )

        manager.deleteTokenOnLogout("user_123")
        assertTrue("User must be marked loggedOut", manager.isLoggedOut("user_123"))

        // Sign-out failed, user is still authenticated
        manager.onSignOutFailed("user_123")

        assertFalse("Guard must be cleared on sign-out failure for authenticated user", manager.isLoggedOut("user_123"))
        assertFalse("signOutCompleted must be cleared", manager.isSignOutCompleted("user_123"))

        // Token sync can proceed
        val syncJob = manager.syncTokenForUser("user_123")
        syncJob.join()

        assertEquals(1, writtenTokens.size)
        assertEquals("user_123" to "token_123", writtenTokens[0])
    }

    @Test
    fun `onSignOutFailed retains guard when current user is null or different UID`() = runBlocking {
        val scope = createTestScope()
        var currentUid: String? = null

        val manager = FcmTokenManager(
            appScope = scope,
            currentUidProvider = { currentUid },
            tokenProvider = { "token_123" },
            firestoreWriter = { _, _ -> },
            firestoreDeleter = { }
        )

        manager.deleteTokenOnLogout("user_123")
        assertTrue("User must be marked loggedOut", manager.isLoggedOut("user_123"))

        // Sign-out failed, but currentUid is null
        manager.onSignOutFailed("user_123")
        assertTrue("Guard must NOT be cleared when current user is null", manager.isLoggedOut("user_123"))

        // Sign-out failed, but currentUid is different user
        currentUid = "user_456"
        manager.onSignOutFailed("user_123")
        assertTrue("Guard must NOT be cleared when current user is different", manager.isLoggedOut("user_123"))
    }

    @Test
    fun `onSignOutFailed does not clear guard for another user`() = runBlocking {
        val scope = createTestScope()
        var currentUid: String? = "user_123"

        val manager = FcmTokenManager(
            appScope = scope,
            currentUidProvider = { currentUid },
            tokenProvider = { "token_123" },
            firestoreWriter = { _, _ -> },
            firestoreDeleter = { }
        )

        manager.deleteTokenOnLogout("user_123")
        manager.deleteTokenOnLogout("user_456")
        assertTrue("user_123 must be marked loggedOut", manager.isLoggedOut("user_123"))
        assertTrue("user_456 must be marked loggedOut", manager.isLoggedOut("user_456"))

        // Sign-out failed for user_123 while user_123 is authenticated
        manager.onSignOutFailed("user_123")

        assertFalse("user_123 guard must be cleared", manager.isLoggedOut("user_123"))
        assertTrue("user_456 guard must NOT be cleared", manager.isLoggedOut("user_456"))
    }
}
