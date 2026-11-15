package com.astraconnect.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import java.time.LocalDate
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
                SectionCard("Nächste Wartung", Icons.Filled.Build) {
                    Row(Modifier.fillMaxWidth()) {
                        BigStat(s.maintenanceDays?.let { "${it.toInt()} Tage" } ?: "–", "bis zur Wartung", Modifier.weight(1f))
                        BigStat(s.maintenanceKm.km(), "bis zur Wartung", Modifier.weight(1f))
                    }
                    s.maintenanceDays?.let {
                        Spacer(Modifier.height(10.dp))
                        val due = LocalDate.now().plusDays(it.toLong())
                        InfoRow("Fällig ca. am", due.format(DateTimeFormatter.ofPattern("dd.MM.yyyy")))
                    }
                    s.maintenanceKm?.let { left ->
                        s.mileageKm?.let { InfoRow("Fällig bei km-Stand ca.", (it + left).km()) }
                    }
                    val urgent = (s.maintenanceDays ?: Double.MAX_VALUE) < 30 || (s.maintenanceKm ?: Double.MAX_VALUE) < 1000
                    if (urgent) InfoRow("Hinweis", "Wartung bald fällig", Warn)
                    if (s.maintenanceDays == null && s.maintenanceKm == null) {
                        Text(
                            "Die API hat keine Wartungsdaten geliefert.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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
                    InfoRow("Reichweite elektrisch", s.electricRangeKm.km())
                    InfoRow("Reichweite Benzin", s.fuelRangeKm.km())
                }
            }
        }
    }
}
