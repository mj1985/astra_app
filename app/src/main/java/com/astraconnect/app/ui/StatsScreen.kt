package com.astraconnect.app.ui

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.automirrored.filled.CompareArrows
import androidx.compose.material.icons.filled.Euro
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.astraconnect.app.AppViewModel
import com.astraconnect.app.data.History
import com.astraconnect.app.data.MonthStats
import com.astraconnect.app.data.TripCategory
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

private val monthShort = DateTimeFormatter.ofPattern("MMM", Locale.GERMANY)
private val monthLong = DateTimeFormatter.ofPattern("MMMM yyyy", Locale.GERMANY)

private fun Double.eur() = String.format(Locale.GERMANY, "%,.2f €", this)

@Composable
fun StatsScreen(vm: AppViewModel) {
    val ui = vm.ui
    val zone = ZoneId.systemDefault()
    // Zeitraum: null = letzte 12 Monate, 0 = alles, sonst Jahr
    var range by rememberSaveable { mutableStateOf<Int?>(null) }
    var tab by rememberSaveable { mutableStateOf(0) }
    val years = remember(ui.trips) {
        ui.trips.mapNotNull { it.startedAt?.atZone(zone)?.year }.distinct().sortedDescending()
    }
    val from: Instant? = when (range) {
        null -> YearMonth.now().minusMonths(11).atDay(1).atStartOfDay(zone).toInstant()
        0 -> null
        else -> YearMonth.of(range!!, 1).atDay(1).atStartOfDay(zone).toInstant()
    }
    val to: Instant? = range?.takeIf { it > 0 }?.let { YearMonth.of(it + 1, 1).atDay(1).atStartOfDay(zone).toInstant() }
    fun inRange(i: Instant?) = i != null && (from == null || !i.isBefore(from)) && (to == null || i.isBefore(to))

    val trips = ui.trips.filter { inRange(it.startedAt) }
    val costs = vm.costs
    val months = remember(trips, ui.battery, costs) { History.monthly(trips, ui.battery, costs) }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Filter in einer Zeile über den Diagrammen
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = range == null, onClick = { range = null }, label = { Text("12 Monate") })
                    years.forEach { y -> FilterChip(selected = range == y, onClick = { range = y }, label = { Text("$y") }) }
                    FilterChip(selected = range == 0, onClick = { range = 0 }, label = { Text("Alles") })
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Übersicht", "Datenabgleich").forEachIndexed { i, t ->
                        FilterChip(selected = tab == i, onClick = { tab = i }, label = { Text(t) })
                    }
                }
            }
        }
        when (tab) {
            0 -> overview(this, vm, months, trips)
            else -> reconciliation(this, vm)
        }
    }
}

// ---------------------------------------------------------------------------------- Übersicht

