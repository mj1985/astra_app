package com.astraconnect.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Euro
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.ViewWeek
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.astraconnect.app.AppViewModel
import com.astraconnect.app.data.Backup
import com.astraconnect.app.data.NotifyPrefs
import com.astraconnect.app.data.Prices
import com.astraconnect.app.ui.theme.Warn
import com.astraconnect.app.widget.CarImage
import com.astraconnect.app.widget.Widgets
import java.time.Instant
import java.util.Locale

internal fun parseNum(s: String) = s.replace(',', '.').trim().toDoubleOrNull()

@Composable
internal fun NumberField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    OutlinedTextField(
        value = value, onValueChange = onChange, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = modifier,
    )
}

@Composable
fun CostSettings(vm: AppViewModel) {
    val p = vm.ui.prices
    var fuel by remember(p) { mutableStateOf(String.format(Locale.GERMANY, "%.3f", p.fuelPerLiter)) }
    var power by remember(p) { mutableStateOf(String.format(Locale.GERMANY, "%.3f", p.powerPerKwh)) }
    var loss by remember(p) { mutableStateOf(String.format(Locale.GERMANY, "%.0f", p.chargeLossPct)) }
    var tank by remember(vm.ui.tankLiters) { mutableStateOf(String.format(Locale.GERMANY, "%.0f", vm.ui.tankLiters)) }
    SectionCard("Kosten & Tank", Icons.Filled.Euro) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("Benzin €/l", fuel, { fuel = it }, Modifier.weight(1f))
            NumberField("Strom €/kWh", power, { power = it }, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField("Ladeverluste %", loss, { loss = it }, Modifier.weight(1f))
            NumberField("Tankinhalt l", tank, { tank = it }, Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Button(onClick = {
            val f = parseNum(fuel)
            val pw = parseNum(power)
            val l = parseNum(loss)
            if (f != null && pw != null && l != null && l in 0.0..50.0) vm.setPrices(Prices(f, pw, l))
            parseNum(tank)?.let(vm::setTankLiters)
        }) { Text("Speichern") }
        Text(
            "Standardpreise: Benzin gilt bis zum ersten unter „Tanken“ eingetragenen Preis, Strom immer dann, wenn weder beim Ladevorgang noch beim Ort ein Preis hinterlegt ist. " +
                "Ladeverluste: Aus der Steckdose kommt mehr Strom, als im Akku landet (typisch 8–15 %). " +
                "Tankinhalt Astra Plug-in-Hybrid: 40 l – Grundlage für das Tankbuch.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Toggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, Modifier.weight(1f))
        Switch(checked = value, onCheckedChange = onChange)
    }
}

@Composable
fun NotificationSettings(vm: AppViewModel) {
    val n = vm.ui.notify
    val ctx = LocalContext.current
    val granted = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    var threshold by remember(n.battery12VThreshold) { mutableStateOf(String.format(Locale.GERMANY, "%.0f", n.battery12VThreshold)) }
    fun set(v: NotifyPrefs) = vm.setNotify(v)

    SectionCard("Benachrichtigungen", Icons.Filled.Notifications) {
        if (!granted) {
            Text("Benachrichtigungen sind für die App nicht erlaubt.", color = Warn)
            OutlinedButton(onClick = {
                ctx.startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                )
            }) { Text("Systemeinstellungen öffnen") }
            Spacer(Modifier.height(8.dp))
        }
        Toggle("Laden abgeschlossen", n.chargeDone) { set(n.copy(chargeDone = it)) }
        Toggle("Laden vorzeitig beendet", n.chargeAborted) { set(n.copy(chargeAborted = it)) }
        Toggle("Auto nachts offen (21–6 Uhr)", n.openAtNight) { set(n.copy(openAtNight = it)) }
        Toggle("12-V-Batterie schwach", n.battery12V) { set(n.copy(battery12V = it)) }
        Toggle("Wartung fällig (≤ 30 Tage / 1.000 km)", n.maintenance) { set(n.copy(maintenance = it)) }
        Toggle("Anmeldung abgelaufen", n.loginExpired) { set(n.copy(loginExpired = it)) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            NumberField("12-V-Grenze %", threshold, { threshold = it }, Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Button(onClick = { parseNum(threshold)?.takeIf { it in 10.0..95.0 }?.let { set(n.copy(battery12VThreshold = it)) } }) { Text("OK") }
        }
        Text(
            "Geprüft wird bei jedem Statusabruf – im Hintergrund etwa alle 15 Minuten (Android kann das verzögern).",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun BackupSettings(vm: AppViewModel) {
    val ui = vm.ui
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let(vm::exportBackup)
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::importBackup) }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(vm::setBackupFolder) }

    SectionCard("Datensicherung", Icons.Filled.Backup) {
        Text(
            "Sichert Fahrtenbuch (mit Kategorien, Notizen und Importen), Orte & Regeln, Ladestand-Messpunkte und Preise. " +
                "Zugangsdaten werden nicht gesichert.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { export.launch(Backup.fileName()) }) { Text("Exportieren") }
            OutlinedButton(onClick = { import.launch(arrayOf("application/json", "*/*")) }) { Text("Einspielen") }
        }
        HorizontalDivider(Modifier.padding(vertical = 10.dp))
        Text("Automatisch täglich sichern", fontWeight = FontWeight.SemiBold)
        if (ui.backupFolder == null) {
            Text(
                "Wähle einen Ordner, z. B. in Google Drive oder Nextcloud – dann übersteht das Fahrtenbuch auch einen Handywechsel.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = { folder.launch(null) }) { Text("Ordner wählen") }
        } else {
            InfoRow("Ordner", Uri.parse(ui.backupFolder).lastPathSegment?.substringAfterLast(':')?.ifBlank { null } ?: "gewählt")
            InfoRow("Letzte Sicherung", if (ui.lastBackup > 0) Instant.ofEpochMilli(ui.lastBackup).dateTime() else "noch keine")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.backupNow() }) { Text("Jetzt sichern") }
                OutlinedButton(onClick = { folder.launch(null) }) { Text("Ändern") }
                TextButton(onClick = { vm.setBackupFolder(null) }) { Text("Aus") }
            }
        }
        ui.backupInfo?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp)) }
    }
}

