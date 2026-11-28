package com.nagi.dosecircle.ui.screens

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.firebase.auth.FirebaseAuth
import com.nagi.dosecircle.data.entity.DoseStatus
import com.nagi.dosecircle.viewmodel.DashboardViewModel
import com.nagi.dosecircle.viewmodel.TodayDoseItem
import com.nagi.dosecircle.viewmodel.UpcomingDoseItem

// Glassmorphism pastel colors
private val GlassSurface = Color(0xFFFFFFFF).copy(alpha = 0.85f)
private val GlassBorder = Color(0xFFFFFFFF).copy(alpha = 0.65f)
private val GlassCardBorder = Color(0xFFE4DFFC)

private val TakenPastel = Color(0xFF2E9F63)
private val TakenPastelBg = Color(0xFFE8F7ED)

private val SkippedPastel = Color(0xFF7B8794)
private val SkippedPastelBg = Color(0xFFF0F4F8)

private val MissedPastel = Color(0xFFE05368)
private val MissedPastelBg = Color(0xFFFFECEF)

private val PendingPastel = Color(0xFFD97706)
private val PendingPastelBg = Color(0xFFFFF7ED)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToSettings: () -> Unit = {},
    seniorMode: Boolean = false,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val displayName = remember {
        FirebaseAuth.getInstance().currentUser?.displayName?.trim()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Today's Schedule",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF24213A)
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color(0xFF6554F0),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = onNavigateToSettings,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings",
                            tint = Color(0xFF6554F0),
                            modifier = Modifier.size(22.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        },
        containerColor = Color(0xFFF1EEFF),
        modifier = modifier
    ) { innerPadding ->

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    start = innerPadding.calculateLeftPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
                    end = innerPadding.calculateRightPadding(androidx.compose.ui.unit.LayoutDirection.Ltr),
                    top = innerPadding.calculateTopPadding(),
                    bottom = innerPadding.calculateBottomPadding()
                )
        ) {
            // Compact Greeting Section
            if (!displayName.isNullOrBlank()) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "Hi, $displayName",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFF24213A),
                        letterSpacing = (-0.3).sp
                    )
                    Text(
                        text = "Here is your medication plan for today",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF6B6687)
                    )
                }
            }

            // Compact Summary Stats Grid
            SummaryRow(
                takenCount = uiState.takenCount,
                skippedCount = uiState.skippedCount,
                missedCount = uiState.missedCount,
                pendingCount = uiState.pendingCount,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )

            if (uiState.todayDoses.isEmpty() && uiState.upcomingDoses.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Surface(
                        modifier = Modifier.fillMaxWidth(0.9f),
                        shape = RoundedCornerShape(16.dp),
                        color = GlassSurface,
                        border = BorderStroke(1.dp, GlassBorder),
                        shadowElevation = 2.dp
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(48.dp)
                                    .background(Color(0xFFE8E3FF), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DateRange,
                                    contentDescription = null,
                                    tint = Color(0xFF6554F0),
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "All Clear!",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF24213A)
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "No doses scheduled for today.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Color(0xFF6B6687),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = 2.dp,
                        bottom = 12.dp
                    ),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (uiState.todayDoses.isNotEmpty()) {
                        item(key = "today_header") {
                            Text(
                                text = "Today's Doses",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF24213A),
                                modifier = Modifier.padding(start = 2.dp, top = 4.dp, bottom = 2.dp)
                            )
                        }

                        items(
                            items = uiState.todayDoses,
                            key = { item -> "today_${item.doseEvent.id}" }
                        ) { item ->
                            TodayDoseCard(
                                item = item,
                                seniorMode = seniorMode,
                                onTaken = {
                                    viewModel.markDoseTaken(item.doseEvent.id)
                                },
                                onSkip = {
                                    viewModel.markDoseSkipped(item.doseEvent.id)
                                }
                            )
                        }
                    }

                    if (uiState.upcomingDoses.isNotEmpty()) {
                        item(key = "upcoming_header") {
                            Text(
                                text = "Upcoming",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF24213A),
                                modifier = Modifier.padding(start = 2.dp, top = 6.dp, bottom = 2.dp)
                            )
                        }

                        items(
                            items = uiState.upcomingDoses,
                            key = { item -> "upcoming_${item.scheduleTimeId}" }
                        ) { item ->
                            UpcomingDoseCard(
                                item = item,
                                seniorMode = seniorMode
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryRow(
    takenCount: Int,
    skippedCount: Int,
    missedCount: Int,
    pendingCount: Int,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SummaryCard(
            label = "Taken",
            count = takenCount,
            textColor = TakenPastel,
            bgColor = TakenPastelBg,
            borderColor = Color(0xFFB7E4C7),
            modifier = Modifier.weight(1f)
        )
        SummaryCard(
            label = "Pending",
            count = pendingCount,
            textColor = PendingPastel,
            bgColor = PendingPastelBg,
            borderColor = Color(0xFFFDE68A),
            modifier = Modifier.weight(1f)
        )
        SummaryCard(
            label = "Missed",
            count = missedCount,
            textColor = MissedPastel,
            bgColor = MissedPastelBg,
            borderColor = Color(0xFFFECDD3),
            modifier = Modifier.weight(1f)
        )
        SummaryCard(
            label = "Skipped",
            count = skippedCount,
            textColor = SkippedPastel,
            bgColor = SkippedPastelBg,
            borderColor = Color(0xFFE2E8F0),
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun SummaryCard(
    label: String,
    count: Int,
    textColor: Color,
    bgColor: Color,
    borderColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = bgColor.copy(alpha = 0.90f),
        border = BorderStroke(1.dp, borderColor.copy(alpha = 0.8f)),
        shadowElevation = 1.dp
    ) {
        Column(
            modifier = Modifier
                .padding(vertical = 6.dp, horizontal = 2.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.ExtraBold,
                color = textColor
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                fontWeight = FontWeight.SemiBold,
                color = textColor.copy(alpha = 0.85f)
            )
        }
    }
}

@Composable
private fun TodayDoseCard(
    item: TodayDoseItem,
    seniorMode: Boolean = false,
    onTaken: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.White
        ),
        border = BorderStroke(1.dp, GlassCardBorder),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.5.dp)
    ) {
        Column(
            modifier = Modifier
                .padding(
                    horizontal = if (seniorMode) 18.dp else 14.dp,
                    vertical = if (seniorMode) 16.dp else 12.dp
                )
                .fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(if (seniorMode) 42.dp else 34.dp)
                            .background(Color(0xFFE8E3FF), RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Notifications,
                            contentDescription = null,
                            tint = Color(0xFF6554F0),
                            modifier = Modifier.size(if (seniorMode) 22.dp else 18.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(10.dp))

                    Column {
                        Text(
                            text = item.medicationName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF24213A)
                        )
                        if (item.dosage.isNotBlank()) {
                            Text(
                                text = item.dosage,
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF6B6687)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                StatusChip(status = item.doseEvent.status)
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Scheduled: ${item.timeFormatted}",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = Color(0xFF8175C9)
                )
            }

            if (item.doseEvent.status == DoseStatus.PENDING) {
                Spacer(modifier = Modifier.height(if (seniorMode) 12.dp else 8.dp))
                if (seniorMode) {
                    // Senior mode: full width, at least 56dp tall buttons
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onTaken,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF6554F0)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Taken",
                                color = Color.White,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        OutlinedButton(
                            onClick = onSkip,
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.5.dp, Color(0xFFD2CBF7)),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = Color(0xFF6B6687)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                "Skip",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(
                            onClick = onSkip,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, Color(0xFFD2CBF7)),
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = Color(0xFF6B6687)
                            ),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            modifier = Modifier.padding(end = 8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                "Skip",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Button(
                            onClick = onTaken,
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF6554F0)
                            ),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                "Taken",
                                color = Color.White,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun UpcomingDoseCard(
    item: UpcomingDoseItem,
    seniorMode: Boolean = false,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.White.copy(alpha = 0.85f)
        ),
        border = BorderStroke(1.dp, GlassCardBorder),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(
                    horizontal = if (seniorMode) 18.dp else 14.dp,
                    vertical = if (seniorMode) 14.dp else 10.dp
                )
                .fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.medicationName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF24213A)
                )
                if (item.dosage.isNotBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = item.dosage,
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFF6B6687)
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Scheduled: ${item.timeFormatted}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF8175C9),
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFFE8E3FF),
                border = BorderStroke(1.dp, Color(0xFFD2CBF7))
            ) {
                Text(
                    text = "Upcoming",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF6554F0),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }
    }
}

private data class StatusChipStyle(
    val textColor: Color,
    val bgColor: Color,
    val borderColor: Color,
    val label: String
)

@Composable
private fun StatusChip(
    status: DoseStatus,
    modifier: Modifier = Modifier
) {
    val style = when (status) {
        DoseStatus.TAKEN -> StatusChipStyle(TakenPastel, TakenPastelBg, Color(0xFFB7E4C7), "Taken")
        DoseStatus.SKIPPED -> StatusChipStyle(SkippedPastel, SkippedPastelBg, Color(0xFFE2E8F0), "Skipped")
        DoseStatus.MISSED -> StatusChipStyle(MissedPastel, MissedPastelBg, Color(0xFFFECDD3), "Missed")
        DoseStatus.PENDING -> StatusChipStyle(PendingPastel, PendingPastelBg, Color(0xFFFDE68A), "Pending")
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = style.bgColor,
        border = BorderStroke(1.dp, style.borderColor),
        modifier = modifier
    ) {
        Text(
            text = style.label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = style.textColor,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}
