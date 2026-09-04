package com.milelog.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.milelog.data.DeductionClass
import com.milelog.data.Fmt
import com.milelog.data.MileageRate
import com.milelog.data.Purpose
import com.milelog.data.ServiceReminder
import com.milelog.data.Vehicle
import com.milelog.data.WorkWindow
import com.milelog.export.Backup
import com.milelog.export.CsvImport
import com.milelog.export.Exporter
import com.milelog.tracking.DriveDetect
import com.milelog.ui.components.CardTitle
import com.milelog.ui.components.DatePickerSheet
import com.milelog.ui.components.Divider
import com.milelog.ui.components.PurposeSheet
import com.milelog.ui.components.SectionCard
import com.milelog.ui.components.SheetList
import com.milelog.ui.components.SheetRow
import com.milelog.ui.components.TimePickerDialog
import com.milelog.ui.theme.Blue
import com.milelog.ui.theme.Card
import com.milelog.ui.theme.Spend
import com.milelog.ui.theme.TextHi
import com.milelog.ui.theme.TextLow
import com.milelog.ui.theme.TextMid
import com.milelog.ui.theme.Warn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

private val DAY_NAMES = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

@Composable
fun SettingsScreen(vm: SettingsVm, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = vm.prefs
    val purposes by vm.purposes.collectAsState()
    val vehicles by vm.vehicles.collectAsState()
    val windows by vm.windows.collectAsState()
    val reminders by vm.reminders.collectAsState()
    val rates by vm.rates.collectAsState()
    val categories by vm.expenseCategories.collectAsState()

    var email by remember { mutableStateOf(prefs.exportEmail) }
    var scheduleOn by remember { mutableStateOf(prefs.scheduleEnabled) }
    var dailyBackup by remember { mutableStateOf(prefs.dailyBackup) }
    var autoDetect by remember { mutableStateOf(prefs.autoDetect) }
    var workPurposeId by remember { mutableStateOf(prefs.workHoursPurposeId.takeIf { it != 0L }) }

    var editWindow by remember { mutableStateOf<WorkWindow?>(null) }
    var editVehicle by remember { mutableStateOf<Vehicle?>(null) }
    var editPurpose by remember { mutableStateOf<Purpose?>(null) }
    var editRate by remember { mutableStateOf<MileageRate?>(null) }
    var editReminder by remember { mutableStateOf<ServiceReminder?>(null) }
    var showWorkPurpose by remember { mutableStateOf(false) }
    var newCategoryName by remember { mutableStateOf("") }
    var confirmRestore by remember { mutableStateOf<android.net.Uri?>(null) }
    var importUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var importPreview by remember { mutableStateOf<CsvImport.Preview?>(null) }
    var importBusy by remember { mutableStateOf(false) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            importUri = uri
            importBusy = true
            scope.launch {
                val name = displayName(context, uri)
                importPreview = withContext(Dispatchers.IO) { CsvImport.preview(context, uri, name) }
                importBusy = false
            }
        }
    }

    val restoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> if (uri != null) confirmRestore = uri }

    // Keep the form above the keyboard so the field being typed into is reachable.
    Column(Modifier.fillMaxSize().imePadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, "Back", tint = TextHi) }
            Text("Settings", style = MaterialTheme.typography.headlineMedium, color = TextHi)
        }
        Divider()

        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            // ---- Email ----
            SectionCard {
                CardTitle("Where reports get sent")
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it; prefs.exportEmail = it.trim() },
                    label = { Text("Email address") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "The spreadsheet opens in your mail app with this address already filled in. " +
                        "Nothing sends until you tap send.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMid
                )
            }

            // ---- Tracking ----
            SectionCard {
                CardTitle("Tracking")
                Spacer(Modifier.height(8.dp))
                ToggleRow("Automatic drive detection", autoDetect) { on ->
                    if (!on) {
                        autoDetect = false
                        prefs.autoDetect = false
                        DriveDetect.disable(context)
                        return@ToggleRow
                    }
                    // enable() refuses without the physical-activity permission, and this
                    // screen has no way to ask for it. Turning the switch on regardless
                    // used to leave the app claiming it was tracking when it was not.
                    if (DriveDetect.enable(context)) {
                        autoDetect = true
                        prefs.autoDetect = true
                    } else {
                        autoDetect = false
                        prefs.autoDetect = false
                        Toast.makeText(
                            context,
                            "Turn this on from the Home screen — it needs to ask for permission first.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
                ToggleRow("Track during my work hours", scheduleOn) { on ->
                    scheduleOn = on
                    prefs.scheduleEnabled = on
                }
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier.fillMaxWidth().clickable { showWorkPurpose = true }.padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Work-hours trips count as", color = TextMid)
                    Text(
                        purposes.firstOrNull { it.id == workPurposeId }?.name ?: "Unclassified",
                        color = Blue
                    )
                }
            }

            // ---- Work hours ----
            SectionCard {
                CardTitle("Work hours") {
                    IconButton(onClick = {
                        editWindow = WorkWindow(dayOfWeek = 1, startMinute = 8 * 60, endMinute = 17 * 60)
                    }) { Icon(Icons.Filled.Add, "Add hours", tint = Blue) }
                }
                if (windows.isEmpty()) {
                    Text(
                        "No hours set. Add the days and times you normally drive and tracking " +
                            "turns itself on and off.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextMid
                    )
                }
                windows.forEach { w ->
                    Row(
                        Modifier.fillMaxWidth().clickable { editWindow = w }.padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "${DAY_NAMES.getOrElse(w.dayOfWeek - 1) { "?" }}  " +
                                "${minuteLabel(w.startMinute)} to ${minuteLabel(w.endMinute)}",
                            color = if (w.enabled) TextHi else TextLow
                        )
                        Icon(
                            Icons.Filled.Delete, "Remove",
                            tint = TextMid,
                            modifier = Modifier.size(20.dp).clickable { vm.deleteWindow(w) }
                        )
                    }
                }
            }

            // ---- Service reminders ----
            SectionCard {
                CardTitle("Service reminders") {
                    IconButton(onClick = {
                        editReminder = ServiceReminder(
                            title = "Oil change",
                            intervalMiles = 5000.0,
                            lastDoneOdometer = vehicles.firstOrNull()?.odometer ?: 0.0,
                            lastDoneEpochDay = LocalDate.now().toEpochDay(),
                            vehicleId = vehicles.firstOrNull()?.id
                        )
                    }) { Icon(Icons.Filled.Add, "Add reminder", tint = Blue) }
                }
                if (reminders.isEmpty()) {
                    Text(
                        "Nothing set. Add one for oil changes, tires, or tags.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextMid
                    )
                }
                reminders.forEach { r ->
                    Row(
                        Modifier.fillMaxWidth().clickable { editReminder = r }.padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(r.title, color = TextHi)
                            Text(
                                buildString {
                                    r.intervalMiles?.let { append("every ${it.toInt()} miles") }
                                    r.intervalDays?.let {
                                        if (isNotEmpty()) append(" or ")
                                        append("every $it days")
                                    }
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextMid
                            )
                        }
                        Icon(
                            Icons.Filled.Delete, "Remove",
                            tint = TextMid,
                            modifier = Modifier.size(20.dp).clickable { vm.deleteReminder(r) }
                        )
                    }
                }
            }

            // ---- Vehicles ----
            SectionCard {
                CardTitle("Vehicles") {
                    IconButton(onClick = { editVehicle = Vehicle(name = "") }) {
                        Icon(Icons.Filled.Add, "Add vehicle", tint = Blue)
                    }
                }
                vehicles.forEach { v ->
                    Row(
                        Modifier.fillMaxWidth().clickable { editVehicle = v }.padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(v.name + if (v.isDefault) "  (default)" else "", color = TextHi)
                            Text(
                                listOfNotNull(
                                    v.year.ifBlank { null },
                                    v.makeModel.ifBlank { null },
                                    "${Fmt.miles(v.odometer)} on the clock"
                                ).joinToString(" · "),
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextMid
                            )
                        }
                        if (vehicles.size > 1) {
                            Icon(
                                Icons.Filled.Delete, "Remove",
                                tint = TextMid,
                                modifier = Modifier.size(20.dp).clickable { vm.deleteVehicle(v) }
                            )
                        }
                    }
                }
            }

            // ---- Purposes ----
            SectionCard {
                CardTitle("Purposes") {
                    IconButton(onClick = {
                        editPurpose = Purpose(name = "", deductionClass = DeductionClass.BUSINESS)
                    }) { Icon(Icons.Filled.Add, "Add purpose", tint = Blue) }
                }
                purposes.forEach { p ->
                    Row(
                        Modifier.fillMaxWidth().clickable { editPurpose = p }.padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(p.name, color = TextHi)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                p.deductionClass.name.lowercase(),
                                style = MaterialTheme.typography.labelMedium,
                                color = TextMid
                            )
                            if (!p.isBuiltIn) {
                                Spacer(Modifier.width(12.dp))
                                Icon(
                                    Icons.Filled.Delete, "Remove",
                                    tint = TextMid,
                                    modifier = Modifier.size(20.dp).clickable { vm.deletePurpose(p) }
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Commute counts as personal on purpose. The IRS does not let you deduct " +
                        "driving from home to a regular workplace.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMid
                )
            }

            // ---- Expense categories ----
            SectionCard {
                CardTitle("Expense categories")
                Spacer(Modifier.height(6.dp))
                categories.forEach { c ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(c.name, color = TextHi)
                        if (!c.isBuiltIn) {
                            Icon(
                                Icons.Filled.Delete, "Remove",
                                tint = TextMid,
                                modifier = Modifier.size(20.dp).clickable { vm.deleteCategory(c) }
                            )
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newCategoryName,
                        onValueChange = { newCategoryName = it },
                        label = { Text("New category") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(8.dp))
                    TextButton(
                        onClick = {
                            if (newCategoryName.isNotBlank()) {
                                vm.saveCategory(
                                    com.milelog.data.Category(
                                        name = newCategoryName.trim(),
                                        kind = com.milelog.data.CategoryKind.EXPENSE,
                                        sortOrder = 500
                                    )
                                )
                                newCategoryName = ""
                            }
                        }
                    ) { Text("Add") }
                }
            }

            // ---- IRS rates ----
            SectionCard {
                CardTitle("IRS mileage rates") {
                    IconButton(onClick = {
                        // Default to the year after the last one on file, carrying the
                        // most recent figures forward for you to correct.
                        val newest = rates.maxByOrNull { it.toEpochDay }
                        val nextYear = (newest?.let { LocalDate.ofEpochDay(it.toEpochDay).year }
                            ?: LocalDate.now().year) + 1
                        editRate = MileageRate(
                            label = nextYear.toString(),
                            fromEpochDay = LocalDate.of(nextYear, 1, 1).toEpochDay(),
                            toEpochDay = LocalDate.of(nextYear, 12, 31).toEpochDay(),
                            businessCents = newest?.businessCents ?: 0.0,
                            medicalCents = newest?.medicalCents ?: 0.0,
                            charityCents = newest?.charityCents ?: 14.0,
                            movingCents = newest?.movingCents ?: 0.0
                        )
                    }) { Icon(Icons.Filled.Add, "Add a rate period", tint = Blue) }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "2026 has two rows because the business rate went from 72.5 to 76 cents on " +
                        "July 1st. Add a row each January when the IRS publishes the new rate — " +
                        "without one, the newest rate here is used and your figures will be wrong.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMid
                )
                Spacer(Modifier.height(8.dp))
                rates.forEach { r ->
                    Row(
                        Modifier.fillMaxWidth().clickable { editRate = r }.padding(vertical = 10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(r.label, color = TextHi)
                            Text(
                                "${Fmt.date(r.fromEpochDay)} to ${Fmt.date(r.toEpochDay)}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextMid
                            )
                        }
                        Text("${r.businessCents} cents", color = Blue)
                        Spacer(Modifier.width(12.dp))
                        Icon(
                            Icons.Filled.Delete, "Remove this rate period",
                            tint = TextMid,
                            modifier = Modifier.size(20.dp).clickable { vm.deleteRate(r) }
                        )
                    }
                }
                val lastCovered = rates.maxByOrNull { it.toEpochDay }?.toEpochDay
                if (lastCovered != null && lastCovered < LocalDate.now().toEpochDay()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Today is past the last rate on file, so miles are being priced at " +
                            "the newest rate here. Add the current year to get real numbers.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Warn
                    )
                }
            }

            // ---- Bring data over from Everlance ----
            SectionCard {
                CardTitle("Import from Everlance")
                Spacer(Modifier.height(6.dp))
                Text(
                    "In Everlance, export your trips and your transactions as CSV and save the " +
                        "files to this phone. Then pick a file here. Do the trips file and the " +
                        "transactions file one at a time.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMid
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { importLauncher.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain", "*/*")) },
                    enabled = !importBusy,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Blue)
                ) { Text(if (importBusy) "Reading the file..." else "Choose a file") }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Anything already in MileLog is left alone. Rows that look like something " +
                        "you already have get skipped.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextLow
                )
            }

            // ---- Backup ----
            SectionCard {
                CardTitle("Backup")
                Spacer(Modifier.height(4.dp))
                ToggleRow("Write a backup file every day", dailyBackup) { on ->
                    dailyBackup = on
                    prefs.dailyBackup = on
                }
                Text(
                    "Nothing goes to Google. Your trips carry the GPS route you actually drove, " +
                        "so MileLog keeps them off the cloud entirely. That means a new phone will " +
                        "not restore on its own — send yourself a copy now and again so there is " +
                        "one somewhere other than this phone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMid
                )
                Spacer(Modifier.height(10.dp))
                val last = prefs.lastBackupEpoch
                Text(
                    if (last > 0) "Last backup ${Fmt.dateOf(last)} at ${Fmt.time(last)}"
                    else "No backup written yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMid
                )
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            scope.launch {
                                val file = withContext(Dispatchers.IO) {
                                    runCatching { Backup.create(context) }
                                }
                                file.onSuccess {
                                    Toast.makeText(context, "Backed up: ${it.name}", Toast.LENGTH_SHORT).show()
                                }.onFailure {
                                    Toast.makeText(context, "Backup failed: ${it.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Blue)
                    ) { Text("Back up now") }
                    OutlinedButton(
                        onClick = { restoreLauncher.launch(arrayOf("application/zip", "*/*")) },
                        modifier = Modifier.weight(1f)
                    ) { Text("Restore") }
                }
                Spacer(Modifier.height(10.dp))
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val file = withContext(Dispatchers.IO) {
                                runCatching { Backup.list(context).firstOrNull() ?: Backup.create(context) }
                            }
                            file.onSuccess {
                                context.startActivity(
                                    Exporter.shareIntent(context, it, "MileLog backup ${it.name}")
                                )
                            }.onFailure {
                                Toast.makeText(context, "No backup to send: ${it.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Send myself a copy") }
            }

            SectionCard {
                CardTitle("About")
                Spacer(Modifier.height(6.dp))
                Text(
                    "MileLog keeps everything on this phone. No account, no server, nothing " +
                        "leaves unless you send it yourself. Map tiles come from OpenStreetMap.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMid
                )
            }

            Spacer(Modifier.height(60.dp))
        }
    }

    // ---- Dialogs ----
    editWindow?.let { w ->
        WorkWindowDialog(
            window = w,
            onSave = { vm.saveWindow(it); editWindow = null },
            onDismiss = { editWindow = null }
        )
    }
    editVehicle?.let { v ->
        VehicleDialog(
            vehicle = v,
            onSave = { vm.saveVehicle(it); editVehicle = null },
            onDismiss = { editVehicle = null }
        )
    }
    editPurpose?.let { p ->
        PurposeDialog(
            purpose = p,
            onSave = { vm.savePurpose(it); editPurpose = null },
            onDismiss = { editPurpose = null }
        )
    }
    editRate?.let { r ->
        RateDialog(
            rate = r,
            onSave = { vm.saveRate(it); editRate = null },
            onDismiss = { editRate = null }
        )
    }
    editReminder?.let { r ->
        ReminderDialog(
            reminder = r,
            onSave = { vm.saveReminder(it); editReminder = null },
            onDismiss = { editReminder = null }
        )
    }
    if (showWorkPurpose) {
        PurposeSheet(
            purposes = purposes,
            currentId = workPurposeId,
            onPick = {
                workPurposeId = it
                vm.prefs.workHoursPurposeId = it ?: 0L
                showWorkPurpose = false
            },
            onDismiss = { showWorkPurpose = false }
        )
    }
    importPreview?.let { p ->
        AlertDialog(
            onDismissRequest = { importPreview = null; importUri = null },
            containerColor = Card,
            title = { Text(if (p.usable) "Ready to import" else "Cannot read that file", color = TextHi) },
            text = {
                Column {
                    Text(p.fileName, style = MaterialTheme.typography.titleMedium, color = TextHi)
                    Spacer(Modifier.height(8.dp))
                    if (p.problem != null) {
                        Text(p.problem, color = Warn)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Columns it found: " + p.headers.joinToString(", ").ifBlank { "none" },
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextMid
                        )
                    } else {
                        if (p.tripCount > 0) {
                            Text("${p.tripCount} trips, ${Fmt.miles(p.miles)} miles", color = TextHi)
                        }
                        if (p.txnCount > 0) {
                            Text("${p.txnCount} transactions", color = TextHi)
                            Text(
                                "Expenses ${Fmt.cents(p.expenseCents)}  ·  Revenue ${Fmt.cents(p.revenueCents)}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextMid
                            )
                        }
                        if (p.firstDay != null && p.lastDay != null) {
                            Text(
                                "${Fmt.date(p.firstDay)} through ${Fmt.date(p.lastDay)}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextMid
                            )
                        }
                        if (p.duplicates > 0) {
                            Text(
                                "${p.duplicates} already in MileLog, will be skipped",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextMid
                            )
                        }
                        if (p.skipped > 0) {
                            Text(
                                "${p.skipped} rows could not be read and will be left out",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Warn
                            )
                        }
                    }
                }
            },
            confirmButton = {
                if (p.usable) {
                    TextButton(onClick = {
                        val uri = importUri
                        importPreview = null
                        if (uri != null) {
                            scope.launch {
                                val result = withContext(Dispatchers.IO) {
                                    runCatching { CsvImport.commit(context, uri) }
                                }
                                importUri = null
                                result.onSuccess {
                                    Toast.makeText(
                                        context,
                                        "Added ${it.tripsAdded} trips and ${it.txnsAdded} transactions",
                                        Toast.LENGTH_LONG
                                    ).show()
                                }.onFailure {
                                    Toast.makeText(context, "Import failed: ${it.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                        }
                    }) { Text("Import") }
                }
            },
            dismissButton = {
                TextButton(onClick = { importPreview = null; importUri = null }) {
                    Text(if (p.usable) "Cancel" else "Close")
                }
            }
        )
    }

    confirmRestore?.let { uri ->
        AlertDialog(
            onDismissRequest = { confirmRestore = null },
            containerColor = Card,
            title = { Text("Replace everything?", color = TextHi) },
            text = {
                Text(
                    "Restoring wipes what is on this phone now and puts the backup in its place. " +
                        "Close and reopen MileLog afterwards.",
                    color = TextMid
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { Backup.restore(context, uri) }
                        confirmRestore = null
                        result.onSuccess {
                            Toast.makeText(context, "Restored. Close and reopen the app.", Toast.LENGTH_LONG).show()
                        }.onFailure {
                            Toast.makeText(context, "Restore failed: ${it.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                }) { Text("Restore", color = Spend) }
            },
            dismissButton = { TextButton(onClick = { confirmRestore = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = TextHi, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = Blue)
        )
    }
}

private fun minuteLabel(minute: Int): String =
    LocalTime.of(minute / 60, minute % 60)
        .format(java.time.format.DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.US))

@Composable
private fun WorkWindowDialog(window: WorkWindow, onSave: (WorkWindow) -> Unit, onDismiss: () -> Unit) {
    var w by remember { mutableStateOf(window) }
    var showStart by remember { mutableStateOf(false) }
    var showEnd by remember { mutableStateOf(false) }
    var showDay by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Card,
        title = { Text("Work hours", color = TextHi) },
        text = {
            Column {
                Row(
                    Modifier.fillMaxWidth().clickable { showDay = true }.padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Day", color = TextMid)
                    Text(DayOfWeek.of(w.dayOfWeek).name.lowercase().replaceFirstChar { it.uppercase() }, color = Blue)
                }
                Row(
                    Modifier.fillMaxWidth().clickable { showStart = true }.padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Starts", color = TextMid)
                    Text(minuteLabel(w.startMinute), color = Blue)
                }
                Row(
                    Modifier.fillMaxWidth().clickable { showEnd = true }.padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Ends", color = TextMid)
                    Text(minuteLabel(w.endMinute), color = Blue)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(w) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )

    if (showDay) {
        SheetList(title = "Day", onDismiss = { showDay = false }) {
            (1..7).forEach { d ->
                SheetRow(
                    DayOfWeek.of(d).name.lowercase().replaceFirstChar { it.uppercase() },
                    selected = d == w.dayOfWeek
                ) { w = w.copy(dayOfWeek = d); showDay = false }
            }
        }
    }
    if (showStart) {
        TimePickerDialog(
            initial = LocalTime.of(w.startMinute / 60, w.startMinute % 60),
            onPick = { w = w.copy(startMinute = it.hour * 60 + it.minute) },
            onDismiss = { showStart = false }
        )
    }
    if (showEnd) {
        TimePickerDialog(
            initial = LocalTime.of(w.endMinute / 60, w.endMinute % 60),
            onPick = { w = w.copy(endMinute = it.hour * 60 + it.minute) },
            onDismiss = { showEnd = false }
        )
    }
}

@Composable
private fun VehicleDialog(vehicle: Vehicle, onSave: (Vehicle) -> Unit, onDismiss: () -> Unit) {
    var v by remember { mutableStateOf(vehicle) }
    var odo by remember { mutableStateOf(if (vehicle.odometer > 0) vehicle.odometer.toString() else "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Card,
        title = { Text(if (vehicle.id == 0L) "Add vehicle" else "Edit vehicle", color = TextHi) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = v.name, onValueChange = { v = v.copy(name = it) },
                    label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = v.year, onValueChange = { v = v.copy(year = it) },
                    label = { Text("Year") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = v.makeModel, onValueChange = { v = v.copy(makeModel = it) },
                    label = { Text("Make and model") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = odo,
                    onValueChange = {
                        odo = it.filter { c -> c.isDigit() || c == '.' }
                        v = v.copy(odometer = odo.toDoubleOrNull() ?: 0.0)
                    },
                    label = { Text("Odometer") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Use as default", color = TextMid)
                    Switch(
                        checked = v.isDefault,
                        onCheckedChange = { v = v.copy(isDefault = it) },
                        colors = SwitchDefaults.colors(checkedTrackColor = Blue)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { if (v.name.isNotBlank()) onSave(v) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun PurposeDialog(purpose: Purpose, onSave: (Purpose) -> Unit, onDismiss: () -> Unit) {
    var p by remember { mutableStateOf(purpose) }
    var showClass by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Card,
        title = { Text(if (purpose.id == 0L) "Add purpose" else "Edit purpose", color = TextHi) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = p.name, onValueChange = { p = p.copy(name = it) },
                    label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                Row(
                    Modifier.fillMaxWidth().clickable { showClass = true }.padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Counts as", color = TextMid)
                    Text(p.deductionClass.name.lowercase(), color = Blue)
                }
                Text(
                    "Business is the deductible one. Personal means these miles are not claimed.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMid
                )
            }
        },
        confirmButton = { TextButton(onClick = { if (p.name.isNotBlank()) onSave(p) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
    if (showClass) {
        SheetList(title = "Counts as", onDismiss = { showClass = false }) {
            DeductionClass.entries.forEach { c ->
                SheetRow(
                    c.name.lowercase().replaceFirstChar { it.uppercase() },
                    selected = c == p.deductionClass
                ) { p = p.copy(deductionClass = c); showClass = false }
            }
        }
    }
}

@Composable
private fun RateDialog(rate: MileageRate, onSave: (MileageRate) -> Unit, onDismiss: () -> Unit) {
    var label by remember { mutableStateOf(rate.label) }
    var from by remember { mutableStateOf(LocalDate.ofEpochDay(rate.fromEpochDay)) }
    var to by remember { mutableStateOf(LocalDate.ofEpochDay(rate.toEpochDay)) }
    var business by remember { mutableStateOf(rate.businessCents.toString()) }
    var medical by remember { mutableStateOf(rate.medicalCents.toString()) }
    var charity by remember { mutableStateOf(rate.charityCents.toString()) }
    var moving by remember { mutableStateOf(rate.movingCents.toString()) }
    var showFrom by remember { mutableStateOf(false) }
    var showTo by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Card,
        title = { Text(if (rate.id == 0L) "Add a rate period" else rate.label, color = TextHi) },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Name, e.g. 2027 or 2027 Jan-Jun") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    Modifier.fillMaxWidth().clickable { showFrom = true }.padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Starts", color = TextMid)
                    Text(Fmt.date(from.toEpochDay()), color = Blue)
                }
                Row(
                    Modifier.fillMaxWidth().clickable { showTo = true }.padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Ends", color = TextMid)
                    Text(Fmt.date(to.toEpochDay()), color = Blue)
                }
                CentsField("Business", business) { business = it }
                CentsField("Medical", medical) { medical = it }
                CentsField("Charity", charity) { charity = it }
                CentsField("Moving", moving) { moving = it }
                Text(
                    "Split a year into two rows when the IRS changes the rate mid-year, " +
                        "the way 2026 changed on July 1st.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMid
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = label.isNotBlank() && !to.isBefore(from),
                onClick = {
                    onSave(
                        rate.copy(
                            label = label.trim(),
                            fromEpochDay = from.toEpochDay(),
                            toEpochDay = to.toEpochDay(),
                            businessCents = business.toDoubleOrNull() ?: rate.businessCents,
                            medicalCents = medical.toDoubleOrNull() ?: rate.medicalCents,
                            charityCents = charity.toDoubleOrNull() ?: rate.charityCents,
                            movingCents = moving.toDoubleOrNull() ?: rate.movingCents
                        )
                    )
                }
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )

    if (showFrom) {
        DatePickerSheet(initial = from, onPick = { from = it }, onDismiss = { showFrom = false })
    }
    if (showTo) {
        DatePickerSheet(initial = to, onPick = { to = it }, onDismiss = { showTo = false })
    }
}

@Composable
private fun CentsField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = { onChange(it.filter { c -> c.isDigit() || c == '.' }) },
        label = { Text("$label, cents per mile") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun ReminderDialog(
    reminder: ServiceReminder,
    onSave: (ServiceReminder) -> Unit,
    onDismiss: () -> Unit
) {
    var r by remember { mutableStateOf(reminder) }
    var miles by remember { mutableStateOf(reminder.intervalMiles?.toInt()?.toString() ?: "") }
    var days by remember { mutableStateOf(reminder.intervalDays?.toString() ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Card,
        title = { Text("Service reminder", color = TextHi) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = r.title, onValueChange = { r = r.copy(title = it) },
                    label = { Text("What") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = miles,
                    onValueChange = {
                        miles = it.filter { c -> c.isDigit() }
                        r = r.copy(intervalMiles = miles.toDoubleOrNull())
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
                        r = r.copy(intervalDays = days.toIntOrNull())
                    },
                    label = { Text("Or every how many days") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "Reset it by opening this again after the work is done.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Warn
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (r.title.isNotBlank()) {
                    onSave(r.copy(lastDoneEpochDay = LocalDate.now().toEpochDay()))
                }
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

/** The file name behind a content:// pick, for showing in the preview. */
private fun displayName(context: android.content.Context, uri: android.net.Uri): String =
    runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (i >= 0 && c.moveToFirst()) c.getString(i) else null
        }
    }.getOrNull() ?: uri.lastPathSegment ?: "the file"
