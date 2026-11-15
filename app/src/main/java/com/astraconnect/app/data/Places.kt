package com.astraconnect.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Ein benannter Ort, z. B. „Zuhause“ oder „Arbeit“. */
data class Place(
    val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val radiusM: Double = 200.0,
    /** Strompreis beim Laden an diesem Ort (€/kWh aus dem Netz), null = Standardpreis. */
    val powerPrice: Double? = null,
) {
    val pos get() = LatLon(lat, lon)
}

/**
 * Regel „von Ort A nach Ort B = Kategorie“. `null` als Ort bedeutet „beliebig“.
 * Mit [bothWays] gilt die Regel auch für die Rückfahrt.
 */
data class CategoryRule(
    val id: String,
    val fromId: String?,
    val toId: String?,
    val category: TripCategory,
    val bothWays: Boolean = true,
)

/** Häufig angefahrener, noch unbenannter Ort (Vorschlag zum Benennen). */
data class Spot(val lat: Double, val lon: Double, val visits: Int, val address: String?) {
    val pos get() = LatLon(lat, lon)
}

/** Speichert benannte Orte und Kategorie-Regeln (Datei `orte.json`). */
class PlaceBook(private val file: File) {

    constructor(context: Context) : this(File(context.filesDir, "orte.json"))

    @Synchronized
    private fun read(): JSONObject =
        if (file.exists()) runCatching { JSONObject(file.readText()) }.getOrElse { JSONObject() } else JSONObject()

    @Synchronized
    private fun write(o: JSONObject) {
        file.parentFile?.mkdirs()
        file.writeText(o.toString())
    }

    @Synchronized
    fun places(): List<Place> = read().arr("places").objects().mapNotNull { o ->
        Place(
            id = o.str("id") ?: return@mapNotNull null,
            name = o.optString("name"),
            lat = o.num("lat") ?: return@mapNotNull null,
            lon = o.num("lon") ?: return@mapNotNull null,
            radiusM = o.num("radius") ?: 200.0,
            powerPrice = o.num("power"),
        )
    }

    @Synchronized
    fun rules(): List<CategoryRule> = read().arr("rules").objects().mapNotNull { o ->
        CategoryRule(
            id = o.str("id") ?: return@mapNotNull null,
            fromId = o.str("from"),
            toId = o.str("to"),
            category = runCatching { TripCategory.valueOf(o.optString("category")) }.getOrNull() ?: return@mapNotNull null,
            bothWays = o.optBoolean("both", true),
        )
    }

    @Synchronized
    fun savePlace(p: Place): Place {
        val o = read()
        val arr = JSONArray(o.arr("places").objects().filter { it.str("id") != p.id })
        arr.put(
            JSONObject().put("id", p.id).put("name", p.name).put("lat", p.lat).put("lon", p.lon).put("radius", p.radiusM)
                .put("power", p.powerPrice ?: JSONObject.NULL)
        )
        write(o.put("places", arr))
        return p
    }

    fun addPlace(name: String, pos: LatLon, radiusM: Double = 200.0, powerPrice: Double? = null) =
        savePlace(Place(UUID.randomUUID().toString(), name.trim(), pos.lat, pos.lon, radiusM, powerPrice))

    /** Löscht einen Ort samt aller Regeln, die ihn verwenden. */
    @Synchronized
    fun deletePlace(id: String) {
        val o = read()
        o.put("places", JSONArray(o.arr("places").objects().filter { it.str("id") != id }))
        o.put("rules", JSONArray(o.arr("rules").objects().filter { it.str("from") != id && it.str("to") != id }))
        write(o)
    }

    @Synchronized
    fun saveRule(r: CategoryRule) {
        val o = read()
        val arr = JSONArray(o.arr("rules").objects().filter { it.str("id") != r.id })
        arr.put(
            JSONObject().put("id", r.id).put("from", r.fromId ?: JSONObject.NULL).put("to", r.toId ?: JSONObject.NULL)
                .put("category", r.category.name).put("both", r.bothWays)
        )
        write(o.put("rules", arr))
    }

    fun addRule(fromId: String?, toId: String?, category: TripCategory, bothWays: Boolean) =
        saveRule(CategoryRule(UUID.randomUUID().toString(), fromId, toId, category, bothWays))

    @Synchronized
    fun deleteRule(id: String) {
        val o = read()
        write(o.put("rules", JSONArray(o.arr("rules").objects().filter { it.str("id") != id })))
    }

