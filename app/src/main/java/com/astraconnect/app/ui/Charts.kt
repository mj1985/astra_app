package com.astraconnect.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * Feste Diagrammfarben (Kategorie-Slots 1 und 2 der geprüften Referenzpalette, je Modus eigene
 * Stufe). Die Zuordnung ist überall gleich: Strom = Blau, Benzin = Orange.
 */
object ChartColors {
    val electric: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFF3987E5) else Color(0xFF2A78D6)
    val fuel: Color @Composable get() = if (isSystemInDarkTheme()) Color(0xFFD95926) else Color(0xFFEB6834)
    /** Neutral für „ohne Angabe“ – bewusst keine Kategorie-Farbe. */
    val unknown: Color @Composable get() = MaterialTheme.colorScheme.outline.copy(alpha = 0.55f)
}

data class ChartSeries(val name: String, val color: Color, val values: List<Double>)

/** Runde Achsen-Obergrenze (1, 2, 2,5, 5 × 10ⁿ). */
private fun niceMax(v: Double): Double {
    if (v <= 0) return 1.0
    val exp = 10.0.pow(floor(log10(v)))
    val f = v / exp
    val nice = when {
        f <= 1 -> 1.0
        f <= 2 -> 2.0
        f <= 2.5 -> 2.5
        f <= 5 -> 5.0
        else -> 10.0
    }
    return nice * exp
}

