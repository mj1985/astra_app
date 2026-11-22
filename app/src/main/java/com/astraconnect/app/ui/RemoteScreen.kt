package com.astraconnect.app.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.EvStation
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.ThermostatAuto
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.astraconnect.app.AppViewModel
import com.astraconnect.app.CommandEntry
import com.astraconnect.app.ui.theme.Bad
import com.astraconnect.app.ui.theme.Ok

@Composable
fun RemoteScreen(vm: AppViewModel) {
    if (vm.ui.remoteActivated) RemoteControls(vm) else RemoteSetup(vm)
}

@Composable
private fun RemoteSetup(vm: AppViewModel) {
    val ui = vm.ui
    var sms by rememberSaveable { mutableStateOf("") }
    var pin by rememberSaveable { mutableStateOf("") }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            SectionCard("Fernsteuerung einrichten", Icons.Filled.SettingsRemote) {
                Text(
                    "Für Fernbefehle verlangt Opel eine einmalige Freischaltung dieses Geräts – genau wie in der MyOpel-App:",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text("1. SMS-Code anfordern (geht an die im Opel-Konto hinterlegte Handynummer).")
                Text("2. SMS-Code und deine 4-stellige MyOpel-PIN eingeben.")
                Spacer(Modifier.height(8.dp))
                Text(
                    "Hinweis: Die Aktivierung kann die Freischaltung in der MyOpel-App auf diesem bzw. einem anderen Gerät ersetzen. " +
                        "Opel erlaubt nur wenige neue Sicherheitscodes pro Tag.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            if (ui.remoteBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
            ui.remoteInfo?.let { Text(it, modifier = Modifier.padding(vertical = 4.dp)) }
        }
        item {
            Button(onClick = { vm.requestSms() }, enabled = !ui.remoteBusy, modifier = Modifier.fillMaxWidth()) {
                Text(if (ui.smsRequested) "SMS erneut anfordern" else "1. SMS-Code anfordern")
            }
        }
        item {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = sms, onValueChange = { sms = it.trim() },
                    label = { Text("SMS-Code") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    value = pin, onValueChange = { if (it.length <= 4) pin = it.filter(Char::isDigit) },
                    label = { Text("MyOpel-PIN (4 Ziffern)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
                Button(
                    onClick = { vm.activateRemote(sms, pin) },
                    enabled = !ui.remoteBusy && sms.isNotBlank() && pin.length == 4,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("2. Aktivieren") }
            }
        }
        // Läuft über die Web-API und braucht die SMS-Freischaltung nicht.
        item { NavigationCard(vm, showStatus = true) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RemoteControls(vm: AppViewModel) {
    val ui = vm.ui
    val s = ui.status
    var confirmUnlock by remember { mutableStateOf(false) }
    var confirmReset by remember { mutableStateOf(false) }
    val deviceAuth = rememberDeviceAuth()
    val ctx = LocalContext.current
    // Entriegeln nur nach Fingerabdruck/Geräte-PIN. Ohne Displaysperre bleibt nur der Bestätigungsdialog.
    fun requestUnlock() {
        if (!deviceAuth.available) {
            confirmUnlock = true
            return
        }
        deviceAuth.authenticate(
            title = "Fahrzeug entriegeln?",
            subtitle = "Das Auto wird aus der Ferne aufgeschlossen.",
            onSuccess = { vm.unlock() },
            onError = { Toast.makeText(ctx, "Entriegeln abgebrochen: $it", Toast.LENGTH_LONG).show() },
        )
    }
    val enabled = !ui.remoteBusy
    val (h, m) = vm.chargeStartTime

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            if (ui.remoteBusy || ui.commandRunning) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
        item {
            SectionCard("Türen") {
                s?.let { InfoRow("Aktuell", it.lockLabel) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionTile("Verriegeln", Icons.Filled.Lock, enabled) { vm.lock() }
                    ActionTile("Entriegeln", Icons.Filled.LockOpen, enabled) { requestUnlock() }
                }
            }
        }
        item {
            SectionCard("Klima") {
                s?.let { InfoRow("Vorklimatisierung", preconditioningLabel(it.preconditioning)) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionTile("Starten", Icons.Filled.AcUnit, enabled) { vm.preconditioning(true) }
                    ActionTile("Stoppen", Icons.Filled.ThermostatAuto, enabled) { vm.preconditioning(false) }
                }
            }
        }
        item {
            SectionCard("Laden") {
                s?.charging?.let { InfoRow("Status", it.statusLabel) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionTile("Sofort laden", Icons.Filled.EvStation, enabled) { vm.charging(true) }
                    ActionTile("Pausieren", Icons.Filled.PauseCircle, enabled) { vm.charging(false) }
                }
                Text(
                    "„Pausieren“ verschiebt das Laden auf die programmierte Startzeit ${"%02d:%02d".format(h, m)} Uhr.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        item {
            SectionCard("Sonstiges") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ActionTile("Hupe", Icons.Filled.Campaign, enabled) { vm.horn() }
                    ActionTile("Licht", Icons.Filled.Lightbulb, enabled) { vm.lights() }
                    ActionTile("Status holen", Icons.Filled.Sync, enabled) { vm.wakeUp() }
                }
                Text(
                    "„Status holen“ weckt das Auto, damit es aktuelle Daten an Opel sendet (max. 6× in 20 Min.).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        item { NavigationCard(vm, showStatus = false) }
        if (ui.commands.isNotEmpty()) {
            item { Text("Letzte Befehle", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
            items(ui.commands, key = { it.id }) { CommandRow(it) }
        }
        item {
            TextButton(onClick = { confirmReset = true }) { Text("Fernsteuerung zurücksetzen") }
        }
    }

    if (confirmUnlock) {
        AlertDialog(
            onDismissRequest = { confirmUnlock = false },
            title = { Text("Fahrzeug entriegeln?") },
            text = {
                Text(
                    "Das Auto wird aus der Ferne aufgeschlossen.\n\nHinweis: Auf diesem Handy ist keine Displaysperre " +
                        "eingerichtet. Mit Displaysperre fragt die App vor dem Entriegeln Fingerabdruck oder Geräte-PIN ab."
                )
            },
            confirmButton = { TextButton(onClick = { confirmUnlock = false; vm.unlock() }) { Text("Entriegeln") } },
            dismissButton = { TextButton(onClick = { confirmUnlock = false }) { Text("Abbrechen") } },
        )
    }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Fernsteuerung zurücksetzen?") },
            text = { Text("Die Freischaltung wird auf diesem Gerät gelöscht. Danach ist eine neue SMS-Aktivierung nötig.") },
            confirmButton = { TextButton(onClick = { confirmReset = false; vm.resetRemote() }) { Text("Zurücksetzen") } },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Abbrechen") } },
        )
    }
}

@Composable
private fun ActionTile(label: String, icon: ImageVector, enabled: Boolean, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.width(100.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = 14.dp, horizontal = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
            Spacer(Modifier.height(6.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun CommandRow(c: CommandEntry) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        val (icon, color) = when {
            c.success -> Icons.Filled.CheckCircle to Ok
            c.final -> Icons.Filled.Error to Bad
            else -> Icons.Filled.HourglassTop to MaterialTheme.colorScheme.primary
        }
        Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(c.name, fontWeight = FontWeight.Medium)
            Text(c.status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(c.sentAt.time(), style = MaterialTheme.typography.bodySmall)
    }
}
