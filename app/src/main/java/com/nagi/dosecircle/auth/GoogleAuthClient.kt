package com.nagi.dosecircle.auth

import android.app.Activity
import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import com.nagi.dosecircle.R
import kotlinx.coroutines.CancellationException

/**
 * Result representing the outcome of a Google Sign-In Credential Manager request.
 */
sealed interface GoogleSignInResult {
    data class Success(val idToken: String) : GoogleSignInResult
    data object Cancelled : GoogleSignInResult
    data class Error(val message: String) : GoogleSignInResult
}

typealias GoogleAuthResult = GoogleSignInResult

/**
 * Helper to retrieve Google ID tokens using Android Credential Manager.
 *
 * Firebase authentication is deliberately not handled here; this helper only interacts
 * with Credential Manager to obtain the Google ID token and returns it to the caller.
 */
class GoogleAuthClient(
    private val context: Context? = null,
    private val credentialManager: CredentialManager? = null
) {

    private fun resolveCredentialManager(activity: Activity): CredentialManager {
        return credentialManager
            ?: context?.let { CredentialManager.create(it) }
            ?: CredentialManager.create(activity)
    }

    suspend fun signIn(activity: Activity): GoogleSignInResult {
        return try {
            val serverClientId = activity.getString(R.string.default_web_client_id).trim()
            if (serverClientId.isEmpty()) {
                return GoogleSignInResult.Error("Google Web Client ID is not configured.")
            }

            val googleIdOption = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId(serverClientId)
                .setAutoSelectEnabled(false)
                .build()

            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()

            val cm = resolveCredentialManager(activity)
            val response = cm.getCredential(request = request, context = activity)
            val credential = response.credential

            if (credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                val idToken = googleIdTokenCredential.idToken.trim()
                if (idToken.isNotEmpty()) {
                    GoogleSignInResult.Success(idToken)
                } else {
                    GoogleSignInResult.Error("Google ID token received was empty.")
                }
            } else {
                GoogleSignInResult.Error("Unexpected credential type received.")
            }
        } catch (e: CancellationException) {
            // Rethrow coroutine cancellation so cancellation propagates correctly
            throw e
        } catch (e: GetCredentialCancellationException) {
            // User cancelled or dismissed the credential picker dialog
            GoogleSignInResult.Cancelled
        } catch (e: NoCredentialException) {
            GoogleSignInResult.Error("No Google accounts found. Please add a Google account to your device.")
        } catch (e: GoogleIdTokenParsingException) {
            GoogleSignInResult.Error("Failed to parse Google ID token credential.")
        } catch (e: GetCredentialException) {
            GoogleSignInResult.Error(e.message ?: "Google Sign-In could not be completed.")
        } catch (e: Exception) {
            GoogleSignInResult.Error(e.message ?: "An unexpected error occurred during Google Sign-In.")
        }
    }

    suspend fun getGoogleIdToken(activity: Activity): GoogleSignInResult = signIn(activity)
}
