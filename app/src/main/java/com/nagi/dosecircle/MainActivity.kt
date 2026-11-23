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
import com.nagi.dosecircle.ui.screens.DashboardScreen
import com.nagi.dosecircle.ui.screens.MedicationListScreen
import com.nagi.dosecircle.ui.theme.DoseCircleTheme
import com.nagi.dosecircle.viewmodel.AddMedicationViewModel
import com.nagi.dosecircle.viewmodel.DashboardViewModel
import com.nagi.dosecircle.viewmodel.MedicationListViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as DoseCircleApp

        setContent {
            DoseCircleTheme {
                val navController = rememberNavController()
                NavHost(
                    navController = navController,
                    startDestination = "medication_list"
                ) {
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
                            onNavigateToDashboard = {
                                navController.navigate("dashboard")
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
