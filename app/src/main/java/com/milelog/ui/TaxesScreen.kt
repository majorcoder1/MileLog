package com.milelog.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import com.milelog.data.Fmt
import com.milelog.export.Exporter
import com.milelog.ui.components.CardTitle
import com.milelog.ui.components.Divider
import com.milelog.ui.components.DropdownLabel
import com.milelog.ui.components.SectionCard
import com.milelog.ui.components.SheetList
import com.milelog.ui.components.SheetRow
import com.milelog.ui.theme.Blue
import com.milelog.ui.theme.Money
import com.milelog.ui.theme.Spend
import com.milelog.ui.theme.TextHi
import com.milelog.ui.theme.TextMid
import com.milelog.ui.theme.Warn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun TaxesScreen(vm: TaxesVm) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val year by vm.year.collectAsState()
    val years by vm.years.collectAsState()
    val summary by vm.summary.collectAsState()
    val busy by vm.busy.collectAsState()

    var showYears by remember { mutableStateOf(false) }
    var built by remember { mutableStateOf<Exporter.Result?>(null) }

    Column(
        Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp)
    ) {
        Text(
            "Taxes",
            style = MaterialTheme.typography.headlineMedium,
            color = TextHi,
            modifier = Modifier.padding(vertical = 10.dp)
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Your ", style = MaterialTheme.typography.titleLarge, color = TextHi)
            DropdownLabel(year.toString()) { showYears = true }
            Text(" totals", style = MaterialTheme.typography.titleLarge, color = TextHi)
        }
        Text(
            "Come tax time in April ${year + 1}, the bigger these numbers, the more you keep.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextMid,
            modifier = Modifier.padding(vertical = 8.dp)
        )

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionCard(Modifier.weight(1f)) {
                Text(
                    "${Fmt.miles(summary.businessMiles)} mi",
                    style = MaterialTheme.typography.headlineSmall,
                    color = TextHi
                )
                Text("Work mileage", style = MaterialTheme.typography.labelMedium, color = TextMid)
            }
            SectionCard(Modifier.weight(1f)) {
                Text(
                    Fmt.dollars(summary.deduction),
                    style = MaterialTheme.typography.headlineSmall,
                    color = Money
                )
                Text("Mileage deduction", style = MaterialTheme.typography.labelMedium, color = TextMid)
            }
        }

        Spacer(Modifier.height(12.dp))

        // ---- How the deduction was figured ----
        SectionCard {
            CardTitle("How that was figured")
            Spacer(Modifier.height(10.dp))
            if (summary.slices.isEmpty()) {
                Text(
                    "No deductible miles yet for $year. Classify your trips and they show up here.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMid
                )
            } else {
                summary.slices.forEach { s ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(s.label, style = MaterialTheme.typography.titleMedium, color = TextHi)
                            Text(
                                "${Fmt.miles(s.miles)} mi at ${s.centsPerMile} cents  ·  " +
                                    s.deductionClass.name.lowercase(),
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextMid
                            )
                        }
                        Text(
                            Fmt.dollars(s.dollars),
                            style = MaterialTheme.typography.titleMedium,
                            color = Money
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Divider()
                Spacer(Modifier.height(8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Total", style = MaterialTheme.typography.titleLarge, color = TextHi)
                    Text(
                        Fmt.dollars(summary.deduction),
                        style = MaterialTheme.typography.titleLarge,
                        color = Money
                    )
                }
            }
            if (summary.unclassifiedMiles > 0) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "${Fmt.miles(summary.unclassifiedMiles)} miles are still unclassified and are " +
                        "not counted above. Sort them on the Trips tab.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Warn
                )
            }
            if (summary.ratesEstimated) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Some trips fell outside the rate table, so the most recent rate was used. " +
                        "Add the new year's rate in Settings.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Warn
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        SectionCard {
            CardTitle("Money in and out")
            Spacer(Modifier.height(10.dp))
            MoneyRow("Revenue", Fmt.cents(summary.revenueCents), Money)
            MoneyRow("Expenses", Fmt.cents(summary.expenseCents), Spend)
            Divider()
            Spacer(Modifier.height(6.dp))
            MoneyRow(
                "Profit",
                Fmt.cents(summary.profitCents),
                if (summary.profitCents >= 0) Money else Spend
            )
        }

        Spacer(Modifier.height(12.dp))

        // ---- Export ----
        SectionCard {
            CardTitle("Doing your taxes yourself?")
            Spacer(Modifier.height(6.dp))
            Text(
                "Builds a spreadsheet with every trip and every transaction for $year, " +
                    "totals already added up.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextMid
            )
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = {
                    vm.setBusy(true)
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching { Exporter.buildYear(context, year) }
                        }
                        vm.setBusy(false)
                        result.onSuccess {
                            built = it
                            withContext(Dispatchers.IO) { Exporter.saveToDownloads(context, it.xlsx) }
                            Toast.makeText(context, "Saved to Downloads/MileLog", Toast.LENGTH_SHORT).show()
                        }.onFailure {
                            Toast.makeText(context, "Could not build the file: ${it.message}", Toast.LENGTH_LONG).show()
                        }
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Blue)
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.height(18.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.height(0.dp))
                } else {
                    Text("Build the $year spreadsheet")
                }
            }

            built?.let { result ->
                Spacer(Modifier.height(12.dp))
                Text(
                    result.xlsx.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = TextHi
                )
                Text(
                    "Saved in Downloads/MileLog on this phone.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextMid
                )
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = {
                            context.startActivity(
                                Exporter.emailIntent(
                                    context,
                                    listOf(result.xlsx),
                                    "MileLog $year",
                                    buildString {
                                        appendLine("MileLog totals for $year")
                                        appendLine()
                                        appendLine("Total miles: ${Fmt.miles(summary.totalMiles)}")
                                        appendLine("Work miles: ${Fmt.miles(summary.businessMiles)}")
                                        appendLine("Mileage deduction: ${Fmt.dollars(summary.deduction)}")
                                        appendLine("Revenue: ${Fmt.cents(summary.revenueCents)}")
                                        appendLine("Expenses: ${Fmt.cents(summary.expenseCents)}")
                                        appendLine("Profit: ${Fmt.cents(summary.profitCents)}")
                                    }
                                )
                            )
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = Blue)
                    ) { Text("Email it") }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    Exporter.saveToDownloads(context, result.csvTrips)
                                    Exporter.saveToDownloads(context, result.csvTxns)
                                }
                                Toast.makeText(context, "CSV files saved too", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier.weight(1f)
                    ) { Text("Also save CSV") }
                }
            }
        }

        Spacer(Modifier.height(110.dp))
    }

    if (showYears) {
        SheetList(title = "Which year", onDismiss = { showYears = false }) {
            years.forEach { y ->
                SheetRow(y.toString(), selected = y == year) {
                    vm.setYear(y); built = null; showYears = false
                }
            }
        }
    }
}

@Composable
private fun MoneyRow(label: String, value: String, color: androidx.compose.ui.graphics.Color) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = TextMid)
        Text(value, style = MaterialTheme.typography.titleMedium, color = color)
    }
}
