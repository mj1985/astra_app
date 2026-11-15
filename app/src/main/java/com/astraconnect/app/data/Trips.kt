package com.astraconnect.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

enum class TripCategory(val label: String) {
    UNSET("Nicht zugeordnet"),
    PRIVATE("Privat"),
    BUSINESS("Dienstlich"),
    COMMUTE("Arbeitsweg"),
}

/** Herkunft eines Fahrtenbuch-Eintrags. */
enum class TripSource(val label: String) {
    API("Opel-Server"),
    MYOPEL("myOpel-Import"),
    BOTH("Opel-Server + myOpel"),
}

data class Trip(
    val key: String,
    val startedAt: Instant?,
    val stoppedAt: Instant?,
    val distanceKm: Double?,
    val startMileage: Double?,
    val endMileage: Double?,
    val startPos: LatLon?,
    val endPos: LatLon?,
    val fuelLiters: Double?,
    val electricKwh: Double?,
    val avgFuel: Double?,
    val avgElectric: Double?,
    /** Ladestand Hochvoltbatterie bei Ankunft (nur aus myOpel-Export). */
    val socEnd: Double?,
    val category: TripCategory,
    val note: String,
    val startAddress: String?,
    val endAddress: String?,
    val source: TripSource,
    val raw: JSONObject,
    val myop: JSONObject?,
) {
    val duration: Duration?
        get() = if (startedAt != null && stoppedAt != null) Duration.between(startedAt, stoppedAt) else null

    companion object {
        fun keyOf(raw: JSONObject) = raw.str("id") ?: raw.str("startedAt") ?: raw.toString().hashCode().toString()

        fun parse(entry: JSONObject): Trip {
            val raw = entry.optJSONObject("raw") ?: JSONObject()
            val m = entry.optJSONObject("myop")
            val ms = m.obj("start")
            val me = m.obj("end")
            val hasApi = raw.length() > 0

            val consumptions = raw.arr("energyConsumptions").objects() + raw.arr("energyConsumption").objects() +
                raw.arr("consumptions").objects()
            val fuelC = consumptions.firstWith("type", "Fuel")
            val elC = consumptions.firstWith("type", "Electric")

            val startMileage = firstNum(raw, "startMileage", "startOdometer", "odometer.start", "start.mileage", "startPosition.properties.mileage")
                ?: ms.num("mileage")
            var distance = firstNum(raw, "distance", "kinetic.distance", "mileage") ?: m.num("distance")
            var endMileage = firstNum(
                raw, "endMileage", "stopMileage", "stopOdometer", "odometer.end", "odometer.stop", "odometer.stopped",
                "stop.mileage", "end.mileage", "stopPosition.properties.mileage", "endPosition.properties.mileage",
            ) ?: me.num("mileage")
            if (distance == null && startMileage != null && endMileage != null) distance = endMileage - startMileage
            // Die Opel-API liefert den End-Kilometerstand nicht immer mit – dann aus Start + Strecke.
            if (endMileage == null && startMileage != null && distance != null) endMileage = startMileage + distance

            // Kraftstoff: Die API liefert Zentiliter (cl bzw. cl/100 km), myOpel Mikroliter.
            val fuel = fuelC.num("consumption")?.let { it / 100.0 }
                ?: m.num("fuelConsumption")?.takeIf { it > 0 }?.let { it / 1_000_000.0 }
            val avgFuel = fuelC.num("avgConsumption")?.let { it / 100.0 }
                ?: if (fuel != null && distance != null && distance > 0.5) fuel / distance * 100.0 else null

            val source = when {
                hasApi && m != null -> TripSource.BOTH
                m != null -> TripSource.MYOPEL
                else -> TripSource.API
            }

            return Trip(
                key = entry.optString("key").ifBlank { keyOf(raw) },
                startedAt = parseInstant(firstStr(raw, "startedAt", "startDate", "start.date", "createdAt"))
                    ?: parseInstant(ms.str("date")),
                stoppedAt = parseInstant(firstStr(raw, "stoppedAt", "endedAt", "endDate", "stop.date", "end.date"))
                    ?: parseInstant(me.str("date")),
                distanceKm = distance,
                startMileage = startMileage,
                endMileage = endMileage,
                startPos = pos(raw, "startPosition", "start") ?: myopPos(ms),
                endPos = pos(raw, "stopPosition", "endPosition", "stop", "end") ?: myopPos(me),
                fuelLiters = fuel,
                electricKwh = elC.num("consumption"),
                avgFuel = avgFuel,
                avgElectric = elC.num("avgConsumption"),
                socEnd = m?.takeIf { source == TripSource.MYOPEL || source == TripSource.BOTH }?.num("otherEnergyLevel"),
                category = runCatching { TripCategory.valueOf(entry.optString("category")) }.getOrDefault(TripCategory.UNSET),
                note = entry.optString("note"),
                startAddress = entry.optString("startAddress").ifBlank { null } ?: myopAddress(ms),
                endAddress = entry.optString("endAddress").ifBlank { null } ?: myopAddress(me),
                source = source,
                raw = raw,
                myop = m,
            )
        }

        private fun pos(raw: JSONObject, vararg keys: String): LatLon? {
            for (k in keys) {
                val o = raw.obj(k) ?: continue
                val coords = o.pathObj("geometry")?.optJSONArray("coordinates") ?: o.optJSONArray("coordinates")
                if (coords != null && coords.length() >= 2) return LatLon(coords.optDouble(1), coords.optDouble(0))
                val lat = o.num("latitude") ?: o.num("lat")
                val lon = o.num("longitude") ?: o.num("lon") ?: o.num("lng")
                if (lat != null && lon != null) return LatLon(lat, lon)
            }
            return null
        }

        private fun myopPos(o: JSONObject?): LatLon? {
            val lat = o.num("latitude") ?: return null
            val lon = o.num("longitude") ?: return null
            return LatLon(lat, lon)
        }

        /** "Straße Nr, PLZ Ort" aus einem myOpel-Ortsobjekt. */
        fun myopAddress(o: JSONObject?): String? {
            if (o == null) return null
            val street = listOf(o.optString("street"), o.optString("streetNumber"))
                .filter { it.isNotBlank() }.joinToString(" ")
            val country = o.optString("country").takeIf { it.isNotBlank() && it != "Deutschland" }
            val city = listOf(o.optString("postalCode"), o.optString("city"))
                .filter { it.isNotBlank() }.joinToString(" ")
            return listOfNotNull(street.ifBlank { null }, city.ifBlank { null }, country)
                .joinToString(", ").ifBlank { null }
        }
    }
}

