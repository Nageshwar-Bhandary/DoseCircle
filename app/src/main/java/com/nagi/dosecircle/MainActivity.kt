
package com.nagi.dosecircle

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.nagi.dosecircle.ui.screens.ActivityScreen
import com.nagi.dosecircle.ui.screens.AddMedicationScreen
import com.nagi.dosecircle.ui.screens.CareForScreen
import com.nagi.dosecircle.ui.screens.CaregiversScreen
import com.nagi.dosecircle.ui.screens.DashboardScreen
import com.nagi.dosecircle.ui.screens.FamilyScreen
import com.nagi.dosecircle.ui.screens.HistoryScreen
import com.nagi.dosecircle.ui.screens.MedicationListScreen
import com.nagi.dosecircle.ui.screens.PatientDashboardScreen
import com.nagi.dosecircle.ui.screens.SignInScreen
import com.nagi.dosecircle.ui.theme.DoseCircleTheme
import com.nagi.dosecircle.viewmodel.ActivityViewModel
import com.nagi.dosecircle.viewmodel.AddMedicationViewModel
import com.nagi.dosecircle.viewmodel.CareForViewModel
import com.nagi.dosecircle.viewmodel.CaregiverViewModel
import com.nagi.dosecircle.viewmodel.DashboardViewModel
import com.nagi.dosecircle.viewmodel.HistoryViewModel
import com.nagi.dosecircle.viewmodel.MedicationListViewModel
import com.nagi.dosecircle.viewmodel.PatientDashboardViewModel
import kotlinx.coroutines.launch

sealed class BottomNavItem(
    val route: String,
    val label: String
) {
    object Today : BottomNavItem("dashboard", "Today")
    object Medicines : BottomNavItem("medication_list", "Medicines")
    object History : BottomNavItem("history", "History")
    object Family : BottomNavItem("family", "Family")
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as DoseCircleApp

        setContent {
            DoseCircleTheme {
                val navController = rememberNavController()
                val navBackStackEntry by navController.currentBackStackEntryAsState()
                val currentRoute = navBackStackEntry?.destination?.route

                val topLevelRoutes = listOf("dashboard", "medication_list", "history", "family")
                val showBottomBar = currentRoute in topLevelRoutes

                val startDestination =
                    if (app.authRepository.currentUser != null) {
                        "dashboard"
                    } else {
                        "sign_in"
                    }

                Scaffold(
                    bottomBar = {
                        if (showBottomBar) {
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .navigationBarsPadding(),
                                color = Color.White.copy(alpha = 0.95f),
                                border = BorderStroke(1.dp, Color(0xFFE4DFFC)),
                                shadowElevation = 8.dp
                            ) {
                                NavigationBar(
                                    containerColor = Color.Transparent,
                                    tonalElevation = 0.dp
                                ) {
                                    val items = listOf(
                                        BottomNavItem.Today,
                                        BottomNavItem.Medicines,
                                        BottomNavItem.History,
                                        BottomNavItem.Family
                                    )
                                    items.forEach { item ->
                                        val isSelected = currentRoute == item.route
                                        NavigationBarItem(
                                            selected = isSelected,
                                            onClick = {
                                                if (currentRoute != item.route) {
                                                    navController.navigate(item.route) {
                                                        popUpTo(navController.graph.findStartDestination().id) {
                                                            saveState = true
                                                        }
                                                        launchSingleTop = true
                                                        restoreState = true
                                                    }
                                                }
                                            },
                                            icon = {
                                                when (item) {
                                                    BottomNavItem.Today -> Icon(
                                                        imageVector = Icons.Default.Home,
                                                        contentDescription = item.label
                                                    )
                                                    BottomNavItem.Medicines -> Icon(
                                                        painter = painterResource(id = R.drawable.ic_medication),
                                                        contentDescription = item.label
                                                    )
                                                    BottomNavItem.History -> Icon(
                                                        painter = painterResource(id = R.drawable.ic_history),
                                                        contentDescription = item.label
                                                    )
                                                    BottomNavItem.Family -> Icon(
                                                        imageVector = Icons.Default.Person,
                                                        contentDescription = item.label
                                                    )
                                                }
                                            },
                                            label = { Text(item.label) },
                                            colors = NavigationBarItemDefaults.colors(
                                                selectedIconColor = Color(0xFF6554F0),
                                                selectedTextColor = Color(0xFF6554F0),
                                                indicatorColor = Color(0xFFE8E3FF),
                                                unselectedIconColor = Color(0xFF8175C9),
                                                unselectedTextColor = Color(0xFF8175C9)
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                ) { innerPadding ->
                    NavHost(
                        navController = navController,
                        startDestination = startDestination,
                        modifier = Modifier.padding(innerPadding)
                    ) {
                        composable("sign_in") {
                            SignInScreen(
                                onAuthenticated = {
                                    navController.navigate("dashboard") {
                                        popUpTo("sign_in") {
                                            inclusive = true
                                        }
                                        launchSingleTop = true
                                    }
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
                                    finish()
                                },
                                onNavigateToActivity = {
                                    navController.navigate("activity")
                                }
                            )
                        }

                        composable("activity") {
                            val viewModel: ActivityViewModel = viewModel(
                                factory = ActivityViewModel.provideFactory(app)
                            )

                            ActivityScreen(
                                viewModel = viewModel,
                                onNavigateBack = {
                                    navController.popBackStack()
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
                                                popUpTo(0) {
                                                    inclusive = true
                                                }
                                                launchSingleTop = true
                                            }
                                        }
                                    }
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

                        composable("family") {
                            FamilyScreen(
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
}
