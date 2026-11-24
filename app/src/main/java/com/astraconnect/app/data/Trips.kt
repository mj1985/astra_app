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
    /** Kategorie wurde durch eine Regel gesetzt (nicht von Hand). */
    val autoCategory: Boolean = false,
    /** Schlüssel der zusammengeführten Einzelfahrten (leer = keine Zusammenführung). */
    val partKeys: List<String> = emptyList(),
    /** Unveränderte Rohwerte für den Datenabgleich API ↔ myOpel. */
    val apiFuelRaw: Double? = null,
    val apiElectricRaw: Double? = null,
    val myopFuelLiters: Double? = null,
    /** Tankstand bei Ankunft in % (nur myOpel). */
    val fuelLevelEnd: Double? = null,
) {
    val duration: Duration?
        get() = if (startedAt != null && stoppedAt != null) Duration.between(startedAt, stoppedAt) else null

    val isMerged: Boolean get() = partKeys.size > 1

    companion object {
        fun keyOf(raw: JSONObject) = raw.str("id") ?: raw.str("startedAt") ?: raw.toString().hashCode().toString()

        /** Liest einen Eintrag; zusammengeführte Fahrten werden zu einer Fahrt kombiniert. */
        fun parse(entry: JSONObject): Trip {
            val base = parseSingle(entry)
            val absorbed = entry.arr("absorbed").objects()
            if (absorbed.isEmpty()) return base
            val parts = (listOf(base) + absorbed.map(::parseSingle)).sortedBy { it.startedAt ?: Instant.EPOCH }
            val first = parts.first()
            val last = parts.last()
            fun sumOrNull(sel: (Trip) -> Double?) = parts.mapNotNull(sel).takeIf { it.isNotEmpty() }?.sum()
            val distance = sumOrNull { it.distanceKm }
            val fuel = sumOrNull { it.fuelLiters }
            val sources = parts.map { it.source }.toSet()
            return base.copy(
                startedAt = first.startedAt,
                stoppedAt = last.stoppedAt,
                distanceKm = distance,
                startMileage = first.startMileage,
                endMileage = last.endMileage,
                startPos = first.startPos,
                endPos = last.endPos,
                fuelLiters = fuel,
                electricKwh = sumOrNull { it.electricKwh },
                avgFuel = if (fuel != null && distance != null && distance > 0.5) fuel / distance * 100 else null,
                avgElectric = null,
                socEnd = last.socEnd,
                fuelLevelEnd = last.fuelLevelEnd,
                startAddress = first.startAddress,
                endAddress = last.endAddress,
                source = when {
                    TripSource.BOTH in sources || (TripSource.API in sources && TripSource.MYOPEL in sources) -> TripSource.BOTH
                    TripSource.MYOPEL in sources -> TripSource.MYOPEL
                    else -> TripSource.API
                },
                partKeys = parts.map { it.key },
                apiFuelRaw = null, apiElectricRaw = null, myopFuelLiters = null,
            )
        }

        /** Liest genau einen Eintrag (ohne zusammengeführte Teilfahrten). */
        fun parseSingle(entry: JSONObject): Trip {
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
            val apiFuelRaw = fuelC.num("consumption")
            val myopFuel = m.num("fuelConsumption")?.let { it / 1_000_000.0 }
            val fuel = apiFuelRaw?.let { it / 100.0 }
                ?: myopFuel
                // Verbrauchsdaten vorhanden, aber kein Kraftstoff-Eintrag → rein elektrisch gefahren
                ?: if (consumptions.isNotEmpty() && fuelC == null) 0.0 else null
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
                socEnd = m.num("otherEnergyLevel"),
                category = runCatching { TripCategory.valueOf(entry.optString("category")) }.getOrDefault(TripCategory.UNSET),
                note = entry.optString("note"),
                startAddress = entry.optString("startAddress").ifBlank { null } ?: myopAddress(ms),
                endAddress = entry.optString("endAddress").ifBlank { null } ?: myopAddress(me),
                source = source,
                raw = raw,
                myop = m,
                autoCategory = entry.optBoolean("auto"),
                apiFuelRaw = apiFuelRaw,
                apiElectricRaw = elC.num("consumption"),
                myopFuelLiters = myopFuel,
                fuelLevelEnd = m.num("fuelLevel"),
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
 *
 * Aufbau eines Eintrags: `{key, raw?, myop?, category, auto?, note, startAddress, endAddress, absorbed?[]}`.
 * `absorbed` enthält Einzelfahrten, die mit diesem Eintrag zusammengeführt wurden („Teilfahrten“);
 * jede Teilfahrt hat dieselbe Struktur (ohne Kategorie/Notiz).
 */
class TripStore(private val file: File, private val exportDir: File) {

    constructor(context: Context) : this(File(context.filesDir, "fahrtenbuch.json"), File(context.cacheDir, "exports"))

    /** Eine Einzelfahrt und der Eintrag, in dem sie steht (bei Teilfahrten der zusammengeführte). */
    private data class Leaf(val json: JSONObject, val top: JSONObject)

    @Synchronized
    private fun readEntries(): MutableMap<String, JSONObject> {
        if (!file.exists()) return linkedMapOf()
        val arr = runCatching { JSONArray(file.readText()) }.getOrElse { JSONArray() }
        return arr.objects().associateByTo(linkedMapOf()) { it.optString("key") }
    }

    @Synchronized
    private fun write(entries: Map<String, JSONObject>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(JSONArray(entries.values).toString())
        tmp.renameTo(file).let { ok -> if (!ok) { file.delete(); tmp.renameTo(file) } }
    }

    private fun leaves(entries: Map<String, JSONObject>): List<Leaf> = entries.values.flatMap { top ->
        listOf(Leaf(top, top)) + top.arr("absorbed").objects().map { Leaf(it, top) }
    }

    @Synchronized
    fun load(): List<Trip> = readEntries().values.map(Trip::parse)
        .sortedByDescending { it.startedAt ?: Instant.EPOCH }

    /** Alle Einzelfahrten (Teilfahrten zusammengeführter Einträge einzeln). */
    @Synchronized
    fun loadSingles(): List<Trip> = leaves(readEntries()).map { Trip.parseSingle(it.json) }

    /** Unveränderte Einträge – für die Datensicherung. */
    @Synchronized
    fun rawEntries(): JSONArray = JSONArray(readEntries().values)

    /** Übernimmt Fahrten aus der API. Benutzerangaben (Kategorie, Notiz, Adressen) bleiben erhalten. */
    @Synchronized
    fun merge(apiTrips: List<JSONObject>): Int {
        val entries = readEntries()
        var added = 0
        for (raw in apiTrips) {
            val key = Trip.keyOf(raw)
            val all = leaves(entries)
            val existing = all.firstOrNull { it.json.optString("key") == key }
            if (existing != null) {
                existing.json.put("raw", raw)
                continue
            }
            // Gibt es die Fahrt schon aus einem myOpel-Import? Dann zusammenführen statt doppeln.
            val start = Trip.parseSingle(JSONObject().put("raw", raw)).startedAt
            val twin = start?.let { s ->
                all.firstOrNull { l ->
                    l.json.optString("key").startsWith(MYOP_PREFIX) &&
                        Trip.parseSingle(l.json).startedAt?.let { closeInTime(it, s, MATCH_WINDOW) } == true
                }
            }
            if (twin != null) {
                val oldKey = twin.json.optString("key")
                twin.json.put("key", key).put("raw", raw)
                if (twin.json === twin.top) {
                    entries.remove(oldKey)
                    entries[key] = twin.top
                }
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
     * Kilometerstand innerhalb 30 min), werden ergänzt; alle anderen werden neu angelegt.
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
        // Kandidaten: alle Einzelfahrten (auch Teilfahrten zusammengeführter Einträge)
        val candidates = leaves(entries).map { it to Trip.parseSingle(it.json) }.toMutableList()
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

                val match = candidates
                    .filter { (_, t) -> t.key !in used && t.startedAt != null }
                    .filter { (_, t) ->
                        closeInTime(t.startedAt!!, start, MATCH_WINDOW) ||
                            (closeInTime(t.startedAt, start, Duration.ofMinutes(30)) &&
                                startKm != null && t.startMileage != null && abs(t.startMileage - startKm) <= 1.0)
                    }
                    .minByOrNull { (_, t) -> abs(Duration.between(t.startedAt!!, start).seconds) }

                val leafJson = if (match != null) {
                    matched++
                    match.first.json
                } else {
                    added++
                    val key = MYOP_PREFIX + start.epochSecond
                    JSONObject().put("key", key).put("category", TripCategory.UNSET.name).also {
                        entries[key] = it
                        candidates += Leaf(it, it) to Trip.parseSingle(it)
                    }
                }
                leafJson.put("myop", mt)
                // Adressen aus myOpel sind genauer als die Geokodierung – nur leere Felder füllen.
                if (leafJson.optString("startAddress").isBlank()) Trip.myopAddress(mt.obj("start"))?.let { leafJson.put("startAddress", it) }
                if (leafJson.optString("endAddress").isBlank()) Trip.myopAddress(mt.obj("end"))?.let { leafJson.put("endAddress", it) }
                used += leafJson.optString("key")
            }
        }
        write(entries)
        return ImportResult(read, matched, added, skipped, invalid)
    }

    /**
     * Ändert eine Fahrt. Kategorie/Notiz gelten für den (ggf. zusammengeführten) Eintrag,
     * Adressen für die jeweilige Einzelfahrt.
     */
    @Synchronized
    fun update(key: String, category: TripCategory? = null, note: String? = null, startAddress: String? = null, endAddress: String? = null) {
        val entries = readEntries()
        val leaf = leaves(entries).firstOrNull { it.json.optString("key") == key } ?: return
        category?.let { leaf.top.put("category", it.name).remove("auto") }
        note?.let { leaf.top.put("note", it) }
        startAddress?.let { leaf.json.put("startAddress", it) }
        endAddress?.let { leaf.json.put("endAddress", it) }
        write(entries)
    }

    /**
     * Hängt die Fahrt [key] an die unmittelbar vorhergehende Fahrt an (z. B. Umparken, kurzer Stopp).
     * Liefert den Schlüssel des zusammengeführten Eintrags oder null, wenn es keine Vorgängerfahrt gibt.
     */
    @Synchronized
    fun mergeWithPrevious(key: String): String? {
        val entries = readEntries()
        val entry = entries[key] ?: return null
        val start = Trip.parse(entry).startedAt ?: return null
        val prev = entries.values
            .filter { it !== entry }
            .map { it to Trip.parse(it) }
            .filter { (_, t) -> t.startedAt != null && t.startedAt < start }
            .maxByOrNull { (_, t) -> t.startedAt!! }?.first ?: return null

        val absorbed = prev.optJSONArray("absorbed") ?: JSONArray().also { prev.put("absorbed", it) }
        // Teilfahrt ohne Kategorie/Notiz übernehmen; eigene Teilfahrten mit hinüberziehen
        val part = JSONObject(entry.toString()).apply {
            remove("category"); remove("note"); remove("auto"); remove("absorbed")
        }
        absorbed.put(part)
        entry.arr("absorbed").objects().forEach { absorbed.put(it) }

        if (Trip.parse(prev).category == TripCategory.UNSET && entry.optString("category") != TripCategory.UNSET.name) {
            prev.put("category", entry.optString("category"))
        }
        val notes = listOf(prev.optString("note"), entry.optString("note")).filter { it.isNotBlank() }.distinct()
        if (notes.isNotEmpty()) prev.put("note", notes.joinToString(" / "))
        entries.remove(key)
        write(entries)
        return prev.optString("key")
    }

    /** Löst eine Zusammenführung wieder auf; Teilfahrten erhalten Kategorie und Notiz des Eintrags. */
    @Synchronized
    fun split(key: String) {
        val entries = readEntries()
        val entry = entries[key] ?: return
        val parts = entry.arr("absorbed").objects()
        if (parts.isEmpty()) return
        entry.remove("absorbed")
        for (p in parts) {
            p.put("category", entry.optString("category", TripCategory.UNSET.name))
            if (entry.optBoolean("auto")) p.put("auto", true)
            entries[p.optString("key")] = p
        }
        write(entries)
    }

    /**
     * Setzt die Kategorie nicht zugeordneter Fahrten anhand der Ortsregeln.
     * Von Hand gesetzte Kategorien werden nie überschrieben. Liefert die Anzahl geänderter Fahrten.
     */
    @Synchronized
    fun applyRules(categoryFor: (Trip) -> TripCategory?): Int {
        val entries = readEntries()
        var changed = 0
        for (e in entries.values) {
            val t = Trip.parse(e)
            if (t.category != TripCategory.UNSET) continue
            val c = categoryFor(t) ?: continue
            e.put("category", c.name).put("auto", true)
            changed++
        }
        if (changed > 0) write(entries)
        return changed
    }

    /**
     * Spielt Einträge aus einer Datensicherung ein. Unbekannte Fahrten werden übernommen,
     * bei vorhandenen werden nur leere Benutzerangaben (Kategorie, Notiz, Adressen) ergänzt.
     */
    @Synchronized
    fun restore(backup: JSONArray): Int {
        val entries = readEntries()
        var changed = 0
        for (b in backup.objects()) {
            val bKeys = (listOf(b) + b.arr("absorbed").objects()).map { it.optString("key") }
            val local = leaves(entries).filter { it.json.optString("key") in bKeys }
            if (local.isEmpty()) {
                entries[b.optString("key")] = b
                changed++
                continue
            }
            val top = local.first().top
            if (top.optString("category", TripCategory.UNSET.name) == TripCategory.UNSET.name &&
                b.optString("category", TripCategory.UNSET.name) != TripCategory.UNSET.name
            ) {
                top.put("category", b.optString("category"))
                if (b.optBoolean("auto")) top.put("auto", true) else top.remove("auto")
                changed++
            }
            if (top.optString("note").isBlank() && b.optString("note").isNotBlank()) top.put("note", b.optString("note"))
            for (l in local) {
                val src = (listOf(b) + b.arr("absorbed").objects()).first { it.optString("key") == l.json.optString("key") }
                for (f in listOf("startAddress", "endAddress")) {
                    if (l.json.optString(f).isBlank() && src.optString(f).isNotBlank()) l.json.put(f, src.optString(f))
                }
                if (!l.json.has("myop") && src.has("myop")) l.json.put("myop", src.getJSONObject("myop"))
            }
        }
        write(entries)
        return changed
    }

    @Synchronized
    fun clear() {
        file.delete()
    }

    /** Jüngste Fahrt vom Opel-Server (Importe zählen nicht, sonst würden Lücken nie nachgeladen). */
    fun newestStart(): Instant? = loadSingles()
        .filter { it.source != TripSource.MYOPEL }
        .mapNotNull { it.startedAt }
        .maxOrNull()

    /** CSV (Semikolon, für Excel/LibreOffice in deutscher Einstellung). */
    fun exportCsv(
        trips: List<Trip>,
        battery: Map<String, BatteryUse> = emptyMap(),
        cost: (Trip) -> Double? = { null },
        placeName: (LatLon?) -> String? = { null },
    ): File {
        exportDir.mkdirs()
        val zone = ZoneId.systemDefault()
        val date = DateTimeFormatter.ofPattern("dd.MM.yyyy").withZone(zone)
        val time = DateTimeFormatter.ofPattern("HH:mm").withZone(zone)
        fun n(v: Double?, digits: Int = 1) = v?.let { String.format(Locale.GERMANY, "%.${digits}f", it) }.orEmpty()
        fun q(s: String?) = "\"" + (s ?: "").replace("\"", "\"\"") + "\""
        fun place(addr: String?, p: LatLon?) = listOfNotNull(placeName(p), addr ?: p?.let { String.format(Locale.US, "%.5f, %.5f", it.lat, it.lon) })
            .joinToString(" – ")

        val sb = StringBuilder("﻿")
        sb.appendLine("Datum;Abfahrt;Ankunft;Startort;Zielort;km-Stand Start;km-Stand Ende;Strecke km;Kategorie;Zweck / Notiz;Kraftstoff l;Ø Kraftstoff l/100 km;Strom kWh;Akku Start %;Akku Ende %;Akku-Verbrauch kWh (berechnet);kWh/100 km;Kosten ${Currency.current.symbol}")
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
                .append(n(b?.per100km(t.distanceKm), 1)).append(';')
                .append(n(cost(t), 2)).appendLine()
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
