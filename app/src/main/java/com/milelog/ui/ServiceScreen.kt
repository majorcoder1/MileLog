package com.milelog.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.milelog.data.Fmt
import com.milelog.data.ServiceLog
import com.milelog.data.ServiceReminder
import com.milelog.data.ServiceState
import com.milelog.data.ServiceStatus
import com.milelog.data.ServiceTypes
import com.milelog.ui.components.CardTitle
import com.milelog.ui.components.DatePickerSheet
import com.milelog.ui.components.Divider
import com.milelog.ui.components.EmptyNote
import com.milelog.ui.components.SectionCard
import com.milelog.ui.components.SheetList
import com.milelog.ui.components.SheetRow
import com.milelog.ui.components.Tag
import com.milelog.ui.components.VehicleSheet
import com.milelog.ui.theme.Blue
import com.milelog.ui.theme.Card
import com.milelog.ui.theme.Money
import com.milelog.ui.theme.Spend
import com.milelog.ui.theme.TextHi
import com.milelog.ui.theme.TextMid
import com.milelog.ui.theme.Warn
import java.time.LocalDate

/**
 * Upkeep. What has been done to the vehicle, at what mileage, and what is coming due.
 */
@Composable
fun ServiceScreen(vm: ServiceVm) {
    val statuses by vm.statuses.collectAsState()
    val history by vm.history.collectAsState()
    val vehicles by vm.vehicles.collectAsState()
    val odometer by vm.odometer.collectAsState()

    var logging by remember { mutableStateOf<ServiceStatus?>(null) }
    var loggingFresh by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ServiceReminder?>(null) }
    var confirmDelete by remember { mutableStateOf<ServiceReminder?>(null) }
    var deleteLog by remember { mutableStateOf<ServiceLog?>(null) }

    val due = statuses.filter { it.state == ServiceState.DUE }
    val soon = statuses.filter { it.state == ServiceState.DUE_SOON }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text("Upkeep", style = MaterialTheme.typography.headlineMedium, color = TextHi)
                Text(
                    if (odometer > 0) "About ${Fmt.miles(odometer)} on the clock"
                    else "Log a service to start tracking",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMid
                )
            }
            IconButton(onClick = { editing = ServiceReminder(title = "") }) {
                Icon(Icons.Filled.Add, "Add something to watch", tint = Blue)
            }
        }
        Divider()

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (due.isNotEmpty() || soon.isNotEmpty()) {
                item("banner") {
                    SectionCard {
                        Text(
                            if (due.isNotEmpty()) "Due now" else "Coming up",
                            style = MaterialTheme.typography.titleLarge,
                            color = if (due.isNotEmpty()) Spend else Warn
                        )
                        Spacer(Modifier.height(6.dp))
                        (due + soon).forEach { s ->
                            Text(
                                "•  ${s.reminder.title}${s.vehicleName?.let { " · $it" } ?: ""}",
                                style = MaterialTheme.typography.bodyLarge,
                                color = TextHi,
                                modifier = Modifier.padding(vertical = 2.dp)
                            )
                        }
                    }
                }
            }

            item("log") {
                Button(
                    onClick = { loggingFresh = true },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Blue)
                ) { Text("Log a service") }
            }

            if (statuses.isEmpty()) {
                item { EmptyNote("Nothing being watched yet. Use the plus to add a job.") }
            }
            items(statuses, key = { it.reminder.id }) { status ->
                ServiceCard(
                    status = status,
                    onLog = { logging = status },
                    onEdit = { editing = status.reminder },
                    onDelete = { confirmDelete = status.reminder }
                )
            }

            if (history.isNotEmpty()) {
                item("history-title") {
                    Text(
                        "What has been done",
                        style = MaterialTheme.typography.titleLarge,
                        color = TextHi,
                        modifier = Modifier.padding(top = 10.dp, start = 4.dp)
                    )
                }
                items(history, key = { "log-${it.id}" }) { log ->
                    HistoryRow(log) { deleteLog = log }
                }
            }
        }
    }

    if (logging != null || loggingFresh) {
        LogServiceDialog(
            status = logging,
            vehicles = vehicles,
            suggestedOdometer = odometer,
            onSave = { title, miles, day, notes, vehicleId ->
                vm.logService(logging?.reminder, title, miles, day, notes, vehicleId)
                logging = null; loggingFresh = false
            },
            onDismiss = { logging = null; loggingFresh = false }
        )
    }

    editing?.let { job ->
        EditJobDialog(
            job = job,
            vehicles = vehicles,
            onSave = { vm.saveJob(it); editing = null },
            onDismiss = { editing = null }
        )
    }

    confirmDelete?.let { job ->
        ConfirmDelete(
            what = "\"${job.title}\" from the watch list",
            onConfirm = { vm.deleteJob(job); confirmDelete = null },
            onDismiss = { confirmDelete = null }
        )
    }

    deleteLog?.let { log ->
        ConfirmDelete(
            what = "this ${log.title.lowercase()} record",
            onConfirm = { vm.deleteLog(log); deleteLog = null },
            onDismiss = { deleteLog = null }
        )
    }
}

