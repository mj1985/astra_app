package com.astraconnect.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.EvStation
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.astraconnect.app.AppViewModel
import com.astraconnect.app.data.ChargeEntry
import com.astraconnect.app.data.ChargeRow
import com.astraconnect.app.data.CostBook
import com.astraconnect.app.data.CostSource
import com.astraconnect.app.data.FuelEntry
import com.astraconnect.app.data.FuelRow
import com.astraconnect.app.data.Place
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

private fun Double.eur() = String.format(Locale.GERMANY, "%,.2f €", this)
private fun Double.fmt(d: Int) = String.format(Locale.GERMANY, "%.${d}f", this)
private val inputFormat = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
private fun Instant.toInput() = inputFormat.format(atZone(ZoneId.systemDefault()))
private fun parseInput(s: String): Instant? =
    runCatching { LocalDateTime.parse(s.trim(), inputFormat).atZone(ZoneId.systemDefault()).toInstant() }.getOrNull()

/** Zeitraum-Filter: 0 = dieser Monat, 1 = dieses Jahr, 2 = alles. */
@Composable
private fun RangeChips(range: Int, onChange: (Int) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("Dieser Monat", "Dieses Jahr", "Alles").forEachIndexed { i, t ->
            FilterChip(selected = range == i, onClick = { onChange(i) }, label = { Text(t) })
        }
    }
}

private fun rangeStart(range: Int): Instant? {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    return when (range) {
        0 -> today.withDayOfMonth(1).atStartOfDay(zone).toInstant()
        1 -> today.withDayOfYear(1).atStartOfDay(zone).toInstant()
        else -> null
    }
}

// ================================================================================== Laden

