package com.nagi.dosecircle

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.nagi.dosecircle.ui.screens.MedicationListScreen
import com.nagi.dosecircle.ui.theme.DoseCircleTheme
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
                        MedicationListScreen(viewModel = viewModel)
                    }
                }
            }
        }
    }
}
