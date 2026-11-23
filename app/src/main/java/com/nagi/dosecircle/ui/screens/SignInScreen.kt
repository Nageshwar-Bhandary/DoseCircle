package com.nagi.dosecircle.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.firebase.auth.FirebaseUser
import com.nagi.dosecircle.DoseCircleApp
import com.nagi.dosecircle.auth.AuthState
import com.nagi.dosecircle.auth.GoogleAuthClient
import com.nagi.dosecircle.auth.GoogleSignInResult
import com.nagi.dosecircle.viewmodel.AuthViewModel
import com.nagi.dosecircle.viewmodel.PasswordResetState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Healthcare-themed Sign-In screen for DoseCircle supporting Google Authentication via Credential Manager.
 */
@Composable
fun SignInScreen(
    onAuthenticated: (FirebaseUser) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AuthViewModel = viewModel(
        factory = AuthViewModel.provideFactory(LocalContext.current.applicationContext as DoseCircleApp)
    ),
    googleAuthClient: GoogleAuthClient = remember { GoogleAuthClient() }
) {
    val authState by viewModel.authState.collectAsState()
    val passwordResetState by viewModel.passwordResetState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }

    var isSignUpMode by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }

    var isPasswordVisible by remember { mutableStateOf(false) }
    var isConfirmPasswordVisible by remember { mutableStateOf(false) }

    var isLaunchingGooglePicker by remember { mutableStateOf(false) }
    val isLoading = authState is AuthState.Loading ||
            passwordResetState is PasswordResetState.Loading ||
            isLaunchingGooglePicker

    LaunchedEffect(authState) {
        when (val state = authState) {
            is AuthState.Success -> {
                onAuthenticated(state.user)
            }
            is AuthState.Error -> {
                snackbarHostState.showSnackbar(
                    message = state.message,
                    duration = SnackbarDuration.Short
                )
            }
            else -> Unit
        }
    }

    LaunchedEffect(passwordResetState) {
        when (val state = passwordResetState) {
            is PasswordResetState.Sent -> {
                snackbarHostState.showSnackbar(
                    message = "Password reset email sent. Please check your inbox.",
                    duration = SnackbarDuration.Long
                )
                viewModel.resetPasswordResetState()
            }
            is PasswordResetState.Error -> {
                snackbarHostState.showSnackbar(
                    message = state.message,
                    duration = SnackbarDuration.Short
                )
                viewModel.resetPasswordResetState()
            }
            else -> Unit
        }
    }

    fun validateAndSubmitEmailAuth() {
        focusManager.clearFocus()
        val trimmedEmail = email.trim()
        val trimmedPassword = password.trim()
        val trimmedName = displayName.trim()

        if (isSignUpMode && trimmedName.isEmpty()) {
            coroutineScope.launch {
                snackbarHostState.showSnackbar("Please enter your full name.")
            }
            return
        }

        if (trimmedEmail.isEmpty()) {
            coroutineScope.launch {
                snackbarHostState.showSnackbar("Please enter your email address.")
            }
            return
        }

        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(trimmedEmail).matches()) {
            coroutineScope.launch {
                snackbarHostState.showSnackbar("Please enter a valid email address.")
            }
            return
        }

        if (trimmedPassword.isEmpty()) {
            coroutineScope.launch {
                snackbarHostState.showSnackbar("Please enter your password.")
            }
            return
        }

        if (trimmedPassword.length < 6) {
            coroutineScope.launch {
                snackbarHostState.showSnackbar("Password must be at least 6 characters.")
            }
            return
        }

        if (isSignUpMode) {
            val trimmedConfirm = confirmPassword.trim()
            if (trimmedPassword != trimmedConfirm) {
                coroutineScope.launch {
                    snackbarHostState.showSnackbar("Passwords do not match.")
                }
                return
            }
            viewModel.signUp(trimmedEmail, trimmedPassword, trimmedName)
        } else {
            viewModel.login(trimmedEmail, trimmedPassword)
        }
    }

    fun handleForgotPassword() {
        focusManager.clearFocus()
        val trimmedEmail = email.trim()
        if (trimmedEmail.isEmpty()) {
            coroutineScope.launch {
                snackbarHostState.showSnackbar("Enter your email address above to reset password.")
            }
            return
        }
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(trimmedEmail).matches()) {
            coroutineScope.launch {
                snackbarHostState.showSnackbar("Please enter a valid email address.")
            }
            return
        }
        viewModel.sendPasswordReset(trimmedEmail)
    }

    val onGoogleSignInClick: () -> Unit = {
        if (!isLoading) {
            if (activity == null) {
                coroutineScope.launch {
                    snackbarHostState.showSnackbar(
                        message = "Unable to open sign-in dialog. Please restart the application.",
                        duration = SnackbarDuration.Short
                    )
                }
            } else {
                isLaunchingGooglePicker = true
                coroutineScope.launch {
                    try {
                        val result = googleAuthClient.signIn(activity)
                        when (result) {
                            is GoogleSignInResult.Success -> {
                                viewModel.signInWithGoogle(result.idToken)
                            }
                            is GoogleSignInResult.Cancelled -> {
                                // User cancelled or dismissed picker; do nothing silently
                            }
                            is GoogleSignInResult.Error -> {
                                snackbarHostState.showSnackbar(
                                    message = result.message,
                                    duration = SnackbarDuration.Short
                                )
                            }
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        snackbarHostState.showSnackbar(
                            message = "Sign-in could not be completed. Please try again.",
                            duration = SnackbarDuration.Short
                        )
                    } finally {
                        isLaunchingGooglePicker = false
                    }
                }
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Spacer(modifier = Modifier.height(32.dp))

                // Brand Emblem
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .background(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Favorite,
                        contentDescription = "DoseCircle Emblem",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(44.dp)
                    )
                }

                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = "DoseCircle",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "Smart Medication Reminder & Caregiver Support",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(48.dp))

                // Authentication Card
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = if (isSignUpMode) "Create Account" else "Welcome",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = if (isSignUpMode) {
                                "Register to sync your medication schedules securely across all your devices."
                            } else {
                                "Sign in to keep your medications, schedules, and reminders safely synchronized across your devices."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        // Registration: Full Name
                        if (isSignUpMode) {
                            OutlinedTextField(
                                value = displayName,
                                onValueChange = { displayName = it },
                                label = { Text("Full Name") },
                                singleLine = true,
                                enabled = !isLoading,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Text,
                                    imeAction = ImeAction.Next
                                ),
                                keyboardActions = KeyboardActions(
                                    onNext = { focusManager.moveFocus(FocusDirection.Down) }
                                ),
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                        }

                        // Email Field
                        OutlinedTextField(
                            value = email,
                            onValueChange = { email = it },
                            label = { Text("Email Address") },
                            singleLine = true,
                            enabled = !isLoading,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Email,
                                imeAction = ImeAction.Next
                            ),
                            keyboardActions = KeyboardActions(
                                onNext = { focusManager.moveFocus(FocusDirection.Down) }
                            ),
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // Password Field
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("Password") },
                            singleLine = true,
                            enabled = !isLoading,
                            visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = if (isSignUpMode) ImeAction.Next else ImeAction.Done
                            ),
                            keyboardActions = KeyboardActions(
                                onNext = { focusManager.moveFocus(FocusDirection.Down) },
                                onDone = { validateAndSubmitEmailAuth() }
                            ),
                            trailingIcon = {
                                TextButton(onClick = { isPasswordVisible = !isPasswordVisible }) {
                                    Text(
                                        text = if (isPasswordVisible) "Hide" else "Show",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        )

                        // Registration: Confirm Password
                        if (isSignUpMode) {
                            Spacer(modifier = Modifier.height(16.dp))
                            OutlinedTextField(
                                value = confirmPassword,
                                onValueChange = { confirmPassword = it },
                                label = { Text("Confirm Password") },
                                singleLine = true,
                                enabled = !isLoading,
                                visualTransformation = if (isConfirmPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Password,
                                    imeAction = ImeAction.Done
                                ),
                                keyboardActions = KeyboardActions(
                                    onDone = { validateAndSubmitEmailAuth() }
                                ),
                                trailingIcon = {
                                    TextButton(onClick = { isConfirmPasswordVisible = !isConfirmPasswordVisible }) {
                                        Text(
                                            text = if (isConfirmPasswordVisible) "Hide" else "Show",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(12.dp)
                            )
                        }

                        // Forgot Password Link (Login mode only)
                        if (!isSignUpMode) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End
                            ) {
                                TextButton(
                                    onClick = { handleForgotPassword() },
                                    enabled = !isLoading
                                ) {
                                    Text(
                                        text = "Forgot Password?",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        } else {
                            Spacer(modifier = Modifier.height(20.dp))
                        }

                        // Primary Action Button (Login / Create Account)
                        Button(
                            onClick = { validateAndSubmitEmailAuth() },
                            enabled = !isLoading,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                            shape = RoundedCornerShape(25.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            if (isLoading && !isLaunchingGooglePicker) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = if (isSignUpMode) "Creating account..." else "Signing in...",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Medium
                                )
                            } else {
                                Text(
                                    text = if (isSignUpMode) "Create Account" else "Sign In",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        // Divider with "or"
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            HorizontalDivider(
                                modifier = Modifier.weight(1f),
                                color = MaterialTheme.colorScheme.outlineVariant
                            )
                            Text(
                                text = "or",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                            HorizontalDivider(
                                modifier = Modifier.weight(1f),
                                color = MaterialTheme.colorScheme.outlineVariant
                            )
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        // Google Sign-In Button
                        OutlinedButton(
                            onClick = onGoogleSignInClick,
                            enabled = !isLoading,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                            shape = RoundedCornerShape(25.dp),
                            colors = ButtonDefaults.outlinedButtonColors(
                                containerColor = MaterialTheme.colorScheme.surface,
                                contentColor = MaterialTheme.colorScheme.onSurface
                            ),
                            border = BorderStroke(
                                width = 1.dp,
                                color = if (isLoading) {
                                    MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                                } else {
                                    MaterialTheme.colorScheme.outlineVariant
                                }
                            )
                        ) {
                            if (isLaunchingGooglePicker) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = "Signing in...",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Medium
                                )
                            } else {
                                Image(
                                    imageVector = GoogleLogoVector,
                                    contentDescription = "Google Logo",
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = "Sign in with Google",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // Toggle Mode Button (Sign In <-> Create Account)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = if (isSignUpMode) "Already have an account?" else "Don't have an account?",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            TextButton(
                                onClick = {
                                    isSignUpMode = !isSignUpMode
                                    viewModel.resetState()
                                    viewModel.resetPasswordResetState()
                                },
                                enabled = !isLoading
                            ) {
                                Text(
                                    text = if (isSignUpMode) "Sign In" else "Sign Up",
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }

            // Footer Notice
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 32.dp)
            ) {
                Text(
                    text = "Your health data stays private and secure.\nOffline mode is supported at all times.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

/**
 * Extracts the host [Activity] from a given [Context], traversing through wrappers if present.
 */
private fun Context.findActivity(): Activity? {
    var currentContext = this
    while (currentContext is ContextWrapper) {
        if (currentContext is Activity) return currentContext
        currentContext = currentContext.baseContext
    }
    return null
}

/**
 * Google 4-color "G" logo vector.
 */
private val GoogleLogoVector: ImageVector by lazy {
    ImageVector.Builder(
        name = "GoogleLogo",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f
    ).apply {
        // Red segment
        addPath(
            pathData = listOf(
                PathNode.MoveTo(12f, 5f),
                PathNode.CurveTo(13.68f, 5f, 15.2f, 5.58f, 16.39f, 6.72f),
                PathNode.LineTo(19.74f, 3.37f),
                PathNode.CurveTo(17.7f, 1.47f, 15.06f, 0.28f, 12f, 0.28f),
                PathNode.CurveTo(7.31f, 0.28f, 3.25f, 2.97f, 1.28f, 6.89f),
                PathNode.LineTo(5.27f, 9.98f),
                PathNode.CurveTo(6.21f, 7.14f, 8.87f, 5f, 12f, 5f),
                PathNode.Close
            ),
            fill = SolidColor(Color(0xFFEA4335))
        )
        // Blue segment
        addPath(
            pathData = listOf(
                PathNode.MoveTo(23.49f, 12.28f),
                PathNode.CurveTo(23.49f, 11.49f, 23.41f, 10.73f, 23.3f, 10f),
                PathNode.LineTo(12f, 10f),
                PathNode.LineTo(12f, 14.51f),
                PathNode.LineTo(18.47f, 14.51f),
                PathNode.CurveTo(18.18f, 15.99f, 17.34f, 17.25f, 16.08f, 18.1f),
                PathNode.LineTo(19.94f, 21.1f),
                PathNode.CurveTo(22.2f, 19.01f, 23.49f, 15.93f, 23.49f, 12.28f),
                PathNode.Close
            ),
            fill = SolidColor(Color(0xFF4285F4))
        )
        // Yellow segment
        addPath(
            pathData = listOf(
                PathNode.MoveTo(5.27f, 14.02f),
                PathNode.CurveTo(5.03f, 13.29f, 4.9f, 12.51f, 4.9f, 11.71f),
                PathNode.CurveTo(4.9f, 10.91f, 5.03f, 10.13f, 5.27f, 9.4f),
                PathNode.LineTo(1.28f, 6.31f),
                PathNode.CurveTo(0.46f, 7.94f, 0f, 9.77f, 0f, 11.71f),
                PathNode.CurveTo(0f, 13.65f, 0.46f, 15.48f, 1.28f, 17.11f),
                PathNode.LineTo(5.27f, 14.02f),
                PathNode.Close
            ),
            fill = SolidColor(Color(0xFFFBBC05))
        )
        // Green segment
        addPath(
            pathData = listOf(
                PathNode.MoveTo(12f, 23.14f),
                PathNode.CurveTo(15.06f, 23.14f, 17.65f, 22.13f, 19.51f, 20.41f),
                PathNode.LineTo(15.65f, 17.41f),
                PathNode.CurveTo(14.65f, 18.08f, 13.43f, 18.5f, 12f, 18.5f),
                PathNode.CurveTo(8.87f, 18.5f, 6.21f, 16.36f, 5.27f, 13.52f),
                PathNode.LineTo(1.28f, 16.61f),
                PathNode.CurveTo(3.25f, 20.53f, 7.31f, 23.14f, 12f, 23.14f),
                PathNode.Close
            ),
            fill = SolidColor(Color(0xFF34A853))
        )
    }.build()
}
