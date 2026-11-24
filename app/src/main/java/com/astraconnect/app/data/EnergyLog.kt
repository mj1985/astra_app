package com.astraconnect.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** Ein Messpunkt: Stand, den das Auto zu einem Zeitpunkt an den Server gemeldet hat. */
data class EnergySnapshot(
    val at: Instant,
    val mileage: Double?,
    val soc: Double?,
    val charging: Boolean,
    /** Tankstand in % (ab dieser Version mit aufgezeichnet – für das Tankbuch). */
    val fuel: Double? = null,
)

/** Berechneter Akku-Verbrauch einer Fahrt. */
data class BatteryUse(
    val socStart: Double?,
    val socEnd: Double?,
    val kwh: Double?,
    /** Mehrere Fahrten lagen zwischen denselben Messpunkten → nach Strecke aufgeteilt. */
    val shared: Boolean,
    /** Erklärung, falls nicht berechenbar (oder wie der Wert zustande kam). */
    val note: String?,
    /** Wert ist eine Näherung (z. B. Ladebeginn direkt nach der Fahrt). */
    val approx: Boolean = false,
) {
    /** Mit „≈“ anzeigen. */
    val estimated: Boolean get() = shared || approx

    fun per100km(distanceKm: Double?): Double? =
        if (kwh != null && distanceKm != null && distanceKm > 0.5) kwh / distanceKm * 100 else null
}

/**
 * Speichert Ladestand-Messpunkte (bei jedem Abruf und im Hintergrund alle 15 Min.).
 * Daraus wird der Akku-Verbrauch pro Fahrt berechnet: Ladestand vor der Fahrt minus
 * Ladestand nach der Fahrt × nutzbare Akkukapazität.
 */
class EnergyLog(private val file: File) {

    @Synchronized
    fun all(): List<EnergySnapshot> {
        if (!file.exists()) return emptyList()
        val arr = runCatching { JSONArray(file.readText()) }.getOrElse { return emptyList() }
        return parse(arr)
    }

    private fun parse(arr: JSONArray) = arr.objects().mapNotNull { o ->
        val at = parseInstant(o.str("at")) ?: return@mapNotNull null
        EnergySnapshot(at, o.num("km"), o.num("soc"), o.optBoolean("ch"), o.num("fu"))
    }

    /** Fügt einen Messpunkt hinzu (doppelte Zeitstempel werden ignoriert). Liefert true, wenn neu. */
    @Synchronized
    fun record(status: VehicleStatus): Boolean {
        val at = status.updatedAt ?: return false
        if (status.batteryPercent == null) return false
        val list = all().toMutableList()
        if (list.any { it.at == at }) return false
        list += EnergySnapshot(at, status.mileageKm, status.batteryPercent, status.charging?.isCharging == true, status.fuelPercent)
        save(list)
        return true
    }

    private fun save(list: List<EnergySnapshot>) {
        file.parentFile?.mkdirs()
        file.writeText(toJson(list.sortedBy { it.at }.takeLast(MAX)).toString())
    }