data class ImportResult(val read: Int, val matched: Int, val added: Int, val skippedOtherVin: Int, val invalid: Int) {
    val summary: String
        get() = buildString {
            append("$read Fahrt(en) gelesen: $matched mit vorhandenen abgeglichen, $added neu übernommen.")
            if (skippedOtherVin > 0) append(" $skippedOtherVin von einem anderen Fahrzeug übersprungen.")
            if (invalid > 0) append(" $invalid ohne Startzeit ignoriert.")
        }
}

/**
 * Lokales Fahrtenbuch. Fahrten werden aus der API (und optional aus myOpel-Exporten) übernommen
 * und bleiben dauerhaft auf dem Gerät gespeichert, zusammen mit Kategorie, Notiz und Adressen.
 */
class TripStore(context: Context) {
    private val file = File(context.filesDir, "fahrtenbuch.json")
    private val exportDir = File(context.cacheDir, "exports")

    @Synchronized
    private fun readEntries(): MutableMap<String, JSONObject> {
        if (!file.exists()) return linkedMapOf()
        val arr = runCatching { JSONArray(file.readText()) }.getOrElse { JSONArray() }
        return arr.objects().associateByTo(linkedMapOf()) { it.optString("key") }
    }

    @Synchronized
    private fun write(entries: Map<String, JSONObject>) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(JSONArray(entries.values).toString())
        tmp.renameTo(file).let { ok -> if (!ok) { file.delete(); tmp.renameTo(file) } }
    }

    @Synchronized
    fun load(): List<Trip> = readEntries().values.map(Trip::parse)
        .sortedByDescending { it.startedAt ?: Instant.EPOCH }

    /** Übernimmt Fahrten aus der API. Benutzerangaben (Kategorie, Notiz, Adressen) bleiben erhalten. */
    @Synchronized
    fun merge(apiTrips: List<JSONObject>): Int {
        val entries = readEntries()
        var added = 0
        for (raw in apiTrips) {
            val key = Trip.keyOf(raw)
            val existing = entries[key]
            if (existing != null) {
                existing.put("raw", raw)
                continue
            }
            // Gibt es die Fahrt schon aus einem myOpel-Import? Dann zusammenführen statt doppeln.
            val start = Trip.parse(JSONObject().put("raw", raw)).startedAt
            val twin = start?.let { s ->
                entries.values.firstOrNull { e ->
                    e.optString("key").startsWith(MYOP_PREFIX) &&
                        Trip.parse(e).startedAt?.let { closeInTime(it, s, MATCH_WINDOW) } == true
                }
            }
            if (twin != null) {
                entries.remove(twin.optString("key"))
                twin.put("key", key).put("raw", raw)
                entries[key] = twin
            } else {
                added++
                entries[key] = JSONObject().put("key", key).put("raw", raw).put("category", TripCategory.UNSET.name)
            }
        }
        write(entries)
        return added
    }

    /**
     * Importiert einen Export der myOpel-App (Datei *.myop bzw. ohne Endung, JSON).
     * Fahrten, die schon im Fahrtenbuch stehen (gleiche Startzeit ± 3 min oder gleicher
     * Kilometerstand), werden ergänzt; alle anderen werden neu angelegt.
     */
    @Synchronized
    fun importMyop(text: String, vin: String?): ImportResult {
        val root = text.trim().removePrefix("﻿")
        val vehicles: List<JSONObject> = when {
            root.startsWith("[") -> JSONArray(root).objects()
            root.startsWith("{") -> JSONObject(root).let { o ->
                if (o.has("trips")) listOf(o) else o.arr("vehicles").objects()
            }
            else -> throw IllegalArgumentException("Die Datei ist kein myOpel-Export (kein JSON).")
        }
        if (vehicles.none { it.has("trips") }) throw IllegalArgumentException("Keine Fahrten in der Datei gefunden.")

        val entries = readEntries()
        val parsed = entries.values.associate { it.optString("key") to Trip.parse(it) }.toMutableMap()
        val used = mutableSetOf<String>()
        var read = 0; var matched = 0; var added = 0; var skipped = 0; var invalid = 0

        for (v in vehicles) {
            val fileVin = v.optString("vin")
            val trips = v.arr("trips").objects()
            if (!vin.isNullOrBlank() && fileVin.isNotBlank() && !fileVin.equals(vin, ignoreCase = true)) {
                skipped += trips.size
                continue
            }
            for (mt in trips) {
                read++
                val start = parseInstant(mt.obj("start").str("date"))
                if (start == null) { invalid++; continue }
                val startKm = mt.obj("start").num("mileage")

                val match = parsed.values
                    .filter { it.key !in used && it.startedAt != null }
                    .filter { t ->
                        closeInTime(t.startedAt!!, start, MATCH_WINDOW) ||
                            (closeInTime(t.startedAt, start, Duration.ofMinutes(30)) &&
                                startKm != null && t.startMileage != null && abs(t.startMileage - startKm) <= 1.0)
                    }
                    .minByOrNull { abs(Duration.between(it.startedAt!!, start).seconds) }

                val entry = if (match != null) {
                    matched++
                    entries.getValue(match.key)
                } else {
                    added++
                    val key = MYOP_PREFIX + start.epochSecond
                    JSONObject().put("key", key).put("category", TripCategory.UNSET.name).also { entries[key] = it }
                }
                entry.put("myop", mt)
                // Adressen aus myOpel sind genauer als die Geokodierung – nur leere Felder füllen.
                if (entry.optString("startAddress").isBlank()) Trip.myopAddress(mt.obj("start"))?.let { entry.put("startAddress", it) }
                if (entry.optString("endAddress").isBlank()) Trip.myopAddress(mt.obj("end"))?.let { entry.put("endAddress", it) }
                val t = Trip.parse(entry)
                used += t.key
                parsed[t.key] = t
            }
        }
        write(entries)
        return ImportResult(read, matched, added, skipped, invalid)
    }

    @Synchronized
    fun update(key: String, category: TripCategory? = null, note: String? = null, startAddress: String? = null, endAddress: String? = null) {
        val entries = readEntries()
        val e = entries[key] ?: return
        category?.let { e.put("category", it.name) }
        note?.let { e.put("note", it) }
        startAddress?.let { e.put("startAddress", it) }
        endAddress?.let { e.put("endAddress", it) }
        write(entries)
    }

    @Synchronized
    fun clear() {
        file.delete()
    }

    /** Jüngste Fahrt vom Opel-Server (Importe zählen nicht, sonst würden Lücken nie nachgeladen). */
    fun newestStart(): Instant? = load().filter { it.source != TripSource.MYOPEL }.firstNotNullOfOrNull { it.startedAt }

    /** CSV (Semikolon, für Excel/LibreOffice in deutscher Einstellung). */
    fun exportCsv(trips: List<Trip>, battery: Map<String, BatteryUse> = emptyMap()): File {
        exportDir.mkdirs()
        val zone = ZoneId.systemDefault()
        val date = DateTimeFormatter.ofPattern("dd.MM.yyyy").withZone(zone)
        val time = DateTimeFormatter.ofPattern("HH:mm").withZone(zone)
        fun n(v: Double?, digits: Int = 1) = v?.let { String.format(Locale.GERMANY, "%.${digits}f", it) }.orEmpty()
        fun q(s: String?) = "\"" + (s ?: "").replace("\"", "\"\"") + "\""
        fun place(addr: String?, p: LatLon?) = addr ?: p?.let { String.format(Locale.US, "%.5f, %.5f", it.lat, it.lon) }

        val sb = StringBuilder("﻿")
        sb.appendLine("Datum;Abfahrt;Ankunft;Startort;Zielort;km-Stand Start;km-Stand Ende;Strecke km;Kategorie;Zweck / Notiz;Kraftstoff l;Ø Kraftstoff l/100 km;Strom kWh;Akku Start %;Akku Ende %;Akku-Verbrauch kWh (berechnet);kWh/100 km")
        trips.sortedBy { it.startedAt ?: Instant.EPOCH }.forEach { t ->
            val b = battery[t.key]
            sb.append(t.startedAt?.let(date::format).orEmpty()).append(';')
                .append(t.startedAt?.let(time::format).orEmpty()).append(';')
                .append(t.stoppedAt?.let(time::format).orEmpty()).append(';')
                .append(q(place(t.startAddress, t.startPos))).append(';')
                .append(q(place(t.endAddress, t.endPos))).append(';')
                .append(n(t.startMileage, 0)).append(';')
                .append(n(t.endMileage, 0)).append(';')
                .append(n(t.distanceKm)).append(';')
                .append(t.category.label).append(';')
                .append(q(t.note)).append(';')
                .append(n(t.fuelLiters, 2)).append(';')
                .append(n(t.avgFuel, 1)).append(';')
                .append(n(t.electricKwh, 2)).append(';')
                .append(n(b?.socStart, 0)).append(';')
                .append(n(b?.socEnd ?: t.socEnd, 0)).append(';')
                .append(n(b?.kwh, 2)).append(';')
                .append(n(b?.per100km(t.distanceKm), 1)).appendLine()
        }
        val out = File(exportDir, "fahrtenbuch_${System.currentTimeMillis()}.csv")
        out.writeText(sb.toString(), Charsets.UTF_8)
        return out
    }

    private companion object {
        const val MYOP_PREFIX = "myop-"
        val MATCH_WINDOW: Duration = Duration.ofMinutes(3)

        fun closeInTime(a: Instant, b: Instant, window: Duration) =
            abs(Duration.between(a, b).seconds) <= window.seconds
    }
}
