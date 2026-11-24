package com.astraconnect.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.math.abs

/** Getankt: Preis gilt für alle Fahrten ab diesem Zeitpunkt (bis zur nächsten Eintragung). */
data class FuelEntry(
    val id: String,
    val at: Instant,
    val pricePerLiter: Double,
    val liters: Double?,
    val note: String = "",
)

/**
 * Ladekosten. Entweder eine Korrektur zu einem erkannten Ladevorgang ([manual] = false, über die
 * Startzeit zugeordnet) oder ein von Hand nachgetragener Ladevorgang ([manual] = true).
 * [kwh] = bezogene Energie laut Ladesäule/Zähler (inkl. Verluste), nur bei manuellen Einträgen.
 * Kosten: [cost] (Gesamtbetrag) hat Vorrang vor [pricePerKwh].
 */
data class ChargeEntry(
    val id: String,
    val at: Instant,
    val manual: Boolean,
    val kwh: Double? = null,
    val cost: Double? = null,
    val pricePerKwh: Double? = null,
    val placeId: String? = null,
    val note: String = "",
)

enum class CostSource { ENTERED, PLACE, DEFAULT }

/** Ein Ladevorgang für die Ladeübersicht (erkannt oder nachgetragen) samt Kosten. */
data class ChargeRow(
    val start: Instant,
    val end: Instant?,
    val socFrom: Double?,
    val socTo: Double?,
    /** Im Akku gelandet. */
    val kwhBattery: Double,
    /** Aus dem Netz bezogen (inkl. Ladeverluste). */
    val kwhGrid: Double,
    val place: Place?,
    val cost: Double,
    val costSource: CostSource,
    val ongoing: Boolean,
    /** Zugehöriger Eintrag (Korrektur oder manueller Ladevorgang). */
    val entry: ChargeEntry?,
) {
    val manual get() = entry?.manual == true
    val pricePerGridKwh: Double? get() = if (kwhGrid > 0.01) cost / kwhGrid else null
    /** Kosten je kWh, die tatsächlich im Akku angekommen ist (Grundlage für Fahrtkosten). */
    val pricePerBatteryKwh: Double? get() = if (kwhBattery > 0.01) cost / kwhBattery else null
}

/** Ein Tankvorgang fürs Tankbuch: erkannt und/oder mit eingetragenem Preis. */
data class FuelRow(val at: Instant, val refuel: Refuel?, val entry: FuelEntry?) {
    val liters: Double? get() = entry?.liters ?: refuel?.liters
    val cost: Double? get() = entry?.let { e -> liters?.let { it * e.pricePerLiter } }
}

/**
 * Preise über die Zeit: Kraftstoff nach dem zuletzt eingetragenen Tankpreis, Strom nach den Kosten
 * des letzten Ladevorgangs vor der Fahrt. Fehlt beides, gelten die Standardpreise.
 */
class CostModel(
    val prices: Prices,
    fuelEntries: List<FuelEntry> = emptyList(),
    chargeRows: List<ChargeRow> = emptyList(),
) {
    private val fuel = fuelEntries.sortedBy { it.at }
    private val charges = chargeRows.filter { it.pricePerBatteryKwh != null && !it.ongoing }.sortedBy { it.start }

    /** Preis je Liter (Landeswährung): letzter eingetragener Tankpreis vor [t]; davor bzw. ohne Eintrag der Standardpreis. */
    fun fuelPriceAt(t: Instant?): Double =
        (if (t == null) fuel.lastOrNull() else fuel.lastOrNull { !it.at.isAfter(t) })?.pricePerLiter ?: prices.fuelPerLiter

    /** Preis je kWh aus dem Akku (Landeswährung). */
    fun powerPriceAt(t: Instant?): Double =
        t?.let { at -> charges.lastOrNull { !it.start.isAfter(at) }?.pricePerBatteryKwh }
            ?: prices.powerPerKwh * (1 + prices.chargeLossPct / 100.0)

    fun fuelCost(t: Trip): Double? = t.fuelLiters?.let { it * fuelPriceAt(t.startedAt) }
    fun powerCost(t: Trip, b: BatteryUse?): Double? = b?.kwh?.let { it * powerPriceAt(t.startedAt) }

    fun tripCost(t: Trip, b: BatteryUse?): Double? {
        val f = fuelCost(t)
        val p = powerCost(t, b)
        return if (f == null && p == null) null else (f ?: 0.0) + (p ?: 0.0)
    }

    companion object {
        /** Ladeübersicht: erkannte Ladevorgänge plus manuelle Einträge, neueste zuerst. */
        fun chargeRows(
            sessions: List<ChargeSession>,
            entries: List<ChargeEntry>,
            trips: List<Trip>,
            places: List<Place>,
            prices: Prices,
        ): List<ChargeRow> {
            val loss = 1 + prices.chargeLossPct / 100.0
            val parked = trips.filter { it.stoppedAt != null && it.endPos != null }.sortedBy { it.stoppedAt }
            fun placeAt(at: Instant): Place? =
                parked.lastOrNull { !it.stoppedAt!!.isAfter(at.plusSeconds(15 * 60)) }?.let { PlaceBook.placeAt(places, it.endPos) }

            fun price(entry: ChargeEntry?, place: Place?, kwhGrid: Double): Pair<Double, CostSource> = when {
                entry?.cost != null -> entry.cost to CostSource.ENTERED
                entry?.pricePerKwh != null -> entry.pricePerKwh * kwhGrid to CostSource.ENTERED
                place?.powerPrice != null -> place.powerPrice * kwhGrid to CostSource.PLACE
                else -> prices.powerPerKwh * kwhGrid to CostSource.DEFAULT
            }

            val corrections = entries.filter { !it.manual }
            val rows = sessions.map { s ->
                val e = corrections.filter { abs(Duration.between(it.at, s.start).toMinutes()) <= 30 }
                    .minByOrNull { abs(Duration.between(it.at, s.start).seconds) }
                val place = e?.placeId?.let { id -> places.firstOrNull { it.id == id } } ?: placeAt(s.start)
                val grid = s.kwh * loss
                val (cost, src) = price(e, place, grid)
                ChargeRow(s.start, s.end, s.socFrom, s.socTo, s.kwh, grid, place, cost, src, s.ongoing, e)
            }
            val manual = entries.filter { it.manual }.map { e ->
                val place = e.placeId?.let { id -> places.firstOrNull { it.id == id } }
                val grid = e.kwh ?: 0.0
                val (cost, src) = price(e, place, grid)
                ChargeRow(e.at, null, null, null, grid / loss, grid, place, cost, src, false, e)
            }
            return (rows + manual).sortedByDescending { it.start }
        }

        /** Tankbuch: erkannte Tankvorgänge mit zugeordneten Preis-Einträgen (±12 h) plus Einträge ohne Erkennung. */
        fun fuelRows(refuels: List<Refuel>, entries: List<FuelEntry>): List<FuelRow> {
            val used = mutableSetOf<String>()
            val rows = refuels.map { r ->
                val e = entries.filter { it.id !in used && abs(Duration.between(it.at, r.at).toHours()) < 12 }
                    .minByOrNull { abs(Duration.between(it.at, r.at).seconds) }
                e?.let { used += it.id }
                FuelRow(r.at, r, e)
            }
            val extra = entries.filter { it.id !in used }.map { FuelRow(it.at, null, it) }
            return (rows + extra).sortedByDescending { it.at }
        }
    }
}