@Composable
private fun ServiceCard(
    status: ServiceStatus,
    onLog: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val (label, colour) = when (status.state) {
        ServiceState.DUE -> "DUE NOW" to Spend
        ServiceState.DUE_SOON -> "SOON" to Warn
        ServiceState.NEVER_DONE -> "NOT LOGGED" to TextMid
        ServiceState.OK -> "OK" to Money
    }

    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Card)
    ) {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onEdit).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(38.dp).clip(CircleShape).background(colour.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.Build, null, tint = colour, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(status.reminder.title, style = MaterialTheme.typography.titleMedium, color = TextHi)
                Text(
                    buildString {
                        append(intervalText(status.reminder))
                        status.vehicleName?.let { append("  ·  $it") }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMid
                )
            }
            Tag(label, colour)
        }
        Divider()
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    status.lastDone?.let {
                        "Last done ${Fmt.miles(it.odometer)} mi, ${Fmt.date(it.dateEpochDay)}"
                    } ?: "Never logged",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMid
                )
                remainingText(status)?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = colour)
                }
            }
            TextButton(onClick = onLog) { Text("Done today") }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, "Stop watching this", tint = TextMid, modifier = Modifier.size(20.dp))
            }
        }
    }
}

private fun intervalText(r: ServiceReminder): String {
    val miles = r.intervalMiles?.let { "every ${Fmt.miles(it)} miles" }
    val days = r.intervalDays?.let {
        when {
            it % 365 == 0 -> "every ${it / 365} year${if (it > 365) "s" else ""}"
            it % 30 == 0 -> "every ${it / 30} months"
            else -> "every $it days"
        }
    }
    return listOfNotNull(miles, days).joinToString(" or ").ifBlank { "no interval set" }
}

private fun remainingText(status: ServiceStatus): String? {
    val miles = status.milesRemaining
    val days = status.daysRemaining
    return when {
        miles != null && miles <= 0 -> "Overdue by ${Fmt.miles(-miles)} miles"
        days != null && days <= 0 -> "Overdue by ${-days} days"
        miles != null && days != null -> "${Fmt.miles(miles)} miles or $days days to go"
        miles != null -> "${Fmt.miles(miles)} miles to go"
        days != null -> "$days days to go"
        else -> null
    }
}

