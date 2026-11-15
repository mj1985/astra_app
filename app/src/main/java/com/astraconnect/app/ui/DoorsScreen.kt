package com.astraconnect.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material.icons.filled.Window
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.astraconnect.app.AppViewModel
import com.astraconnect.app.data.Opening
import com.astraconnect.app.ui.theme.Bad
import com.astraconnect.app.ui.theme.Ok
import com.astraconnect.app.ui.theme.Warn

private val standardDoors = listOf("Driver", "Passenger", "RearLeft", "RearRight", "Trunk")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DoorsScreen(vm: AppViewModel) {
    val s = vm.ui.status
    PullToRefreshBox(isRefreshing = vm.ui.loading, onRefresh = { vm.refresh() }, modifier = Modifier.fillMaxSize()) {
        LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (s == null) {
                item { Text("Keine Daten geladen.") }
                return@LazyColumn
            }
            item {
                SectionCard("Zentralverriegelung", if (s.isLocked == false) Icons.Filled.LockOpen else Icons.Filled.Lock) {
                    InfoRow(
                        "Status", s.lockLabel,
                        when (s.isLocked) { true -> Ok; false -> Bad; null -> Warn },
                    )
                    if (s.lockStates.isNotEmpty()) {
                        Text(
                            "Rohwert: ${s.lockStates.joinToString()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item {
                SectionCard("Türen & Klappen", Icons.Filled.MeetingRoom) {
                    // Standardtüren immer anzeigen, auch wenn das Fahrzeug sie nicht meldet
                    val reported = s.doors.associateBy { it.identifier.lowercase() }
                    val list = standardDoors.map { id -> reported[id.lowercase()] ?: Opening(id, Opening.label(id), null) } +
                        s.doors.filter { it.identifier.lowercase() !in standardDoors.map(String::lowercase) }
                    list.forEach { OpeningRow(it) }
                }
            }
            item {
                SectionCard("Fenster", Icons.Filled.Window) {
                    if (s.windowsReported) {
                        s.windows.forEach { OpeningRow(it) }
                    } else {
                        Text(
                            "Dein Astra übermittelt aktuell keinen Fensterstatus an die Opel-API. " +
                                "Sobald der Server Fensterdaten liefert, erscheinen sie hier automatisch.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item {
                Text(
                    "Stand: ${s.updatedAt.dateTime()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun OpeningRow(o: Opening) {
    when (o.isOpen) {
        true -> InfoRow(o.label, "offen", Bad, Icons.Filled.Error)
        false -> InfoRow(o.label, "geschlossen", Ok, Icons.Filled.CheckCircle)
        null -> InfoRow(o.label, o.state ?: "keine Angabe", MaterialTheme.colorScheme.onSurfaceVariant, Icons.AutoMirrored.Filled.HelpOutline)
    }
}
