package com.astraconnect.app.ui

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astraconnect.app.AppViewModel
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.BatteryChargingFull
import com.astraconnect.app.data.BatteryUse
import com.astraconnect.app.data.LatLon
import com.astraconnect.app.data.Trip
import com.astraconnect.app.data.TripCategory
import com.astraconnect.app.data.TripSource
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.LocalGasStation
import com.astraconnect.app.ui.theme.Ok
import com.astraconnect.app.ui.theme.Warn
import java.time.ZoneId
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TripLogScreen(vm: AppViewModel) {
    val ui = vm.ui
    val ctx = LocalContext.current
    val years = remember(ui.trips) {
        ui.trips.mapNotNull { it.startedAt?.atZone(ZoneId.systemDefault())?.year }.distinct().sortedDescending()
    }
    var year by rememberSaveable { mutableStateOf<Int?>(null) }
    var filter by rememberSaveable { mutableStateOf<TripCategory?>(null) }
    var editing by remember { mutableStateOf<Trip?>(null) }

    // myOpel-Exporte haben die Endung .myop oder gar keine – daher alle Dateitypen zulassen.
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(vm::importMyop)
    }

    val shown = ui.trips.filter { t ->
        (year == null || t.startedAt?.atZone(ZoneId.systemDefault())?.year == year) &&
            (filter == null || t.category == filter)
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.syncTrips() }, enabled = !ui.tripsSyncing) {
                    Icon(Icons.Filled.Sync, null); Spacer(Modifier.width(6.dp)); Text("Fahrten abrufen")
                }
                OutlinedButton(onClick = {
                    val uri = vm.exportCsv(shown)
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/csv"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        putExtra(Intent.EXTRA_SUBJECT, "Fahrtenbuch Opel Astra")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    ctx.startActivity(Intent.createChooser(send, "Fahrtenbuch exportieren"))
                }, enabled = shown.isNotEmpty()) {
                    Icon(Icons.Filled.Share, null); Spacer(Modifier.width(6.dp)); Text("CSV")
                }
                OutlinedButton(onClick = { importLauncher.launch(arrayOf("*/*")) }, enabled = !ui.tripsSyncing) {
                    Icon(Icons.Filled.FileOpen, null); Spacer(Modifier.width(6.dp)); Text("myOpel-Import")
                }
            }
            if (ui.tripsSyncing) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
            ui.tripsInfo?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = year == null, onClick = { year = null }, label = { Text("Alle Jahre") })
                    years.forEach { y -> FilterChip(selected = year == y, onClick = { year = y }, label = { Text("$y") }) }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = filter == null, onClick = { filter = null }, label = { Text("Alle") })
                    TripCategory.entries.forEach { c ->
                        FilterChip(selected = filter == c, onClick = { filter = if (filter == c) null else c }, label = { Text(c.label) })
                    }
                }
            }
        }

        item {
            SectionCard("Zusammenfassung") {
                Row(Modifier.fillMaxWidth()) {
                    BigStat("${shown.size}", "Fahrten", Modifier.weight(1f))
                    BigStat(shown.sumOf { it.distanceKm ?: 0.0 }.km(), "gesamt", Modifier.weight(1f))
                }
                val withFuel = shown.filter { it.fuelLiters != null }
                if (withFuel.any { (it.fuelLiters ?: 0.0) > 0 }) {
                    val liters = withFuel.sumOf { it.fuelLiters ?: 0.0 }
                    val km = withFuel.sumOf { it.distanceKm ?: 0.0 }
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth()) {
                        BigStat(liters.num(2, "l"), "Kraftstoff gesamt", Modifier.weight(1f))
                        BigStat(if (km > 0) (liters / km * 100).num(1, "l") else "–", "Ø pro 100 km", Modifier.weight(1f))
                    }
                }
                val withKwh = shown.filter { ui.battery[it.key]?.kwh != null }
                if (withKwh.isNotEmpty()) {
                    val kwh = withKwh.sumOf { ui.battery[it.key]?.kwh ?: 0.0 }
                    val km = withKwh.sumOf { it.distanceKm ?: 0.0 }
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth()) {
                        BigStat(kwh.num(1, "kWh"), "Akku-Verbrauch (berechnet)", Modifier.weight(1f))
                        BigStat(if (km > 0) (kwh / km * 100).num(1, "kWh") else "–", "pro 100 km", Modifier.weight(1f))
                    }
                    if (withKwh.size < shown.size) {
                        Text(
                            "Berechnet für ${withKwh.size} von ${shown.size} Fahrten.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    TripCategory.entries.forEach { c ->
                        val km = shown.filter { it.category == c }.sumOf { it.distanceKm ?: 0.0 }
                        if (km > 0) AssistChip(onClick = { filter = c }, label = { Text("${c.label}: ${km.km()}") })
                    }
                }
            }
        }

        if (shown.isEmpty()) {
            item {
                Text(
                    if (ui.trips.isEmpty()) "Noch keine Fahrten gespeichert. Tippe auf „Fahrten abrufen“, um die Fahrten aus My Trip Report zu laden."
                    else "Keine Fahrten für diesen Filter.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
        }

        items(shown, key = { it.key }) { t -> TripItem(t, ui.battery[t.key], vm) { editing = t } }

        if (ui.trips.isNotEmpty()) {
            item {
                TextButton(onClick = { vm.syncTrips(full = true) }, enabled = !ui.tripsSyncing) {
                    Text("Alle Fahrten der letzten 12 Monate erneut abrufen")
                }
            }
        }
    }

    editing?.let { t ->
        TripSheet(t, ui.battery[t.key], ui.capacityKwh, vm, onDismiss = { editing = null }, onSave = { c, n -> vm.updateTrip(t.key, c, n); editing = null })
    }
}