private fun fmt(v: Double, digits: Int) = String.format(Locale.GERMANY, "%,.${digits}f", v)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Legend(series: List<ChartSeries>) {
    if (series.size < 2) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        series.forEach { s ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(s.color))
                Spacer(Modifier.width(6.dp))
                Text(s.name, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * Gestapeltes Säulendiagramm mit einer Achse. Antippen einer Säule zeigt deren Werte oberhalb
 * des Diagramms an (Werte stehen in Textfarbe, die Farbe kennzeichnet nur die Reihe).
 */
@Composable
fun StackedBarChart(
    labels: List<String>,
    series: List<ChartSeries>,
    unit: String,
    digits: Int = 0,
    height: Dp = 180.dp,
    longLabels: List<String> = labels,
) {
    if (labels.isEmpty() || series.isEmpty()) {
        Text("Keine Daten im gewählten Zeitraum.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    var selected by remember(labels) { mutableStateOf<Int?>(null) }
    val n = labels.size
    val totals = (0 until n).map { i -> series.sumOf { it.values.getOrElse(i) { 0.0 } } }
    val top = niceMax(totals.maxOrNull() ?: 0.0)

    val measurer = rememberTextMeasurer()
    val axisStyle = TextStyle(fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val grid = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    val highlight = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f)

    Column {
        // Ablese-Zeile (statt Hover auf dem Handy: antippen)
        val readout = selected?.let { i ->
            longLabels[i] + ": " + series.filter { it.values.getOrElse(i) { 0.0 } > 0 }
                .joinToString(" · ") { "${it.name} ${fmt(it.values[i], digits)} $unit" }
                .ifBlank { "0 $unit" } +
                if (series.size > 1) "  (Σ ${fmt(totals[i], digits)} $unit)" else ""
        } ?: "Säule antippen für Werte"
        Text(readout, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        Canvas(
            Modifier.fillMaxWidth().height(height).pointerInput(n) {
                detectTapGestures { pos ->
                    val left = 40.dp.toPx()
                    val slot = (size.width - left) / n
                    val i = ((pos.x - left) / slot).toInt()
                    selected = if (i in 0 until n && selected != i) i else null
                }
            }
        ) {
            val left = 40.dp.toPx()
            val bottomPad = 18.dp.toPx()
            val plotH = size.height - bottomPad
            val plotW = size.width - left
            val slot = plotW / n
            val barW = minOf(slot * 0.62f, 28.dp.toPx())
            val gap = 2.dp.toPx()
            val r = 4.dp.toPx()

            // Raster + Achsenbeschriftung (zurückhaltend)
            listOf(0.0, top / 2, top).forEach { v ->
                val y = plotH - (v / top * plotH).toFloat()
                drawLine(grid, Offset(left, y), Offset(size.width, y), strokeWidth = 1f)
                val t = measurer.measure(fmt(v, if (top < 5) 1 else 0), axisStyle)
                drawText(t, topLeft = Offset(left - t.size.width - 6.dp.toPx(), y - t.size.height / 2f))
            }

            for (i in 0 until n) {
                val cx = left + slot * i + slot / 2
                if (selected == i) drawRect(highlight, Offset(left + slot * i, 0f), Size(slot, plotH))
                var base = plotH
                val segs = series.mapNotNull { s -> s.values.getOrElse(i) { 0.0 }.takeIf { it > 0 }?.let { s.color to it } }
                segs.forEachIndexed { k, (color, v) ->
                    val h = (v / top * plotH).toFloat()
                    val isTop = k == segs.lastIndex
                    val yTop = base - h
                    val segH = (h - if (k > 0) gap else 0f).coerceAtLeast(0.5f)
                    val rect = RoundRect(
                        left = cx - barW / 2, top = yTop, right = cx + barW / 2, bottom = yTop + segH,
                        topLeftCornerRadius = if (isTop) CornerRadius(r) else CornerRadius.Zero,
                        topRightCornerRadius = if (isTop) CornerRadius(r) else CornerRadius.Zero,
                        bottomLeftCornerRadius = CornerRadius.Zero, bottomRightCornerRadius = CornerRadius.Zero,
                    )
                    val alpha = if (selected == null || selected == i) 1f else 0.45f
                    drawPath(Path().apply { addRoundRect(rect) }, color.copy(alpha = color.alpha * alpha))
                    base = yTop
                }
            }
            // Grundlinie
            drawLine(grid.copy(alpha = 1f), Offset(left, plotH), Offset(size.width, plotH), strokeWidth = 1.5f)

            // x-Beschriftung, ausgedünnt
            val step = ceil(n / 6.0).toInt().coerceAtLeast(1)
            for (i in 0 until n step step) {
                val t = measurer.measure(labels[i], axisStyle)
                val cx = left + slot * i + slot / 2
                drawText(t, topLeft = Offset((cx - t.size.width / 2f).coerceIn(left, size.width - t.size.width), plotH + 3.dp.toPx()))
            }
        }
        Spacer(Modifier.height(8.dp))
        Legend(series)
    }
}

/** Liniendiagramm für eine Zeitreihe (z. B. Ladestand), eine Achse 0…[yMax]. */
@Composable
fun TimeLineChart(
    points: List<Pair<Instant, Double>>,
    color: Color,
    unit: String,
    yMax: Double = 100.0,
    height: Dp = 170.dp,
) {
    if (points.size < 2) {
        Text("Zu wenige Messpunkte im Zeitraum.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val zone = ZoneId.systemDefault()
    val sorted = points.sortedBy { it.first }
    val t0 = sorted.first().first.epochSecond.toDouble()
    val t1 = sorted.last().first.epochSecond.toDouble().coerceAtLeast(t0 + 1)
    var selected by remember(points) { mutableStateOf<Int?>(null) }
    val measurer = rememberTextMeasurer()
    val axisStyle = TextStyle(fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val grid = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    val surface = MaterialTheme.colorScheme.surfaceContainer
    val cursor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    val dayFmt = DateTimeFormatter.ofPattern("EE", Locale.GERMANY).withZone(zone)
    val fullFmt = DateTimeFormatter.ofPattern("EE dd.MM., HH:mm", Locale.GERMANY).withZone(zone)

    Column {
        Text(
            selected?.let { "${fullFmt.format(sorted[it].first)} · ${fmt(sorted[it].second, 0)} $unit" } ?: "Kurve antippen für Werte",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Canvas(
            Modifier.fillMaxWidth().height(height).pointerInput(points) {
                detectTapGestures { pos ->
                    val left = 36.dp.toPx()
                    val w = size.width - left
                    val t = t0 + (pos.x - left) / w * (t1 - t0)
                    selected = sorted.indices.minByOrNull { abs(sorted[it].first.epochSecond - t) }
                }
            }
        ) {
            val left = 36.dp.toPx()
            val bottomPad = 18.dp.toPx()
            val plotH = size.height - bottomPad
            val plotW = size.width - left
            fun x(i: Instant) = left + ((i.epochSecond - t0) / (t1 - t0) * plotW).toFloat()
            fun y(v: Double) = plotH - (v.coerceIn(0.0, yMax) / yMax * plotH).toFloat()

            listOf(0.0, yMax / 2, yMax).forEach { v ->
                drawLine(grid, Offset(left, y(v)), Offset(size.width, y(v)), strokeWidth = 1f)
                val t = measurer.measure(fmt(v, 0), axisStyle)
                drawText(t, topLeft = Offset(left - t.size.width - 6.dp.toPx(), y(v) - t.size.height / 2f))
            }
            // Tageswechsel als x-Beschriftung
            var day = sorted.first().first.atZone(zone).toLocalDate().plusDays(1)
            val endDay = sorted.last().first.atZone(zone).toLocalDate()
            while (!day.isAfter(endDay)) {
                val inst = day.atStartOfDay(zone).toInstant()
                val xx = x(inst)
                drawLine(grid, Offset(xx, 0f), Offset(xx, plotH), strokeWidth = 1f)
                val t = measurer.measure(dayFmt.format(inst), axisStyle)
                if (xx + t.size.width < size.width) drawText(t, topLeft = Offset(xx + 3.dp.toPx(), plotH + 3.dp.toPx()))
                day = day.plusDays(1)
            }

            val path = Path()
            sorted.forEachIndexed { i, (at, v) -> if (i == 0) path.moveTo(x(at), y(v)) else path.lineTo(x(at), y(v)) }
            drawPath(path, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

            selected?.let { i ->
                val (at, v) = sorted[i]
                drawLine(cursor, Offset(x(at), 0f), Offset(x(at), plotH), strokeWidth = 1f)
                // Punkt mit Flächen-Ring, damit er sich von der Linie abhebt
                drawCircle(surface, radius = 6.dp.toPx(), center = Offset(x(at), y(v)))
                drawCircle(color, radius = 4.dp.toPx(), center = Offset(x(at), y(v)))
            }
        }
    }
}
