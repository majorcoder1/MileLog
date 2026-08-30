package com.milelog

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.milelog.data.TxnType
import com.milelog.tracking.TripTracker
import com.milelog.tracking.TripTrackingService
import com.milelog.ui.BottomBar
import com.milelog.ui.EditTripScreen
import com.milelog.ui.EditTripVm
import com.milelog.ui.EditTxnScreen
import com.milelog.ui.EditTxnVm
import com.milelog.ui.HomeScreen
import com.milelog.ui.HomeVm
import com.milelog.ui.SettingsScreen
import com.milelog.ui.SettingsVm
import com.milelog.ui.Tab
import com.milelog.ui.TaxesScreen
import com.milelog.ui.TaxesVm
import com.milelog.ui.TransactionsScreen
import com.milelog.ui.TripsScreen
import com.milelog.ui.TripsVm
import com.milelog.ui.TxnVm
import com.milelog.ui.components.AddSheet
import com.milelog.ui.theme.Ink
import com.milelog.ui.theme.MileLogTheme

private const val TRANSPARENT_BAR = 0x00FFFFFF

/** What is showing on top of the tabs, if anything. */
private sealed interface Overlay {
    data object None : Overlay
    data object Settings : Overlay
    data class Trip(val id: Long) : Overlay
    data class Money(val id: Long, val type: TxnType) : Overlay
}

class MainActivity : ComponentActivity() {

    private val homeVm: HomeVm by viewModels()
    private val tripsVm: TripsVm by viewModels()
    private val txnVm: TxnVm by viewModels()
    private val taxesVm: TaxesVm by viewModels()
    private val settingsVm: SettingsVm by viewModels()
    private val editTripVm: EditTripVm by viewModels()
    private val editTxnVm: EditTxnVm by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        // Light background needs dark status-bar icons.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(TRANSPARENT_BAR, TRANSPARENT_BAR),
            navigationBarStyle = SystemBarStyle.light(TRANSPARENT_BAR, TRANSPARENT_BAR)
        )
        super.onCreate(savedInstanceState)

        setContent {
            MileLogTheme {
                var tab by remember { mutableStateOf(startTab()) }
                var overlay by remember { mutableStateOf<Overlay>(Overlay.None) }
                var addOpen by remember { mutableStateOf(false) }
                val live by TripTracker.state.collectAsState()
                val unclassified by homeVm.unclassifiedCount.collectAsState()

                val startPermission = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { granted ->
                    if (granted[Manifest.permission.ACCESS_FINE_LOCATION] == true) {
                        TripTrackingService.start(this, auto = false)
                    }
                }

                LaunchedEffect(Unit) { homeVm.refreshAutoDetect() }

                BackHandler(enabled = overlay != Overlay.None) { overlay = Overlay.None }

                Scaffold(
                    containerColor = Ink,
                    bottomBar = {
                        if (overlay == Overlay.None) {
                            BottomBar(
                                current = tab,
                                unclassifiedCount = unclassified,
                                addOpen = addOpen,
                                onTab = { tab = it },
                                onAdd = { addOpen = true }
                            )
                        }
                    }
                ) { insets ->
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(Ink)
                            .padding(
                                bottom = if (overlay == Overlay.None) insets.calculateBottomPadding() else 0.dp
                            )
                            .statusBarsPadding()
                    ) {
                        when (val current = overlay) {
                            Overlay.None -> when (tab) {
                                Tab.HOME -> HomeScreen(
                                    vm = homeVm,
                                    onOpenSettings = { overlay = Overlay.Settings },
                                    onReviewTrips = { tab = Tab.TRIPS },
                                    onOpenTransactions = { tab = Tab.TRANSACTIONS }
                                )
                                Tab.TRIPS -> TripsScreen(
                                    vm = tripsVm,
                                    onOpenTrip = { overlay = Overlay.Trip(it) }
                                )
                                Tab.TRANSACTIONS -> TransactionsScreen(
                                    vm = txnVm,
                                    onOpenTxn = { id, type -> overlay = Overlay.Money(id, type) }
                                )
                                Tab.TAXES -> TaxesScreen(vm = taxesVm)
                            }

                            Overlay.Settings -> SettingsScreen(
                                vm = settingsVm,
                                onBack = { overlay = Overlay.None }
                            )

                            is Overlay.Trip -> EditTripScreen(
                                vm = editTripVm,
                                id = current.id,
                                onClose = { overlay = Overlay.None }
                            )

                            is Overlay.Money -> EditTxnScreen(
                                vm = editTxnVm,
                                id = current.id,
                                type = current.type,
                                onClose = { overlay = Overlay.None }
                            )
                        }
                    }
                }

                if (addOpen) {
                    AddSheet(
                        trackingActive = live.active,
                        onAddTrip = { addOpen = false; overlay = Overlay.Trip(0) },
                        onAddExpense = { addOpen = false; overlay = Overlay.Money(0, TxnType.EXPENSE) },
                        onAddRevenue = { addOpen = false; overlay = Overlay.Money(0, TxnType.REVENUE) },
                        onTracking = {
                            addOpen = false
                            if (live.active) {
                                TripTrackingService.stop(this)
                            } else {
                                startPermission.launch(
                                    buildList {
                                        add(Manifest.permission.ACCESS_FINE_LOCATION)
                                        add(Manifest.permission.ACCESS_COARSE_LOCATION)
                                        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                                    }.toTypedArray()
                                )
                            }
                        },
                        onDismiss = { addOpen = false }
                    )
                }
            }
        }
    }

    private fun startTab(): Tab = when (intent?.getStringExtra(EXTRA_TAB)) {
        "taxes" -> Tab.TAXES
        "trips" -> Tab.TRIPS
        "transactions" -> Tab.TRANSACTIONS
        else -> Tab.HOME
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    companion object {
        const val EXTRA_TAB = "tab"
    }
}
