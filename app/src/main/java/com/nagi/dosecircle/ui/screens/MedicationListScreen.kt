
package com.nagi.dosecircle.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil.compose.rememberAsyncImagePainter
import com.nagi.dosecircle.DoseCircleApp
import com.nagi.dosecircle.R
import com.nagi.dosecircle.data.entity.Medication
import com.nagi.dosecircle.util.RequestActivityRecognitionPermissionEffect
import com.nagi.dosecircle.util.RequestNotificationPermissionEffect
import com.nagi.dosecircle.viewmodel.MedicationListViewModel
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MedicationListScreen(
    viewModel: MedicationListViewModel,
    onNavigateToAddMedication: () -> Unit,
    onNavigateToEditMedication: (Long) -> Unit,
    onSignOut: () -> Unit = {},
    seniorMode: Boolean = false,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val app = context.applicationContext as? DoseCircleApp

    RequestNotificationPermissionEffect()

    RequestActivityRecognitionPermissionEffect(
        onPermissionResult = { isGranted ->
            if (isGranted && app != null) {
                val personId = app.sessionManager.currentPersonId.value
                if (personId != null) {
                    app.stepTracker.onPermissionGranted(personId)
                }
            }
        }
    )

    val medications by viewModel.medications.collectAsState()
    var medicationToDelete by remember { mutableStateOf<Medication?>(null) }
    var medicationToRefill by remember { mutableStateOf<Medication?>(null) }
    var refillQuantityText by remember { mutableStateOf("") }
    var refillErrorText by remember { mutableStateOf<String?>(null) }
    var showSignOutDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text("My Medicines")
                },
                actions = {
                    IconButton(
                        onClick = {
                            showSignOutDialog = true
                        }
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ExitToApp,
                            contentDescription = "Sign Out"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onNavigateToAddMedication
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Add Medicine"
                )
            }
        },
        modifier = modifier
    ) { innerPadding ->

        if (medications.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No medicines yet. Tap + to add one.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(
                    items = medications,
                    key = { medication -> "medication_${medication.id}" }
                ) { medication ->
                    MedicationItemCard(
                        medication = medication,
                        seniorMode = seniorMode,
                        onClick = {
                            onNavigateToEditMedication(medication.id)
                        },
                        onDeleteClick = {
                            medicationToDelete = medication
                        },
                        onRefillClick = {
                            medicationToRefill = medication
                            refillQuantityText = ""
                            refillErrorText = null
                        }
                    )
                }
            }
        }

        medicationToDelete?.let { medication ->
            AlertDialog(
                onDismissRequest = {
                    medicationToDelete = null
                },
                title = {
                    Text("Delete Medicine")
                },
                text = {
                    Text("Are you sure you want to delete ${medication.name}?")
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.deleteMedication(medication)
                            medicationToDelete = null
                        }
                    ) {
                        Text(
                            "Delete",
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            medicationToDelete = null
                        }
                    ) {
                        Text("Cancel")
                    }
                }
            )
        }

        medicationToRefill?.let { medication ->
            AlertDialog(
                onDismissRequest = {
                    medicationToRefill = null
                    refillQuantityText = ""
                    refillErrorText = null
                },
                title = {
                    Text("Refill ${medication.name}")
                },
                text = {
                    Column {
                        Text(
                            "Enter the quantity to add to current stock (${medication.stockCount}):"
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = refillQuantityText,
                            onValueChange = {
                                refillQuantityText = it
                                refillErrorText = null
                            },
                            label = {
                                Text("Quantity")
                            },
                            isError = refillErrorText != null,
                            supportingText = refillErrorText?.let {
                                { Text(it) }
                            },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Number
                            ),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            val qty = refillQuantityText.toIntOrNull()
                            if (qty == null || qty <= 0) {
                                refillErrorText =
                                    "Enter a valid positive number"
                            } else {
                                viewModel.refillMedication(medication, qty)
                                medicationToRefill = null
                                refillQuantityText = ""
                                refillErrorText = null
                            }
                        },
                        modifier = if (seniorMode) Modifier.height(56.dp) else Modifier
                    ) {
                        Text("Refill", style = if (seniorMode) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge)
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            medicationToRefill = null
                            refillQuantityText = ""
                            refillErrorText = null
                        },
                        modifier = if (seniorMode) Modifier.height(56.dp) else Modifier
                    ) {
                        Text("Cancel", style = if (seniorMode) MaterialTheme.typography.titleMedium else MaterialTheme.typography.labelLarge)
                    }
                }
            )
        }

        if (showSignOutDialog) {
            AlertDialog(
                onDismissRequest = {
                    showSignOutDialog = false
                },
                title = {
                    Text("Sign Out")
                },
                text = {
                    Text("Are you sure you want to sign out of DoseCircle?")
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showSignOutDialog = false
                            onSignOut()
                        }
                    ) {
                        Text(
                            "Sign Out",
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = {
                            showSignOutDialog = false
                        }
                    ) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}

@Composable
fun MedicationItemCard(
    medication: Medication,
    onClick: () -> Unit,
    onDeleteClick: () -> Unit,
    onRefillClick: () -> Unit,
    seniorMode: Boolean = false,
    modifier: Modifier = Modifier
) {
    val isOutOfStock = medication.stockCount == 0
    val isLowStock =
        !isOutOfStock && medication.stockCount <= medication.lowStockThreshold

    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 2.dp
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(if (seniorMode) 20.dp else 16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val photoFile = medication.photoUri?.let {
                    File(it)
                }

                if (photoFile != null && photoFile.exists()) {
                    Image(
                        painter = rememberAsyncImagePainter(photoFile),
                        contentDescription = "${medication.name} photo",
                        modifier = Modifier
                            .size(if (seniorMode) 64.dp else 56.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(if (seniorMode) 64.dp else 56.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                MaterialTheme.colorScheme.primaryContainer
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = "Pill icon",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(if (seniorMode) 32.dp else 28.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(
                    modifier = Modifier.weight(1f)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = medication.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )

                        if (isOutOfStock) {
                            Box(
                                modifier = Modifier
                                    .background(
                                        color = MaterialTheme.colorScheme.error,
                                        shape = RoundedCornerShape(4.dp)
                                    )
                                    .padding(
                                        horizontal = 6.dp,
                                        vertical = 2.dp
                                    )
                            ) {
                                Text(
                                    text = "Out of Stock",
                                    color = Color.White,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        } else if (isLowStock) {
                            Box(
                                modifier = Modifier
                                    .background(
                                        color = Color(0xFFFF9800),
                                        shape = RoundedCornerShape(4.dp)
                                    )
                                    .padding(
                                        horizontal = 6.dp,
                                        vertical = 2.dp
                                    )
                            ) {
                                Text(
                                    text = "Low stock",
                                    color = Color.White,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        text = "Dosage: ${medication.dosage}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(2.dp))

                    Text(
                        text = "Stock: ${medication.stockCount}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isOutOfStock) {
                            MaterialTheme.colorScheme.error
                        } else if (isLowStock) {
                            Color(0xFFFF9800)
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }

                if (!seniorMode) {
                    TextButton(onClick = onRefillClick) {
                        Text("Refill")
                    }

                    IconButton(onClick = onDeleteClick) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete ${medication.name}",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            if (seniorMode) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = onRefillClick,
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp)
                    ) {
                        Text(
                            "Refill",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    OutlinedButton(
                        onClick = onDeleteClick,
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        ),
                        modifier = Modifier
                            .weight(1f)
                            .height(56.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            "Delete",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
