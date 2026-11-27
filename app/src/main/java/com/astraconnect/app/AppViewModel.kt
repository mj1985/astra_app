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
import com.astraconnect.app.data.AppSettings
import com.astraconnect.app.data.Backup
import com.astraconnect.app.data.CategoryRule
import com.astraconnect.app.data.ChargeLimit
import com.astraconnect.app.data.ChargeLimitPrefs
import com.astraconnect.app.data.ChargeEntry
import com.astraconnect.app.data.ChargeRow
import com.astraconnect.app.data.CostModel
import com.astraconnect.app.data.FuelEntry
import com.astraconnect.app.data.FuelRow
import com.astraconnect.app.data.Garage
import com.astraconnect.app.data.EnergySnapshot
import com.astraconnect.app.data.History
import com.astraconnect.app.data.Notifier
import com.astraconnect.app.data.NotifyPrefs
import com.astraconnect.app.data.Place
import com.astraconnect.app.data.PlaceBook
import com.astraconnect.app.data.Prices
import com.astraconnect.app.widget.Widgets
import com.astraconnect.app.data.AuthExpiredException
import com.astraconnect.app.data.AuthRepository
import com.astraconnect.app.data.BatteryUse
import com.astraconnect.app.data.EnergyLog
import com.astraconnect.app.data.LatLon
import com.astraconnect.app.data.NavTarget
import com.astraconnect.app.data.Powertrain
import com.astraconnect.app.data.VehicleProfile
import com.astraconnect.app.data.RemoteCommand
import com.astraconnect.app.data.RemoteControl
import com.astraconnect.app.data.TripRoute
import com.astraconnect.app.data.SecureStore
import com.astraconnect.app.data.SnapshotWorker
import com.astraconnect.app.data.StellantisApi
import com.astraconnect.app.data.Stores
import com.astraconnect.app.data.TripFollow
import com.astraconnect.app.data.TrackPoint
import com.astraconnect.app.data.Trip
import com.astraconnect.app.data.TripCategory
import com.astraconnect.app.data.TripStore
import com.astraconnect.app.data.Vehicle
import com.astraconnect.app.data.VehicleSettings
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
import com.astraconnect.app.data.MaintenanceInfo
import java.time.Instant
import java.time.LocalDate
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
    /** Wird erhöht, wenn neue Standorte aufgezeichnet wurden (Karten neu zeichnen). */
    val routeVersion: Int = 0,
    // Akku-Verbrauch
    val battery: Map<String, BatteryUse> = emptyMap(),
    /** Antriebsart, Akku- und Tankgröße. */
    val profile: VehicleProfile = VehicleProfile.resolve(null, null, null, null, null),
    val backgroundEnabled: Boolean = true,
    val snapshotCount: Int = 0,
    val lastSnapshot: Instant? = null,
    // Fernsteuerung
    val remoteActivated: Boolean = false,
    val smsRequested: Boolean = false,
    val remoteBusy: Boolean = false,
    val remoteInfo: String? = null,
    val commands: List<CommandEntry> = emptyList(),
    // Navigationsziel
    val navSearching: Boolean = false,
    val navSending: Boolean = false,
    val navResults: List<NavTarget> = emptyList(),
    val navInfo: String? = null,
    // Orte & Regeln
    val places: List<Place> = emptyList(),
    val rules: List<CategoryRule> = emptyList(),
    // Kosten, Tank, Benachrichtigungen, Sicherung
    val prices: Prices = Prices(1.75, 0.35, 10.0),
    val notify: NotifyPrefs = NotifyPrefs(),
    val backupFolder: String? = null,
    val lastBackup: Long = 0L,
    val backupInfo: String? = null,
    /** Ladestand-Messpunkte (für Diagramme und Ladehistorie). */
    val snapshots: List<EnergySnapshot> = emptyList(),
    // Tank- und Ladekosten
    val fuelEntries: List<FuelEntry> = emptyList(),
    val chargeEntries: List<ChargeEntry> = emptyList(),
    /** Leiste unten (Ziel-Namen), null = Standard. */
    val bottomBar: List<String>? = null,
    /** Ladelimit der App. */
    val chargeLimit: ChargeLimitPrefs = ChargeLimitPrefs(false, ChargeLimitPrefs.DEFAULT_PCT),
    /** Selbst eingetragener Wartungstermin des Fahrzeugs (Vorrang vor der API). */
    val maintDueDate: LocalDate? = null,
    val maintDueMileage: Double? = null,
) {
    val vehicle: Vehicle? get() = vehicles.firstOrNull { it.id == selectedId } ?: vehicles.firstOrNull()
    val commandRunning: Boolean get() = commands.any { !it.final && Instant.now().epochSecond - it.sentAt.epochSecond < 180 }
    val powertrain: Powertrain get() = profile.powertrain
    /** Eigener Name aus dem Opel-Konto, sonst das Modell aus der Liste. */
    val vehicleName: String
        get() = Garage.displayName(vehicle, profile.model)
    val capacityKwh: Double get() = profile.capacityKwh
    val tankLiters: Double get() = profile.tankLiters

    /** Wartung aus API und eigenem Termin zusammengeführt (eigener Termin hat Vorrang). */
    val maintenance: MaintenanceInfo
        get() = MaintenanceInfo.resolve(
            status?.maintenanceDays, status?.maintenanceKm, status?.mileageKm, maintDueDate, maintDueMileage,
        )
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
    private val remote = RemoteControl(store, auth, http)
    private val placeBook = Stores.places(app)
    private val settings = AppSettings(app)
    private val backup = Backup(app)
    private val wakeups = ArrayDeque<Long>()

    // Daten des gewählten Fahrzeugs – folgen beim Umschalten automatisch (Quelle: „vehicle_id“, siehe Garage)
    private val vid: String get() = Garage.selectedId(getApplication())
    private val tripStore get() = Stores.trips(getApplication(), vid)
    private val energyLog get() = Stores.energy(getApplication(), vid)
    private val costBook get() = Stores.costs(getApplication(), vid)
    private val trackLog get() = Stores.track(getApplication(), vid)
    private val vehicleSettings get() = VehicleSettings(getApplication(), vid)

    var ui by mutableStateOf(
        UiState(
            loggedIn = auth.isLoggedIn,
            profile = VehicleProfile.load(app, Garage.selectedId(app)),
            backgroundEnabled = prefs.getBoolean("bg_enabled", true),
            remoteActivated = remote.isActivated,
            places = placeBook.places(),
            rules = placeBook.rules(),
            prices = settings.prices,
            notify = settings.notify,
            backupFolder = settings.backupTreeUri,
            lastBackup = settings.lastBackupAt,
            bottomBar = settings.bottomBar,
        )
    )
        private set

    init {
        Notifier.ensureChannels(app)
        restoreCache()
        ui = loadVehicleData(ui)
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

    fun saveClientConfig(country: String, clientId: String, clientSecret: String) {
        auth.country = country
        auth.clientId = clientId
        auth.clientSecret = clientSecret
        // Neue Landeswährung: nicht selbst gesetzte Standardpreise neu bestimmen
        ui = ui.copy(prices = settings.prices)
    }

    fun onAuthCode(code: String) = viewModelScope.launch {
        ui = ui.copy(loggingIn = true, error = null)
        try {
            auth.exchangeCode(code)
            Notifier.loginRestored(getApplication())
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
            val app = getApplication<Application>()
            val vehiclesJson = api.vehiclesRaw()
            val vehicles = vehiclesJson.obj("_embedded").arr("vehicles").objects().map(Vehicle::parse)
            val wanted = vid
            val selected = vehicles.firstOrNull { it.id == wanted } ?: vehicles.firstOrNull()
                ?: throw IllegalStateException("Kein Fahrzeug in deinem Opel-Konto gefunden.")
            prefs.edit().putString("cache_vehicles", vehiclesJson.toString()).apply()
            if (selected.id != wanted) {
                // Erstes Mal bzw. Fahrzeug nicht mehr im Konto: Daten ohne Fahrzeug übernehmen und anzeigen
                Garage.select(app, selected.id)
                withContext(Dispatchers.IO) { Garage.adopt(app, selected.id) }
                ui = loadVehicleData(ui.copy(selectedId = selected.id))
            }
            ui = ui.copy(vehicles = vehicles, selectedId = selected.id, rawVehicles = vehiclesJson.toString(2))

            val statusJson = api.status(selected.id)
            val maintenance = api.maintenance(selected.id)
            val status = VehicleStatus.parse(statusJson, maintenance)
            // Bewusst für selected.id gespeichert – auch wenn inzwischen umgeschaltet wurde
            val newPoint = withContext(Dispatchers.IO) {
                Stores.energy(app, selected.id).record(status)
                Stores.track(app, selected.id).record(status)
            }
            TripFollow.onStatus(app, status)
            VehicleSettings(app, selected.id).cacheStatus(statusJson, maintenance)
            Notifier.check(app, selected.id, status)
            // Ladelimit prüfen, ohne die Anzeige aufzuhalten (kann bis zu 2 Min. auf das Auto warten)
            viewModelScope.launch(Dispatchers.IO) { ChargeLimit.check(app, selected.id, status) }
            // Während des Abrufs auf ein anderes Fahrzeug umgeschaltet → Anzeige nicht überschreiben
            if (vid != selected.id) return@launch
            ui = ui.copy(
                status = status,
                profile = resolveProfile(selected, status),
                rawStatus = statusJson.toString(2),
                rawMaintenance = maintenance?.toString(2),
                trips = tripStore.load(),
                routeVersion = if (newPoint) ui.routeVersion + 1 else ui.routeVersion,
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

    /** Fahrzeugliste aus dem Zwischenspeicher (Status kommt mit [loadVehicleData]). */
    private fun restoreCache() {
        runCatching {
            val v = prefs.getString("cache_vehicles", null)?.let(::JSONObject) ?: return
            ui = ui.copy(
                vehicles = v.obj("_embedded").arr("vehicles").objects().map(Vehicle::parse),
                selectedId = Garage.selectedId(getApplication()).takeIf { it != Garage.UNASSIGNED },
                rawVehicles = v.toString(2),
            )
        }
    }

    /** Alles, was zum gewählten Fahrzeug gehört, aus dessen Ablage in [base] laden. */
    private fun loadVehicleData(base: UiState): UiState {
        val vs = vehicleSettings
        val s = vs.cachedStatus
        val m = vs.cachedMaintenance
        val withStatus = base.copy(
            status = s?.let { runCatching { VehicleStatus.parse(it, m) }.getOrNull() },
            rawStatus = s?.toString(2),
            rawMaintenance = m?.toString(2),
            trips = tripStore.load(),
            fuelEntries = costBook.fuel(),
            chargeEntries = costBook.charges(),
            chargeLimit = vs.chargeLimit,
            maintDueDate = vs.maintenanceDueDate,
            maintDueMileage = vs.maintenanceDueMileage,
            routeVersion = base.routeVersion + 1,
        )
        return withStatus.copy(profile = resolveProfile(withStatus.vehicle, withStatus.status))
    }

    /**
     * Auf ein anderes Fahrzeug des Kontos umschalten: dessen gespeicherte Daten sofort zeigen,
     * dann frisch abrufen. Widgets, die dem gewählten Fahrzeug folgen, ziehen mit.
     */
    fun selectVehicle(id: String) {
        if (id == vid || ui.vehicles.none { it.id == id }) return
        Garage.select(getApplication(), id)
        ui = loadVehicleData(
            ui.copy(selectedId = id, commands = emptyList(), tripsInfo = null, navResults = emptyList(), navInfo = null),
        )
        recomputeBattery()
        if (ui.loggedIn) refresh()
    }

    // ------------------------------------------------------------------ Fahrtenbuch

    fun syncTrips(full: Boolean = false) = viewModelScope.launch {
        val vehicleId = ui.vehicle?.id ?: run {
            ui = ui.copy(tripsInfo = "Bitte zuerst die Fahrzeugdaten laden.")
            return@launch
        }
        ui = ui.copy(tripsSyncing = true, tripsInfo = null)
        // Fest an dieses Fahrzeug gebunden – auch wenn während des Abrufs umgeschaltet wird
        val store = Stores.trips(getApplication(), vehicleId)
        try {
            val since = if (full) null else store.newestStart()?.minusSeconds(3600)
            val raw = api.trips(vehicleId, since)
            val added = withContext(Dispatchers.IO) { store.merge(raw) }
            val auto = applyRulesQuietly(store)
            if (vid != vehicleId) return@launch
            ui = ui.copy(
                trips = store.load(),
                tripsInfo = (if (added == 0) "Keine neuen Fahrten." else "$added neue Fahrt(en) übernommen.") +
                    if (auto > 0) " $auto automatisch zugeordnet." else ""
            )
            recomputeBattery()
            resolveAddresses(store)
        } catch (e: AuthExpiredException) {
            ui = ui.copy(loggedIn = false, error = e.message)
        } catch (e: Exception) {
            ui = ui.copy(tripsInfo = "Fahrten konnten nicht geladen werden: ${e.message}")
        } finally {
            ui = ui.copy(tripsSyncing = false)
        }
    }

    private suspend fun resolveAddresses(store: TripStore) = withContext(Dispatchers.IO) {
        if (!Geocoder.isPresent()) return@withContext
        val geocoder = Geocoder(getApplication(), Locale.GERMANY)
        val todo = store.loadSingles().filter {
            (it.startAddress == null && it.startPos != null) || (it.endAddress == null && it.endPos != null)
        }.take(40)
        for (t in todo) {
            val s = if (t.startAddress == null) t.startPos?.let { address(geocoder, it) } else null
            val e = if (t.endAddress == null) t.endPos?.let { address(geocoder, it) } else null
            if (s != null || e != null) store.update(t.key, startAddress = s, endAddress = e)
        }
        val trips = store.load()
        withContext(Dispatchers.Main) { if (store === tripStore) ui = ui.copy(trips = trips) }
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
        val store = tripStore
        try {
            val result = withContext(Dispatchers.IO) {
                val text = getApplication<Application>().contentResolver.openInputStream(uri)
                    ?.use { it.readBytes().toString(Charsets.UTF_8) }
                    ?: throw IllegalStateException("Datei konnte nicht geöffnet werden.")
                store.importMyop(text, ui.vehicle?.vin)
            }
            val auto = applyRulesQuietly(store)
            if (store !== tripStore) return@launch
            ui = ui.copy(
                trips = store.load(),
                tripsInfo = "myOpel-Import: ${result.summary}" + if (auto > 0) " $auto automatisch zugeordnet." else "",
            )
            recomputeBattery()
            resolveAddresses(store)
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
        val file = tripStore.exportCsv(trips, ui.battery, cost = ::tripCost, placeName = ::placeName)
        val ctx = getApplication<Application>()
        return FileProvider.getUriForFile(ctx, ctx.packageName + ".fileprovider", file)
    }

    /** Hängt eine Fahrt an die vorherige an (z. B. Umparken oder kurzer Zwischenstopp). */
    fun mergeTripWithPrevious(key: String): Boolean {
        val merged = tripStore.mergeWithPrevious(key) ?: run {
            ui = ui.copy(tripsInfo = "Keine vorherige Fahrt zum Zusammenführen gefunden.")
            return false
        }
        ui = ui.copy(trips = tripStore.load(), tripsInfo = "Fahrten zusammengeführt.")
        recomputeBattery()
        return merged.isNotEmpty()
    }

    fun splitTrip(key: String) {
        tripStore.split(key)
        ui = ui.copy(trips = tripStore.load(), tripsInfo = "Zusammenführung aufgehoben.")
        recomputeBattery()
    }

    fun tripCost(t: Trip): Double? = costs.tripCost(t, ui.battery[t.key])

    // ------------------------------------------------------------------ Tank- und Ladekosten

    private var costCache: Pair<List<Any?>, Triple<List<ChargeRow>, List<FuelRow>, CostModel>>? = null

    /** Ladeübersicht, Tankbuch und Preismodell – neu berechnet, sobald sich eine Grundlage ändert. */
    private fun costData(): Triple<List<ChargeRow>, List<FuelRow>, CostModel> {
        val u = ui
        val key = listOf(u.trips, u.snapshots, u.places, u.prices, u.capacityKwh, u.tankLiters, u.fuelEntries, u.chargeEntries)
        costCache?.let { (k, v) -> if (k.size == key.size && k.indices.all { k[it] === key[it] || k[it] == key[it] && k[it] !is List<*> }) return v }
        val sessions = History.chargeSessions(u.snapshots, u.capacityKwh)
        val charge = CostModel.chargeRows(sessions, u.chargeEntries, u.trips, u.places, u.prices)
        val fuel = CostModel.fuelRows(History.refuels(u.trips, u.snapshots, u.tankLiters), u.fuelEntries)
        val model = CostModel(u.prices, u.fuelEntries, charge)
        return Triple(charge, fuel, model).also { costCache = key to it }
    }

    val chargeRows: List<ChargeRow> get() = costData().first
    val fuelRows: List<FuelRow> get() = costData().second
    val costs: CostModel get() = costData().third

    /** Tankpreis zu einem Tankvorgang eintragen oder ändern. */
    fun saveFuelEntry(e: FuelEntry) {
        costBook.saveFuel(e)
        ui = ui.copy(fuelEntries = costBook.fuel())
    }

    fun saveChargeEntry(e: ChargeEntry) {
        costBook.saveCharge(e)
        ui = ui.copy(chargeEntries = costBook.charges())
    }

    fun deleteCostEntry(id: String) {
        costBook.delete(id)
        ui = ui.copy(fuelEntries = costBook.fuel(), chargeEntries = costBook.charges())
    }

    fun setBottomBar(items: List<String>?) {
        settings.bottomBar = items
        ui = ui.copy(bottomBar = items)
    }

    // ------------------------------------------------------------------ Orte & Regeln

    fun placeName(p: LatLon?): String? = PlaceBook.placeAt(ui.places, p)?.name

    fun addPlace(name: String, pos: LatLon, radiusM: Double = 200.0, powerPrice: Double? = null) {
        if (name.isBlank()) return
        placeBook.addPlace(name, pos, radiusM, powerPrice)
        reloadPlaces()
    }

    fun updatePlace(p: Place) {
        placeBook.savePlace(p)
        reloadPlaces()
    }

    fun deletePlace(id: String) {
        placeBook.deletePlace(id)
        reloadPlaces()
    }

    fun addRule(fromId: String?, toId: String?, category: TripCategory, bothWays: Boolean) {
        if (fromId == null && toId == null) return
        placeBook.addRule(fromId, toId, category, bothWays)
        reloadPlaces()
    }

    fun deleteRule(id: String) {
        placeBook.deleteRule(id)
        reloadPlaces()
    }

    /** Wendet die Regeln auf alle noch nicht zugeordneten Fahrten an. */
    fun applyRulesNow(): Int {
        val n = applyRulesQuietly()
        ui = ui.copy(trips = tripStore.load(), tripsInfo = if (n == 0) "Keine weiteren Fahrten passen zu den Regeln." else "$n Fahrt(en) automatisch zugeordnet.")
        return n
    }

    private fun applyRulesQuietly(store: TripStore = tripStore): Int {
        val places = ui.places
        val rules = ui.rules
        if (rules.isEmpty()) return 0
        return store.applyRules { PlaceBook.categoryFor(it, places, rules) }
    }

    private fun reloadPlaces() {
        ui = ui.copy(places = placeBook.places(), rules = placeBook.rules())
    }

    // ------------------------------------------------------------------ Kosten & Benachrichtigungen

    fun setPrices(p: Prices) {
        settings.prices = p
        ui = ui.copy(prices = p)
    }

    /** null = wieder aus Modellliste bzw. Standard. Gilt für das gewählte Fahrzeug. */
    fun setTankLiters(l: Double?) {
        if (l != null && l !in 10.0..150.0) return
        vehicleSettings.tankLitersManual = l
        updateProfile()
    }

    /** null = Antriebsart wieder automatisch erkennen. Gilt für das gewählte Fahrzeug. */
    fun setPowertrain(p: Powertrain?) {
        vehicleSettings.powertrainManual = p
        updateProfile()
    }

    /**
     * Selbst eingetragener Wartungstermin des gewählten Fahrzeugs (hat Vorrang vor der API).
     * [dueMileage] ist der absolute Kilometerstand bei Fälligkeit; je null = Teil nicht gesetzt.
     */
    fun setMaintenance(dueDate: LocalDate?, dueMileage: Double?) {
        val vs = vehicleSettings
        vs.maintenanceDueDate = dueDate
        vs.maintenanceDueMileage = dueMileage?.takeIf { it > 0 }
        ui = ui.copy(maintDueDate = vs.maintenanceDueDate, maintDueMileage = vs.maintenanceDueMileage)
    }

    private fun resolveProfile(vehicle: Vehicle? = ui.vehicle, status: VehicleStatus? = ui.status): VehicleProfile {
        val vs = vehicleSettings
        return VehicleProfile.resolve(vs.powertrainManual, vs.capacityKwhManual, vs.tankLitersManual, vehicle?.vin, status)
    }

    /** Profil neu bestimmen; bei geänderter Kapazität Verbräuche neu rechnen, Widgets nachziehen. */
    private fun updateProfile() {
        val before = ui.profile
        ui = ui.copy(profile = resolveProfile())
        if (ui.profile.capacityKwh != before.capacityKwh || ui.profile.tankLiters != before.tankLiters) recomputeBattery()
        else Widgets.updateAll(getApplication())
    }

    /** Gemerkter Zustand des Ladelimits des gewählten Fahrzeugs (u. a. verlegte Startzeit). */
    fun chargeLimitState() = ChargeLimit.loadState(vehicleSettings.prefs)

    fun setChargeLimit(p: ChargeLimitPrefs) {
        val v = p.copy(percent = p.percent.coerceIn(ChargeLimitPrefs.MIN_PCT, ChargeLimitPrefs.MAX_PCT))
        vehicleSettings.chargeLimit = v
        ui = ui.copy(chargeLimit = v)
        // Gleich prüfen – vielleicht ist das neue Limit schon erreicht
        val id = vid
        ui.status?.let { s -> viewModelScope.launch(Dispatchers.IO) { ChargeLimit.check(getApplication(), id, s) } }
    }

    fun setNotify(n: NotifyPrefs) {
        settings.notify = n
        ui = ui.copy(notify = n)
    }

    // ------------------------------------------------------------------ Datensicherung

    fun exportBackup(uri: Uri) = viewModelScope.launch {
        ui = ui.copy(backupInfo = runCatching {
            withContext(Dispatchers.IO) { backup.writeTo(uri) }
            "Sicherung gespeichert."
        }.getOrElse { "Sicherung fehlgeschlagen: ${it.message}" })
    }

    fun importBackup(uri: Uri) = viewModelScope.launch {
        ui = ui.copy(backupInfo = runCatching {
            val r = withContext(Dispatchers.IO) { backup.readFrom(uri) }
            ui = loadVehicleData(ui.copy(prices = settings.prices, bottomBar = settings.bottomBar))
            reloadPlaces()
            recomputeBattery()
            "Wiederhergestellt: ${r.summary}"
        }.getOrElse { "Wiederherstellen fehlgeschlagen: ${it.message}" })
    }

    /** Ordner für die automatische tägliche Sicherung (null = ausschalten). */
    fun setBackupFolder(uri: Uri?) {
        val cr = getApplication<Application>().contentResolver
        val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        settings.backupTreeUri?.let { old -> runCatching { cr.releasePersistableUriPermission(Uri.parse(old), flags) } }
        if (uri != null) runCatching { cr.takePersistableUriPermission(uri, flags) }
        settings.backupTreeUri = uri?.toString()
        ui = ui.copy(backupFolder = uri?.toString())
        if (uri != null) backupNow()
    }

    fun backupNow() = viewModelScope.launch {
        val ok = runCatching { withContext(Dispatchers.IO) { backup.autoBackup(force = true) } }
        ui = ui.copy(
            lastBackup = settings.lastBackupAt,
            backupInfo = when {
                ok.isFailure -> "Sicherung fehlgeschlagen: ${ok.exceptionOrNull()?.message}"
                ok.getOrNull() == true -> "In den gewählten Ordner gesichert."
                else -> "Kein Zugriff auf den Sicherungsordner – bitte neu auswählen."
            },
        )
    }

    // ------------------------------------------------------------------ Akku-Verbrauch

    private fun recomputeBattery() {
        val snaps = energyLog.all()
        ui = ui.copy(
            battery = EnergyLog.compute(ui.trips, snaps, ui.capacityKwh),
            snapshotCount = snaps.size,
            lastSnapshot = snaps.lastOrNull()?.at,
            snapshots = snaps,
        )
        // Fahrten, Kapazität oder Status haben sich geändert → Widgets (u. a. „Letzte Fahrt“) nachziehen
        Widgets.updateAll(getApplication())
    }

    /** null = wieder aus Modellliste bzw. Standard. Gilt für das gewählte Fahrzeug. */
    fun setCapacity(kwh: Double?) {
        if (kwh != null && kwh !in 1.0..150.0) return
        vehicleSettings.capacityKwhManual = kwh
        updateProfile()
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

    fun lock() = command("Verriegeln", RemoteCommand.lock())
    fun unlock() = command("Entriegeln", RemoteCommand.unlock())
    fun horn() = command("Hupe", RemoteCommand.horn())
    fun lights() = command("Lichtsignal", RemoteCommand.lights())

    fun preconditioning(on: Boolean) = command(
        if (on) "Vorklimatisierung starten" else "Vorklimatisierung stoppen",
        RemoteCommand.preconditioning(on, currentStatusJson()),
    )

    private fun command(name: String, c: RemoteCommand) = command(name, c.service, c.params)

    /** Startzeit für verzögertes Laden aus dem Status (sonst 22:00). */
    val chargeStartTime: Pair<Int, Int>
        get() = RemoteControl.ptTime(ui.status?.charging?.nextDelayedTime) ?: (22 to 0)

    fun charging(start: Boolean) = command(
        if (start) "Laden starten" else "Laden pausieren",
        RemoteCommand.charging(start, ui.status?.charging?.nextDelayedTime),
    )

    fun wakeUp() {
        val now = System.currentTimeMillis()
        while (wakeups.isNotEmpty() && now - wakeups.first() > 20 * 60_000) wakeups.removeFirst()
        if (wakeups.size >= 6) {
            ui = ui.copy(error = "Maximal 6 Weckbefehle in 20 Minuten – bitte etwas warten.")
            return
        }
        wakeups.addLast(now)
        command("Fahrzeug aufwecken / Status anfordern", RemoteCommand.wakeUp())
    }

    private fun currentStatusJson(): JSONObject? = vehicleSettings.cachedStatus

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

    // ------------------------------------------------------------------ Streckenverlauf

    /**
     * Grober Streckenverlauf aus den während der Fahrt aufgezeichneten Standorten; null = keine Punkte.
     * [UiState.routeVersion] lesen, damit Karten nach neuen Punkten neu gezeichnet werden.
     */
    fun routeFor(t: Trip): TripRoute? {
        val s = t.startedAt ?: return null
        val e = t.stoppedAt ?: return null
        return TripRoute.fromTrack(t, trackLog.between(s.minus(TripRoute.MARGIN), e.plus(TripRoute.MARGIN)))
    }

    /** Die einzelnen aufgezeichneten Wegpunkte einer Fahrt (mit Uhrzeit), für die Anzeige als Punkte. */
    fun waypointsFor(t: Trip): List<TrackPoint> {
        val s = t.startedAt ?: return emptyList()
        val e = t.stoppedAt ?: return emptyList()
        return TripRoute.pointsOf(t, trackLog.between(s.minus(TripRoute.MARGIN), e.plus(TripRoute.MARGIN)))
    }

    // ------------------------------------------------------------------ Navigationsziel

    /** Sucht Adressen/Orte für ein Navigationsziel (Android-Geocoder). */
    fun searchDestination(query: String) = viewModelScope.launch {
        val q = query.trim()
        if (q.isBlank()) return@launch
        ui = ui.copy(navSearching = true, navResults = emptyList(), navInfo = null)
        val results = withContext(Dispatchers.IO) { geocode(q) }
        ui = ui.copy(
            navSearching = false,
            navResults = results,
            navInfo = when {
                !Geocoder.isPresent() -> "Adresssuche ist auf diesem Gerät nicht verfügbar."
                results.isEmpty() -> "Nichts gefunden für „$q“."
                else -> null
            },
        )
    }

    @Suppress("DEPRECATION")
    private fun geocode(q: String): List<NavTarget> {
        if (!Geocoder.isPresent()) return emptyList()
        return runCatching {
            Geocoder(getApplication(), Locale.getDefault()).getFromLocationName(q, 6).orEmpty().map { a ->
                val name = (0..a.maxAddressLineIndex).mapNotNull { a.getAddressLine(it) }.joinToString(", ")
                    .ifBlank { listOfNotNull(a.featureName, a.locality).joinToString(", ") }
                    .ifBlank { "%.5f, %.5f".format(Locale.ROOT, a.latitude, a.longitude) }
                NavTarget(name, a.latitude, a.longitude)
            }
        }.getOrDefault(emptyList())
    }

    fun clearDestinationSearch() {
        ui = ui.copy(navResults = emptyList(), navInfo = null)
    }

    /** Sendet das Ziel über die Web-API an das Navi des Autos und verfolgt den Status. */
    fun sendNavigation(target: NavTarget) {
        val vehicleId = ui.vehicle?.id ?: run {
            ui = ui.copy(error = "Kein Fahrzeug geladen.")
            return
        }
        val name = "Navigation: ${target.name}"
        viewModelScope.launch {
            ui = ui.copy(navSending = true)
            val localId = "nav-${System.nanoTime()}"
            fun update(status: String, final: Boolean, success: Boolean) {
                val entry = CommandEntry(localId, name, Instant.now(), status, final, success)
                ui = ui.copy(
                    commands = if (ui.commands.any { it.id == localId })
                        ui.commands.map { if (it.id == localId) entry.copy(sentAt = it.sentAt) else it }
                    else listOf(entry) + ui.commands.take(19)
                )
            }
            try {
                if (api.navigationSupported(vehicleId) == false) {
                    update("Nicht gesendet: Laut Opel unterstützt dieses Fahrzeug das Senden von Zielen nicht.", true, false)
                    return@launch
                }
                val (cbid, rid) = api.sendNavigation(vehicleId, listOf(target.pos))
                update("Gesendet …", false, false)
                ui = ui.copy(navSending = false)
                // Ergebnis abfragen: bis zu 3 Minuten alle 5 Sekunden.
                repeat(36) {
                    delay(5_000)
                    val s = runCatching { api.navigationState(vehicleId, cbid, rid) }.getOrNull() ?: return@repeat
                    update(s.label, s.final, s.success)
                    if (s.final) return@launch
                }
                update("Keine Rückmeldung vom Auto. Ist es mit dem Netz verbunden?", true, false)
            } catch (e: AuthExpiredException) {
                ui = ui.copy(loggedIn = false, error = e.message)
            } catch (e: Exception) {
                update("Nicht gesendet: ${e.message}", true, false)
            } finally {
                ui = ui.copy(navSending = false)
            }
        }
    }

    // ------------------------------------------------------------------ Abmelden

    fun logout() {
        remote.reset()
        auth.logout()
        SnapshotWorker.schedule(getApplication(), false)
        CookieManager.getInstance().removeAllCookies(null)
        val app = getApplication<Application>()
        (Garage.vehicles(app).map { it.id } + vid).distinct().forEach { VehicleSettings(app, it).clearCache() }
        prefs.edit().remove("cache_vehicles").apply()
        ui = ui.copy(
            loggedIn = false, loading = false, vehicles = emptyList(), selectedId = null, status = null,
            rawStatus = null, rawMaintenance = null, rawVehicles = null, remoteActivated = false,
            smsRequested = false, commands = emptyList(), remoteInfo = null,
        )
        Widgets.updateAll(getApplication())
    }

    override fun onCleared() {
        remote.disconnect()
    }
}
