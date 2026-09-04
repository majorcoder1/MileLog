package com.milelog.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.LocalOffer
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.milelog.data.Fmt
import com.milelog.tracking.Geo
import com.milelog.ui.components.DatePickerSheet
import com.milelog.ui.components.Divider
import com.milelog.ui.components.MapPreview
import com.milelog.ui.components.MapPlaceholder
import com.milelog.ui.components.PurposeSheet
import com.milelog.ui.components.TimePickerDialog
import com.milelog.ui.components.VehicleSheet
import com.milelog.ui.theme.Sky
import com.milelog.ui.theme.Spend
import com.milelog.ui.theme.TextHi
import com.milelog.ui.theme.TextMid
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

@Composable
fun EditTripScreen(vm: EditTripVm, id: Long, onClose: () -> Unit) {
    val trip by vm.trip.collectAsState()
    val purposes by vm.purposes.collectAsState()
    val vehicles by vm.vehicles.collectAsState()

    var showPurpose by remember { mutableStateOf(false) }
    var showVehicle by remember { mutableStateOf(false) }
    var showDate by remember { mutableStateOf(false) }
    var showStartTime by remember { mutableStateOf(false) }
    var showEndTime by remember { mutableStateOf(false) }
    var milesText by remember { mutableStateOf("") }

    LaunchedEffect(id) { vm.load(id) }
    // Reseed on every record change, so the previous trip's mileage and odometer
    // readings can never be carried onto this one.
    LaunchedEffect(trip?.id) {
        trip?.let { t ->
            milesText = if (t.miles > 0) String.format(java.util.Locale.US, "%.1f", t.miles) else ""
        }
    }

    val current = trip ?: return
    val points = remember(current.pathCsv) { Geo.parsePath(current.pathCsv) }
    val zone = ZoneId.systemDefault()
    val startLocal = Instant.ofEpochMilli(current.startEpoch).atZone(zone)
    val endLocal = Instant.ofEpochMilli(current.endEpoch).atZone(zone)

    fun setDate(date: LocalDate) {
        val newStart = startLocal.with(date).toInstant().toEpochMilli()
        val newEnd = endLocal.with(date).toInstant().toEpochMilli()
        vm.edit { it.copy(startEpoch = newStart, endEpoch = newEnd) }
    }

    // imePadding keeps the form above the keyboard, so the field being typed into
    // can actually be scrolled to instead of sitting underneath it.
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TextButton(onClick = onClose) { Text("Cancel") }
            Text(
                if (current.id == 0L) "Add trip" else "Edit trip",
                style = MaterialTheme.typography.titleLarge,
                color = TextHi
            )
            if (current.id != 0L) {
                TextButton(onClick = { vm.delete(onClose) }) {
                    Icon(Icons.Filled.Delete, "Delete", tint = Spend)
                }
            } else {
                Spacer(Modifier.width(64.dp))
            }
        }
        Divider()

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {

            if (points.isNotEmpty()) {
                MapPreview(points = points, modifier = Modifier.fillMaxWidth().height(180.dp))
            } else {
                MapPlaceholder(
                    Modifier.fillMaxWidth().height(120.dp),
                    "No route on this trip. Fill in the addresses below."
                )
            }

            Spacer(Modifier.height(10.dp))

            EndpointField(
                dot = Sky,
                label = "Start",
                value = current.startAddress,
                onChange = { v -> vm.edit { it.copy(startAddress = v) } }
            )
            EndpointField(
                dot = Spend,
                label = "End",
                value = current.endAddress,
                onChange = { v -> vm.edit { it.copy(endAddress = v) } }
            )

            FormRow(
                Icons.Filled.Work,
                "Choose purpose",
                purposes.firstOrNull { it.id == current.purposeId }?.name
            ) { showPurpose = true }

            FormRow(
                Icons.Filled.CalendarMonth,
                "Date",
                Fmt.dateOf(current.startEpoch)
            ) { showDate = true }

            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp)) {
                Icon(Icons.Filled.Schedule, null, tint = TextMid, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(16.dp))
                Text(
                    Fmt.time(current.startEpoch),
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextHi,
                    modifier = Modifier
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                        .background(com.milelog.ui.theme.CardHigh)
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                        .then(Modifier)
                )
                Spacer(Modifier.width(10.dp))
                Text("TO", style = MaterialTheme.typography.labelMedium, color = TextMid)
                Spacer(Modifier.width(10.dp))
                Text(
                    Fmt.time(current.endEpoch),
                    style = MaterialTheme.typography.bodyLarge,
                    color = TextHi,
                    modifier = Modifier
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                        .background(com.milelog.ui.theme.CardHigh)
                        .padding(horizontal = 12.dp, vertical = 6.dp)
                )
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                TextButton(onClick = { showStartTime = true }) { Text("Change start time") }
                TextButton(onClick = { showEndTime = true }) { Text("Change end time") }
            }

            // ---- Miles: typed in, or worked out from the odometer ----
            OutlinedTextField(
                value = milesText,
                onValueChange = { v ->
                    milesText = v.filter { it.isDigit() || it == '.' }
                    vm.edit { it.copy(miles = milesText.toDoubleOrNull() ?: 0.0) }
                },
                label = { Text("Miles") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)
            )
            FormRow(
                Icons.Filled.DirectionsCar,
                "Vehicle",
                vehicles.firstOrNull { it.id == current.vehicleId }?.name
            ) { showVehicle = true }

            OutlinedTextField(
                value = current.notes,
                onValueChange = { v -> vm.edit { it.copy(notes = v) } },
                label = { Text("Notes") },
                leadingIcon = { Icon(Icons.Filled.Notes, null, tint = TextMid) },
                minLines = 3,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)
            )
            OutlinedTextField(
                value = current.tags,
                onValueChange = { v -> vm.edit { it.copy(tags = v) } },
                label = { Text("Tags, separated by commas") },
                leadingIcon = { Icon(Icons.Filled.LocalOffer, null, tint = TextMid) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)
            )
            Spacer(Modifier.height(20.dp))
        }

        Divider()
        Button(
            onClick = { vm.save(onClose) },
            modifier = Modifier.fillMaxWidth().padding(16.dp).height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = com.milelog.ui.theme.Blue)
        ) { Text("Save", style = MaterialTheme.typography.titleMedium) }
    }

    if (showPurpose) {
        PurposeSheet(
            purposes = purposes,
            currentId = current.purposeId,
            onPick = { pid -> vm.edit { it.copy(purposeId = pid) }; showPurpose = false },
            onDismiss = { showPurpose = false }
        )
    }
    if (showVehicle) {
        VehicleSheet(
            vehicles = vehicles,
            currentId = current.vehicleId,
            onPick = { vid -> vm.edit { it.copy(vehicleId = vid) }; showVehicle = false },
            onDismiss = { showVehicle = false }
        )
    }
    if (showDate) {
        DatePickerSheet(
            initial = startLocal.toLocalDate(),
            onPick = { setDate(it) },
            onDismiss = { showDate = false }
        )
    }
    if (showStartTime) {
        TimePickerDialog(
            initial = startLocal.toLocalTime(),
            onPick = { t ->
                vm.edit { it.copy(startEpoch = startLocal.with(t).toInstant().toEpochMilli()) }
            },
            onDismiss = { showStartTime = false }
        )
    }
    if (showEndTime) {
        TimePickerDialog(
            initial = endLocal.toLocalTime(),
            onPick = { t ->
                vm.edit { it.copy(endEpoch = endLocal.with(t).toInstant().toEpochMilli()) }
            },
            onDismiss = { showEndTime = false }
        )
    }
}

@Composable
private fun EndpointField(
    dot: androidx.compose.ui.graphics.Color,
    label: String,
    value: String,
    onChange: (String) -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(14.dp).clip(CircleShape).background(dot))
        Spacer(Modifier.width(14.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(label) },
            singleLine = true,
            modifier = Modifier.weight(1f)
        )
    }
}