private fun overview(scope: androidx.compose.foundation.lazy.LazyListScope, vm: AppViewModel, months: List<MonthStats>, trips: List<com.astraconnect.app.data.Trip>) = with(scope) {
    val ui = vm.ui
    val km = months.sumOf { it.km }
    val el = months.sumOf { it.kmElectric }
    val fu = months.sumOf { it.kmFuel }
    val cost = months.sumOf { it.cost }
    val labels = months.map { monthShort.format(it.month) }
    val long = months.map { monthLong.format(it.month) }

    item {
        SectionCard("Kennzahlen", Icons.Filled.BarChart) {
            Row(Modifier.fillMaxWidth()) {
                BigStat(km.km(), "gefahren", Modifier.weight(1f))
                BigStat(if (el + fu > 0) (el / (el + fu) * 100).pct() else "–", "rein elektrisch (km)", Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth()) {
                BigStat(cost.eur(), "Energiekosten", Modifier.weight(1f))
                BigStat(if (km > 0) (cost / km * 100).eur() else "–", "pro 100 km", Modifier.weight(1f))
            }
            Text(
                "Kosten = Benzin × Literpreis des letzten Tankvorgangs davor + berechneter Akku-Verbrauch × Preis der letzten Ladung davor. " +
                    "Ohne Eintrag gelten die Standardpreise (${ui.prices.fuelPerLiter.num(2, "€/l")}, ${ui.prices.powerPerKwh.num(2, "€/kWh")} + ${ui.prices.chargeLossPct.num(0, "%")} Ladeverluste).",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
    item {
        SectionCard("Kilometer pro Monat", Icons.Filled.BarChart) {
            val series = listOfNotNull(
                ChartSeries("rein elektrisch", ChartColors.electric, months.map { it.kmElectric }),
                ChartSeries("mit Benzin", ChartColors.fuel, months.map { it.kmFuel }),
                months.map { it.kmUnknown }.takeIf { v -> v.any { it > 0 } }?.let { ChartSeries("ohne Angabe", ChartColors.unknown, it) },
            )
            StackedBarChart(labels, series, "km", longLabels = long)
            Text(
                "„Rein elektrisch“ = Fahrten ganz ohne Benzinverbrauch. Fahrten „mit Benzin“ sind oft teilweise elektrisch.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
    item {
        SectionCard("Benzinverbrauch (Ø l/100 km)", Icons.Filled.LocalGasStation) {
            StackedBarChart(labels, listOf(ChartSeries("Benzin", ChartColors.fuel, months.map { it.avgFuel ?: 0.0 })), "l/100 km", digits = 1, longLabels = long)
        }
    }
    item {
        SectionCard("Energiekosten pro Monat", Icons.Filled.Euro) {
            StackedBarChart(
                labels,
                listOf(
                    ChartSeries("Strom", ChartColors.electric, months.map { it.costPower }),
                    ChartSeries("Benzin", ChartColors.fuel, months.map { it.costFuel }),
                ),
                "€", digits = 2, longLabels = long,
            )
            val withoutKwh = trips.count { History.isElectric(it) != null && ui.battery[it.key]?.kwh == null }
            if (withoutKwh > 0) {
                Text(
                    "Stromkosten nur für Fahrten mit berechnetem Akku-Verbrauch ($withoutKwh Fahrten ohne Messpunkte fehlen).",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
    item {
        SectionCard("Nach Kategorie", Icons.Filled.Euro) {
            TripCategory.entries.forEach { c ->
                val list = trips.filter { it.category == c }
                if (list.isNotEmpty()) {
                    val k = list.sumOf { it.distanceKm ?: 0.0 }
                    val e = list.sumOf { vm.tripCost(it) ?: 0.0 }
                    InfoRow(c.label, "${list.size} Fahrten · ${k.km()} · ${e.eur()}")
                }
            }
        }
    }
    item {
        // Tabellenansicht – dieselben Zahlen wie in den Diagrammen
        SectionCard("Monatstabelle", Icons.Filled.TableChart) {
            TableRow(listOf("Monat", "km", "el. %", "Liter", "kWh", "€"), header = true)
            HorizontalDivider()
            months.reversed().forEach { m ->
                TableRow(
                    listOf(
                        monthShort.format(m.month) + " " + (m.month.year % 100).toString().padStart(2, '0'),
                        m.km.num(0),
                        m.electricShare?.let { (it * 100).num(0) } ?: "–",
                        m.fuelLiters.num(1),
                        m.kwh.num(1),
                        m.cost.num(0),
                    )
                )
            }
        }
    }
}

@Composable
private fun TableRow(cells: List<String>, header: Boolean = false) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        cells.forEachIndexed { i, c ->
            Text(
                c,
                Modifier.weight(if (i == 0) 1.4f else 1f),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
                color = if (header) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                textAlign = if (i == 0) TextAlign.Start else TextAlign.End,
            )
        }
    }
}

// ---------------------------------------------------------------------------------- Datenabgleich

private fun reconciliation(scope: androidx.compose.foundation.lazy.LazyListScope, vm: AppViewModel) = with(scope) {
    val ui = vm.ui
    val fuelPairs = ui.trips.filter { (it.apiFuelRaw ?: 0.0) > 0 && (it.myopFuelLiters ?: 0.0) > 0.05 }
    val fuelFactors = fuelPairs.map { it.apiFuelRaw!! / it.myopFuelLiters!! }
    val fuelMedian = History.median(fuelFactors)

    val elPairs = ui.trips.filter { (it.apiElectricRaw ?: 0.0) > 0 && (ui.battery[it.key]?.kwh ?: 0.0) > 0.2 }
    val elFactors = elPairs.map { it.apiElectricRaw!! / ui.battery[it.key]!!.kwh!! }
    val elMedian = History.median(elFactors)

    item {
        SectionCard("Kraftstoff: Opel-Server ↔ myOpel", Icons.AutoMirrored.Filled.CompareArrows) {
            Text(
                "Vergleicht den Rohwert der Opel-API mit den Litern aus dem myOpel-Export für dieselbe Fahrt. " +
                    "Die App rechnet API-Werte als Zentiliter (÷ 100) – der Faktor sollte also bei etwa 100 liegen.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            if (fuelMedian == null) {
                Text("Noch keine Fahrt, die vom Server UND aus myOpel stammt und Benzin verbraucht hat.")
            } else {
                InfoRow("Verglichene Fahrten", "${fuelPairs.size}")
                InfoRow(
                    "Faktor (Median)", fuelMedian.num(1),
                    if (fuelMedian in 80.0..125.0) com.astraconnect.app.ui.theme.Ok else com.astraconnect.app.ui.theme.Warn,
                )
                Text(
                    when {
                        fuelMedian in 80.0..125.0 -> "Bestätigt: Die API liefert Zentiliter, die Umrechnung stimmt."
                        fuelMedian in 0.8..1.25 -> "Die API liefert offenbar Liter – die ÷100-Umrechnung wäre falsch."
                        fuelMedian in 800.0..1250.0 -> "Die API liefert offenbar Milliliter."
                        else -> "Kein klares Muster – Werte bitte prüfen."
                    },
                    fontWeight = FontWeight.Medium,
                )
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                TableRow(listOf("Fahrt", "API roh", "myOpel l", "Faktor"), header = true)
                fuelPairs.take(15).forEach { t ->
                    TableRow(listOf(t.startedAt.dateTime(), t.apiFuelRaw.num(1), t.myopFuelLiters.num(2), (t.apiFuelRaw!! / t.myopFuelLiters!!).num(1)))
                }
            }
        }
    }
    item {
        SectionCard("Strom: Opel-Server ↔ berechneter Akku-Verbrauch", Icons.AutoMirrored.Filled.CompareArrows) {
            Text(
                "Vergleicht den Strom-Rohwert der API mit dem aus dem Ladestand berechneten Verbrauch.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            if (elMedian == null) {
                Text(
                    if (ui.trips.none { it.apiElectricRaw != null }) "Die API hat bisher keinen Stromverbrauch pro Fahrt geliefert."
                    else "Noch keine Fahrt mit API-Stromwert UND berechnetem Akku-Verbrauch."
                )
            } else {
                InfoRow("Verglichene Fahrten", "${elPairs.size}")
                InfoRow("Faktor (Median)", elMedian.num(2))
                Text(
                    when {
                        elMedian in 0.7..1.4 -> "Die API liefert offenbar kWh."
                        elMedian in 700.0..1400.0 -> "Die API liefert offenbar Wh."
                        elMedian in 70.0..140.0 -> "Die API liefert offenbar Zehntel-Wh bzw. ein anderes Format – bitte prüfen."
                        else -> "Kein klares Muster – Werte bitte prüfen."
                    },
                    fontWeight = FontWeight.Medium,
                )
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                TableRow(listOf("Fahrt", "API roh", "berechnet kWh", "Faktor"), header = true)
                elPairs.take(15).forEach { t ->
                    val k = ui.battery[t.key]!!.kwh!!
                    TableRow(listOf(t.startedAt.dateTime(), t.apiElectricRaw.num(2), k.num(2), (t.apiElectricRaw!! / k).num(2)))
                }
            }
        }
    }
}
