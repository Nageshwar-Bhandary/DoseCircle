package com.nagi.dosecircle

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.nagi.dosecircle.ui.screens.AddMedicationScreen
import com.nagi.dosecircle.ui.screens.MedicationListScreen
import com.nagi.dosecircle.ui.screens.SignInScreen
import com.nagi.dosecircle.ui.theme.DoseCircleTheme
import com.nagi.dosecircle.viewmodel.AddMedicationViewModel
import com.nagi.dosecircle.viewmodel.MedicationListViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as DoseCircleApp

        setContent {
            DoseCircleTheme {
                val navController = rememberNavController()
                val startDestination = if (app.authRepository.currentUser != null) {
                    "medication_list"
                } else {
                    "sign_in"
                }

                NavHost(
                    navController = navController,
                    startDestination = startDestination
                ) {
                    composable("sign_in") {
                        SignInScreen(
                            onAuthenticated = {
                                navController.navigate("medication_list") {
                                    popUpTo("sign_in") {
                                        inclusive = true
                                    }
                                    launchSingleTop = true
                                }
                            }
                        )
                    }
                    composable("medication_list") {
                        val viewModel: MedicationListViewModel = viewModel(
                            factory = MedicationListViewModel.provideFactory(app)
                        )
                        MedicationListScreen(
                            viewModel = viewModel,
                            onNavigateToAddMedication = {
                                navController.navigate("add_medication")
                            },
                            onNavigateToEditMedication = { medicationId ->
                                navController.navigate("edit_medication/$medicationId")
                            },
                            onSignOut = {
                                val logoutResult = app.authRepository.logout()
                                if (logoutResult.isSuccess) {
                                    navController.navigate("sign_in") {
                                        popUpTo("medication_list") {
                                            inclusive = true
                                        }
                                        launchSingleTop = true
                                    }
                                }
                            }
                        )
                    }
                    composable("add_medication") {
                        val viewModel: AddMedicationViewModel = viewModel(
                            factory = AddMedicationViewModel.provideFactory(app)
                        )
                        AddMedicationScreen(
                            viewModel = viewModel,
                            onNavigateBack = {
                                navController.popBackStack()
                            }
                        )
                    }
                    composable(
                        route = "edit_medication/{medicationId}",
                        arguments = listOf(
                            navArgument("medicationId") { type = NavType.LongType }
                        )
                    ) { backStackEntry ->
                        val medicationId = backStackEntry.arguments?.getLong("medicationId")
                        val viewModel: AddMedicationViewModel = viewModel(
                            key = "edit_medication_$medicationId",
                            factory = AddMedicationViewModel.provideFactory(app, medicationId)
                        )
                        AddMedicationScreen(
                            viewModel = viewModel,
                            onNavigateBack = {
                                navController.popBackStack()
                            }
                        )
                    }
                }
            }
        }
    }
}