@Composable
private fun TripItem(t: Trip, b: BatteryUse?, vm: AppViewModel, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t.startedAt.dateLong(), fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(t.distanceKm.km(1), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            }
            Text(
                "${t.startedAt.time()} – ${t.stoppedAt.time()} · ${t.duration.pretty()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "${vm.placeName(t.startPos) ?: place(t.startAddress, t.startPos)}  →  ${vm.placeName(t.endPos) ?: place(t.endAddress, t.endPos)}",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
            if (t.startMileage != null || t.endMileage != null) {
                Text(
                    "km-Stand ${t.startMileage.km()} → ${t.endMileage.km()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(6.dp))
            if (t.fuelLiters != null && t.fuelLiters > 0.005) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.LocalGasStation, null, tint = Warn, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        t.fuelLiters.num(2, "l") + (t.avgFuel?.let { " · ${it.num(1, "l/100 km")}" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.height(6.dp))
            }
            vm.tripCost(t)?.takeIf { it >= 0.01 }?.let { c ->
                Text(
                    "Kosten ≈ " + String.format(Locale.GERMANY, "%.2f €", c),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
            }
            if (b?.kwh != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.BatteryChargingFull, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        "${b.socStart.pct()} → ${b.socEnd.pct()} · ${(if (b.shared) "≈ " else "")}${b.kwh.num(2, "kWh")}" +
                            (b.per100km(t.distanceKm)?.let { " · ${it.num(1, "kWh/100 km")}" } ?: ""),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Spacer(Modifier.height(6.dp))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusChip(t.category.label + if (t.autoCategory) " · auto" else "", if (t.category == TripCategory.UNSET) Warn else Ok)
                if (t.isMerged) {
                    Spacer(Modifier.width(8.dp))
                    StatusChip("${t.partKeys.size} Teile", MaterialTheme.colorScheme.secondary)
                }
                if (t.source == TripSource.MYOPEL) {
                    Spacer(Modifier.width(8.dp))
                    StatusChip("myOpel", MaterialTheme.colorScheme.tertiary)
                }
                if (t.note.isNotBlank()) {
                    Spacer(Modifier.width(8.dp))
                    Text(t.note, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

private fun place(addr: String?, p: LatLon?) =
    addr ?: p?.let { String.format(Locale.US, "%.4f, %.4f", it.lat, it.lon) } ?: "unbekannt"

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun TripSheet(t: Trip, b: BatteryUse?, capacityKwh: Double, vm: AppViewModel, onDismiss: () -> Unit, onSave: (TripCategory, String) -> Unit) {
    var category by remember { mutableStateOf(t.category) }
    var note by remember { mutableStateOf(t.note) }
    var showRaw by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        LazyColumn(Modifier.navigationBarsPadding().padding(horizontal = 20.dp)) {
            item {
                Text("Fahrt vom ${t.startedAt.dateLong()}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(8.dp))
                TripMiniMap(t)
                Spacer(Modifier.height(8.dp))
                InfoRow("Abfahrt", t.startedAt.dateTime())
                InfoRow("Ankunft", t.stoppedAt.dateTime())
                InfoRow("Dauer", t.duration.pretty())
                InfoRow("Strecke", t.distanceKm.km(1))
                InfoRow("km-Stand Start", t.startMileage.km())
                InfoRow("km-Stand Ende", t.endMileage.km())
                InfoRow("Start", listOfNotNull(vm.placeName(t.startPos), place(t.startAddress, t.startPos)).joinToString(" – "))
                InfoRow("Ziel", listOfNotNull(vm.placeName(t.endPos), place(t.endAddress, t.endPos)).joinToString(" – "))
                InfoRow("Quelle", t.source.label)
                if (t.isMerged) InfoRow("Zusammengeführt", "aus ${t.partKeys.size} Fahrten")
                vm.tripCost(t)?.let { InfoRow("Energiekosten (geschätzt)", String.format(Locale.GERMANY, "%.2f €", it)) }
                if (t.fuelLiters != null || t.avgFuel != null) {
                    HorizontalDivider(Modifier.padding(vertical = 12.dp))
                    Text("Kraftstoff", fontWeight = FontWeight.SemiBold)
                    InfoRow("Verbrauch gesamt", t.fuelLiters.num(2, "l"))
                    t.avgFuel?.let { InfoRow("Ø pro 100 km", it.num(1, "l")) }
                }
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                Text("Akku-Verbrauch (berechnet)", fontWeight = FontWeight.SemiBold)
                if (b == null) {
                    t.socEnd?.let { InfoRow("Ladestand bei Ankunft (myOpel)", it.pct()) }
                        ?: Text("Keine Daten.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    InfoRow("Ladestand Start", b.socStart.pct())
                    InfoRow("Ladestand Ende", b.socEnd.pct())
                    b.kwh?.let { kwh ->
                        InfoRow("Verbrauch", (if (b.shared) "≈ " else "") + kwh.num(2, "kWh"))
                        b.per100km(t.distanceKm)?.let { InfoRow("Ø pro 100 km", it.num(1, "kWh")) }
                    }
                    val hint = b.note ?: if (b.shared)
                        "Mehrere Fahrten lagen zwischen denselben Messpunkten – der Verbrauch wurde nach Strecke aufgeteilt."
                    else null
                    Text(
                        listOfNotNull(hint, "Grundlage: ${capacityKwh.num(1, "kWh")} nutzbare Kapazität.").joinToString(" "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (t.electricKwh != null || t.avgElectric != null) {
                    InfoRow("Stromverbrauch", t.electricKwh.num(2, "kWh"))
                    t.avgElectric?.let { InfoRow("Ø Strom", it.num(1, "kWh/100 km")) }
                }
                HorizontalDivider(Modifier.padding(vertical = 12.dp))
                Text("Kategorie", fontWeight = FontWeight.SemiBold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TripCategory.entries.forEach { c ->
                        FilterChip(selected = category == c, onClick = { category = c }, label = { Text(c.label) })
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = note, onValueChange = { note = it },
                    label = { Text("Zweck / Geschäftspartner / Notiz") },
                    modifier = Modifier.fillMaxWidth(), minLines = 2,
                )
                if (t.autoCategory && category == t.category) {
                    Text(
                        "Automatisch über eine Ortsregel zugeordnet. Speichern macht die Kategorie zur festen Angabe.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(12.dp))
                // Zusammenführen (Umparken, kurzer Stopp) bzw. wieder trennen
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { if (vm.mergeTripWithPrevious(t.key)) onDismiss() }) {
                        Text("Mit vorheriger Fahrt zusammenführen")
                    }
                    if (t.isMerged) {
                        OutlinedButton(onClick = { vm.splitTrip(t.key); onDismiss() }) { Text("Wieder trennen") }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Row {
                    TextButton(onClick = { showRaw = !showRaw }) { Text(if (showRaw) "Rohdaten ausblenden" else "Rohdaten anzeigen") }
                    Spacer(Modifier.weight(1f))
                    Button(onClick = { onSave(category, note.trim()) }) { Text("Speichern") }
                }
                if (showRaw) {
                    Box(Modifier.horizontalScroll(rememberScrollState())) {
                        SelectionContainer {
                            val text = listOfNotNull(
                                t.raw.takeIf { it.length() > 0 }?.let { "// Opel-Server\n" + it.toString(2) },
                                t.myop?.let { "// myOpel-Export\n" + it.toString(2) },
                            ).joinToString("\n\n")
                            Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.sp)
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