@Composable
fun ChargingScreen(vm: AppViewModel) {
    val ui = vm.ui
    var range by rememberSaveable { mutableStateOf(1) }
    var edit by remember { mutableStateOf<ChargeRow?>(null) }
    var addNew by remember { mutableStateOf(false) }
    val from = rangeStart(range)
    val rows = vm.chargeRows.filter { from == null || !it.start.isBefore(from) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { RangeChips(range) { range = it } }
        item {
            SectionCard("Zusammenfassung", Icons.Filled.EvStation) {
                val done = rows.filter { !it.ongoing }
                val kwh = done.sumOf { it.kwhGrid }
                val cost = done.sumOf { it.cost }
                Row(Modifier.fillMaxWidth()) {
                    BigStat("${done.size}", "Ladevorgänge", Modifier.weight(1f))
                    BigStat(kwh.num(0, "kWh"), "aus dem Netz", Modifier.weight(1f))
                    BigStat(cost.eur(), "Kosten", Modifier.weight(1f))
                }
                if (kwh > 0.1) InfoRow("Ø Preis", "${(cost / kwh).fmt(3)} €/kWh")
                val byPlace = done.groupBy { it.place?.name ?: "Unbekannter Ort" }
                    .map { (n, l) -> Triple(n, l.sumOf { it.kwhGrid }, l.sumOf { it.cost }) }
                    .sortedByDescending { it.third }
                if (byPlace.size > 1 || byPlace.firstOrNull()?.first != "Unbekannter Ort") {
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                    byPlace.forEach { (n, k, c) -> InfoRow(n, "${k.num(0, "kWh")} · ${c.eur()}", icon = Icons.Filled.Place) }
                }
            }
        }
        item {
            SectionCard("Ladestand der letzten 7 Tage", Icons.Filled.BatteryChargingFull) {
                val since = Instant.now().minus(7, ChronoUnit.DAYS)
                TimeLineChart(
                    ui.snapshots.filter { it.at.isAfter(since) && it.soc != null }.map { it.at to it.soc!! },
                    ChartColors.electric, "%",
                )
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Ladevorgänge", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { addNew = true }) {
                    Icon(Icons.Filled.Add, null); Spacer(Modifier.padding(start = 4.dp)); Text("Nachtragen")
                }
            }
        }
        if (rows.isEmpty()) item {
            Text(
                "Keine Ladevorgänge im Zeitraum. Erkannt wird am steigenden Ladestand (Hintergrund-Aufzeichnung alle 15 Min.). " +
                    "Fehlende Ladungen, z. B. an einer öffentlichen Säule, kannst du nachtragen.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(rows, key = { it.entry?.id ?: it.start.toString() }) { r -> ChargeRowCard(r) { edit = r } }
        item {
            Text(
                "Kosten: ✎ selbst eingetragen · ⌂ Strompreis des Orts · ≈ Standardpreis aus den Einstellungen. " +
                    "Erkannte Ladungen: Energie = Ladestand-Anstieg × Akkukapazität + ${ui.prices.chargeLossPct.num(0, "%")} Ladeverluste. " +
                    "Der Preis je kWh der letzten Ladung vor einer Fahrt geht in deren Stromkosten ein.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    edit?.let { r -> ChargeDialog(vm, r, onDismiss = { edit = null }) }
    if (addNew) ChargeDialog(vm, null, onDismiss = { addNew = false })
}

@Composable
private fun ChargeRowCard(r: ChargeRow, onClick: () -> Unit) {
    val mark = when (r.costSource) { CostSource.ENTERED -> "✎ "; CostSource.PLACE -> "⌂ "; CostSource.DEFAULT -> "≈ " }
    SectionCard(
        r.start.dateTime() + if (r.ongoing) " (läuft)" else if (r.manual) " (nachgetragen)" else "",
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        InfoRow(r.place?.name ?: "Unbekannter Ort", mark + r.cost.eur(), icon = Icons.Filled.Place)
        val soc = if (r.socFrom != null && r.socTo != null) "${r.socFrom.pct()} → ${r.socTo.pct()} · " else ""
        val dur = r.end?.let { " · " + java.time.Duration.between(r.start, it).pretty() }.orEmpty()
        Text(
            soc + "${r.kwhGrid.num(1, "kWh")}" + (r.pricePerGridKwh?.let { " · ${it.fmt(3)} €/kWh" } ?: "") + dur,
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        r.entry?.note?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
private fun PlacePicker(places: List<Place>, selected: String?, onSelect: (String?) -> Unit, autoLabel: String) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected = selected == null, onClick = { onSelect(null) }, label = { Text(autoLabel) })
        places.forEach { p -> FilterChip(selected = selected == p.id, onClick = { onSelect(p.id) }, label = { Text(p.name) }) }
    }
}

/** Kosten zu einem erkannten Ladevorgang eintragen (row != null, nicht manuell) oder Ladevorgang nachtragen. */
@Composable
private fun ChargeDialog(vm: AppViewModel, row: ChargeRow?, onDismiss: () -> Unit) {
    val e = row?.entry
    val manual = row == null || row.manual
    var at by remember { mutableStateOf((row?.start ?: Instant.now()).toInput()) }
    var kwh by remember { mutableStateOf(e?.kwh?.fmt(2) ?: "") }
    var cost by remember { mutableStateOf(e?.cost?.fmt(2) ?: "") }
    var price by remember { mutableStateOf(e?.pricePerKwh?.fmt(3) ?: "") }
    var placeId by remember { mutableStateOf(e?.placeId) }
    var note by remember { mutableStateOf(e?.note ?: "") }

    val time = if (manual) parseInput(at) else row!!.start
    val valid = time != null && (!manual || parseNum(kwh) != null)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (row == null) "Ladevorgang nachtragen" else "Ladekosten") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (manual) {
                    OutlinedTextField(at, { at = it }, label = { Text("Datum, Uhrzeit (TT.MM.JJJJ HH:MM)") }, singleLine = true, isError = time == null)
                    NumberField("Geladen kWh (laut Säule/Zähler)", kwh, { kwh = it }, Modifier.fillMaxWidth())
                } else {
                    Text(
                        "${row!!.start.dateTime()} · ${row.kwhGrid.num(1, "kWh")} (berechnet)",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField("Betrag €", cost, { cost = it }, Modifier.weight(1f))
                    NumberField("oder €/kWh", price, { price = it }, Modifier.weight(1f))
                }
                Text("Ort", style = MaterialTheme.typography.labelLarge)
                PlacePicker(vm.ui.places, placeId, { placeId = it }, if (manual) "keiner" else "automatisch")
                OutlinedTextField(note, { note = it }, label = { Text("Notiz (z. B. Ladesäule)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text(
                    "Leer gelassen gilt der Strompreis des Orts bzw. der Standardpreis.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = {
                vm.saveChargeEntry(
                    ChargeEntry(
                        id = e?.id ?: CostBook.newId(),
                        at = time!!,
                        manual = manual,
                        kwh = if (manual) parseNum(kwh) else null,
                        cost = parseNum(cost),
                        pricePerKwh = parseNum(price),
                        placeId = placeId,
                        note = note.trim(),
                    )
                )
                onDismiss()
            }) { Text("Speichern") }
        },
        dismissButton = {
            Row {
                if (e != null) TextButton(onClick = { vm.deleteCostEntry(e.id); onDismiss() }) {
                    Text(if (manual) "Löschen" else "Zurücksetzen")
                }
                TextButton(onClick = onDismiss) { Text("Abbrechen") }
            }
        },
    )
}

// ================================================================================== Tanken

@Composable
fun FuelScreen(vm: AppViewModel) {
    val ui = vm.ui
    var range by rememberSaveable { mutableStateOf(2) }
    var edit by remember { mutableStateOf<FuelRow?>(null) }
    var addNew by remember { mutableStateOf(false) }
    val from = rangeStart(range)
    val rows = vm.fuelRows.filter { from == null || !it.at.isBefore(from) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            SectionCard("Aktueller Kraftstoffpreis", Icons.Filled.LocalGasStation) {
                val last = ui.fuelEntries.maxByOrNull { it.at }
                InfoRow(
                    if (last != null) "Getankt am ${last.at.dateLong()}" else "Standardpreis (Einstellungen)",
                    "${vm.costs.fuelPriceAt(Instant.now()).fmt(3)} €/l",
                )
                Text(
                    "Trag nach dem Tanken den Literpreis ein – er gilt für alle Fahrten ab diesem Zeitpunkt bis zum nächsten Eintrag. " +
                        "Fahrten davor werden mit dem jeweils davor gültigen Preis berechnet.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = { addNew = true }) {
                    Icon(Icons.Filled.Add, null); Spacer(Modifier.padding(start = 4.dp)); Text("Tankvorgang eintragen")
                }
            }
        }
        item { RangeChips(range) { range = it } }
        item {
            SectionCard("Zusammenfassung", Icons.Filled.LocalGasStation) {
                val liters = rows.sumOf { it.liters ?: 0.0 }
                val priced = rows.filter { it.cost != null }
                val cost = priced.sumOf { it.cost!! }
                Row(Modifier.fillMaxWidth()) {
                    BigStat("${rows.size}", "Tankvorgänge", Modifier.weight(1f))
                    BigStat(liters.num(0, "l"), "getankt", Modifier.weight(1f))
                    BigStat(if (priced.isEmpty()) "–" else cost.eur(), "bezahlt¹", Modifier.weight(1f))
                }
                val pl = priced.sumOf { it.liters ?: 0.0 }
                if (pl > 0.1) InfoRow("Ø Preis", "${(cost / pl).fmt(3)} €/l")
            }
        }
        if (rows.isEmpty()) item {
            Text(
                "Keine Tankvorgänge im Zeitraum. Erkannt wird am Sprung im Tankstand (Messpunkte bzw. myOpel-Import); " +
                    "du kannst Tankvorgänge auch selbst eintragen.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(rows, key = { it.entry?.id ?: it.at.toString() }) { r -> FuelRowCard(r) { edit = r } }
        item {
            Text(
                "≈ Menge geschätzt aus dem Tankstand × ${ui.tankLiters.num(0, "l")} Tankinhalt; trägst du die Liter ein, gilt dein Wert. " +
                    "¹ nur Tankvorgänge mit eingetragenem Preis.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    edit?.let { r -> FuelDialog(vm, r, onDismiss = { edit = null }) }
    if (addNew) FuelDialog(vm, null, onDismiss = { addNew = false })
}

@Composable
private fun FuelRowCard(r: FuelRow, onClick: () -> Unit) {
    SectionCard(r.at.dateTime(), modifier = Modifier.clickable(onClick = onClick)) {
        val e = r.entry
        InfoRow(
            if (e != null) "${e.pricePerLiter.fmt(3)} €/l" else "Preis antippen zum Eintragen",
            r.cost?.eur() ?: "–",
            valueColor = MaterialTheme.colorScheme.onSurface,
        )
        val est = e?.liters == null
        val parts = listOfNotNull(
            r.refuel?.let { "${it.levelFrom.pct()} → ${it.levelTo.pct()}" },
            r.liters?.let { (if (est) "≈ " else "") + it.num(1, "l") },
            r.refuel?.mileage?.km(),
        )
        if (parts.isNotEmpty()) Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        e?.note?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

/** Preis zu einem Tankvorgang eintragen. Zwei von drei Angaben (€/l, Liter, Betrag) genügen. */
@Composable
private fun FuelDialog(vm: AppViewModel, row: FuelRow?, onDismiss: () -> Unit) {
    val e = row?.entry
    var at by remember { mutableStateOf((e?.at ?: row?.at ?: Instant.now()).toInput()) }
    var price by remember { mutableStateOf(e?.pricePerLiter?.fmt(3) ?: "") }
    var liters by remember { mutableStateOf(e?.liters?.fmt(2) ?: "") }
    var total by remember { mutableStateOf("") }
    var note by remember { mutableStateOf(e?.note ?: "") }

    val time = parseInput(at)
    val p = parseNum(price)
    val l = parseNum(liters)
    val t = parseNum(total)
    val effPrice = p ?: if (t != null && l != null && l > 0) t / l else null
    val effLiters = l ?: if (t != null && p != null && p > 0) t / p else null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (e == null) "Tankvorgang eintragen" else "Tankvorgang bearbeiten") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(at, { at = it }, label = { Text("Datum, Uhrzeit (TT.MM.JJJJ HH:MM)") }, singleLine = true, isError = time == null)
                NumberField("Preis €/l", price, { price = it }, Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumberField(
                        "Liter" + (row?.refuel?.liters?.let { " (≈ ${it.fmt(0)})" } ?: ""),
                        liters, { liters = it }, Modifier.weight(1f),
                    )
                    NumberField("Betrag €", total, { total = it }, Modifier.weight(1f))
                }
                OutlinedTextField(note, { note = it }, label = { Text("Notiz (z. B. Tankstelle)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text(
                    effPrice?.let { "Preis ${it.fmt(3)} €/l" + (effLiters?.let { li -> " · ${li.fmt(2)} l · ${(li * it).eur()}" } ?: "") }
                        ?: "Preis eingeben – oder Liter und Betrag.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = time != null && effPrice != null && effPrice > 0.3 && effPrice < 5, onClick = {
                vm.saveFuelEntry(FuelEntry(e?.id ?: CostBook.newId(), time!!, effPrice!!, effLiters, note.trim()))
                onDismiss()
            }) { Text("Speichern") }
        },
        dismissButton = {
            Row {
                if (e != null) TextButton(onClick = { vm.deleteCostEntry(e.id); onDismiss() }) { Text("Löschen") }
                TextButton(onClick = onDismiss) { Text("Abbrechen") }
            }
        },
    )
}
