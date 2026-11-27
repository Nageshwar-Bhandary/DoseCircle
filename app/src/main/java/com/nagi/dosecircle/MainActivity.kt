
package com.nagi.dosecircle

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import kotlinx.coroutines.launch
import com.nagi.dosecircle.ui.screens.AddMedicationScreen
import com.nagi.dosecircle.ui.screens.CaregiversScreen
import com.nagi.dosecircle.ui.screens.CareForScreen
import com.nagi.dosecircle.ui.screens.DashboardScreen
import com.nagi.dosecircle.ui.screens.HistoryScreen
import com.nagi.dosecircle.ui.screens.MedicationListScreen
import com.nagi.dosecircle.ui.screens.PatientDashboardScreen
import com.nagi.dosecircle.ui.screens.SignInScreen
import com.nagi.dosecircle.ui.theme.DoseCircleTheme
import com.nagi.dosecircle.viewmodel.AddMedicationViewModel
import com.nagi.dosecircle.viewmodel.CaregiverViewModel
import com.nagi.dosecircle.viewmodel.CareForViewModel
import com.nagi.dosecircle.viewmodel.DashboardViewModel
import com.nagi.dosecircle.viewmodel.HistoryViewModel
import com.nagi.dosecircle.viewmodel.MedicationListViewModel
import com.nagi.dosecircle.viewmodel.PatientDashboardViewModel

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as DoseCircleApp

        setContent {
            DoseCircleTheme {
                val navController = rememberNavController()

                val startDestination =
                    if (app.authRepository.currentUser != null) {
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
                        val coroutineScope = rememberCoroutineScope()

                        MedicationListScreen(
                            viewModel = viewModel,
                            onNavigateToAddMedication = {
                                navController.navigate("add_medication")
                            },
                            onNavigateToEditMedication = { medicationId ->
                                navController.navigate("edit_medication/$medicationId")
                            },
                            onSignOut = {
                                coroutineScope.launch {
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
                            },
                            onNavigateToDashboard = {
                                navController.navigate("dashboard")
                            },
                            onNavigateToHistory = {
                                navController.navigate("history")
                            },
                            onNavigateToCaregivers = {
                                navController.navigate("caregivers")
                            },
                            onNavigateToCareFor = {
                                navController.navigate("care_for")
                            }
                        )
                    }

                    composable("caregivers") {
                        val viewModel: CaregiverViewModel = viewModel(
                            factory = CaregiverViewModel.provideFactory(app)
                        )

                        CaregiversScreen(
                            viewModel = viewModel,
                            onNavigateBack = {
                                navController.popBackStack()
                            }
                        )
                    }

                    composable("care_for") {
                        val viewModel: CareForViewModel = viewModel(
                            factory = CareForViewModel.provideFactory(app)
                        )

                        CareForScreen(
                            viewModel = viewModel,
                            onNavigateBack = {
                                navController.popBackStack()
                            },
                            onOpenPatientDashboard = { patientUid ->
                                navController.navigate("patient_dashboard/$patientUid")
                            }
                        )
                    }

                    composable(
                        route = "patient_dashboard/{patientUid}",
                        arguments = listOf(navArgument("patientUid") { type = NavType.StringType })
                    ) { backStackEntry ->
                        val patientUid = backStackEntry.arguments?.getString("patientUid") ?: ""
                        val viewModel: PatientDashboardViewModel = viewModel(
                            factory = PatientDashboardViewModel.provideFactory(app, patientUid)
                        )

                        PatientDashboardScreen(
                            viewModel = viewModel,
                            onNavigateBack = {
                                navController.popBackStack()
                            }
                        )
                    }

                    composable("dashboard") {
                        val viewModel: DashboardViewModel = viewModel(
                            factory = DashboardViewModel.provideFactory(app)
                        )

                        DashboardScreen(
                            viewModel = viewModel,
                            onNavigateBack = {
                                navController.popBackStack()
                            }
                        )
                    }

                    composable("history") {
                        val viewModel: HistoryViewModel = viewModel(
                            factory = HistoryViewModel.provideFactory(app)
                        )

                        HistoryScreen(
                            viewModel = viewModel,
                            onNavigateBack = {
                                navController.popBackStack()
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
                            navArgument("medicationId") {
                                type = NavType.LongType
                            }
                        )
                    ) { backStackEntry ->
                        val medicationId =
                            backStackEntry.arguments?.getLong("medicationId")

                        val viewModel: AddMedicationViewModel = viewModel(
                            key = "edit_medication_$medicationId",
                            factory = AddMedicationViewModel.provideFactory(
                                app,
                                medicationId
                            )
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