@Composable
fun WidgetSettings() {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("app", android.content.Context.MODE_PRIVATE) }
    var mirror by remember { mutableStateOf(prefs.getBoolean(CarImage.K_MIRROR, false)) }
    SectionCard("Widgets", Icons.Filled.Widgets) {
        Text(
            "Vier Widgets auf dem Startbildschirm: Laden, Steuerung (mit Fahrzeugbild), Reichweite und Letzte Fahrt. " +
                "Die Steuer-Tasten brauchen die eingerichtete Fernsteuerung.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text("Fahrzeugbild spiegeln")
                Text(
                    "Die Front soll zu den Tasten zeigen, das Heck wird am rechten Rand angeschnitten.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = mirror, onCheckedChange = {
                mirror = it
                prefs.edit().putBoolean(CarImage.K_MIRROR, it).apply()
                Widgets.updateAll(ctx)
            })
        }
    }
}

@Composable
fun BottomBarSettings(vm: AppViewModel) {
    val chosen = Dest.bottom(vm.ui.bottomBar)
    SectionCard("Leiste unten", Icons.Filled.ViewWeek) {
        Text(
            "„Start“ ist immer links. Dazu bis zu ${Dest.MAX_BOTTOM} Einträge – in der Reihenfolge, in der du sie anhakst. " +
                "Alles andere bleibt über das Menü ☰ erreichbar.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        Dest.entries.filter { it != Dest.OVERVIEW }.forEach { d ->
            val on = d in chosen
            val pos = chosen.indexOf(d)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Checkbox(
                    checked = on,
                    enabled = on || chosen.size < Dest.MAX_BOTTOM,
                    onCheckedChange = { v -> vm.setBottomBar((if (v) chosen + d else chosen - d).map { it.name }) },
                )
                Icon(d.icon, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(12.dp))
                Text(d.title, Modifier.weight(1f))
                if (on) Text("${pos + 1}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
        }
        TextButton(onClick = { vm.setBottomBar(null) }) { Text("Standard wiederherstellen") }
    }
}
