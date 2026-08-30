package com.milelog.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.milelog.data.DeductionClass
import com.milelog.data.Fmt
import com.milelog.data.Period
import com.milelog.data.TripRow
import com.milelog.tracking.Geo
import com.milelog.tracking.TripTrackingService
import com.milelog.ui.components.DatePickerSheet
import com.milelog.ui.components.Divider
import com.milelog.ui.components.EmptyNote
import com.milelog.ui.components.MapPreview
import com.milelog.ui.components.PeriodChooser
import com.milelog.ui.components.Pill
import com.milelog.ui.components.PurposeSheet
import com.milelog.ui.components.SectionCard
import com.milelog.ui.components.SwipeBackdrop
import com.milelog.ui.components.Tag
import com.milelog.ui.theme.Blue
import com.milelog.ui.theme.Card
import com.milelog.ui.theme.CardHigh
import com.milelog.ui.theme.Line
import com.milelog.ui.theme.Money
import com.milelog.ui.theme.Sky
import com.milelog.ui.theme.Spend
import com.milelog.ui.theme.TextHi
import com.milelog.ui.theme.TextLow
import com.milelog.ui.theme.TextMid
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripsScreen(vm: TripsVm, onOpenTrip: (Long) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tab by vm.tab.collectAsState()
    val filter by vm.filter.collectAsState()
    val rows by vm.rows.collectAsState()
    val groups by vm.groups.collectAsState()
    val selection by vm.selection.collectAsState()
    val undo by vm.undo.collectAsState()
    val year by vm.yearSummary.collectAsState()
    val purposes by vm.purposes.collectAsState()
    val live by com.milelog.tracking.TripTracker.state.collectAsState()

    var showPurpose by remember { mutableStateOf(false) }
    var showPeriod by remember { mutableStateOf(false) }
    var showPlace by remember { mutableStateOf(false) }
    var showBulkPurpose by remember { mutableStateOf(false) }
    var showTagDialog by remember { mutableStateOf(false) }
    var confirmDeleteTrip by remember { mutableStateOf<Long?>(null) }
    var confirmDeleteSelection by remember { mutableStateOf(false) }

    val businessId = remember(purposes) {
        purposes.firstOrNull { it.deductionClass == DeductionClass.BUSINESS }?.id
    }
    val personalId = remember(purposes) {
        purposes.firstOrNull { it.name == "Personal" }?.id
            ?: purposes.firstOrNull { it.deductionClass == DeductionClass.PERSONAL }?.id
    }

    Column(Modifier.fillMaxSize()) {

        // ---- Trips / Daily / Weekly / Monthly ----
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(50))
                .background(CardHigh)
        ) {
            TripsTab.entries.forEach { t ->
                Box(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(50))
                        .background(if (t == tab) Blue else Color.Transparent)
                        .clickable { vm.setTab(t) }
                        .padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        t.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (t == tab) Color.White else TextMid,
                        maxLines = 1
                    )
                }
            }
        }

        // ---- Year header ----
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    "${Fmt.miles(year.totalMiles)} mi",
                    style = MaterialTheme.typography.headlineSmall,
                    color = TextHi
                )
                Text(
                    "${java.time.LocalDate.now().year} work miles ${Fmt.miles(year.businessMiles)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextMid
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    Fmt.dollars(year.deduction),
                    style = MaterialTheme.typography.headlineSmall,
                    color = Money
                )
                Text("TAX DEDUCTION", style = MaterialTheme.typography.labelMedium, color = TextMid)
            }
        }
        Divider()

        // ---- Filters ----
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val purposeLabel = when {
                filter.unclassifiedOnly -> "Unclassified" + if (rows.isNotEmpty()) " ${rows.size}" else ""
                filter.purposeId != null ->
                    purposes.firstOrNull { it.id == filter.purposeId }?.name ?: "Filtered"
                else -> "All trips"
            }
            Pill(
                purposeLabel,
                selected = filter.unclassifiedOnly || filter.purposeId != null,
                trailingChevron = true
            ) { showPurpose = true }
            Pill(filter.period.label, trailingChevron = true) { showPeriod = true }
            Pill(
                if (filter.placeQuery.isBlank()) "Favorite place" else filter.placeQuery,
                selected = filter.placeQuery.isNotBlank(),
                trailingChevron = true
            ) { showPlace = true }
        }

        if (undo.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                TextButton(onClick = { vm.undoLast() }) {
                    Icon(Icons.Filled.Undo, null, tint = Blue, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Undo ${undo.size}", color = Blue)
                }
            }
        }

        Box(Modifier.weight(1f)) {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 14.dp, end = 14.dp, bottom = 110.dp
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (live.active) {
                    item("live") {
                        SectionCard {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    "${Fmt.miles(live.miles)} miles",
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = TextHi
                                )
                                Text(
                                    Fmt.duration(System.currentTimeMillis() - live.startedAt),
                                    style = MaterialTheme.typography.headlineSmall,
                                    color = Money
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "Trip in progress",
                                style = MaterialTheme.typography.titleMedium,
                                color = TextMid
                            )
                            Spacer(Modifier.height(10.dp))
                            MapPreview(
                                points = live.points,
                                modifier = Modifier.fillMaxWidth().height(150.dp)
                                    .clip(RoundedCornerShape(12.dp))
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = { TripTrackingService.stop(context) },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = Spend)
                            ) { Text("Stop trip") }
                        }
                    }
                }

                if (tab == TripsTab.TRIPS) {
                    if (rows.isEmpty() && !live.active) {
                        item { EmptyNote("No trips in this period yet.") }
                    }
                    items(rows, key = { it.id }) { row ->
                        SwipeTripCard(
                            row = row,
                            selected = row.id in selection,
                            selectionMode = selection.isNotEmpty(),
                            onSwipeBusiness = { businessId?.let { vm.classify(listOf(row.id), it) } },
                            onSwipePersonal = { personalId?.let { vm.classify(listOf(row.id), it) } },
                            onClick = {
                                if (selection.isNotEmpty()) vm.toggleSelection(row.id)
                                else onOpenTrip(row.id)
                            },
                            onLongClick = { vm.toggleSelection(row.id) },
                            onDelete = { confirmDeleteTrip = row.id }
                        )
                    }
                } else {
                    if (groups.isEmpty()) item { EmptyNote("Nothing to total up yet.") }
                    items(groups, key = { it.label }) { g ->
                        SectionCard {
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text(g.label, style = MaterialTheme.typography.titleLarge, color = TextHi)
                                    Text(
                                        "${g.count} trip${if (g.count == 1) "" else "s"}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = TextMid
                                    )
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text(
                                        "${Fmt.miles(g.miles)} mi",
                                        style = MaterialTheme.typography.titleLarge,
                                        color = TextHi
                                    )
                                    Text(
                                        Fmt.dollars(g.deduction),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = Money
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ---- Bulk action bar ----
            if (selection.isNotEmpty()) {
                Row(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Blue)
                        .padding(vertical = 14.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    BulkAction("Classify") { showBulkPurpose = true }
                    BulkAction("Merge") { vm.merge(selection.toList()) }
                    BulkAction("Add tags") { showTagDialog = true }
                    BulkAction("Delete") { confirmDeleteSelection = true }
                }
            }
        }
    }

    if (showPurpose) {
        com.milelog.ui.components.SheetList(title = "Filter by purpose", onDismiss = { showPurpose = false }) {
            com.milelog.ui.components.SheetRow(
                "All trips",
                selected = !filter.unclassifiedOnly && filter.purposeId == null
            ) {
                vm.setFilter(filter.copy(purposeId = null, unclassifiedOnly = false))
                showPurpose = false
            }
            com.milelog.ui.components.SheetRow("Unclassified", selected = filter.unclassifiedOnly) {
                vm.setFilter(filter.copy(purposeId = null, unclassifiedOnly = true))
                showPurpose = false
            }
            purposes.forEach { p ->
                com.milelog.ui.components.SheetRow(
                    p.name,
                    selected = filter.purposeId == p.id,
                    dot = runCatching { Color(android.graphics.Color.parseColor(p.colorHex)) }.getOrDefault(Blue)
                ) {
                    vm.setFilter(filter.copy(purposeId = p.id, unclassifiedOnly = false))
                    showPurpose = false
                }
            }
        }
    }
    if (showPeriod) {
        PeriodChooser(
            current = filter.period.period,
            from = filter.period.from,
            to = filter.period.to,
            onPick = { p, f, t ->
                vm.setFilter(filter.copy(period = PeriodChoice(p, f, t)))
                showPeriod = false
            },
            onDismiss = { showPeriod = false }
        )
    }
    if (showPlace) {
        PlaceFilterDialog(
            current = filter.placeQuery,
            onApply = { vm.setFilter(filter.copy(placeQuery = it)); showPlace = false },
            onDismiss = { showPlace = false }
        )
    }
    if (showBulkPurpose) {
        PurposeSheet(
            purposes = purposes,
            currentId = null,
            allowUnclassified = true,
            onPick = { id -> vm.classify(selection.toList(), id); showBulkPurpose = false },
            onDismiss = { showBulkPurpose = false }
        )
    }
    if (showTagDialog) {
        TagDialog(
            onApply = { tags -> vm.addTags(selection.toList(), tags); showTagDialog = false },
            onDismiss = { showTagDialog = false }
        )
    }

    confirmDeleteTrip?.let { id ->
        ConfirmDelete(
            what = "this trip",
            onConfirm = { vm.delete(listOf(id)); confirmDeleteTrip = null },
            onDismiss = { confirmDeleteTrip = null }
        )
    }
    if (confirmDeleteSelection) {
        ConfirmDelete(
            what = "${selection.size} trip${if (selection.size == 1) "" else "s"}",
            onConfirm = { vm.delete(selection.toList()); confirmDeleteSelection = false },
            onDismiss = { confirmDeleteSelection = false }
        )
    }

    LaunchedEffect(undo) {
        if (undo.isNotEmpty()) {
            kotlinx.coroutines.delay(6000)
            vm.clearUndo()
        }
    }
}

@Composable
private fun BulkAction(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.titleMedium,
        color = Color.White,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp)
    )
}

