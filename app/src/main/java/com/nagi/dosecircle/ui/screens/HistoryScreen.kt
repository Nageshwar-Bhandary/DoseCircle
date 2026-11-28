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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nagi.dosecircle.data.entity.DoseStatus
import com.nagi.dosecircle.viewmodel.HistoryDoseItem
import com.nagi.dosecircle.viewmodel.HistoryRangeType
import com.nagi.dosecircle.viewmodel.HistoryViewModel

// Lavender Glassmorphism palette
private val GlassCardBorder = Color(0xFFE4DFFC)
private val TakenPastel = Color(0xFF2E9F63)
private val TakenPastelBg = Color(0xFFE8F7ED)
private val SkippedPastel = Color(0xFF7B8794)
private val SkippedPastelBg = Color(0xFFF0F4F8)
private val MissedPastel = Color(0xFFE05368)
private val MissedPastelBg = Color(0xFFFFECEF)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Medication History",
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
            // Compact Segmented selector (Daily, Weekly, Monthly)
            RangeSelector(
                selectedRange = uiState.rangeType,
                onRangeSelected = { viewModel.setRangeType(it) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp)
            )

            // Compact Date navigation row (for Daily and Monthly) or Range Label
            if (uiState.rangeType != HistoryRangeType.WEEKLY) {
                DateNavigationRow(
                    label = uiState.rangeLabel,
                    onPrevious = {
                        if (uiState.rangeType == HistoryRangeType.DAILY) {
                            viewModel.previousDay()
                        } else {
                            viewModel.previousMonth()
                        }
                    },
                    onNext = {
                        if (uiState.rangeType == HistoryRangeType.DAILY) {
                            viewModel.nextDay()
                        } else {
                            viewModel.nextMonth()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 1.dp)
                )
            } else {
                Text(
                    text = uiState.rangeLabel,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF8175C9),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 3.dp),
                    textAlign = TextAlign.Center
                )
            }

            // Compact History Summary Card (Adherence % & Metrics)
            HistorySummaryCard(
                takenCount = uiState.takenCount,
                skippedCount = uiState.skippedCount,
                missedCount = uiState.missedCount,
                adherencePercentage = uiState.adherencePercentage,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            )

            // Content List or Empty State
            if (uiState.groupedEvents.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No history yet.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color(0xFF6B6687),
                        textAlign = TextAlign.Center
                    )
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
                    uiState.groupedEvents.forEach { group ->
                        item(key = "header_${group.date}") {
                            Text(
                                text = group.dateHeader,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF6554F0),
                                modifier = Modifier.padding(start = 2.dp, top = 6.dp, bottom = 2.dp)
                            )
                        }

                        items(group.items, key = { it.doseEvent.id }) { item ->
                            HistoryItemCard(item = item)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RangeSelector(
    selectedRange: HistoryRangeType,
    onRangeSelected: (HistoryRangeType) -> Unit,
    modifier: Modifier = Modifier
) {
    val ranges = listOf(
        HistoryRangeType.DAILY to "Daily",
        HistoryRangeType.WEEKLY to "Weekly",
        HistoryRangeType.MONTHLY to "Monthly"
    )

    SingleChoiceSegmentedButtonRow(
        modifier = modifier
    ) {
        ranges.forEachIndexed { index, (range, label) ->
            SegmentedButton(
                shape = SegmentedButtonDefaults.itemShape(index = index, count = ranges.size),
                onClick = { onRangeSelected(range) },
                selected = selectedRange == range,
                colors = SegmentedButtonDefaults.colors(
                    activeContainerColor = Color(0xFFE8E3FF),
                    activeContentColor = Color(0xFF6554F0),
                    inactiveContainerColor = Color.White.copy(alpha = 0.8f),
                    inactiveContentColor = Color(0xFF6B6687),
                    activeBorderColor = Color(0xFFD2CBF7),
                    inactiveBorderColor = Color(0xFFE4DFFC)
                )
            ) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (selectedRange == range) FontWeight.Bold else FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun DateNavigationRow(
    label: String,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        IconButton(
            onClick = onPrevious,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = "Previous",
                tint = Color(0xFF6554F0),
                modifier = Modifier.size(20.dp)
            )
        }

        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF24213A)
        )

        IconButton(
            onClick = onNext,
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = "Next",
                tint = Color(0xFF6554F0),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun HistorySummaryCard(
    takenCount: Int,
    skippedCount: Int,
    missedCount: Int,
    adherencePercentage: Int,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.White
        ),
        border = BorderStroke(1.dp, GlassCardBorder),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.5.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Adherence Section
            Column(
                modifier = Modifier.padding(end = 12.dp),
                horizontalAlignment = Alignment.Start
            ) {
                Text(
                    text = "Adherence",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF6B6687)
                )
                Text(
                    text = "$adherencePercentage%",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (adherencePercentage >= 80) {
                        Color(0xFF2E9F63)
                    } else if (adherencePercentage >= 50) {
                        Color(0xFFD97706)
                    } else {
                        Color(0xFFE05368)
                    }
                )
            }

            // Compact Metric Items Row
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                SummaryMetricItem(
                    label = "Taken",
                    count = takenCount,
                    textColor = TakenPastel,
                    bgColor = TakenPastelBg,
                    borderColor = Color(0xFFB7E4C7),
                    modifier = Modifier.weight(1f)
                )
                SummaryMetricItem(
                    label = "Skipped",
                    count = skippedCount,
                    textColor = SkippedPastel,
                    bgColor = SkippedPastelBg,
                    borderColor = Color(0xFFE2E8F0),
                    modifier = Modifier.weight(1f)
                )
                SummaryMetricItem(
                    label = "Missed",
                    count = missedCount,
                    textColor = MissedPastel,
                    bgColor = MissedPastelBg,
                    borderColor = Color(0xFFFECDD3),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun SummaryMetricItem(
    label: String,
    count: Int,
    textColor: Color,
    bgColor: Color,
    borderColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = bgColor.copy(alpha = 0.90f),
        border = BorderStroke(1.dp, borderColor.copy(alpha = 0.8f))
    ) {
        Column(
            modifier = Modifier
                .padding(vertical = 4.dp, horizontal = 2.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = textColor
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                fontWeight = FontWeight.SemiBold,
                color = textColor.copy(alpha = 0.85f)
            )
        }
    }
}

@Composable
private fun HistoryItemCard(
    item: HistoryDoseItem,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.White
        ),
        border = BorderStroke(1.dp, GlassCardBorder),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 12.dp, vertical = 10.dp)
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
                    text = "Scheduled: ${item.scheduledTimeFormatted}",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF8175C9),
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            HistoryStatusChip(status = item.doseEvent.status)
        }
    }
}

private data class HistoryChipStyle(
    val textColor: Color,
    val bgColor: Color,
    val borderColor: Color,
    val label: String
)

@Composable
private fun HistoryStatusChip(
    status: DoseStatus,
    modifier: Modifier = Modifier
) {
    val style = when (status) {
        DoseStatus.TAKEN -> HistoryChipStyle(TakenPastel, TakenPastelBg, Color(0xFFB7E4C7), "Taken")
        DoseStatus.SKIPPED -> HistoryChipStyle(SkippedPastel, SkippedPastelBg, Color(0xFFE2E8F0), "Skipped")
        DoseStatus.MISSED -> HistoryChipStyle(MissedPastel, MissedPastelBg, Color(0xFFFECDD3), "Missed")
        DoseStatus.PENDING -> HistoryChipStyle(Color(0xFFD97706), Color(0xFFFFF7ED), Color(0xFFFDE68A), "Pending")
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
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
