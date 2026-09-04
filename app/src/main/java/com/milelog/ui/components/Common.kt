package com.milelog.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.milelog.ui.theme.Blue
import com.milelog.ui.theme.Card as CardColor
import com.milelog.ui.theme.CardHigh
import com.milelog.ui.theme.Line
import com.milelog.ui.theme.Personal
import com.milelog.ui.theme.TextHi
import com.milelog.ui.theme.TextLow
import com.milelog.ui.theme.TextMid

@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(16.dp),
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = CardColor),
        border = BorderStroke(1.dp, Line)
    ) {
        Column(Modifier.padding(padding), content = content)
    }
}

@Composable
fun CardTitle(text: String, trailing: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, style = MaterialTheme.typography.titleLarge, color = TextHi)
        trailing?.invoke()
    }
}

/** The "Today ˅" control that opens the period list. */
@Composable
fun DropdownLabel(text: String, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = TextMid)
        Icon(Icons.Filled.ExpandMore, null, tint = TextMid, modifier = Modifier.size(20.dp))
    }
}

@Composable
fun BigStat(value: String, label: String, color: Color = Blue, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(
            value,
            style = MaterialTheme.typography.displaySmall,
            color = color,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            label.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = TextMid,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun LegendRow(value: String, label: String, dot: Color) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Text(value, style = MaterialTheme.typography.headlineSmall, color = TextHi)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(9.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium, color = TextMid)
        }
    }
}

/** Mileage split, drawn as a ring. Values are miles; zero slices are skipped. */
@Composable
fun Donut(
    slices: List<Pair<Color, Double>>,
    modifier: Modifier = Modifier.size(140.dp),
    strokeWidth: Float = 46f,
    description: String? = null
) {
    val total = slices.sumOf { it.second }
    val progress by animateFloatAsState(if (total > 0) 1f else 0f, label = "donut")
    Canvas(
        if (description != null) {
            modifier.semantics { contentDescription = description }
        } else modifier
    ) {
        val stroke = Stroke(width = strokeWidth)
        val inset = strokeWidth / 2
        val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
        val topLeft = Offset(inset, inset)
        if (total <= 0.0) {
            drawArc(
                color = Line, startAngle = 0f, sweepAngle = 360f, useCenter = false,
                topLeft = topLeft, size = arcSize, style = stroke
            )
            return@Canvas
        }
        // A small gap between slices. Four hues cannot all be 3:1 apart from each other,
        // so the boundary is drawn rather than left to colour alone.
        val gap = 3f
        var start = -90f
        slices.forEach { (color, value) ->
            if (value <= 0.0) return@forEach
            val sweep = (value / total * 360.0).toFloat() * progress
            drawArc(
                color = color,
                startAngle = start + gap / 2,
                sweepAngle = (sweep - gap).coerceAtLeast(0.5f),
                useCenter = false,
                topLeft = topLeft, size = arcSize, style = stroke
            )
            start += sweep
        }
    }
}

@Composable
fun Pill(
    text: String,
    selected: Boolean = false,
    trailingChevron: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    Surface(
        color = if (selected) Blue else CardHigh,
        contentColor = if (selected) Color.White else TextMid,
        shape = RoundedCornerShape(50),
        modifier = Modifier.then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            if (trailingChevron) {
                Icon(Icons.Filled.ExpandMore, null, modifier = Modifier.size(18.dp))
            }
        }
    }
}

/**
 * [color] tints the background; [textColor] defaults to a darker shade of it because the
 * accent itself is not readable on its own 18% tint.
 */
@Composable
fun Tag(
    text: String,
    color: Color = Blue,
    textColor: Color = TextHi,
    onClick: (() -> Unit)? = null
) {
    Surface(
        color = color.copy(alpha = 0.18f),
        contentColor = textColor,
        shape = RoundedCornerShape(8.dp),
        modifier = if (onClick != null) {
            Modifier.clickable(onClick = onClick).semantics {
                contentDescription = "Filed as $text. Tap to change."
            }
        } else Modifier
    ) {
        Row(
            // Roomy enough to hit while driving, since this is now a control.
            Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, maxLines = 1)
            if (onClick != null) {
                Icon(Icons.Filled.ExpandMore, null, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
fun Divider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Line))
}

@Composable
fun EmptyNote(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = TextMid,
        modifier = Modifier.padding(vertical = 24.dp)
    )
}

/**
 * What shows behind a card as it is dragged. Work and personal are a different hue from
 * each other rather than two blues, and only the side being swiped is labelled, so there
 * is no guessing which way the release will file it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeBackdrop(direction: SwipeToDismissBoxValue) {
    val toWork = direction == SwipeToDismissBoxValue.StartToEnd
    val fill = when (direction) {
        SwipeToDismissBoxValue.StartToEnd -> Blue
        SwipeToDismissBoxValue.EndToStart -> Personal
        else -> Color.Transparent
    }
    Row(
        Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(18.dp))
            .background(fill)
            .padding(horizontal = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = if (toWork) Arrangement.Start else Arrangement.End
    ) {
        if (direction == SwipeToDismissBoxValue.Settled) return@Row
        val label = if (toWork) "WORK" else "PERSONAL"
        val icon = if (toWork) Icons.Filled.Work else Icons.Filled.Home
        if (toWork) {
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(label, style = MaterialTheme.typography.titleMedium, color = Color.White)
        if (!toWork) {
            Spacer(Modifier.width(10.dp))
            Icon(icon, null, tint = Color.White, modifier = Modifier.size(22.dp))
        }
    }
}
