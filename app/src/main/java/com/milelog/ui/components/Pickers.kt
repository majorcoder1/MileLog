package com.milelog.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircleOutline
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.milelog.data.Category
import com.milelog.data.Period
import com.milelog.data.Purpose
import com.milelog.data.Vehicle
import com.milelog.ui.theme.Blue
import com.milelog.ui.theme.Card
import com.milelog.ui.theme.Money
import com.milelog.ui.theme.Spend
import com.milelog.ui.theme.TextHi
import com.milelog.ui.theme.TextMid
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SheetList(
    title: String?,
    onDismiss: () -> Unit,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Card) {
        Column(Modifier.navigationBarsPadding().padding(bottom = 12.dp)) {
            if (title != null) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    color = TextHi,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                )
            }
            content()
        }
    }
}

@Composable
fun SheetRow(
    label: String,
    selected: Boolean = false,
    dot: Color? = null,
    icon: ImageVector? = null,
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (dot != null) {
            Box(Modifier.size(11.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(12.dp))
        }
        if (icon != null) {
            Icon(icon, null, tint = TextMid, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(14.dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (selected) Blue else TextHi,
            modifier = Modifier.weight(1f)
        )
        if (selected) Icon(Icons.Filled.Check, null, tint = Blue, modifier = Modifier.size(20.dp))
    }
}

@Composable
fun PeriodSheet(current: Period, onPick: (Period) -> Unit, onDismiss: () -> Unit) {
    SheetList(title = null, onDismiss = onDismiss) {
        Period.entries.forEach { p ->
            SheetRow(p.label, selected = p == current) { onPick(p); }
        }
    }
}

@Composable
fun PurposeSheet(
    purposes: List<Purpose>,
    currentId: Long?,
    allowUnclassified: Boolean = true,
    unclassifiedLabel: String = "Unclassified",
    onPick: (Long?) -> Unit,
    onDismiss: () -> Unit,
    onAddNew: (() -> Unit)? = null
) {
    SheetList(title = "Choose purpose", onDismiss = onDismiss) {
        LazyColumn {
            if (allowUnclassified) {
                item {
                    SheetRow(unclassifiedLabel, selected = currentId == null) { onPick(null) }
                }
            }
            items(purposes, key = { it.id }) { p ->
                SheetRow(
                    p.name,
                    selected = p.id == currentId,
                    dot = runCatching { Color(android.graphics.Color.parseColor(p.colorHex)) }.getOrDefault(Blue)
                ) { onPick(p.id) }
            }
            if (onAddNew != null) {
                item {
                    SheetRow("Add a purpose", icon = Icons.Filled.AddCircleOutline) { onAddNew() }
                }
            }
        }
    }
}

@Composable
fun CategorySheet(
    categories: List<Category>,
    currentId: Long?,
    onPick: (Long?) -> Unit,
    onDismiss: () -> Unit
) {
    SheetList(title = "Choose category", onDismiss = onDismiss) {
        LazyColumn {
            item { SheetRow("None", selected = currentId == null) { onPick(null) } }
            items(categories, key = { it.id }) { c ->
                SheetRow(c.name, selected = c.id == currentId) { onPick(c.id) }
            }
        }
    }
}

@Composable
fun VehicleSheet(
    vehicles: List<Vehicle>,
    currentId: Long?,
    onPick: (Long?) -> Unit,
    onDismiss: () -> Unit
) {
    SheetList(title = "Choose vehicle", onDismiss = onDismiss) {
        LazyColumn {
            items(vehicles, key = { it.id }) { v ->
                SheetRow(
                    listOfNotNull(v.year.ifBlank { null }, v.makeModel.ifBlank { null })
                        .joinToString(" ").ifBlank { v.name }
                        .let { if (it == v.name) v.name else "${v.name}  ·  $it" },
                    selected = v.id == currentId,
                    icon = Icons.Filled.DirectionsCar
                ) { onPick(v.id) }
            }
        }
    }
}

/** The four actions behind the center button. */
@Composable
fun AddSheet(
    onAddTrip: () -> Unit,
    onAddExpense: () -> Unit,
    onAddRevenue: () -> Unit,
    onTracking: () -> Unit,
    trackingActive: Boolean,
    onDismiss: () -> Unit
) {
    SheetList(title = null, onDismiss = onDismiss) {
        SheetRow("Add trip", icon = Icons.Filled.DirectionsCar) { onAddTrip() }
        SheetRow("Add expense", icon = Icons.Filled.RemoveCircleOutline) { onAddExpense() }
        SheetRow("Add revenue", icon = Icons.Filled.Payments) { onAddRevenue() }
        SheetRow(
            if (trackingActive) "Stop tracking" else "Start tracking",
            icon = Icons.Filled.MyLocation
        ) { onTracking() }
        Spacer(Modifier.height(8.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DatePickerSheet(initial: LocalDate, onPick: (LocalDate) -> Unit, onDismiss: () -> Unit) {
    val state = rememberDatePickerState(
        initialSelectedDateMillis = initial.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()
    )
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let {
                    onPick(Instant.ofEpochMilli(it).atZone(ZoneId.of("UTC")).toLocalDate())
                }
                onDismiss()
            }) { Text("OK") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    ) { DatePicker(state = state) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimePickerDialog(initial: LocalTime, onPick: (LocalTime) -> Unit, onDismiss: () -> Unit) {
    val state = rememberTimePickerState(initial.hour, initial.minute, false)
    Dialog(onDismissRequest = onDismiss) {
        androidx.compose.material3.Surface(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
            color = Card
        ) {
            Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                TimePicker(state = state)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    TextButton(onClick = {
                        onPick(LocalTime.of(state.hour, state.minute)); onDismiss()
                    }) { Text("OK") }
                }
            }
        }
    }
}

val RevenueColor = Money
val ExpenseColor = Spend
