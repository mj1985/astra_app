package com.astraconnect.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.EvStation
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material.icons.filled.Place
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.MeetingRoom
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.astraconnect.app.AppViewModel
import kotlinx.coroutines.launch

enum class Dest(val title: String, val icon: ImageVector, val short: String = title) {
    OVERVIEW("Übersicht", Icons.Filled.Dashboard, "Start"),
    REMOTE("Fernsteuerung", Icons.Filled.SettingsRemote, "Steuern"),
    TRIPS("Fahrtenbuch", Icons.Filled.Route, "Fahrten"),
    CHARGING("Laden", Icons.Filled.EvStation),
    FUEL("Tanken", Icons.Filled.LocalGasStation),
    STATS("Statistik", Icons.Filled.BarChart),
    MAP("Karte", Icons.Filled.Map),
    PLACES("Orte & Regeln", Icons.Filled.Place, "Orte"),
    DOORS("Türen & Fenster", Icons.Filled.MeetingRoom, "Türen"),
    SERVICE("Wartung & 12V-Batterie", Icons.Filled.Build, "Wartung"),
    SETTINGS("Einstellungen", Icons.Filled.Settings, "Optionen"),
    RAW("API-Rohdaten", Icons.Filled.Code, "Rohdaten"),
    ;

    companion object {
        /** Höchstens so viele frei wählbare Einträge neben der festen Startseite (Material: 3–5 Ziele). */
        const val MAX_BOTTOM = 4
        val DEFAULT_BOTTOM = listOf(TRIPS, CHARGING, REMOTE, STATS)

        /** Gewählte Einträge der Leiste unten (ohne Startseite). */
        fun bottom(names: List<String>?): List<Dest> =
            names?.mapNotNull { n -> entries.firstOrNull { it.name == n } }?.filter { it != OVERVIEW }?.distinct()?.take(MAX_BOTTOM)
                ?: DEFAULT_BOTTOM
    }
}

@Composable
fun AppRoot(vm: AppViewModel) {
    if (!vm.ui.loggedIn) LoginScreen(vm) else MainScreen(vm)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(vm: AppViewModel) {
    var dest by rememberSaveable { mutableStateOf(Dest.OVERVIEW) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val ui = vm.ui

    // Android 13+: einmal pro App-Start nach der Benachrichtigungs-Erlaubnis fragen
    val ctx = LocalContext.current
    val askNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    LaunchedEffect(ui.error) {
        ui.error?.let { snackbar.showSnackbar(it); vm.showError(null) }
    }
    BackHandler(enabled = drawer.isOpen || dest != Dest.OVERVIEW) {
        if (drawer.isOpen) scope.launch { drawer.close() } else dest = Dest.OVERVIEW
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet {
                Column(Modifier.padding(horizontal = 24.dp, vertical = 20.dp)) {
                    Text(ui.vehicle?.displayName ?: "Opel Astra", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        ui.vehicle?.vin ?: "Plug-in-Hybrid",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                    Dest.entries.forEach { d ->
                        NavigationDrawerItem(
                            label = { Text(d.title) },
                            icon = { Icon(d.icon, null) },
                            selected = d == dest,
                            onClick = { dest = d; scope.launch { drawer.close() } },
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                }
                HorizontalDivider()
                NavigationDrawerItem(
                    label = { Text("Abmelden") },
                    icon = { Icon(Icons.AutoMirrored.Filled.Logout, null) },
                    selected = false,
                    onClick = { scope.launch { drawer.close() }; vm.logout() },
                    modifier = Modifier.padding(12.dp),
                )
            }
        },
    ) {
        Scaffold(
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = {
                TopAppBar(
                    title = { Text(dest.title) },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawer.open() } }) { Icon(Icons.Filled.Menu, "Menü") }
                    },
                    actions = {
                        if (dest in setOf(Dest.OVERVIEW, Dest.REMOTE, Dest.DOORS, Dest.SERVICE, Dest.RAW)) {
                            IconButton(onClick = { vm.refresh() }, enabled = !ui.loading) {
                                Icon(Icons.Filled.Refresh, "Aktualisieren")
                            }
                        }
                    },
                )
            },
            bottomBar = {
                val items = listOf(Dest.OVERVIEW) + Dest.bottom(ui.bottomBar)
                NavigationBar {
                    items.forEach { d ->
                        NavigationBarItem(
                            selected = d == dest,
                            onClick = { dest = d },
                            icon = { Icon(d.icon, null) },
                            label = { Text(d.short, maxLines = 1) },
                        )
                    }
                }
            },
        ) { pad ->
            Box(Modifier.fillMaxSize().padding(pad)) {
                when (dest) {
                    Dest.OVERVIEW -> OverviewScreen(vm, onNavigate = { dest = it })
                    Dest.REMOTE -> RemoteScreen(vm)
                    Dest.SETTINGS -> SettingsScreen(vm)
                    Dest.TRIPS -> TripLogScreen(vm)
                    Dest.CHARGING -> ChargingScreen(vm)
                    Dest.FUEL -> FuelScreen(vm)
                    Dest.STATS -> StatsScreen(vm)
                    Dest.MAP -> MapScreen(vm)
                    Dest.PLACES -> PlacesScreen(vm)
                    Dest.DOORS -> DoorsScreen(vm)
                    Dest.SERVICE -> ServiceScreen(vm)
                    Dest.RAW -> RawDataScreen(vm)
                }
            }
        }
    }
}
