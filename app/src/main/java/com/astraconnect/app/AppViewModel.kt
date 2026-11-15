package com.astraconnect.app

import android.app.Application
import android.location.Geocoder
import android.net.Uri
import android.webkit.CookieManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.astraconnect.app.data.AuthExpiredException
import com.astraconnect.app.data.AuthRepository
import com.astraconnect.app.data.BatteryUse
import com.astraconnect.app.data.EnergyLog
import com.astraconnect.app.data.LatLon
import com.astraconnect.app.data.RemoteControl
import com.astraconnect.app.data.SecureStore
import com.astraconnect.app.data.SnapshotWorker
import com.astraconnect.app.data.StellantisApi
import com.astraconnect.app.data.Stores
import com.astraconnect.app.data.Trip
import com.astraconnect.app.data.TripCategory
import com.astraconnect.app.data.Vehicle
import com.astraconnect.app.data.VehicleStatus
import com.astraconnect.app.data.arr
import com.astraconnect.app.data.obj
import com.astraconnect.app.data.objects
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONObject
import java.time.Instant
import java.util.Locale
import java.util.concurrent.TimeUnit

data class CommandEntry(
    val id: String,
    val name: String,
    val sentAt: Instant,
    val status: String,
    val final: Boolean,
    val success: Boolean,
)