@Composable
private fun HistoryRow(log: ServiceLog, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Card)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(log.title, style = MaterialTheme.typography.titleMedium, color = TextHi)
            Text(
                "${Fmt.miles(log.odometer)} mi  ·  ${Fmt.date(log.dateEpochDay)}" +
                    log.notes.takeIf { it.isNotBlank() }?.let { "  ·  $it" }.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = TextMid
            )
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, "Delete this record", tint = TextMid, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun LogServiceDialog(
    status: ServiceStatus?,
    vehicles: List<com.milelog.data.Vehicle>,
    suggestedOdometer: Double,
    onSave: (String, Double, Long, String, Long?) -> Unit,
    onDismiss: () -> Unit
) {
    var title by remember { mutableStateOf(status?.reminder?.title ?: "") }
    var odo by remember {
        mutableStateOf(if (suggestedOdometer > 0) suggestedOdometer.toLong().toString() else "")
    }
    var day by remember { mutableStateOf(LocalDate.now()) }
    var notes by remember { mutableStateOf("") }
    var vehicleId by remember { mutableStateOf(status?.reminder?.vehicleId ?: vehicles.firstOrNull()?.id) }
    var showTypes by remember { mutableStateOf(false) }
    var showDate by remember { mutableStateOf(false) }
    var showVehicle by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Card,
        title = { Text("Log a service", color = TextHi) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()).imePadding(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                PickerRow(Icons.Filled.Build, "What was done", title.ifBlank { null }) { showTypes = true }
                OutlinedTextField(
                    value = odo,
                    onValueChange = { odo = it.filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("Odometer now") },
                    leadingIcon = { Icon(Icons.Filled.Speed, null, tint = TextMid) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                PickerRow(Icons.Filled.CalendarMonth, "Date", Fmt.date(day.toEpochDay())) { showDate = true }
                if (vehicles.size > 1) {
                    PickerRow(
                        Icons.Filled.DirectionsCar,
                        "Vehicle",
                        vehicles.firstOrNull { it.id == vehicleId }?.name
                    ) { showVehicle = true }
                }
                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("Notes, who did it, what it cost") },
                    leadingIcon = { Icon(Icons.Filled.Notes, null, tint = TextMid) },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = title.isNotBlank() && (odo.toDoubleOrNull() ?: 0.0) > 0,
                onClick = {
                    onSave(title.trim(), odo.toDouble(), day.toEpochDay(), notes.trim(), vehicleId)
                }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )

    if (showTypes) {
        SheetList(title = "What was done", onDismiss = { showTypes = false }) {
            LazyColumn {
                items(ServiceTypes.presets, key = { it.name }) { preset ->
                    SheetRow(preset.name, selected = preset.name == title) {
                        title = preset.name
                        showTypes = false
                    }
                }
            }
        }
    }
    if (showDate) {
        DatePickerSheet(initial = day, onPick = { day = it }, onDismiss = { showDate = false })
    }
    if (showVehicle) {
        VehicleSheet(vehicles, vehicleId, onPick = { vehicleId = it; showVehicle = false }) {
            showVehicle = false
        }
    }
}

@Composable
private fun EditJobDialog(
    job: ServiceReminder,
    vehicles: List<com.milelog.data.Vehicle>,
    onSave: (ServiceReminder) -> Unit,
    onDismiss: () -> Unit
) {
    var current by remember { mutableStateOf(job) }
    var miles by remember { mutableStateOf(job.intervalMiles?.toLong()?.toString() ?: "") }
    var days by remember { mutableStateOf(job.intervalDays?.toString() ?: "") }
    var showTypes by remember { mutableStateOf(false) }
    var showVehicle by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Card,
        title = { Text(if (job.id == 0L) "Watch something new" else current.title, color = TextHi) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()).imePadding(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                PickerRow(Icons.Filled.Build, "What to watch", current.title.ifBlank { null }) {
                    showTypes = true
                }
                OutlinedTextField(
                    value = miles,
                    onValueChange = {
                        miles = it.filter { c -> c.isDigit() }
                        current = current.copy(intervalMiles = miles.toDoubleOrNull())
                    },
                    label = { Text("Every how many miles") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = days,
                    onValueChange = {
                        days = it.filter { c -> c.isDigit() }
                        current = current.copy(intervalDays = days.toIntOrNull())
                    },
                    label = { Text("Or every how many days") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                if (vehicles.size > 1) {
                    PickerRow(
                        Icons.Filled.DirectionsCar,
                        "Vehicle",
                        vehicles.firstOrNull { it.id == current.vehicleId }?.name
                    ) { showVehicle = true }
                }
                Text(
                    "Set either, or both. Whichever comes round first is what you get told about.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMid
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = current.title.isNotBlank(),
                onClick = { onSave(current) }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )

    if (showTypes) {
        SheetList(title = "What to watch", onDismiss = { showTypes = false }) {
            LazyColumn {
                items(ServiceTypes.presets, key = { it.name }) { preset ->
                    SheetRow(preset.name, selected = preset.name == current.title) {
                        // Picking a job fills in the usual interval for it.
                        current = current.copy(
                            title = preset.name,
                            intervalMiles = preset.everyMiles,
                            intervalDays = preset.everyDays
                        )
                        miles = preset.everyMiles?.toLong()?.toString() ?: ""
                        days = preset.everyDays?.toString() ?: ""
                        showTypes = false
                    }
                }
            }
        }
    }
    if (showVehicle) {
        VehicleSheet(vehicles, current.vehicleId, onPick = {
            current = current.copy(vehicleId = it); showVehicle = false
        }) { showVehicle = false }
    }
}

@Composable
private fun PickerRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String?,
    onClick: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = TextMid, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Text(label, color = TextMid, modifier = Modifier.weight(1f))
        Text(value ?: "Choose", color = if (value == null) TextMid else Blue)
    }
}