/** Speichert Tankpreise und Ladekosten (Datei `kosten.json`). */
class CostBook(private val file: File) {

    @Synchronized
    private fun read(): JSONObject =
        if (file.exists()) runCatching { JSONObject(file.readText()) }.getOrElse { JSONObject() } else JSONObject()

    @Synchronized
    private fun write(o: JSONObject) {
        file.parentFile?.mkdirs()
        file.writeText(o.toString())
    }

    @Synchronized
    fun fuel(): List<FuelEntry> = read().arr("fuel").objects().mapNotNull { o ->
        FuelEntry(
            id = o.str("id") ?: return@mapNotNull null,
            at = parseInstant(o.str("at")) ?: return@mapNotNull null,
            pricePerLiter = o.num("price") ?: return@mapNotNull null,
            liters = o.num("liters"),
            note = o.optString("note"),
        )
    }.sortedByDescending { it.at }

    @Synchronized
    fun charges(): List<ChargeEntry> = read().arr("charge").objects().mapNotNull { o ->
        ChargeEntry(
            id = o.str("id") ?: return@mapNotNull null,
            at = parseInstant(o.str("at")) ?: return@mapNotNull null,
            manual = o.optBoolean("manual"),
            kwh = o.num("kwh"),
            cost = o.num("cost"),
            pricePerKwh = o.num("price"),
            placeId = o.str("place"),
            note = o.optString("note"),
        )
    }.sortedByDescending { it.at }

    @Synchronized
    fun saveFuel(e: FuelEntry) = upsert("fuel", e.id, JSONObject()
        .put("id", e.id).put("at", e.at.toString()).put("price", e.pricePerLiter)
        .put("liters", e.liters ?: JSONObject.NULL).put("note", e.note))

    @Synchronized
    fun saveCharge(e: ChargeEntry) = upsert("charge", e.id, JSONObject()
        .put("id", e.id).put("at", e.at.toString()).put("manual", e.manual)
        .put("kwh", e.kwh ?: JSONObject.NULL).put("cost", e.cost ?: JSONObject.NULL)
        .put("price", e.pricePerKwh ?: JSONObject.NULL).put("place", e.placeId ?: JSONObject.NULL).put("note", e.note))

    @Synchronized
    fun delete(id: String) {
        val o = read()
        for (k in listOf("fuel", "charge")) o.put(k, JSONArray(o.arr(k).objects().filter { it.str("id") != id }))
        write(o)
    }

    private fun upsert(key: String, id: String, entry: JSONObject) {
        val o = read()
        val arr = JSONArray(o.arr(key).objects().filter { it.str("id") != id })
        arr.put(entry)
        write(o.put(key, arr))
    }

    /** Für die Datensicherung. */
    @Synchronized
    fun toJson(): JSONObject = read()

    /** Einspielen: Einträge mit unbekannter ID werden ergänzt, vorhandene bleiben. */
    @Synchronized
    fun restore(b: JSONObject) {
        val o = read()
        for (key in listOf("fuel", "charge")) {
            val local = o.arr(key).objects()
            val ids = local.mapNotNull { it.str("id") }.toSet()
            val merged = JSONArray(local)
            b.arr(key).objects().filter { it.str("id") !in ids }.forEach { merged.put(it) }
            o.put(key, merged)
        }
        write(o)
    }

    companion object {
        fun newId(): String = UUID.randomUUID().toString()
    }
}