    /** Für die Datensicherung. */
    @Synchronized
    fun toJson(): JSONObject = read()

    /** Spielt Orte/Regeln aus einer Sicherung ein (vorhandene IDs bleiben unverändert). */
    @Synchronized
    fun restore(b: JSONObject) {
        val o = read()
        for (key in listOf("places", "rules")) {
            val local = o.arr(key).objects()
            val ids = local.mapNotNull { it.str("id") }.toSet()
            val merged = JSONArray(local)
            b.arr(key).objects().filter { it.str("id") !in ids }.forEach { merged.put(it) }
            o.put(key, merged)
        }
        write(o)
    }

    companion object {
        /** Entfernung in Metern (Haversine). */
        fun distanceM(a: LatLon, b: LatLon): Double {
            val r = 6_371_000.0
            val dLat = Math.toRadians(b.lat - a.lat)
            val dLon = Math.toRadians(b.lon - a.lon)
            val h = sin(dLat / 2).let { it * it } +
                cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).let { it * it }
            return 2 * r * atan2(sqrt(h), sqrt(1 - h))
        }

        /** Nächstgelegener benannter Ort innerhalb seines Radius. */
        fun placeAt(places: List<Place>, p: LatLon?): Place? {
            if (p == null) return null
            return places.map { it to distanceM(it.pos, p) }
                .filter { (pl, d) -> d <= pl.radiusM }
                .minByOrNull { it.second }?.first
        }

        /**
         * Kategorie für eine Fahrt nach den Regeln. Spezifische Regeln (Start UND Ziel) gehen vor
         * Regeln mit nur einem Ort. Bei widersprüchlichen gleich spezifischen Regeln: keine Zuordnung.
         */
        fun categoryFor(t: Trip, places: List<Place>, rules: List<CategoryRule>): TripCategory? {
            val from = placeAt(places, t.startPos)?.id
            val to = placeAt(places, t.endPos)?.id
            fun matches(r: CategoryRule, f: String?, d: String?) =
                (r.fromId != null || r.toId != null) &&
                    (r.fromId == null || r.fromId == f) && (r.toId == null || r.toId == d)
            val hits = rules.filter { r -> matches(r, from, to) || (r.bothWays && matches(r, to, from)) }
            if (hits.isEmpty()) return null
            val best = hits.groupBy { (if (it.fromId != null) 1 else 0) + (if (it.toId != null) 1 else 0) }
                .maxBy { it.key }.value
            return best.map { it.category }.distinct().singleOrNull()
        }

        /**
         * Häufig angefahrene Orte, die noch keinen Namen haben. Start- und Zielpunkte werden in
         * 150-m-Gruppen zusammengefasst; Vorschläge ab [minVisits] Besuchen.
         */
        fun suggestions(trips: List<Trip>, places: List<Place>, minVisits: Int = 3, max: Int = 12): List<Spot> {
            data class Cluster(var lat: Double, var lon: Double, var n: Int, val addresses: MutableList<String>)
            val clusters = mutableListOf<Cluster>()
            val points = trips.flatMap { t -> listOf(t.startPos to t.startAddress, t.endPos to t.endAddress) }
            for ((p, addr) in points) {
                if (p == null) continue
                val c = clusters.firstOrNull { distanceM(LatLon(it.lat, it.lon), p) <= 150.0 }
                if (c == null) {
                    clusters += Cluster(p.lat, p.lon, 1, listOfNotNull(addr).toMutableList())
                } else {
                    // laufender Mittelpunkt
                    c.lat = (c.lat * c.n + p.lat) / (c.n + 1)
                    c.lon = (c.lon * c.n + p.lon) / (c.n + 1)
                    c.n++
                    addr?.let { c.addresses += it }
                }
            }
            return clusters
                .filter { it.n >= minVisits }
                .filter { c -> placeAt(places, LatLon(c.lat, c.lon)) == null }
                .sortedByDescending { it.n }
                .take(max)
                .map { c -> Spot(c.lat, c.lon, c.n, c.addresses.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key) }
        }

        /** Alle Start-/Zielpunkte in Gruppen – für die Häufigkeitskarte. */
        fun hotspots(trips: List<Trip>): List<Spot> = suggestions(trips, emptyList(), minVisits = 1, max = Int.MAX_VALUE)
    }
}
