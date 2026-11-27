
package com.nagi.dosecircle.data.repository

import com.nagi.dosecircle.notifications.fcm.FcmTokenManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class AuthRepositoryLogoutTest {

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
    fun `logout calls onSignOutCompleted only when signOut succeeds`() = runBlocking {
        val scope = createTestScope()
        val currentUid = "user_123"
        val signOutCalled = AtomicBoolean(false)

        val tokenManager = FcmTokenManager(
            appScope = scope,
            currentUidProvider = { currentUid },
            tokenProvider = { "token_123" },
            firestoreWriter = { _, _ -> },
            firestoreDeleter = { _ -> }
        )

        val repository = AuthRepository(
            fcmTokenManager = tokenManager,
            currentUidProvider = { currentUid },
            signOutAction = {
                assertFalse(
                    "isSignOutCompleted must still be false inside signOutAction",
                    tokenManager.isSignOutCompleted("user_123")
                )
                signOutCalled.set(true)
            }
        )

        val result = repository.logout()

        assertTrue("logout result must be success", result.isSuccess)
        assertTrue("signOutAction must have been executed", signOutCalled.get())
        assertTrue(
            "onSignOutCompleted must be called after successful signOut",
            tokenManager.isSignOutCompleted("user_123")
        )
    }

    @Test
    fun `logout does not call onSignOutCompleted when signOut throws exception`() = runBlocking {
        val scope = createTestScope()
        val currentUid = "user_123"

        val tokenManager = FcmTokenManager(
            appScope = scope,
            currentUidProvider = { currentUid },
            tokenProvider = { "token_123" },
            firestoreWriter = { _, _ -> },
            firestoreDeleter = { _ -> }
        )

        val repository = AuthRepository(
            fcmTokenManager = tokenManager,
            currentUidProvider = { currentUid },
            signOutAction = {
                throw RuntimeException("Sign-out failed due to network")
            }
        )

        val result = repository.logout()

        assertTrue("logout result must be failure when signOut throws", result.isFailure)
        assertFalse(
            "onSignOutCompleted must NOT be called when signOut throws",
            tokenManager.isSignOutCompleted("user_123")
        )
    }

    @Test
    fun `logout rethrows CancellationException when signOut is cancelled`() = runBlocking {
        val scope = createTestScope()
        val currentUid = "user_123"

        val tokenManager = FcmTokenManager(
            appScope = scope,
            currentUidProvider = { currentUid },
            tokenProvider = { "token_123" },
            firestoreWriter = { _, _ -> },
            firestoreDeleter = { _ -> }
        )

        val repository = AuthRepository(
            fcmTokenManager = tokenManager,
            currentUidProvider = { currentUid },
            signOutAction = {
                throw CancellationException("Coroutine cancelled")
            }
        )

        try {
            repository.logout()
            fail("Expected CancellationException to be rethrown")
        } catch (e: CancellationException) {
            assertEquals("Coroutine cancelled", e.message)
        }

        assertFalse(
            "onSignOutCompleted must NOT be called when signOut is cancelled",
            tokenManager.isSignOutCompleted("user_123")
        )
    }

    @Test
    fun `logout continues to sign out and call onSignOutCompleted even if token deletion fails`() = runBlocking {
        val scope = createTestScope()
        val currentUid = "user_123"
        val signOutCalled = AtomicBoolean(false)

        val tokenManager = FcmTokenManager(
            appScope = scope,
            currentUidProvider = { currentUid },
            tokenProvider = { "token_123" },
            firestoreWriter = { _, _ -> },
            firestoreDeleter = { _ ->
                throw RuntimeException("Firestore offline")
            }
        )

        val repository = AuthRepository(
            fcmTokenManager = tokenManager,
            currentUidProvider = { currentUid },
            signOutAction = {
                signOutCalled.set(true)
            }
        )

        val result = repository.logout()

        assertTrue(
            "logout result must be success when signOut succeeds even if token deletion threw",
            result.isSuccess
        )
        assertTrue("signOutAction must have been executed", signOutCalled.get())
        assertTrue(
            "onSignOutCompleted must be called after successful signOut even if deletion failed",
            tokenManager.isSignOutCompleted("user_123")
        )
    }

    @Test
    fun `logout restores token syncing when signOut fails for still-authenticated UID`() = runBlocking {
        val scope = createTestScope()
        var currentUid: String? = "user_123"
        val writtenTokens = mutableListOf<Pair<String, String>>()

        val tokenManager = FcmTokenManager(
            appScope = scope,
            currentUidProvider = { currentUid },
            tokenProvider = { "token_123" },
            firestoreWriter = { uid, token -> writtenTokens.add(uid to token) },
            firestoreDeleter = { _ -> }
        )

        val repository = AuthRepository(
            fcmTokenManager = tokenManager,
            currentUidProvider = { currentUid },
            signOutAction = {
                throw RuntimeException("Sign-out network failure")
            }
        )

        val result = repository.logout()

        assertTrue("logout result must be failure when signOut fails", result.isFailure)
        assertFalse(
            "loggedOut guard must be cleared when signOut fails for still-authenticated user",
            tokenManager.isLoggedOut("user_123")
        )
        assertFalse(
            "signOutCompleted must NOT be marked when signOut fails",
            tokenManager.isSignOutCompleted("user_123")
        )

        // Verify token syncing works again for this user
        val syncJob = tokenManager.syncTokenForUser("user_123")
        syncJob.join()

        assertEquals(1, writtenTokens.size)
        assertEquals("user_123" to "token_123", writtenTokens[0])
    }

    @Test
    fun `logout restores guard when signOut is cancelled`() = runBlocking {
        val scope = createTestScope()
        var currentUid: String? = "user_123"
        val writtenTokens = mutableListOf<Pair<String, String>>()

        val tokenManager = FcmTokenManager(
            appScope = scope,
            currentUidProvider = { currentUid },
            tokenProvider = { "token_123" },
            firestoreWriter = { uid, token -> writtenTokens.add(uid to token) },
            firestoreDeleter = { _ -> }
        )

        val repository = AuthRepository(
            fcmTokenManager = tokenManager,
            currentUidProvider = { currentUid },
            signOutAction = {
                throw CancellationException("Sign-out cancelled")
            }
        )

        try {
            repository.logout()
            fail("Expected CancellationException to be rethrown")
        } catch (e: CancellationException) {
            assertEquals("Sign-out cancelled", e.message)
        }

        assertFalse(
            "loggedOut guard must be cleared when signOut is cancelled for still-authenticated user",
            tokenManager.isLoggedOut("user_123")
        )

        // Verify token syncing works
        val syncJob = tokenManager.syncTokenForUser("user_123")
        syncJob.join()

        assertEquals(1, writtenTokens.size)
        assertEquals("user_123" to "token_123", writtenTokens[0])
    }

    @Test
    fun `logout restores guard when token deletion is cancelled`() = runBlocking {
        val scope = createTestScope()
        var currentUid: String? = "user_123"
        val writtenTokens = mutableListOf<Pair<String, String>>()

        val tokenManager = FcmTokenManager(
            appScope = scope,
            currentUidProvider = { currentUid },
            tokenProvider = { "token_123" },
            firestoreWriter = { uid, token -> writtenTokens.add(uid to token) },
            firestoreDeleter = { _ ->
                throw CancellationException("Token deletion cancelled")
            }
        )

        val repository = AuthRepository(
            fcmTokenManager = tokenManager,
            currentUidProvider = { currentUid },
            signOutAction = { }
        )

        try {
            repository.logout()
            fail("Expected CancellationException to be rethrown")
        } catch (e: CancellationException) {
            assertEquals("Token deletion cancelled", e.message)
        }

        assertFalse(
            "loggedOut guard must be cleared when token deletion is cancelled for still-authenticated user",
            tokenManager.isLoggedOut("user_123")
        )

        // Verify token syncing works
        val syncJob = tokenManager.syncTokenForUser("user_123")
        syncJob.join()

        assertEquals(1, writtenTokens.size)
        assertEquals("user_123" to "token_123", writtenTokens[0])
    }

    @Test
    fun `logout does not unblock a different or no-longer-authenticated UID on failure`() = runBlocking {
        val scope = createTestScope()
        var currentUid: String? = "user_123"

        val tokenManager = FcmTokenManager(
            appScope = scope,
            currentUidProvider = { currentUid },
            tokenProvider = { "token_123" },
            firestoreWriter = { _, _ -> },
            firestoreDeleter = { _ -> }
        )

        val repository = AuthRepository(
            fcmTokenManager = tokenManager,
            currentUidProvider = { currentUid },
            signOutAction = {
                // User became null during signOut before failure was reported
                currentUid = null
                throw RuntimeException("Sign-out failed after auth state changed")
            }
        )

        val result = repository.logout()

        assertTrue("logout result must be failure", result.isFailure)
        assertTrue(
            "user_123 must remain guarded because user is no longer authenticated",
            tokenManager.isLoggedOut("user_123")
        )
    }

    @Test
    fun `successful logout keeps logout guard until a genuine subsequent login`() = runBlocking {
        val scope = createTestScope()
        var currentUid: String? = "user_123"
        val writtenTokens = mutableListOf<Pair<String, String>>()

        val tokenManager = FcmTokenManager(
            appScope = scope,
            currentUidProvider = { currentUid },
            tokenProvider = { "token_123" },
            firestoreWriter = { uid, token -> writtenTokens.add(uid to token) },
            firestoreDeleter = { _ -> }
        )

        val repository = AuthRepository(
            fcmTokenManager = tokenManager,
            currentUidProvider = { currentUid },
            signOutAction = {
                currentUid = null
            }
        )

        val result = repository.logout()

        assertTrue("logout must succeed", result.isSuccess)
        assertTrue("user_123 must be marked logged out", tokenManager.isLoggedOut("user_123"))
        assertTrue("user_123 must be marked signOutCompleted", tokenManager.isSignOutCompleted("user_123"))

        // Attempting to sync token while logged out must be rejected
        val rejectedJob = tokenManager.syncTokenForUser("user_123")
        rejectedJob.join()
        assertTrue("No token write should occur after logout", writtenTokens.isEmpty())
        assertTrue("Logout guard must still be active", tokenManager.isLoggedOut("user_123"))

        // Genuine subsequent login occurs
        currentUid = "user_123"
        val genuineLoginJob = tokenManager.syncTokenForUser("user_123")
        genuineLoginJob.join()

        assertEquals("Token must be written on genuine login", 1, writtenTokens.size)
        assertEquals("user_123" to "token_123", writtenTokens[0])
        assertFalse("Guard must be released after genuine login write", tokenManager.isLoggedOut("user_123"))
        assertFalse("signOutCompleted must be reset after genuine login write", tokenManager.isSignOutCompleted("user_123"))
    }
}
