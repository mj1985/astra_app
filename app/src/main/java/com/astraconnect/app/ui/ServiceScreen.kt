package com.astraconnect.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.astraconnect.app.AppViewModel
import com.astraconnect.app.ui.theme.Bad
import com.astraconnect.app.ui.theme.Ok
import com.astraconnect.app.ui.theme.Warn
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServiceScreen(vm: AppViewModel) {
    val s = vm.ui.status
    PullToRefreshBox(isRefreshing = vm.ui.loading, onRefresh = { vm.refresh() }, modifier = Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (s == null) {
                item { Text("Keine Daten geladen.") }
                return@LazyColumn
            }
            item {
                val m = vm.ui.maintenance
                SectionCard("Nächste Wartung", Icons.Filled.Build) {
                    fun days(v: Double) = if (v < 0) "${(-v).toInt()} Tage überfällig" else "${v.toInt()} Tage"
                    Row(Modifier.fillMaxWidth()) {
                        BigStat(m.daysLeft?.let { days(it) } ?: "–", "bis zur Wartung", Modifier.weight(1f))
                        BigStat(m.kmLeft?.let { if (it < 0) "${(-it).km()} zu viel" else it.km() } ?: "–", "bis zur Wartung", Modifier.weight(1f))
                    }
                    val fmt = DateTimeFormatter.ofPattern("dd.MM.yyyy")
                    m.dueDate?.let {
                        Spacer(Modifier.height(10.dp))
                        InfoRow(if (m.manual) "Fällig am" else "Fällig ca. am", it.format(fmt))
                    }
                    m.dueMileage?.let { InfoRow(if (m.manual) "Fällig bei km-Stand" else "Fällig bei km-Stand ca.", it.km()) }
                    val overdue = (m.daysLeft != null && m.daysLeft < 0) || (m.kmLeft != null && m.kmLeft < 0)
                    if (overdue) InfoRow("Hinweis", "Wartung überfällig", Bad)
                    else if (m.due) InfoRow("Hinweis", "Wartung bald fällig", Warn)
                    Text(
                        when {
                            m.manual -> "Eigener Wartungstermin (in den Einstellungen unter „Fahrzeug“). Hat Vorrang vor dem Wert aus der API."
                            m.hasData -> "Wert aus der Connected-Car-API. Bei manchen Modellen ist er ungenau – dann in den Einstellungen unter „Fahrzeug“ den Termin aus der MyOpel-App eintragen."
                            else -> "Die API hat keine Wartungsdaten geliefert. In den Einstellungen unter „Fahrzeug“ lässt sich der Termin aus der MyOpel-App eintragen."
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                }
            }
            item {
                SectionCard("12V-Batterie", Icons.Filled.BatteryStd) {
                    LevelBar(
                        "Ladezustand", s.battery12V,
                        "zuletzt gemeldet: ${(s.battery12VUpdatedAt ?: s.updatedAt).dateTime()}",
                        battery12VColor(s.battery12V).takeIf { s.battery12V != null } ?: MaterialTheme.colorScheme.primary,
                        Icons.Filled.BatteryStd,
                    )
                    s.battery12VCurrent?.let { InfoRow("Strom", it.num(1, "A")) }
                    InfoRow(
                        "Bewertung",
                        when {
                            s.battery12V == null -> "keine Daten"
                            s.battery12V >= 70 -> "gut"
                            s.battery12V >= 50 -> "mittel – Auto öfter bewegen"
                            else -> "schwach – Batterie prüfen lassen"
                        },
                        when {
                            s.battery12V == null -> MaterialTheme.colorScheme.onSurfaceVariant
                            s.battery12V >= 70 -> Ok
                            s.battery12V >= 50 -> Warn
                            else -> Bad
                        },
                    )
                    Text(
                        "Opel übermittelt den Wert als Prozent (im API-Feld „battery.voltage“).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                SectionCard("Kilometer", Icons.Filled.Speed) {
                    InfoRow("Kilometerstand", s.mileageKm.km())
                    if (vm.ui.powertrain.hasBattery) InfoRow("Reichweite elektrisch", s.electricRangeKm.km())
                    if (vm.ui.powertrain.hasFuel) InfoRow("Reichweite Kraftstoff", s.fuelRangeKm.km())
                }
            }
        }
    }
}
