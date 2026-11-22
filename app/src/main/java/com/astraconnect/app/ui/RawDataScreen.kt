package com.astraconnect.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.astraconnect.app.AppViewModel

/** Zeigt die unveränderten API-Antworten – hilfreich, um weitere Felder deines Autos zu entdecken. */
@Composable
fun RawDataScreen(vm: AppViewModel) {
    val tabs = listOf("Status", "Wartung", "Fahrzeuge")
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val text = when (tab) {
        0 -> vm.ui.rawStatus
        1 -> vm.ui.rawMaintenance ?: "Kein separater Wartungs-Endpunkt verfügbar (Daten ggf. im Status enthalten)."
        else -> vm.ui.rawVehicles
    } ?: "Keine Daten geladen."
    val clipboard = LocalClipboardManager.current

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            tabs.forEachIndexed { i, t -> FilterChip(selected = tab == i, onClick = { tab = i }, label = { Text(t) }) }
            OutlinedButton(onClick = { clipboard.setText(AnnotatedString(text)) }) { Text("Kopieren") }
        }
        SelectionContainer(Modifier.padding(top = 12.dp).verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState())) {
            Text(text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}
