package com.astraconnect.app.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.EvStation
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.astraconnect.app.AppViewModel
import com.astraconnect.app.data.VehicleStatus
import com.astraconnect.app.ui.theme.Bad
import com.astraconnect.app.ui.theme.Ok
import com.astraconnect.app.ui.theme.Warn
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun OverviewScreen(vm: AppViewModel, onNavigate: (Dest) -> Unit) {
    val ui = vm.ui
    val s = ui.status
    val ctx = LocalContext.current

    PullToRefreshBox(isRefreshing = ui.loading, onRefresh = { vm.refresh() }, modifier = Modifier.fillMaxSize()) {
        if (s == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (ui.loading) CircularProgressIndicator()
                else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Noch keine Fahrzeugdaten.")
                    OutlinedButton(onClick = { vm.refresh() }) { Text("Laden") }
                }
            }
            return@PullToRefreshBox
        }

        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Kopfbereich
            item {
                Column(Modifier.fillMaxWidth()) {
                    ui.vehicle?.pictureUrl?.let {
                        AsyncImage(
                            model = it, contentDescription = null, contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxWidth().height(170.dp),
                        )
                    }
                    s.charging?.takeIf { it.isCharging }?.let { c ->
                        ChargingBanner(
                            percent = s.batteryPercent,
                            remaining = c.remaining,
                            reportedAt = s.updatedAt,
                            rateKmh = c.rateKmh,
                            electricRangeKm = s.electricRangeKm,
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                    Text(ui.vehicle?.displayName ?: "Opel Astra", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text(
                        "Plug-in-Hybrid · Daten ${s.updatedAt.ago()} (${s.updatedAt.dateTime()})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        when (s.isLocked) {
                            true -> StatusChip(s.lockLabel, Ok, Icons.Filled.Lock)
                            false -> StatusChip(s.lockLabel, Bad, Icons.Filled.LockOpen)
                            null -> StatusChip("Verriegelung unbekannt", Warn)
                        }
                        when {
                            s.doors.isEmpty() -> {}
                            s.anyDoorOpen -> StatusChip("Tür/Klappe offen", Bad, Icons.Filled.MeetingRoom)
                            else -> StatusChip("Türen zu", Ok, Icons.Filled.MeetingRoom)
                        }
                        if (s.windowsReported) {
                            if (s.anyWindowOpen) StatusChip("Fenster offen", Bad) else StatusChip("Fenster zu", Ok)
                        }
                        if (s.charging?.isCharging == true) StatusChip("Lädt", Ok, Icons.Filled.BatteryChargingFull)
                        else if (s.charging?.plugged == true) StatusChip("Kabel steckt", Warn, Icons.Filled.EvStation)
                    }
                }
            }

            // Schnellzugriff Fernsteuerung
            item {
                if (ui.remoteActivated) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { vm.lock() }, enabled = !ui.remoteBusy, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.Lock, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Zu")
                        }
                        FilledTonalButton(onClick = { onNavigate(Dest.REMOTE) }, enabled = !ui.remoteBusy, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.LockOpen, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Auf…")
                        }
                        FilledTonalButton(onClick = { vm.preconditioning(true) }, enabled = !ui.remoteBusy, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.AcUnit, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Klima")
                        }
                    }
                    ui.commands.firstOrNull()?.let {
                        Text(
                            "${it.name}: ${it.status}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                } else {
                    OutlinedButton(onClick = { onNavigate(Dest.REMOTE) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Fernsteuerung einrichten")
                    }
                }
            }

            // Energie & Reichweite
            item {
                SectionCard("Energie & Reichweite", Icons.Filled.EvStation) {
                    Row(Modifier.fillMaxWidth()) {
                        BigStat(s.totalRangeKm.km(), "Gesamtreichweite", Modifier.weight(1f))
                        BigStat(s.mileageKm.km(), "Kilometerstand", Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(8.dp))
                    LevelBar(
                        "Hochvoltbatterie", s.batteryPercent,
                        "elektrische Reichweite ${s.electricRangeKm.km()}",
                        MaterialTheme.colorScheme.primary, Icons.Filled.BatteryChargingFull,
                    )
                    LevelBar(
                        "Tank", s.fuelPercent,
                        "Reichweite mit Benzin ${s.fuelRangeKm.km()}",
                        Warn, Icons.Filled.LocalGasStation,
                    )
                }
            }

            // Laden
            s.charging?.let { c ->
                item {
                    SectionCard("Laden", Icons.Filled.BatteryChargingFull) {
                        InfoRow("Ladekabel", when (c.plugged) { true -> "eingesteckt"; false -> "nicht eingesteckt"; null -> "–" })
                        InfoRow("Status", c.statusLabel, if (c.isCharging) Ok else androidx.compose.ui.graphics.Color.Unspecified)
                        if (c.isCharging) {
                            InfoRow("Restdauer", c.remaining.pretty())
                            InfoRow("Ladegeschwindigkeit", c.rateKmh.num(0, "km/h"))
                        }
                        c.mode?.let { InfoRow("Lademodus", it) }
                    }
                }
            }

            // Zustand
            item {
                SectionCard("Fahrzeugzustand", Icons.Filled.Speed) {
                    InfoRow("Zündung", s.ignitionLabel, icon = Icons.Filled.PowerSettingsNew)
                    InfoRow("Außentemperatur", s.outsideTempC.num(0, "°C"), icon = Icons.Filled.Thermostat)
                    InfoRow("Vorklimatisierung", preconditioningLabel(s.preconditioning), icon = Icons.Filled.AcUnit)
                    if (s.alerts.isNotEmpty()) {
                        HorizontalDivider(Modifier.padding(vertical = 6.dp))
                        s.alerts.forEach { InfoRow("Warnung", it, Bad, Icons.Filled.Warning) }
                    }
                }
            }

            // Kurzinfos mit Sprung
            item {
                SectionCard("Wartung & Batterie", Icons.Filled.Build, Modifier.clickable { onNavigate(Dest.SERVICE) }) {
                    InfoRow("Nächste Wartung", maintenanceShort(s))
                    InfoRow("12V-Batterie", s.battery12V.pct(), battery12VColor(s.battery12V), Icons.Filled.BatteryStd)
                    NavHint("Details")
                }
            }

            // Position
            s.position?.let { p ->
                item {
                    SectionCard("Letzter Standort", Icons.Filled.Place) {
                        Text(String.format(Locale.US, "%.5f, %.5f", p.lat, p.lon), fontWeight = FontWeight.Medium)
                        Text("Stand: ${s.positionAt.dateTime()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = {
                            val uri = Uri.parse("geo:${p.lat},${p.lon}?q=${p.lat},${p.lon}(Opel%20Astra)")
                            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
                        }) { Text("In Karten-App öffnen") }
                    }
                }
            }

            item {
                Text(
                    "Hinweis: Die Daten stammen vom letzten Bericht des Fahrzeugs an den Opel-Server. " +
                        "Ein Aktualisieren lädt den neuesten Stand vom Server, weckt das Auto aber nicht auf.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
fun NavHint(text: String) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
        Text(text, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.primary)
    }
}

fun preconditioningLabel(s: String?) = when (s?.lowercase()) {
    null -> "–"
    "enabled" -> "Aktiv"
    "disabled" -> "Aus"
    "finished" -> "Beendet"
    else -> s
}

fun maintenanceShort(s: VehicleStatus): String = listOfNotNull(
    s.maintenanceDays?.let { "in ${it.toInt()} Tagen" },
    s.maintenanceKm?.let { "${it.km()}" },
).joinToString(" / ").ifBlank { "keine Daten" }

fun battery12VColor(v: Double?) = when {
    v == null -> androidx.compose.ui.graphics.Color.Unspecified
    v >= 70 -> Ok
    v >= 50 -> Warn
    else -> Bad
}
