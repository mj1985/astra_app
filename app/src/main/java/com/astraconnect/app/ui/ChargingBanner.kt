package com.astraconnect.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.astraconnect.app.ui.theme.Ok
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import kotlin.math.abs
import kotlin.math.floor

/**
 * Ladeanzeige unter dem Fahrzeugbild: segmentierter Balken mit Lauflicht über dem geladenen
 * Teil, pulsierendem Segment an der aktuellen Ladegrenze und mitlaufender Restladezeit.
 */
@Composable
fun ChargingBanner(
    percent: Double?,
    remaining: Duration?,
    reportedAt: Instant?,
    rateKmh: Double?,
    electricRangeKm: Double?,
) {
    // Restzeit bezieht sich auf den Meldezeitpunkt des Fahrzeugs – daraus die Fertig-Uhrzeit
    // berechnen und die Restzeit minütlich herunterzählen.
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) {
        while (true) { delay(30_000); now = Instant.now() }
    }
    val eta = remaining?.let { (reportedAt ?: now).plus(it) }
    val left = eta?.let { Duration.between(now, it) }

    val transition = rememberInfiniteTransition(label = "charging")
    val sweep by transition.animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "sweep",
    )
    val pulse by transition.animateFloat(
        initialValue = 0.2f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(750), RepeatMode.Reverse), label = "pulse",
    )

    val segments = 10
    val level = ((percent ?: 0.0) / 100.0).coerceIn(0.0, 1.0) * segments
    val activeSegment = floor(level).toInt().coerceAtMost(segments - 1)
    val track = MaterialTheme.colorScheme.surfaceContainerHighest

    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Ok.copy(alpha = 0.10f)),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Bolt, null, tint = Ok.copy(alpha = 0.4f + 0.6f * pulse), modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(6.dp))
                Text("Lädt", fontWeight = FontWeight.SemiBold, color = Ok, modifier = Modifier.weight(1f))
                Text(percent.pct(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth().height(22.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val wave = sweep * (segments + 3) - 1.5f
                for (i in 0 until segments) {
                    val fill = (level - i).coerceIn(0.0, 1.0).toFloat()
                    val glow = if (i < level) (1f - abs(i - wave) / 1.5f).coerceIn(0f, 1f) else 0f
                    Box(
                        Modifier.weight(1f).fillMaxHeight()
                            .clip(RoundedCornerShape(5.dp))
                            .background(track)
                    ) {
                        if (i == activeSegment && fill < 1f) {
                            Box(Modifier.fillMaxSize().background(Ok.copy(alpha = 0.35f * pulse)))
                        }
                        if (fill > 0f) {
                            Box(
                                Modifier.fillMaxHeight().fillMaxWidth(fill)
                                    .background(lerp(Ok, Color.White, 0.45f * glow))
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(
                when {
                    left == null -> "Restladezeit unbekannt"
                    left.isNegative || left.isZero -> "Sollte inzwischen voll sein (fertig ca. ${eta.time()})"
                    else -> "Noch ca. ${left.pretty()} · fertig ca. ${eta.time()} Uhr"
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            val details = listOfNotNull(
                rateKmh?.takeIf { it > 0 }?.let { "+${it.num(0, "km/h")}" },
                electricRangeKm?.let { "elektrisch ${it.km()}" },
            )
            if (details.isNotEmpty()) {
                Text(
                    details.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
