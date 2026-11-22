package com.astraconnect.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.astraconnect.app.AppViewModel
import java.util.Locale

@Composable
fun SettingsScreen(vm: AppViewModel) {
    val ui = vm.ui
    var confirmClear by remember { mutableStateOf(false) }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { VehicleSettings(vm) }
        item {
            SectionCard("Messpunkte", Icons.Filled.BatteryChargingFull) {
                Text(
                    if (ui.powertrain.hasBattery)
                        "Opel liefert den Stromverbrauch pro Fahrt nicht. Die App berechnet ihn aus dem Ladestand " +
                            "vor und nach der Fahrt × nutzbarer Akkukapazität. Dafür speichert sie regelmäßig Akku- und Tankstand."
                    else "Die App speichert regelmäßig den Tankstand – daraus erkennt sie Tankvorgänge.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text((if (ui.powertrain.hasBattery) "Ladestand" else "Tankstand") + " im Hintergrund aufzeichnen (alle 15 Min.)", Modifier.weight(1f))
                    Switch(checked = ui.backgroundEnabled, onCheckedChange = vm::setBackground)
                }
                Text(
                    "Ohne Hintergrund-Aufzeichnung wird nur bei geöffneter App gemessen – dann teilen sich oft mehrere Fahrten einen Messpunkt.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                InfoRow("Gespeicherte Messpunkte", "${ui.snapshotCount}")
                InfoRow("Letzter Messpunkt", ui.lastSnapshot.dateTime())
                if (!confirmClear) {
                    OutlinedButton(onClick = { confirmClear = true }) { Text("Messpunkte löschen") }
                } else {
                    Text("Alle Messpunkte löschen? Bisher berechnete Verbräuche gehen verloren.")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(onClick = { vm.clearSnapshots(); confirmClear = false }) { Text("Löschen") }
                        OutlinedButton(onClick = { confirmClear = false }) { Text("Abbrechen") }
                    }
                }
            }
        }
        item { BottomBarSettings(vm) }
        item { WidgetSettings() }
        item { CostSettings(vm) }
        item { NotificationSettings(vm) }
        item { BackupSettings(vm) }
        item {
            SectionCard("Fernsteuerung", Icons.Filled.SettingsRemote) {
                InfoRow("Status", if (ui.remoteActivated) "eingerichtet" else "nicht eingerichtet")
                if (ui.remoteActivated) {
                    OutlinedButton(onClick = { vm.resetRemote() }) { Text("Freischaltung löschen") }
                }
            }
        }
    }
}