data class UiState(
    val loggedIn: Boolean,
    val loading: Boolean = false,
    val loggingIn: Boolean = false,
    val error: String? = null,
    val vehicles: List<Vehicle> = emptyList(),
    val selectedId: String? = null,
    val status: VehicleStatus? = null,
    val rawStatus: String? = null,
    val rawMaintenance: String? = null,
    val rawVehicles: String? = null,
    val trips: List<Trip> = emptyList(),
    val tripsSyncing: Boolean = false,
    val tripsInfo: String? = null,
    // Akku-Verbrauch
    val battery: Map<String, BatteryUse> = emptyMap(),
    val capacityKwh: Double = DEFAULT_CAPACITY,
    val backgroundEnabled: Boolean = true,
    val snapshotCount: Int = 0,
    val lastSnapshot: Instant? = null,
    // Fernsteuerung
    val remoteActivated: Boolean = false,
    val smsRequested: Boolean = false,
    val remoteBusy: Boolean = false,
    val remoteInfo: String? = null,
    val commands: List<CommandEntry> = emptyList(),
) {
    val vehicle: Vehicle? get() = vehicles.firstOrNull { it.id == selectedId } ?: vehicles.firstOrNull()
    val commandRunning: Boolean get() = commands.any { !it.final && Instant.now().epochSecond - it.sentAt.epochSecond < 180 }

    companion object {
        /** Nutzbare Kapazität Astra L Plug-in-Hybrid (12,4 kWh brutto). Anpassbar in den Einstellungen. */
        const val DEFAULT_CAPACITY = 10.2
    }
}

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val store = SecureStore(app)
    private val prefs = app.getSharedPreferences("app", Application.MODE_PRIVATE)
    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .build()
    val auth = AuthRepository(store, http)
    private val api = StellantisApi(auth, http)
    private val tripStore = Stores.trips(app)
    private val energyLog = Stores.energy(app)
    private val remote = RemoteControl(store, auth, http)
    private val wakeups = ArrayDeque<Long>()

    var ui by mutableStateOf(
        UiState(
            loggedIn = auth.isLoggedIn,
            capacityKwh = prefs.getFloat("capacity_kwh", UiState.DEFAULT_CAPACITY.toFloat()).toDouble(),
            backgroundEnabled = prefs.getBoolean("bg_enabled", true),
            remoteActivated = remote.isActivated,
        )
    )
        private set

    init {
        ui = ui.copy(trips = tripStore.load())
        restoreCache()
        recomputeBattery()
        if (ui.loggedIn) {
            SnapshotWorker.schedule(app, ui.backgroundEnabled)
            refresh()
        }
        viewModelScope.launch {
            remote.updates.collect { u ->
                ui = ui.copy(commands = ui.commands.map {
                    if (it.id == u.correlationId) it.copy(status = u.label, final = u.final, success = u.success) else it
                })
                if (u.success) launch { delay(10_000); refresh() }
            }
        }
    }

    // ------------------------------------------------------------------ Login

    fun saveClientConfig(clientId: String, clientSecret: String) {
        auth.clientId = clientId
        auth.clientSecret = clientSecret
    }

    fun onAuthCode(code: String) = viewModelScope.launch {
        ui = ui.copy(loggingIn = true, error = null)
        try {
            auth.exchangeCode(code)
            ui = ui.copy(loggedIn = true, loggingIn = false)
            SnapshotWorker.schedule(getApplication(), ui.backgroundEnabled)
            refresh().join()
            syncTrips()
        } catch (e: Exception) {
            ui = ui.copy(loggingIn = false, error = e.message ?: e.toString())
        }
    }

    fun showError(msg: String?) {
        ui = ui.copy(error = msg)
    }

    // ------------------------------------------------------------------ Status

    fun refresh() = viewModelScope.launch {
        ui = ui.copy(loading = true, error = null)
        try {
            val vehiclesJson = api.vehiclesRaw()
            val vehicles = vehiclesJson.obj("_embedded").arr("vehicles").objects().map(Vehicle::parse)
            val selected = vehicles.firstOrNull { it.id == prefs.getString("vehicle_id", null) } ?: vehicles.firstOrNull()
                ?: throw IllegalStateException("Kein Fahrzeug in deinem Opel-Konto gefunden.")
            prefs.edit().putString("vehicle_id", selected.id).apply()

            val statusJson = api.status(selected.id)
            val maintenance = api.maintenance(selected.id)
            val status = VehicleStatus.parse(statusJson, maintenance)
            withContext(Dispatchers.IO) { energyLog.record(status) }
            prefs.edit()
                .putString("cache_vehicles", vehiclesJson.toString())
                .putString("cache_status", statusJson.toString())
                .putString("cache_maint", maintenance?.toString())
                .apply()
            ui = ui.copy(
                vehicles = vehicles,
                selectedId = selected.id,
                status = status,
                rawVehicles = vehiclesJson.toString(2),
                rawStatus = statusJson.toString(2),
                rawMaintenance = maintenance?.toString(2),
                trips = tripStore.load(),
            )
            recomputeBattery()
        } catch (e: AuthExpiredException) {
            ui = ui.copy(loggedIn = false, error = e.message)
        } catch (e: Exception) {
            ui = ui.copy(error = e.message ?: e.toString())
        } finally {
            ui = ui.copy(loading = false)
        }
    }

    private fun restoreCache() {
        runCatching {
            val v = prefs.getString("cache_vehicles", null)?.let(::JSONObject) ?: return
            val s = prefs.getString("cache_status", null)?.let(::JSONObject) ?: return
            val m = prefs.getString("cache_maint", null)?.let(::JSONObject)
            ui = ui.copy(
                vehicles = v.obj("_embedded").arr("vehicles").objects().map(Vehicle::parse),
                selectedId = prefs.getString("vehicle_id", null),
                status = VehicleStatus.parse(s, m),
                rawVehicles = v.toString(2),
                rawStatus = s.toString(2),
                rawMaintenance = m?.toString(2),
            )
        }
    }

    // ------------------------------------------------------------------ Fahrtenbuch

    fun syncTrips(full: Boolean = false) = viewModelScope.launch {
        val vehicleId = ui.vehicle?.id ?: run {
            ui = ui.copy(tripsInfo = "Bitte zuerst die Fahrzeugdaten laden.")
            return@launch
        }
        ui = ui.copy(tripsSyncing = true, tripsInfo = null)
        try {
            val since = if (full) null else tripStore.newestStart()?.minusSeconds(3600)
            val raw = api.trips(vehicleId, since)
            val added = withContext(Dispatchers.IO) { tripStore.merge(raw) }
            ui = ui.copy(
                trips = tripStore.load(),
                tripsInfo = if (added == 0) "Keine neuen Fahrten." else "$added neue Fahrt(en) übernommen."
            )
            recomputeBattery()
            resolveAddresses()
        } catch (e: AuthExpiredException) {
            ui = ui.copy(loggedIn = false, error = e.message)
        } catch (e: Exception) {
            ui = ui.copy(tripsInfo = "Fahrten konnten nicht geladen werden: ${e.message}")
        } finally {
            ui = ui.copy(tripsSyncing = false)
        }
    }

    private suspend fun resolveAddresses() = withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent()) return@withContext
        val geocoder = Geocoder(getApplication(), Locale.GERMANY)
        val todo = tripStore.load().filter {
            (it.startAddress == null && it.startPos != null) || (it.endAddress == null && it.endPos != null)
        }.take(40)
        for (t in todo) {
            val s = if (t.startAddress == null) t.startPos?.let { address(geocoder, it) } else null
            val e = if (t.endAddress == null) t.endPos?.let { address(geocoder, it) } else null
            if (s != null || e != null) tripStore.update(t.key, startAddress = s, endAddress = e)
        }
        val trips = tripStore.load()
        withContext(Dispatchers.Main) { ui = ui.copy(trips = trips) }
    }

    @Suppress("DEPRECATION")
    private fun address(geocoder: Geocoder, p: LatLon): String? = runCatching {
        geocoder.getFromLocation(p.lat, p.lon, 1)?.firstOrNull()?.let { a ->
            listOfNotNull(
                listOfNotNull(a.thoroughfare, a.subThoroughfare).joinToString(" ").ifBlank { null },
                listOfNotNull(a.postalCode, a.locality).joinToString(" ").ifBlank { null },
            ).joinToString(", ").ifBlank { a.getAddressLine(0) }
        }
    }.getOrNull()

    /** Importiert einen myOpel-Export (*.myop oder ohne Endung) und gleicht ihn mit dem Fahrtenbuch ab. */
    fun importMyop(uri: Uri) = viewModelScope.launch {
        ui = ui.copy(tripsSyncing = true, tripsInfo = null)
        try {
            val result = withContext(Dispatchers.IO) {
                val text = getApplication<Application>().contentResolver.openInputStream(uri)
                    ?.use { it.readBytes().toString(Charsets.UTF_8) }
                    ?: throw IllegalStateException("Datei konnte nicht geöffnet werden.")
                tripStore.importMyop(text, ui.vehicle?.vin)
            }
            ui = ui.copy(trips = tripStore.load(), tripsInfo = "myOpel-Import: ${result.summary}")
            recomputeBattery()
            resolveAddresses()
        } catch (e: org.json.JSONException) {
            ui = ui.copy(tripsInfo = "Import fehlgeschlagen: Die Datei ist kein gültiger myOpel-Export.")
        } catch (e: Exception) {
            ui = ui.copy(tripsInfo = "Import fehlgeschlagen: ${e.message ?: e}")
        } finally {
            ui = ui.copy(tripsSyncing = false)
        }
    }

    fun updateTrip(key: String, category: TripCategory, note: String) {
        tripStore.update(key, category = category, note = note)
        ui = ui.copy(trips = tripStore.load())
    }

    fun exportCsv(trips: List<Trip>): Uri {
        val file = tripStore.exportCsv(trips, ui.battery)
        val ctx = getApplication<Application>()
        return FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", file)
    }

    // ------------------------------------------------------------------ Akku-Verbrauch

    private fun recomputeBattery() {
        val snaps = energyLog.all()
        ui = ui.copy(
            battery = EnergyLog.compute(ui.trips, snaps, ui.capacityKwh),
            snapshotCount = snaps.size,
            lastSnapshot = snaps.lastOrNull()?.at,
        )
    }

    fun setCapacity(kwh: Double) {
        if (kwh !in 1.0..100.0) return
        prefs.edit().putFloat("capacity_kwh", kwh.toFloat()).apply()
        ui = ui.copy(capacityKwh = kwh)
        recomputeBattery()
    }

    fun setBackground(enabled: Boolean) {
        prefs.edit().putBoolean("bg_enabled", enabled).apply()
        ui = ui.copy(backgroundEnabled = enabled)
        SnapshotWorker.schedule(getApplication(), enabled)
    }

    fun clearSnapshots() {
        energyLog.clear()
        recomputeBattery()
    }

    // ------------------------------------------------------------------ Fernsteuerung

    fun requestSms() = viewModelScope.launch {
        ui = ui.copy(remoteBusy = true, remoteInfo = null)
        try {
            remote.requestSms()
            ui = ui.copy(smsRequested = true, remoteInfo = "SMS wurde angefordert. Bitte Code und PIN eingeben.")
        } catch (e: Exception) {
            ui = ui.copy(remoteInfo = "SMS-Anforderung fehlgeschlagen: ${e.message}")
        } finally {
            ui = ui.copy(remoteBusy = false)
        }
    }

    fun activateRemote(sms: String, pin: String) = viewModelScope.launch {
        ui = ui.copy(remoteBusy = true, remoteInfo = null)
        try {
            remote.activate(sms, pin)
            ui = ui.copy(remoteActivated = true, smsRequested = false, remoteInfo = "Fernsteuerung ist eingerichtet.")
        } catch (e: Exception) {
            ui = ui.copy(remoteInfo = "Aktivierung fehlgeschlagen: ${e.message}")
        } finally {
            ui = ui.copy(remoteBusy = false)
        }
    }

    fun resetRemote() {
        remote.reset()
        ui = ui.copy(remoteActivated = false, smsRequested = false, commands = emptyList(), remoteInfo = null)
    }

    fun lock() = command("Verriegeln", "/Doors", JSONObject().put("action", "lock"))
    fun unlock() = command("Entriegeln", "/Doors", JSONObject().put("action", "unlock"))
    fun horn() = command("Hupe", "/Horn", JSONObject().put("nb_horn", "2").put("action", "activate"))
    fun lights() = command("Lichtsignal", "/Lights", JSONObject().put("duration", "10").put("action", "activate"))

    fun preconditioning(on: Boolean) = command(
        if (on) "Vorklimatisierung starten" else "Vorklimatisierung stoppen",
        "/ThermalPrecond",
        JSONObject()
            .put("asap", if (on) "activate" else "deactivate")
            .put("programs", RemoteControl.preconditioningPrograms(currentStatusJson())),
    )

    /** Startzeit für verzögertes Laden aus dem Status (sonst 22:00). */
    val chargeStartTime: Pair<Int, Int>
        get() = RemoteControl.ptTime(ui.status?.charging?.nextDelayedTime) ?: (22 to 0)

    fun charging(start: Boolean) {
        val (h, m) = chargeStartTime
        command(
            if (start) "Laden starten" else "Laden pausieren",
            "/VehCharge",
            JSONObject()
                .put("program", JSONObject().put("hour", h).put("minute", m))
                .put("type", if (start) "immediate" else "delayed"),
        )
    }

    fun wakeUp() {
        val now = System.currentTimeMillis()
        while (wakeups.isNotEmpty() && now - wakeups.first() > 20 * 60_000) wakeups.removeFirst()
        if (wakeups.size >= 6) {
            ui = ui.copy(error = "Maximal 6 Weckbefehle in 20 Minuten – bitte etwas warten.")
            return
        }
        wakeups.addLast(now)
        command("Fahrzeug aufwecken / Status anfordern", "/VehCharge/state", JSONObject().put("action", "state"))
    }

    private fun currentStatusJson(): JSONObject? = prefs.getString("cache_status", null)?.let { runCatching { JSONObject(it) }.getOrNull() }

    private fun command(name: String, service: String, params: JSONObject) {
        val vin = ui.vehicle?.vin ?: run {
            ui = ui.copy(error = "Kein Fahrzeug geladen.")
            return
        }
        viewModelScope.launch {
            ui = ui.copy(remoteBusy = true)
            try {
                val id = remote.send(vin, service, params)
                ui = ui.copy(commands = listOf(CommandEntry(id, name, Instant.now(), "Gesendet …", false, false)) + ui.commands.take(19))
            } catch (e: AuthExpiredException) {
                ui = ui.copy(loggedIn = false, error = e.message)
            } catch (e: Exception) {
                ui = ui.copy(
                    commands = listOf(CommandEntry("err-${System.nanoTime()}", name, Instant.now(), "Nicht gesendet: ${e.message}", true, false)) + ui.commands.take(19)
                )
            } finally {
                ui = ui.copy(remoteBusy = false)
            }
        }
    }

    // ------------------------------------------------------------------ Abmelden

    fun logout() {
        remote.reset()
        auth.logout()
        SnapshotWorker.schedule(getApplication(), false)
        CookieManager.getInstance().removeAllCookies(null)
        prefs.edit().remove("cache_status").remove("cache_vehicles").remove("cache_maint").apply()
        ui = UiState(loggedIn = false, trips = ui.trips, capacityKwh = ui.capacityKwh, backgroundEnabled = ui.backgroundEnabled)
    }

    override fun onCleared() {
        remote.disconnect()
    }
}
