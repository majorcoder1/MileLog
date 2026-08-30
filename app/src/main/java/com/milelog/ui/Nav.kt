package com.milelog.ui

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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.milelog.ui.theme.Blue
import com.milelog.ui.theme.Card
import com.milelog.ui.theme.Line
import com.milelog.ui.theme.TextLow

enum class Tab(val route: String, val label: String, val icon: ImageVector) {
    HOME("home", "Home", Icons.Filled.Home),
    TRIPS("trips", "Trips", Icons.Filled.DirectionsCar),
    TRANSACTIONS("transactions", "Transactions", Icons.Filled.ReceiptLong),
    TAXES("taxes", "Taxes", Icons.Filled.Savings)
}

@Composable
fun BottomBar(
    current: Tab,
    unclassifiedCount: Int,
    addOpen: Boolean,
    onTab: (Tab) -> Unit,
    onAdd: () -> Unit
) {
    Box {
        Column(
            Modifier
                .fillMaxWidth()
                .background(Card)
                .navigationBarsPadding()
        ) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(Line))
            Row(
                Modifier.fillMaxWidth().height(64.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BarItem(Tab.HOME, current, 0, Modifier.weight(1f)) { onTab(Tab.HOME) }
                BarItem(Tab.TRIPS, current, unclassifiedCount, Modifier.weight(1f)) { onTab(Tab.TRIPS) }
                Spacer(Modifier.width(76.dp))
                BarItem(Tab.TRANSACTIONS, current, 0, Modifier.weight(1f)) { onTab(Tab.TRANSACTIONS) }
                BarItem(Tab.TAXES, current, 0, Modifier.weight(1f)) { onTab(Tab.TAXES) }
            }
        }

        // The center button sits above the bar, the way Aaron is used to.
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .offset(y = (-18).dp)
                .size(62.dp)
                .clip(CircleShape)
                .background(Blue)
                .clickable(onClick = onAdd),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                if (addOpen) Icons.Filled.Close else Icons.Filled.Add,
                contentDescription = if (addOpen) "Close menu" else "Add",
                tint = androidx.compose.ui.graphics.Color.White,
                modifier = Modifier.size(30.dp)
            )
        }
    }
}

@Composable
private fun BarItem(
    tab: Tab,
    current: Tab,
    badge: Int,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val selected = tab == current
    Column(
        modifier
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        BadgedBox(badge = {
            if (badge > 0) Badge { Text(badge.coerceAtMost(99).toString()) }
        }) {
            Icon(
                tab.icon,
                contentDescription = tab.label,
                tint = if (selected) Blue else TextLow,
                modifier = Modifier.size(24.dp)
            )
        }
        Text(
            tab.label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) Blue else TextLow,
            maxLines = 1
        )
    }
}
