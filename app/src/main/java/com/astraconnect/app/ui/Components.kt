package com.astraconnect.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp

@Composable
fun SectionCard(
    title: String,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

/**
 * Wie [SectionCard], aber auf- und zuklappbar. Zugeklappt zeigt der Kopf optional eine [subtitle]
 * als Kurzinfo. Der Zustand übersteht Drehen/Neuzeichnen (Schlüssel: [title]).
 */
@Composable
fun ExpandableSectionCard(
    title: String,
    icon: ImageVector? = null,
    subtitle: String? = null,
    initiallyExpanded: Boolean = false,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var expanded by rememberSaveable(title) { mutableStateOf(initiallyExpanded) }
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
            ) {
                if (icon != null) {
                    Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    if (!expanded && subtitle != null) {
                        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    if (expanded) "Zuklappen" else "Aufklappen",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(expanded) {
                Column { Spacer(Modifier.height(12.dp)); content() }
            }
        }
    }
}

@Composable
fun InfoRow(label: String, value: String, valueColor: Color = Color.Unspecified, icon: ImageVector? = null) {
    Layout(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        content = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (icon != null) {
                    Icon(icon, null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(10.dp))
                }
                Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(value, fontWeight = FontWeight.Medium, color = valueColor, textAlign = TextAlign.End)
        },
    ) { (labelM, valueM), constraints ->
        val width = constraints.maxWidth
        val gap = 12.dp.roundToPx()
        val widths = infoRowWidths(width, gap, labelM.maxIntrinsicWidth(Int.MAX_VALUE), valueM.maxIntrinsicWidth(Int.MAX_VALUE))
        val label = labelM.measure(Constraints(maxWidth = widths.first))
        val value = valueM.measure(Constraints(maxWidth = widths.second))
        val height = maxOf(label.height, value.height)
        layout(width, height) {
            label.placeRelative(0, (height - label.height) / 2)
            value.placeRelative(width - value.width, (height - value.height) / 2)
        }
    }
}

/**
 * Breitenaufteilung für [InfoRow]: Passt beides nebeneinander, bekommt die Beschriftung ihre volle Breite.
 * Sonst behält sie mindestens 40 % (bzw. so viel, wie sie braucht) und nur der Wert bricht um.
 */
internal fun infoRowWidths(width: Int, gap: Int, labelNeeds: Int, valueNeeds: Int): Pair<Int, Int> {
    val labelW = minOf(labelNeeds, maxOf((width * 0.4).toInt(), width - gap - valueNeeds)).coerceIn(0, width)
    val valueW = (width - gap - labelW).coerceAtLeast(0)
    return labelW to valueW
}

@Composable
fun LevelBar(label: String, percent: Double?, trailing: String, color: Color, icon: ImageVector) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(label, Modifier.weight(1f))
            Text(percent.pct(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { ((percent ?: 0.0) / 100.0).toFloat().coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)),
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            strokeCap = StrokeCap.Round,
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
        Spacer(Modifier.height(4.dp))
        Text(trailing, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun StatusChip(text: String, color: Color, icon: ImageVector? = null) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = color, modifier = Modifier.size(16.dp))
        else Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(text, color = color, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun BigStat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
