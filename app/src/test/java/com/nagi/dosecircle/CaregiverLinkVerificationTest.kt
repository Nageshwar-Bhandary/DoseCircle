package com.nagi.dosecircle

import com.google.firebase.Timestamp
import com.nagi.dosecircle.data.model.CaregiverLink
import com.nagi.dosecircle.data.repository.CaregiverLinkRepository
import com.nagi.dosecircle.viewmodel.CareForViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CaregiverLinkVerificationTest {

    private fun createTestScope() = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // =========================================================================
    // SECTION 1: DIRECT PRODUCTION REPOSITORY LOGIC TESTS
    // =========================================================================

    @Test
    fun `production requestLink rejects invalid code length before any Firestore access`() = runBlocking {
        // Instantiate the REAL production repository without Firebase initialization
        val productionRepo = CaregiverLinkRepository(
            currentUidProvider = { "caregiver_uid" }
        )

        // 7 characters
        val shortResult = productionRepo.requestLink("AB12CD3")
        assertTrue(shortResult.isFailure)
        assertTrue(shortResult.exceptionOrNull() is IllegalArgumentException)
        assertEquals("Invite code must be 8 characters", shortResult.exceptionOrNull()?.message)

        // 9 characters
        val longResult = productionRepo.requestLink("AB12CD345")
        assertTrue(longResult.isFailure)
        assertTrue(longResult.exceptionOrNull() is IllegalArgumentException)
        assertEquals("Invite code must be 8 characters", longResult.exceptionOrNull()?.message)

        // Empty code
        val emptyResult = productionRepo.requestLink("")
        assertTrue(emptyResult.isFailure)
        assertTrue(emptyResult.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `production requestLink rejects logged-out user before any Firestore access`() = runBlocking {
        // Instantiate real production repository with logged-out state
        val productionRepo = CaregiverLinkRepository(
            currentUidProvider = { null }
        )

        val result = productionRepo.requestLink("AB12CD34")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalStateException)
        assertEquals("Please sign in first", result.exceptionOrNull()?.message)
    }

    @Test
    fun `production cancelOrLeave rejects logged-out user`() = runBlocking {
        val productionRepo = CaregiverLinkRepository(
            currentUidProvider = { null }
        )

        val link = CaregiverLink(
            id = "patient1_caregiver1",
            patientUid = "patient1",
            caregiverUid = "caregiver1",
            code = "AB12CD34"
        )

        val result = productionRepo.cancelOrLeave(link)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IllegalStateException)
        assertEquals("Please sign in first", result.exceptionOrNull()?.message)
    }

    @Test
    fun `production cancelOrLeave prevents deleting link belonging to another caregiver`() = runBlocking {
        val productionRepo = CaregiverLinkRepository(
            currentUidProvider = { "current_caregiver_123" }
        )

        val otherUserLink = CaregiverLink(
            id = "patient1_another_caregiver",
            patientUid = "patient1",
            caregiverUid = "another_caregiver",
            code = "AB12CD34"
        )

        val result = productionRepo.cancelOrLeave(otherUserLink)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SecurityException)
        assertEquals("Unauthorized to modify this link", result.exceptionOrNull()?.message)
    }

    @Test
    fun `production validateInviteDocument rejects non-existent, expired, or malformed documents`() {
        val now = 1000L

        // Document does not exist
        val notExists = CaregiverLinkRepository.validateInviteDocument(
            exists = false,
            expiresAtSeconds = now + 600,
            nowSeconds = now,
            patientUid = "patient_1",
            currentUid = "caregiver_1"
        )
        assertTrue(notExists.isFailure)
        assertEquals("Invalid or expired code", notExists.exceptionOrNull()?.message)

        // Document expired
        val expired = CaregiverLinkRepository.validateInviteDocument(
            exists = true,
            expiresAtSeconds = now - 10,
            nowSeconds = now,
            patientUid = "patient_1",
            currentUid = "caregiver_1"
        )
        assertTrue(expired.isFailure)
        assertEquals("Invalid or expired code", expired.exceptionOrNull()?.message)

        // Missing patientUid
        val missingPatient = CaregiverLinkRepository.validateInviteDocument(
            exists = true,
            expiresAtSeconds = now + 600,
            nowSeconds = now,
            patientUid = null,
            currentUid = "caregiver_1"
        )
        assertTrue(missingPatient.isFailure)
        assertEquals("Invalid or expired code", missingPatient.exceptionOrNull()?.message)

        // Self-linking
        val selfLink = CaregiverLinkRepository.validateInviteDocument(
            exists = true,
            expiresAtSeconds = now + 600,
            nowSeconds = now,
            patientUid = "same_uid",
            currentUid = "same_uid"
        )
        assertTrue(selfLink.isFailure)
        assertEquals("You can't link to yourself", selfLink.exceptionOrNull()?.message)

        // Valid invite document
        val valid = CaregiverLinkRepository.validateInviteDocument(
            exists = true,
            expiresAtSeconds = now + 600,
            nowSeconds = now,
            patientUid = "patient_1",
            currentUid = "caregiver_1"
        )
        assertTrue(valid.isSuccess)
        assertEquals("patient_1", valid.getOrNull())
    }

    @Test
    fun `production buildLinkData constructs required fields and omits nulls`() {
        // With all optional fields present
        val fullData = CaregiverLinkRepository.buildLinkData(
            patientUid = "p1",
            caregiverUid = "c1",
            code = "AB12CD34",
            patientName = "Alice",
            caregiverName = "Bob",
            caregiverEmail = "bob@example.com"
        )

        assertEquals("p1", fullData["patientUid"])
        assertEquals("c1", fullData["caregiverUid"])
        assertEquals("AB12CD34", fullData["code"])
        assertEquals("pending", fullData["status"])
        assertEquals("Alice", fullData["patientName"])
        assertEquals("Bob", fullData["caregiverName"])
        assertEquals("bob@example.com", fullData["caregiverEmail"])
        assertNotNull(fullData["createdAt"])

        // With optional fields null or blank
        val partialData = CaregiverLinkRepository.buildLinkData(
            patientUid = "p1",
            caregiverUid = "c1",
            code = "AB12CD34",
            patientName = "   ",
            caregiverName = null,
            caregiverEmail = ""
        )

        assertEquals("p1", partialData["patientUid"])
        assertEquals("c1", partialData["caregiverUid"])
        assertEquals("AB12CD34", partialData["code"])
        assertEquals("pending", partialData["status"])
        assertFalse(partialData.containsKey("patientName"))
        assertFalse(partialData.containsKey("caregiverName"))
        assertFalse(partialData.containsKey("caregiverEmail"))
    }

    // =========================================================================
    // SECTION 2: MODEL AND VIEWMODEL INTERACTION TESTS
    // =========================================================================

    private class FakeCaregiverLinkRepository : CaregiverLinkRepository() {
        var requestedCode: String? = null
        var shouldSucceedRequest: Boolean = true
        var requestErrorMessage: String? = null

        var canceledLink: CaregiverLink? = null
        var shouldSucceedCancel: Boolean = true
        var cancelErrorMessage: String? = null

        var fakeLinks: List<CaregiverLink> = emptyList()

        override suspend fun requestLink(code: String): Result<Unit> {
            requestedCode = code
            return if (shouldSucceedRequest) {
                Result.success(Unit)
            } else {
                Result.failure(Exception(requestErrorMessage ?: "Invalid or expired code"))
            }
        }

        override fun observeMyLinks(): Flow<List<CaregiverLink>> {
            return flowOf(fakeLinks)
        }

        override suspend fun cancelOrLeave(link: CaregiverLink): Result<Unit> {
            canceledLink = link
            return if (shouldSucceedCancel) {
                Result.success(Unit)
            } else {
                Result.failure(Exception(cancelErrorMessage ?: "Network error. Please check your connection."))
            }
        }
    }

    @Test
    fun `CaregiverLink model holds expected fields and defaults`() {
        val link = CaregiverLink(
            id = "patient1_caregiver1",
            patientUid = "patient1",
            caregiverUid = "caregiver1",
            code = "AB12CD34",
            status = "pending",
            patientName = "Alice",
            caregiverName = "Bob",
            caregiverEmail = "bob@example.com",
            createdAt = Timestamp.now()
        )

        assertEquals("patient1_caregiver1", link.id)
        assertEquals("patient1", link.patientUid)
        assertEquals("caregiver1", link.caregiverUid)
        assertEquals("AB12CD34", link.code)
        assertEquals("pending", link.status)
        assertEquals("Alice", link.patientName)
        assertEquals("Bob", link.caregiverName)
        assertEquals("bob@example.com", link.caregiverEmail)
        assertNotNull(link.createdAt)
    }

    @Test
    fun `CareForViewModel automatically uppercases input and limits to 8 characters`() {
        val fakeRepo = FakeCaregiverLinkRepository()
        val viewModel = CareForViewModel(fakeRepo, createTestScope())

        viewModel.onInviteCodeChanged("ab12cd34extra")

        assertEquals("AB12CD34", viewModel.inviteCode.value)
        assertNull(viewModel.errorMessage.value)
        assertNull(viewModel.successMessage.value)
    }

    @Test
    fun `CareForViewModel filters non-alphanumeric characters`() {
        val fakeRepo = FakeCaregiverLinkRepository()
        val viewModel = CareForViewModel(fakeRepo, createTestScope())

        viewModel.onInviteCodeChanged("ab-12_cd!34")

        assertEquals("AB12CD34", viewModel.inviteCode.value)
    }

    @Test
    fun `CareForViewModel rejects invite code shorter than 8 characters`() = runBlocking {
        val fakeRepo = FakeCaregiverLinkRepository()
        val viewModel = CareForViewModel(fakeRepo, createTestScope())

        viewModel.onInviteCodeChanged("AB12CD")
        viewModel.submitInviteCode()

        assertEquals("Invite code must be 8 characters", viewModel.errorMessage.value)
        assertNull(fakeRepo.requestedCode)
    }

    @Test
    fun `CareForViewModel successful submission updates state and clears code`() = runBlocking {
        val fakeRepo = FakeCaregiverLinkRepository()
        fakeRepo.shouldSucceedRequest = true
        val viewModel = CareForViewModel(fakeRepo, createTestScope())

        viewModel.onInviteCodeChanged("AB12CD34")
        val job = viewModel.submitInviteCode()
        job.join()

        assertEquals("AB12CD34", fakeRepo.requestedCode)
        assertEquals("", viewModel.inviteCode.value)
        assertEquals("Link request sent successfully", viewModel.successMessage.value)
        assertNull(viewModel.errorMessage.value)
        assertFalse(viewModel.isSubmitting.value)
    }

    @Test
    fun `CareForViewModel failed submission reflects repository error`() = runBlocking {
        val fakeRepo = FakeCaregiverLinkRepository()
        fakeRepo.shouldSucceedRequest = false
        fakeRepo.requestErrorMessage = "You can't link to yourself"
        val viewModel = CareForViewModel(fakeRepo, createTestScope())

        viewModel.onInviteCodeChanged("AB12CD34")
        val job = viewModel.submitInviteCode()
        job.join()

        assertEquals("You can't link to yourself", viewModel.errorMessage.value)
        assertNull(viewModel.successMessage.value)
        assertFalse(viewModel.isSubmitting.value)
    }

    @Test
    fun `CareForViewModel handles link cancellation dialog flow`() = runBlocking {
        val fakeRepo = FakeCaregiverLinkRepository()
        val viewModel = CareForViewModel(fakeRepo, createTestScope())

        val link = CaregiverLink(
            id = "patient1_caregiver1",
            patientUid = "patient1",
            caregiverUid = "caregiver1",
            code = "AB12CD34",
            status = "pending"
        )

        // Request cancel dialog
        viewModel.requestCancelOrLeave(link)
        assertEquals(link, viewModel.linkToCancelOrLeave.value)

        // Dismiss dialog
        viewModel.dismissConfirmDialog()
        assertNull(viewModel.linkToCancelOrLeave.value)

        // Request again and confirm
        viewModel.requestCancelOrLeave(link)
        val job = viewModel.confirmCancelOrLeave()
        job?.join()

        assertNull(viewModel.linkToCancelOrLeave.value)
        assertEquals(link, fakeRepo.canceledLink)
    }

    @Test
    fun `CareForViewModel cancellation error sets error message`() = runBlocking {
        val fakeRepo = FakeCaregiverLinkRepository()
        fakeRepo.shouldSucceedCancel = false
        fakeRepo.cancelErrorMessage = "Network error. Please check your connection."
        val viewModel = CareForViewModel(fakeRepo, createTestScope())

        val link = CaregiverLink(
            id = "patient1_caregiver1",
            patientUid = "patient1",
            caregiverUid = "caregiver1",
            code = "AB12CD34",
            status = "active"
        )

        viewModel.requestCancelOrLeave(link)
        val job = viewModel.confirmCancelOrLeave()
        job?.join()

        assertEquals("Network error. Please check your connection.", viewModel.errorMessage.value)
    }
}
