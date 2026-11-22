package com.astraconnect.app.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.astraconnect.app.AppViewModel
import com.astraconnect.app.data.NavTarget
import java.util.Locale

/**
 * Ziel suchen (Adresse oder gespeicherter Ort) und an das Navi des Autos senden.
 * [showStatus]: letzten Navigations-Status in der Karte zeigen (dort, wo es keine Befehlsliste gibt).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NavigationCard(vm: AppViewModel, showStatus: Boolean) {
    val ui = vm.ui
    val ctx = LocalContext.current
    val focus = LocalFocusManager.current
    var query by rememberSaveable { mutableStateOf("") }
    var selName by rememberSaveable { mutableStateOf<String?>(null) }
    var selLat by rememberSaveable { mutableStateOf(0.0) }
    var selLon by rememberSaveable { mutableStateOf(0.0) }
    val selected = selName?.let { NavTarget(it, selLat, selLon) }
    fun select(t: NavTarget) {
        selName = t.name; selLat = t.lat; selLon = t.lon
        vm.clearDestinationSearch()
        focus.clearFocus()
    }
    fun search() {
        focus.clearFocus()
        vm.searchDestination(query)
    }

    SectionCard("Navigationsziel", Icons.Filled.Navigation) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Adresse oder Ort suchen") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { search() }),
                trailingIcon = {
                    if (ui.navSearching) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    else IconButton(onClick = { search() }, enabled = query.isNotBlank()) { Icon(Icons.Filled.Search, "Suchen") }
                },
            )

            if (ui.places.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ui.places.forEach { p ->
                        SuggestionChip(
                            onClick = { select(NavTarget(p.name, p.lat, p.lon)) },
                            label = { Text(p.name) },
                            icon = { Icon(Icons.Filled.Place, null, Modifier.size(18.dp)) },
                        )
                    }
                }
            }

            ui.navInfo?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }

            if (ui.navResults.isNotEmpty()) {
                Column {
                    ui.navResults.forEachIndexed { i, t ->
                        if (i > 0) HorizontalDivider()
                        Row(
                            Modifier.fillMaxWidth().clickable { select(t) }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.Place, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(10.dp))
                            Text(t.name, Modifier.weight(1f))
                        }
                    }
                }
            }

            selected?.let { t ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(t.name, fontWeight = FontWeight.SemiBold)
                        Text(
                            "%.5f, %.5f".format(Locale.ROOT, t.lat, t.lon),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    IconButton(onClick = { selName = null }) { Icon(Icons.Filled.Close, "Ziel entfernen") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { vm.sendNavigation(t) },
                        enabled = !ui.navSending,
                        modifier = Modifier.weight(1f),
                    ) {
                        if (ui.navSending) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Filled.Navigation, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("An Auto senden")
                    }
                    OutlinedButton(onClick = { openInMaps(ctx, t) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Filled.Map, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Google Maps")
                    }
                }
            }

            if (showStatus) {
                ui.commands.firstOrNull { it.id.startsWith("nav-") }?.let { c ->
                    Text(
                        "${c.name}: ${c.status}",
                        color = if (c.final && !c.success) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Text(
                "„An Auto senden“ braucht ein aktives Abo für die vernetzte Navigation (wie in der MyOpel-App). " +
                    "Ohne Abo öffnet „Google Maps“ die Route auf dem Handy, z. B. für Android Auto.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Startet die Navigation in Google Maps; ohne Google Maps irgendeine Karten-App. */
private fun openInMaps(ctx: Context, t: NavTarget) {
    val ll = "%.6f,%.6f".format(Locale.ROOT, t.lat, t.lon)
    val nav = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=$ll")).setPackage("com.google.android.apps.maps")
    try {
        ctx.startActivity(nav)
    } catch (_: ActivityNotFoundException) {
        try {
            ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=$ll(${Uri.encode(t.name)})")))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(ctx, "Keine Karten-App installiert.", Toast.LENGTH_LONG).show()
        }
    }
}
