package com.astraconnect.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.astraconnect.app.AppViewModel
import com.astraconnect.app.data.LatLon
import com.astraconnect.app.data.Place
import com.astraconnect.app.data.PlaceBook
import com.astraconnect.app.data.TripCategory
import java.util.Locale

/** Ort, der gerade benannt/bearbeitet wird. */
private data class PlaceDraft(val existing: Place?, val pos: LatLon, val hint: String?)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlacesScreen(vm: AppViewModel) {
    val ui = vm.ui
    var draft by remember { mutableStateOf<PlaceDraft?>(null) }
    val suggestions = remember(ui.trips, ui.places) { PlaceBook.suggestions(ui.trips, ui.places) }
    fun name(id: String?) = ui.places.firstOrNull { it.id == id }?.name ?: "beliebig"

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(
                "Benenne häufige Orte (z. B. „Zuhause“, „Arbeit“) und lege Regeln fest. Neue Fahrten, die zu einer Regel " +
                    "passen, bekommen ihre Kategorie automatisch. Von Hand gesetzte Kategorien werden nie überschrieben.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item {
            SectionCard("Benannte Orte", Icons.Filled.Place) {
                if (ui.places.isEmpty()) Text("Noch keine Orte. Unten aus den Vorschlägen übernehmen.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                ui.places.sortedBy { it.name.lowercase() }.forEach { p ->
                    val visits = ui.trips.count {
                        PlaceBook.placeAt(listOf(p), it.startPos) != null || PlaceBook.placeAt(listOf(p), it.endPos) != null
                    }
                    Row(Modifier.fillMaxWidth().clickable { draft = PlaceDraft(p, p.pos, null) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.name, fontWeight = FontWeight.SemiBold)
                            Text("Radius ${p.radiusM.num(0, "m")} · $visits Fahrten", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { vm.deletePlace(p.id) }) { Icon(Icons.Filled.Delete, "Löschen") }
                    }
                }
                ui.status?.position?.let { pos ->
                    Spacer(Modifier.height(6.dp))
                    OutlinedButton(onClick = { draft = PlaceDraft(null, pos, "aktueller Fahrzeugstandort") }) {
                        Text("Aktuellen Fahrzeugstandort als Ort speichern")
                    }
                }
            }
        }

        if (suggestions.isNotEmpty()) {
            item {
                SectionCard("Vorschläge – häufig angefahren", Icons.Filled.Lightbulb) {
                    suggestions.forEach { s ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(s.address ?: String.format(Locale.US, "%.4f, %.4f", s.lat, s.lon))
                                Text("${s.visits}× Start oder Ziel", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = { draft = PlaceDraft(null, s.pos, s.address) }) { Text("Benennen") }
                        }
                    }
                }
            }
        }

        item {
            SectionCard("Regeln", Icons.Filled.AutoFixHigh) {
                if (ui.rules.isEmpty()) Text("Noch keine Regeln.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                ui.rules.forEach { r ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${name(r.fromId)} ${if (r.bothWays) "↔" else "→"} ${name(r.toId)}  =  ${r.category.label}",
                            Modifier.weight(1f),
                        )
                        IconButton(onClick = { vm.deleteRule(r.id) }) { Icon(Icons.Filled.Delete, "Regel löschen") }
                    }
                }
                if (ui.places.isNotEmpty()) {
                    HorizontalDivider(Modifier.padding(vertical = 8.dp))
                    RuleEditor(vm)
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = { vm.applyRulesNow() }, enabled = ui.rules.isNotEmpty()) {
                    Text("Auf alle offenen Fahrten anwenden")
                }
                ui.tripsInfo?.let { Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)) }
            }
        }
    }

    draft?.let { d ->
        PlaceDialog(d, onDismiss = { draft = null }) { name, radius ->
            if (d.existing != null) vm.updatePlace(d.existing.copy(name = name, radiusM = radius))
            else vm.addPlace(name, d.pos, radius)
            draft = null
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RuleEditor(vm: AppViewModel) {
    val places = vm.ui.places.sortedBy { it.name.lowercase() }
    var from by remember { mutableStateOf<String?>(null) }
    var to by remember { mutableStateOf<String?>(null) }
    var category by remember { mutableStateOf(TripCategory.COMMUTE) }
    var both by remember { mutableStateOf(true) }

    Text("Neue Regel", fontWeight = FontWeight.SemiBold)
    Text("Von", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected = from == null, onClick = { from = null }, label = { Text("beliebig") })
        places.forEach { p -> FilterChip(selected = from == p.id, onClick = { from = p.id }, label = { Text(p.name) }) }
    }
    Text("Nach", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected = to == null, onClick = { to = null }, label = { Text("beliebig") })
        places.forEach { p -> FilterChip(selected = to == p.id, onClick = { to = p.id }, label = { Text(p.name) }) }
    }
    Text("Kategorie", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        TripCategory.entries.filter { it != TripCategory.UNSET }.forEach { c ->
            FilterChip(selected = category == c, onClick = { category = c }, label = { Text(c.label) })
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Gilt auch für die Rückfahrt", Modifier.weight(1f))
        Switch(checked = both, onCheckedChange = { both = it })
    }
    Button(
        onClick = { vm.addRule(from, to, category, both); from = null; to = null },
        enabled = from != null || to != null,
    ) { Text("Regel hinzufügen") }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlaceDialog(d: PlaceDraft, onDismiss: () -> Unit, onSave: (String, Double) -> Unit) {
    var name by remember { mutableStateOf(d.existing?.name.orEmpty()) }
    var radius by remember { mutableStateOf((d.existing?.radiusM ?: 200.0).toInt().toString()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (d.existing == null) "Ort benennen" else "Ort bearbeiten") },
        text = {
            Column {
                d.hint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("Zuhause", "Arbeit", "Eltern", "Einkaufen").forEach { s -> AssistChip(onClick = { name = s }, label = { Text(s) }) }
                }
                OutlinedTextField(
                    value = radius, onValueChange = { radius = it.filter(Char::isDigit) },
                    label = { Text("Radius (m)") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Spacer(Modifier.width(4.dp))
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name.trim(), (radius.toDoubleOrNull() ?: 200.0).coerceIn(50.0, 2000.0)) }, enabled = name.isNotBlank()) {
                Text("Speichern")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}
