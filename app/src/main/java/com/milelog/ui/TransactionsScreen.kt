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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notes
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.dp
import com.milelog.data.DeductionClass
import com.milelog.data.Fmt
import com.milelog.data.TxnRow
import com.milelog.data.TxnType
import com.milelog.ui.components.Divider
import com.milelog.ui.components.DropdownLabel
import com.milelog.ui.components.EmptyNote
import com.milelog.ui.components.PeriodChooser
import com.milelog.ui.components.PurposeSheet
import com.milelog.ui.components.SheetList
import com.milelog.ui.components.SheetRow
import com.milelog.ui.components.SwipeBackdrop
import com.milelog.ui.components.Tag
import com.milelog.ui.theme.Blue
import com.milelog.ui.theme.Card
import com.milelog.ui.theme.Money
import com.milelog.ui.theme.Sky
import com.milelog.ui.theme.Spend
import com.milelog.ui.theme.TextHi
import com.milelog.ui.theme.TextLow
import com.milelog.ui.theme.TextMid
import kotlinx.coroutines.launch

@Composable
fun TransactionsScreen(vm: TxnVm, onOpenTxn: (Long, TxnType) -> Unit) {
    val scope = rememberCoroutineScope()
    val filter by vm.filter.collectAsState()
    val rows by vm.rows.collectAsState()
    val totals by vm.totals.collectAsState()
    val purposes by vm.purposes.collectAsState()

    var showType by remember { mutableStateOf(false) }
    var showPurpose by remember { mutableStateOf(false) }
    var showPeriod by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<Long?>(null) }

    val typeLabel = when (filter.type) {
        null -> "All transactions"
        TxnType.EXPENSE -> "Expenses"
        TxnType.REVENUE -> "Revenue"
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            DropdownLabel(typeLabel) { showType = true }
            Row(verticalAlignment = Alignment.CenterVertically) {
                DropdownLabel(filter.period.label) { showPeriod = true }
                Icon(
                    Icons.Filled.FilterList, "Filter",
                    tint = TextMid,
                    modifier = Modifier.size(24.dp).clickable { showPurpose = true }
                )
                Spacer(Modifier.width(4.dp))
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(Fmt.cents(totals.first), style = MaterialTheme.typography.titleLarge, color = Money)
                Text("REVENUE", style = MaterialTheme.typography.labelSmall, color = TextMid)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(Fmt.cents(totals.second), style = MaterialTheme.typography.titleLarge, color = Spend)
                Text("EXPENSES", style = MaterialTheme.typography.labelSmall, color = TextMid)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    Fmt.cents(totals.third),
                    style = MaterialTheme.typography.titleLarge,
                    color = if (totals.third >= 0) Money else Spend
                )
                Text("PROFIT", style = MaterialTheme.typography.labelSmall, color = TextMid)
            }
        }

        // Swipe hint, same idea as the app Aaron is used to.
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Home, null, tint = TextLow, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("← PERSONAL", style = MaterialTheme.typography.labelMedium, color = TextLow)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("WORK →", style = MaterialTheme.typography.labelMedium, color = TextLow)
                Spacer(Modifier.width(8.dp))
                Icon(Icons.Filled.Work, null, tint = TextLow, modifier = Modifier.size(18.dp))
            }
        }
        Divider()

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 110.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (rows.isEmpty()) item { EmptyNote("Nothing recorded in this period yet.") }
            items(rows, key = { it.id }) { row ->
                SwipeTxnCard(
                    row = row,
                    onWork = {
                        scope.launch { vm.classify(row.id, vm.firstBusinessPurposeId()) }
                    },
                    onPersonal = {
                        scope.launch { vm.classify(row.id, vm.personalPurposeId()) }
                    },
                    onClick = { onOpenTxn(row.id, row.type) },
                    onDelete = { confirmDelete = row.id }
                )
            }
        }
    }

    confirmDelete?.let { id ->
        ConfirmDelete(
            what = "this entry",
            onConfirm = { vm.delete(id); confirmDelete = null },
            onDismiss = { confirmDelete = null }
        )
    }

    if (showType) {
        SheetList(title = "Show", onDismiss = { showType = false }) {
            SheetRow("All transactions", selected = filter.type == null) {
                vm.setFilter(filter.copy(type = null)); showType = false
            }
            SheetRow("Expenses only", selected = filter.type == TxnType.EXPENSE) {
                vm.setFilter(filter.copy(type = TxnType.EXPENSE)); showType = false
            }
            SheetRow("Revenue only", selected = filter.type == TxnType.REVENUE) {
                vm.setFilter(filter.copy(type = TxnType.REVENUE)); showType = false
            }
        }
    }
    if (showPeriod) {
        PeriodChooser(
            current = filter.period.period,
            from = filter.period.from,
            to = filter.period.to,
            onPick = { p, f, t ->
                vm.setFilter(filter.copy(period = PeriodChoice(p, f, t))); showPeriod = false
            },
            onDismiss = { showPeriod = false }
        )
    }
    if (showPurpose) {
        PurposeSheet(
            purposes = purposes,
            currentId = filter.purposeId,
            unclassifiedLabel = "All purposes",
            onPick = { vm.setFilter(filter.copy(purposeId = it)); showPurpose = false },
            onDismiss = { showPurpose = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeTxnCard(
    row: TxnRow,
    onWork: () -> Unit,
    onPersonal: () -> Unit,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> { onWork(); false }
                SwipeToDismissBoxValue.EndToStart -> { onPersonal(); false }
                else -> false
            }
        }
    )
    SwipeToDismissBox(
        state = state,
        backgroundContent = { SwipeBackdrop(state.dismissDirection) }
    ) {
        TxnCard(row, onClick, onDelete)
    }
}

@Composable
private fun TxnCard(row: TxnRow, onClick: () -> Unit, onDelete: () -> Unit) {
    val amountColor = if (row.type == TxnType.REVENUE) Money else Spend
    val sign = if (row.type == TxnType.REVENUE) "+" else "-"
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Card)
            .clickable(onClick = onClick)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                Fmt.date(row.dateEpochDay).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                color = TextMid
            )
            Tag(row.purposeName ?: "Unclassified", if (row.purposeName == null) TextMid else Blue)
        }
        Divider()
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Box(
                    Modifier.size(38.dp).clip(CircleShape)
                        .background(amountColor.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Filled.AttachMoney, null, tint = amountColor, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        row.merchant.ifBlank { row.categoryName ?: "Transaction" },
                        style = MaterialTheme.typography.titleMedium,
                        color = TextHi,
                        maxLines = 1
                    )
                    Text(
                        row.categoryName ?: "Uncategorized",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextMid,
                        maxLines = 1
                    )
                }
            }
            Text(
                "$sign${Fmt.cents(row.amountCents)}",
                style = MaterialTheme.typography.titleLarge,
                color = amountColor
            )
        }
        Divider()
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.AttachMoney, null,
                    tint = if (row.type == TxnType.REVENUE) Money else TextMid,
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
                    Icons.Filled.PhotoCamera,
                    if (!row.receiptPath.isNullOrBlank()) "Has a receipt photo" else "No receipt photo",
                    tint = if (!row.receiptPath.isNullOrBlank()) Blue else TextMid,
                    modifier = Modifier.size(20.dp)
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, "Delete this entry", tint = TextMid, modifier = Modifier.size(22.dp))
            }
        }
    }
}
