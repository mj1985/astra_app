package com.astraconnect.app.data

import java.time.Duration
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import kotlin.math.abs

/** Ein Ladevorgang, erkannt am steigenden Ladestand zwischen Messpunkten. */
data class ChargeSession(
    val start: Instant,
    val end: Instant,
    val socFrom: Double,
    val socTo: Double,
    /** Im Akku gelandete Energie (ohne Ladeverluste). */
    val kwh: Double,
    val ongoing: Boolean,
) {
    val duration: Duration get() = Duration.between(start, end)
}

/** Ein Tankvorgang, erkannt am Sprung im Tankstand. */
data class Refuel(
    val at: Instant,
    val levelFrom: Double,
    val levelTo: Double,
    val liters: Double,
    val mileage: Double?,
    /** Aus den eigenen Messpunkten oder aus dem myOpel-Export. */
    val fromMyop: Boolean,
)

/** Kennzahlen eines Monats. */
data class MonthStats(
    val month: YearMonth,
    val trips: Int,
    val km: Double,
    /** Fahrten ganz ohne Benzin. */
    val kmElectric: Double,
    /** Fahrten, bei denen Benzin verbraucht wurde. */
    val kmFuel: Double,
    /** Fahrten ohne Verbrauchsangabe. */
    val kmUnknown: Double,
    val fuelLiters: Double,
    /** km der Fahrten mit bekanntem Kraftstoffverbrauch (Bezugsgröße für l/100 km). */
    val kmWithFuelData: Double,
    val kwh: Double,
    val costFuel: Double,
    val costPower: Double,
) {
    val cost get() = costFuel + costPower
    val avgFuel: Double? get() = if (kmWithFuelData > 1) fuelLiters / kmWithFuelData * 100 else null
    val electricShare: Double? get() = (kmElectric + kmFuel).takeIf { it > 0 }?.let { kmElectric / it }
}

object History {

    /** Fahrt rein elektrisch (true), mit Benzin (false) oder unbekannt (null). */
    fun isElectric(t: Trip): Boolean? = t.fuelLiters?.let { it < 0.05 }

    /** Kosten einer Fahrt mit festen Preisen; null, wenn weder Kraftstoff noch Akku-Verbrauch bekannt ist. */
    fun tripCost(t: Trip, b: BatteryUse?, prices: Prices): Double? = CostModel(prices).tripCost(t, b)

    fun monthly(trips: List<Trip>, battery: Map<String, BatteryUse>, prices: Prices, zone: ZoneId = ZoneId.systemDefault()) =
        monthly(trips, battery, CostModel(prices), zone)

    fun monthly(
        trips: List<Trip>,
        battery: Map<String, BatteryUse>,
        costs: CostModel,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<MonthStats> = trips
        .filter { it.startedAt != null }
        .groupBy { YearMonth.from(it.startedAt!!.atZone(zone)) }
        .map { (m, list) ->
            fun km(sel: (Trip) -> Boolean) = list.filter(sel).sumOf { it.distanceKm ?: 0.0 }
            MonthStats(
                month = m,
                trips = list.size,
                km = km { true },
                kmElectric = km { isElectric(it) == true },
                kmFuel = km { isElectric(it) == false },
                kmUnknown = km { isElectric(it) == null },
                fuelLiters = list.sumOf { it.fuelLiters ?: 0.0 },
                kmWithFuelData = km { it.fuelLiters != null },
                kwh = list.sumOf { battery[it.key]?.kwh ?: 0.0 },
                costFuel = list.sumOf { costs.fuelCost(it) ?: 0.0 },
                costPower = list.sumOf { costs.powerCost(it, battery[it.key]) ?: 0.0 },
            )
        }
        .sortedBy { it.month }

    /**
     * Ladevorgänge aus den Messpunkten: Ein Vorgang beginnt beim letzten Messpunkt vor einem
     * Anstieg des Ladestands und endet, sobald der Ladestand nicht mehr steigt.
     */
    fun chargeSessions(snaps: List<EnergySnapshot>, capacityKwh: Double): List<ChargeSession> {
        val s = snaps.filter { it.soc != null }.sortedBy { it.at }
        val out = mutableListOf<ChargeSession>()
        var i = 0
        while (i < s.size - 1) {
            if (s[i + 1].soc!! > s[i].soc!! + 0.5) {
                var j = i + 1
                while (j + 1 < s.size && s[j + 1].soc!! > s[j].soc!! + 0.2) j++
                val from = s[i].soc!!
                val to = s[j].soc!!
                if (to - from >= 2.0) {
                    out += ChargeSession(
                        start = s[i].at, end = s[j].at, socFrom = from, socTo = to,
                        kwh = (to - from) / 100.0 * capacityKwh,
                        ongoing = j == s.size - 1 && s[j].charging,
                    )
                }
                i = j
            } else i++
        }
        return out.sortedByDescending { it.start }
    }

    /**
     * Tankvorgänge: Sprünge im Tankstand um mindestens [minJump] Prozentpunkte – aus den eigenen
     * Messpunkten und aus myOpel-Fahrten (Tankstand bei Ankunft). Liegen zwei Erkennungen weniger
     * als 12 h auseinander, gilt die aus den Messpunkten.
     */
    fun refuels(trips: List<Trip>, snaps: List<EnergySnapshot>, tankLiters: Double, minJump: Double = 8.0): List<Refuel> {
        val fromSnaps = snaps.filter { it.fuel != null }.sortedBy { it.at }.zipWithNext()
            .filter { (a, b) -> b.fuel!! - a.fuel!! >= minJump }
            .map { (a, b) -> Refuel(b.at, a.fuel!!, b.fuel!!, (b.fuel - a.fuel) / 100 * tankLiters, b.mileage, false) }

        // myOpel: Tankstand bei Ankunft; Sprung zwischen zwei aufeinanderfolgenden Fahrten
        // (die zweite Fahrt hat selbst schon etwas verbraucht → Menge eher zu niedrig)
        val fromTrips = trips.filter { it.fuelLevelEnd != null && it.startedAt != null }
            .sortedBy { it.startedAt }
            .zipWithNext()
            .filter { (a, b) -> b.fuelLevelEnd!! - a.fuelLevelEnd!! >= minJump }
            .map { (a, b) ->
                Refuel(b.startedAt!!, a.fuelLevelEnd!!, b.fuelLevelEnd!!, (b.fuelLevelEnd - a.fuelLevelEnd) / 100 * tankLiters, b.startMileage, true)
            }

        val merged = fromSnaps.toMutableList()
        for (r in fromTrips) {
            if (merged.none { abs(Duration.between(it.at, r.at).toHours()) < 12 }) merged += r
        }
        return merged.sortedByDescending { it.at }
    }

    /** Median – robust gegen einzelne Ausreißer. */
    fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val s = values.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }
}