    private fun toJson(list: List<EnergySnapshot>): JSONArray {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(JSONObject().put("at", s.at.toString()).put("km", s.mileage ?: JSONObject.NULL)
                .put("soc", s.soc ?: JSONObject.NULL).put("ch", s.charging).put("fu", s.fuel ?: JSONObject.NULL))
        }
        return arr
    }

    /** Für die Datensicherung. */
    @Synchronized
    fun exportJson(): JSONArray = toJson(all())

    /** Messpunkte aus einer Sicherung ergänzen (gleiche Zeitstempel werden übersprungen). */
    @Synchronized
    fun restore(arr: JSONArray): Int {
        val list = all().toMutableList()
        val known = list.map { it.at }.toHashSet()
        val add = parse(arr).filter { known.add(it.at) }
        if (add.isNotEmpty()) save(list + add)
        return add.size
    }

    @Synchronized
    fun clear() {
        file.delete()
    }

    companion object {
        private const val MAX = 20_000
        private val TOL: Duration = Duration.ofMinutes(3)

        /** So lange nach Fahrtende darf die erste Meldung schon „lädt“ sein und trotzdem als Ankunft gelten. */
        private val CHARGE_START_WINDOW: Duration = Duration.ofMinutes(20)

        fun compute(trips: List<Trip>, snaps: List<EnergySnapshot>, capacityKwh: Double): Map<String, BatteryUse> {
            val sorted = snaps.sortedBy { it.at }
            val result = mutableMapOf<String, BatteryUse>()
            if (sorted.isEmpty()) return result

            data class Window(val before: EnergySnapshot, val after: EnergySnapshot)
            val windows = mutableMapOf<String, Window>()

            for (t in trips) {
                val start = t.startedAt ?: continue
                val stop = t.stoppedAt ?: continue
                val before = sorted.lastOrNull { s ->
                    !s.at.isAfter(start.plus(TOL)) &&
                        (s.mileage == null || t.startMileage == null || s.mileage <= t.startMileage + 1)
                }
                val after = before?.let { b ->
                    sorted.firstOrNull { s ->
                        s.at.isAfter(b.at) && if (s.mileage != null && t.endMileage != null) {
                            // Kilometerstand zeigt das Fahrtende → zählt, auch wenn Opels Endzeit der Fahrt
                            // später liegt als die letzte Meldung des Autos (sonst erst ab der nächsten Fahrt)
                            s.mileage >= t.endMileage - 1
                        } else {
                            !s.at.isBefore(stop.minus(TOL))
                        }
                    }
                }
                if (before == null) {
                    result[t.key] = BatteryUse(null, null, null, false,
                        "Kein Messpunkt vor dieser Fahrt (Aufzeichnung lief noch nicht).")
                    continue
                }
                if (after == null) {
                    result[t.key] = BatteryUse(before.soc, null, null, false,
                        "Noch kein Messpunkt nach dieser Fahrt – das Auto hat seinen Ladestand seitdem nicht gemeldet. " +
                            "Der Wert erscheint, sobald es sich wieder meldet.")
                    continue
                }
                windows[t.key] = Window(before, after)
            }

            // Fahrten, die sich dasselbe Messpunkt-Paar teilen, gemeinsam auswerten
            windows.entries.groupBy { it.value }.forEach { (w, entries) ->
                val socB = w.before.soc
                val socA = w.after.soc
                val between = sorted.filter { it.at.isAfter(w.before.at) && it.at.isBefore(w.after.at) }
                val group = entries.mapNotNull { e -> trips.firstOrNull { it.key == e.key } }
                val totalKm = group.sumOf { it.distanceKm ?: 0.0 }
                val shared = group.size > 1
                // Direkt nach dem Abstellen eingesteckt: Die erste Meldung danach ist schon „lädt“, zeigt aber
                // noch (fast) den Ladestand bei Ankunft → verwendbar, solange er nicht über dem Start liegt.
                val lastStop = group.mapNotNull { it.stoppedAt }.maxOrNull()
                val chargeStartedAtArrival = w.after.charging && socB != null && socA != null && socA <= socB &&
                    lastStop != null && !w.after.at.isAfter(lastStop.plus(CHARGE_START_WINDOW))
                val charged = between.any { it.charging } || (w.after.charging && !chargeStartedAtArrival) ||
                    (socB != null && socA != null && socA > socB + 0.5) ||
                    between.zipWithNext().any { (a, b) -> a.soc != null && b.soc != null && b.soc > a.soc + 0.5 }

                for (t in group) {
                    val share = if (!shared) 1.0
                    else if (totalKm > 0) (t.distanceKm ?: 0.0) / totalKm
                    else 1.0 / group.size
                    result[t.key] = when {
                        socB == null || socA == null ->
                            BatteryUse(socB, socA, null, shared, "Kein Ladestand gemeldet.")
                        charged ->
                            BatteryUse(socB, socA, null, shared, "Zwischen den Messpunkten wurde geladen – nicht berechenbar.")
                        else -> BatteryUse(
                            socB, socA, ((socB - socA) / 100.0 * capacityKwh * share).coerceAtLeast(0.0), shared,
                            if (chargeStartedAtArrival) "Direkt nach der Fahrt wurde geladen – Ende = Ladestand beim Ladebeginn." else null,
                            approx = chargeStartedAtArrival,
                        )
                    }
                }
            }
            return result
        }
    }
}

/**
 * Gemeinsame Instanzen für App, Hintergrund-Job und Widgets (gleiche Sperren auf dieselben Dateien).
 * Fahrtenbuch, Messpunkte, Standorte und Kosten gibt es je Fahrzeug ([Garage]), Orte & Regeln einmal.
 */
object Stores {
    private val trips = ConcurrentHashMap<String, TripStore>()
    private val energy = ConcurrentHashMap<String, EnergyLog>()
    private val costs = ConcurrentHashMap<String, CostBook>()
    private val track = ConcurrentHashMap<String, TrackLog>()
    @Volatile private var places: PlaceBook? = null

    private fun file(ctx: Context, vehicleId: String, name: String) = File(Garage.dir(ctx, vehicleId), name)

    fun trips(ctx: Context, vehicleId: String): TripStore = trips.computeIfAbsent(vehicleId) {
        TripStore(file(ctx, it, "fahrtenbuch.json"), File(ctx.applicationContext.cacheDir, "exports"))
    }

    fun energy(ctx: Context, vehicleId: String): EnergyLog =
        energy.computeIfAbsent(vehicleId) { EnergyLog(file(ctx, it, "energy_log.json")) }

    fun costs(ctx: Context, vehicleId: String): CostBook =
        costs.computeIfAbsent(vehicleId) { CostBook(file(ctx, it, "kosten.json")) }

    fun track(ctx: Context, vehicleId: String): TrackLog =
        track.computeIfAbsent(vehicleId) { TrackLog(file(ctx, it, "positionen.json")) }

    fun places(ctx: Context): PlaceBook = places ?: synchronized(this) {
        places ?: PlaceBook(ctx.applicationContext).also { places = it }
    }

    /** Nach dem Verschieben von Dateien ([Garage.adopt]) neu öffnen – der Standort-Speicher hält einen Zwischenspeicher. */
    fun forget(vehicleId: String) {
        trips.remove(vehicleId)
        energy.remove(vehicleId)
        costs.remove(vehicleId)
        track.remove(vehicleId)
    }
}