/** Deleting a trip removes deductible miles, so it always asks first. */
@Composable
fun ConfirmDelete(what: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Card,
        title = { Text("Delete $what?", color = TextHi) },
        text = { Text("This cannot be undone.", color = TextMid) },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Delete", color = Spend) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep it") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun SwipeTripCard(
    row: TripRow,
    selected: Boolean,
    selectionMode: Boolean,
    onSwipeBusiness: () -> Unit,
    onSwipePersonal: () -> Unit,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDelete: () -> Unit
) {
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> { onSwipeBusiness(); false }
                SwipeToDismissBoxValue.EndToStart -> { onSwipePersonal(); false }
                else -> false
            }
        }
    )

    SwipeToDismissBox(
        state = state,
        backgroundContent = { SwipeBackdrop(state.dismissDirection) }
    ) {
        TripCard(row, selected, selectionMode, onClick, onLongClick, onDelete)
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun TripCard(
    row: TripRow,
    selected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onDelete: () -> Unit
) {
    val points = remember(row.pathCsv) { Geo.parsePath(row.pathCsv) }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) CardHigh else Card)
            .combinedClickable(
                onClick = onClick,
                onLongClickLabel = "Select trips",
                onLongClick = onLongClick
            )
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                Fmt.dateOf(row.startEpoch).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = TextMid
            )
            Tag(
                row.purposeName ?: "Unclassified",
                if (row.purposeName == null) TextMid else Blue
            )
        }
        Divider()
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "${Fmt.miles(row.miles)} miles",
                style = MaterialTheme.typography.headlineSmall,
                color = TextHi
            )
            Text(
                Fmt.time(row.startEpoch) + " – " + Fmt.time(row.endEpoch),
                style = MaterialTheme.typography.labelLarge,
                color = TextMid
            )
        }
        if (points.isNotEmpty()) {
            MapPreview(
                points = points,
                modifier = Modifier.fillMaxWidth().height(140.dp)
            )
        }
        if (row.startAddress.isNotBlank() || row.endAddress.isNotBlank()) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                AddressLine(row.startAddress, Sky, Fmt.time(row.startEpoch))
                Spacer(Modifier.height(6.dp))
                AddressLine(row.endAddress, Spend, Fmt.time(row.endEpoch))
            }
        }
        Divider()
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row {
                Icon(
                    Icons.Filled.Map,
                    if (points.isNotEmpty()) "Has a recorded route" else null,
                    tint = if (points.isNotEmpty()) Blue else TextMid,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(18.dp))
                Icon(
                    Icons.Filled.Notes,
                    if (row.notes.isNotBlank()) "Has a note" else null,
                    tint = if (row.notes.isNotBlank()) Blue else TextMid,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(18.dp))
                Icon(
                    Icons.Filled.DirectionsCar,
                    row.vehicleName?.let { "Vehicle: $it" },
                    tint = TextMid,
                    modifier = Modifier.size(20.dp)
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, "Delete this trip", tint = TextMid, modifier = Modifier.size(22.dp))
            }
        }
        if (selectionMode) {
            Box(Modifier.fillMaxWidth().height(3.dp).background(if (selected) Blue else Line))
        }
    }
}

@Composable
private fun AddressLine(text: String, dot: Color, time: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(10.dp)
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(dot)
        )
        Spacer(Modifier.width(10.dp))
        Text(
            text.ifBlank { "Address not recorded" },
            style = MaterialTheme.typography.bodyMedium,
            color = if (text.isBlank()) TextLow else TextHi,
            modifier = Modifier.weight(1f),
            maxLines = 1
        )
        Text(time, style = MaterialTheme.typography.labelMedium, color = TextMid)
    }
}

@Composable
private fun PlaceFilterDialog(current: String, onApply: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(current) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Card,
        title = { Text("Filter by place", color = TextHi) },
        text = {
            Column {
                Text(
                    "Type part of a street, town or place name. It matches the start or the end of a trip.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMid
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = { TextButton(onClick = { onApply(text) }) { Text("Apply") } },
        dismissButton = { TextButton(onClick = { onApply("") }) { Text("Reset") } }
    )
}

@Composable
private fun TagDialog(onApply: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Card,
        title = { Text("Add tags", color = TextHi) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                label = { Text("Separate with commas") },
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = { TextButton(onClick = { onApply(text) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}
