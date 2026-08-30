package com.milelog.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import com.milelog.data.Fmt
import com.milelog.tracking.DriveDetect
import com.milelog.tracking.TripTrackingService
import com.milelog.ui.components.BigStat
import com.milelog.ui.components.CardTitle
import com.milelog.ui.components.Divider
import com.milelog.ui.components.Donut
import com.milelog.ui.components.DropdownLabel
import com.milelog.ui.components.LegendRow
import com.milelog.ui.components.PeriodChooser
import com.milelog.ui.components.PurposeSheet
import com.milelog.ui.components.SectionCard
import com.milelog.ui.theme.Blue
import com.milelog.ui.theme.Ink
import com.milelog.ui.theme.DonutOther
import com.milelog.ui.theme.DonutPersonal
import com.milelog.ui.theme.DonutUnclassified
import com.milelog.ui.theme.DonutWork
import com.milelog.ui.theme.Money
import com.milelog.ui.theme.Spend
import com.milelog.ui.theme.TextHi
import com.milelog.ui.theme.TextMid
import com.milelog.ui.theme.Warn

@Composable
fun HomeScreen(
    vm: HomeVm,
    onOpenSettings: () -> Unit,
    onReviewTrips: () -> Unit,
    onOpenTransactions: () -> Unit
) {
    val context = LocalContext.current
    val mileage by vm.mileage.collectAsState()
    val mileagePeriod by vm.mileagePeriod.collectAsState()
    val moneyPeriod by vm.moneyPeriod.collectAsState()
    val moneyPurposeId by vm.moneyPurposeId.collectAsState()
    val money by vm.moneyFiltered.collectAsState()
    val autoDetect by vm.autoDetect.collectAsState()
    val live by vm.live.collectAsState()
    val purposes by vm.purposes.collectAsState()

    var showMileagePeriod by remember { mutableStateOf(false) }
    var showMoneyPeriod by remember { mutableStateOf(false) }
    var showMoneyPurpose by remember { mutableStateOf(false) }
    var permissionNote by remember { mutableStateOf<String?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasBackgroundLocation by remember { mutableStateOf(DriveDetect.hasBackgroundLocation(context)) }

    // Re-check on every return to the screen, since "Allow all the time" is granted in Settings.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasBackgroundLocation = DriveDetect.hasBackgroundLocation(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun openAppSettings() {
        context.startActivity(
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", context.packageName, null)
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    // Android only lets you ask for "all the time" after plain location is already granted.
    val backgroundLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasBackgroundLocation = granted
        if (!granted) openAppSettings()
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        val fine = granted[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val motion = granted[Manifest.permission.ACTIVITY_RECOGNITION] ?: true
        if (fine && motion) {
            vm.setAutoDetect(true)
            DriveDetect.enable(context)
            permissionNote = null
            if (!DriveDetect.hasBackgroundLocation(context)) {
                backgroundLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
        } else {
            vm.setAutoDetect(false)
            permissionNote = "Auto detect needs location and physical activity permission."
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Home", style = MaterialTheme.typography.headlineMedium, color = TextHi)
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, "Settings", tint = TextMid)
            }
        }

        // ---- Automatic drive detection ----
        SectionCard {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Automatic drive detection",
                    style = MaterialTheme.typography.titleLarge,
                    color = TextHi,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    if (autoDetect) "ON" else "OFF",
                    style = MaterialTheme.typography.labelLarge,
                    color = TextMid
                )
                Spacer(Modifier.width(8.dp))
                Switch(
                    checked = autoDetect,
                    onCheckedChange = { want ->
                        if (want) {
                            val needed = buildList {
                                add(Manifest.permission.ACCESS_FINE_LOCATION)
                                add(Manifest.permission.ACCESS_COARSE_LOCATION)
                                add(Manifest.permission.ACTIVITY_RECOGNITION)
                                if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            permissionLauncher.launch(needed.toTypedArray())
                        } else {
                            vm.setAutoDetect(false)
                            DriveDetect.disable(context)
                        }
                    },
                    colors = SwitchDefaults.colors(checkedTrackColor = Blue)
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                when {
                    permissionNote != null -> permissionNote!!
                    live.active -> "Recording now. ${Fmt.miles(live.miles)} miles so far."
                    autoDetect && !hasBackgroundLocation ->
                        "On, but it can only record while the app is open."
                    autoDetect -> "Auto-tracking your drives. You're good to go."
                    else -> "Off. Use the plus button to start a drive by hand."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = TextMid
            )
            if (autoDetect && !hasBackgroundLocation) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "Location is set to \"While using the app\". Android will not let MileLog " +
                        "record a drive that starts while the app is closed. Set it to " +
                        "\"Allow all the time\" and it works on its own.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Warn
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { openAppSettings() },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Warn, contentColor = Ink)
                ) { Text("Fix the location setting") }
            }
            if (live.active) {
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = { TripTrackingService.stop(context) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Spend)
                ) { Text("Stop trip") }
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---- Mileage overview ----
        SectionCard {
            CardTitle("Mileage overview") {
                DropdownLabel(mileagePeriod.label) { showMileagePeriod = true }
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                BigStat(Fmt.miles(mileage.totalMiles), "Total miles", Blue)
                BigStat(Fmt.dollars(mileage.deduction), "Value", Money, Modifier.padding(start = 8.dp))
            }
            Spacer(Modifier.height(12.dp))
            Divider()
            Spacer(Modifier.height(10.dp))
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(Modifier.weight(1f)) {
                    LegendRow(Fmt.miles(mileage.businessMiles), "Work miles", DonutWork)
                    LegendRow(Fmt.miles(mileage.otherMiles), "Other", DonutOther)
                    LegendRow(Fmt.miles(mileage.unclassifiedMiles), "Unclassified", DonutUnclassified)
                    LegendRow(Fmt.miles(mileage.personalMiles), "Personal miles", DonutPersonal)
                }
                Donut(
                    description = "Mileage split: " +
                        "${Fmt.miles(mileage.businessMiles)} work, " +
                        "${Fmt.miles(mileage.otherMiles)} other, " +
                        "${Fmt.miles(mileage.unclassifiedMiles)} unclassified, " +
                        "${Fmt.miles(mileage.personalMiles)} personal",
                    slices = listOf(
                        DonutWork to mileage.businessMiles,
                        DonutOther to mileage.otherMiles,
                        DonutUnclassified to mileage.unclassifiedMiles,
                        DonutPersonal to mileage.personalMiles
                    ),
                    modifier = Modifier.size(132.dp)
                )
            }
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = onReviewTrips,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Blue)
            ) { Text("Review trips") }
        }

        Spacer(Modifier.height(12.dp))

        // ---- Money ----
        SectionCard {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "TRANSACTIONS",
                    style = MaterialTheme.typography.labelMedium,
                    color = TextMid
                )
                DropdownLabel(
                    purposes.firstOrNull { it.id == moneyPurposeId }?.name ?: "All"
                ) { showMoneyPurpose = true }
                DropdownLabel(moneyPeriod.label) { showMoneyPeriod = true }
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                BigStat(Fmt.cents(money.first), "Revenue", Money)
                BigStat(Fmt.cents(money.second), "Expenses", Spend)
            }
            Spacer(Modifier.height(14.dp))
            Divider()
            Spacer(Modifier.height(8.dp))
            MoneyLine("Revenue", Fmt.cents(money.first), Money)
            MoneyLine("Expenses", Fmt.cents(money.second), Spend)
            MoneyLine("Profit", Fmt.cents(money.third), if (money.third >= 0) Money else Spend)
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onOpenTransactions,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Blue.copy(alpha = 0.18f), contentColor = Blue)
            ) { Text("See transactions") }
        }

        Spacer(Modifier.height(96.dp))
    }

    if (showMileagePeriod) {
        PeriodChooser(
            current = mileagePeriod.period,
            from = mileagePeriod.from,
            to = mileagePeriod.to,
            onPick = { p, f, t ->
                vm.setMileagePeriod(PeriodChoice(p, f, t)); showMileagePeriod = false
            },
            onDismiss = { showMileagePeriod = false }
        )
    }
    if (showMoneyPeriod) {
        PeriodChooser(
            current = moneyPeriod.period,
            from = moneyPeriod.from,
            to = moneyPeriod.to,
            onPick = { p, f, t ->
                vm.setMoneyPeriod(PeriodChoice(p, f, t)); showMoneyPeriod = false
            },
            onDismiss = { showMoneyPeriod = false }
        )
    }
    if (showMoneyPurpose) {
        PurposeSheet(
            purposes = purposes,
            currentId = moneyPurposeId,
            allowUnclassified = true,
            unclassifiedLabel = "All",
            onPick = { vm.setMoneyPurpose(it); showMoneyPurpose = false },
            onDismiss = { showMoneyPurpose = false }
        )
    }
}

@Composable
private fun MoneyLine(label: String, value: String, color: androidx.compose.ui.graphics.Color) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelLarge, color = color)
        Text(value, style = MaterialTheme.typography.titleMedium, color = color)
    }
}
