package com.astraconnect.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Duration
import java.time.Instant

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
    /** Erklärung, falls nicht berechenbar. */
    val note: String?,
) {
    fun per100km(distanceKm: Double?): Double? =
        if (kwh != null && distanceKm != null && distanceKm > 0.5) kwh / distanceKm * 100 else null
}

/**
 * Speichert Ladestand-Messpunkte (bei jedem Abruf und im Hintergrund alle 15 Min.).
 * Daraus wird der Akku-Verbrauch pro Fahrt berechnet: Ladestand vor der Fahrt minus
 * Ladestand nach der Fahrt × nutzbare Akkukapazität.
 */
class EnergyLog(private val file: File) {

    constructor(context: Context) : this(File(context.filesDir, "energy_log.json"))

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
                val after = sorted.firstOrNull { s ->
                    !s.at.isBefore(stop.minus(TOL)) &&
                        (s.mileage == null || t.endMileage == null || s.mileage >= t.endMileage - 1)
                }
                if (before == null || after == null || !after.at.isAfter(before.at)) {
                    result[t.key] = BatteryUse(null, null, null, false,
                        "Keine Messpunkte vor/nach dieser Fahrt (Aufzeichnung lief noch nicht).")
                    continue
                }
                windows[t.key] = Window(before, after)
            }

            // Fahrten, die sich dasselbe Messpunkt-Paar teilen, gemeinsam auswerten
            windows.entries.groupBy { it.value }.forEach { (w, entries) ->
                val socB = w.before.soc
                val socA = w.after.soc
                val between = sorted.filter { it.at.isAfter(w.before.at) && it.at.isBefore(w.after.at) }
                val charged = between.any { it.charging } || w.after.charging ||
                    (socB != null && socA != null && socA > socB + 0.5) ||
                    between.zipWithNext().any { (a, b) -> a.soc != null && b.soc != null && b.soc > a.soc + 0.5 }
                val group = entries.mapNotNull { e -> trips.firstOrNull { it.key == e.key } }
                val totalKm = group.sumOf { it.distanceKm ?: 0.0 }
                val shared = group.size > 1

                for (t in group) {
                    val share = if (!shared) 1.0
                    else if (totalKm > 0) (t.distanceKm ?: 0.0) / totalKm
                    else 1.0 / group.size
                    result[t.key] = when {
                        socB == null || socA == null ->
                            BatteryUse(socB, socA, null, shared, "Kein Ladestand gemeldet.")
                        charged ->
                            BatteryUse(socB, socA, null, shared, "Zwischen den Messpunkten wurde geladen – nicht berechenbar.")
                        else ->
                            BatteryUse(socB, socA, ((socB - socA) / 100.0 * capacityKwh * share).coerceAtLeast(0.0), shared, null)
                    }
                }
            }
            return result
        }
    }
}

/** Gemeinsame Instanzen für App und Hintergrund-Job (gleiche Sperren auf dieselben Dateien). */
object Stores {
    @Volatile private var trips: TripStore? = null
    @Volatile private var energy: EnergyLog? = null
    @Volatile private var places: PlaceBook? = null

    fun places(ctx: Context): PlaceBook = places ?: synchronized(this) {
        places ?: PlaceBook(ctx.applicationContext).also { places = it }
    }

    fun trips(ctx: Context): TripStore = trips ?: synchronized(this) {
        trips ?: TripStore(ctx.applicationContext).also { trips = it }
    }

    fun energy(ctx: Context): EnergyLog = energy ?: synchronized(this) {
        energy ?: EnergyLog(ctx.applicationContext).also { energy = it }
    }
}
