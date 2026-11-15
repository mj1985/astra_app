package com.astraconnect.app.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset

class HistoryAndPlacesTest {
    private val t0 = Instant.parse("2026-11-10T18:00:00Z")
    private fun at(min: Long) = t0.plusSeconds(min * 60)
    private fun snap(min: Long, soc: Double, ch: Boolean = false, fuel: Double? = null) = EnergySnapshot(at(min), 1000.0, soc, ch, fuel)

    private fun trip(key: String, start: Instant, km: Double, fuel: Double?, from: LatLon? = null, to: LatLon? = null, fuelLevelEnd: Double? = null) = Trip(
        key = key, startedAt = start, stoppedAt = start.plusSeconds(600), distanceKm = km, startMileage = null, endMileage = null,
        startPos = from, endPos = to, fuelLiters = fuel, electricKwh = null, avgFuel = null, avgElectric = null, socEnd = null,
        category = TripCategory.UNSET, note = "", startAddress = null, endAddress = null, source = TripSource.API,
        raw = JSONObject(), myop = null, fuelLevelEnd = fuelLevelEnd,
    )

    // ------------------------------------------------------------------ Laden

    @Test
    fun chargeSession_detectedFromRisingSoc() {
        val snaps = listOf(
            snap(0, 20.0), snap(15, 20.0), snap(30, 35.0, ch = true), snap(45, 60.0, ch = true),
            snap(60, 85.0, ch = true), snap(75, 100.0), snap(90, 100.0), snap(200, 80.0),
        )
        val s = History.chargeSessions(snaps, capacityKwh = 10.0).single()
        assertEquals(20.0, s.socFrom, 0.0)
        assertEquals(100.0, s.socTo, 0.0)
        assertEquals(8.0, s.kwh, 1e-9)
        assertEquals(at(15), s.start)
        assertEquals(at(75), s.end)
    }

    @Test
    fun chargeSession_ignoresNoise() {
        assertTrue(History.chargeSessions(listOf(snap(0, 50.0), snap(15, 51.0), snap(30, 49.0)), 10.0).isEmpty())
    }

    // ------------------------------------------------------------------ Tanken

    @Test
    fun refuel_fromSnapshots_andMyopWithoutDuplicates() {
        val snaps = listOf(snap(0, 50.0, fuel = 24.0), snap(60, 50.0, fuel = 100.0))
        val trips = listOf(
            trip("a", at(-30), 5.0, 0.0, fuelLevelEnd = 24.0),
            trip("b", at(90), 5.0, 0.0, fuelLevelEnd = 100.0),          // gleicher Tankvorgang → nicht doppelt
            trip("c", at(60 * 24 * 10), 100.0, 6.0, fuelLevelEnd = 60.0),
            trip("d", at(60 * 24 * 11), 5.0, 0.0, fuelLevelEnd = 98.0), // zweiter Tankvorgang (nur myOpel)
        )
        val r = History.refuels(trips, snaps, tankLiters = 40.0)
        assertEquals(2, r.size)
        val fromSnap = r.last()
        assertEquals(30.4, fromSnap.liters, 1e-9)
        assertEquals(false, fromSnap.fromMyop)
        assertEquals(true, r.first().fromMyop)
        assertEquals(15.2, r.first().liters, 1e-9)
    }

    // ------------------------------------------------------------------ Monate & Kosten

    @Test
    fun monthly_splitsElectricAndFuel_andComputesCost() {
        val nov = Instant.parse("2026-11-05T08:00:00Z")
        val trips = listOf(
            trip("e", nov, 10.0, 0.0),
            trip("f", nov.plusSeconds(3600), 100.0, 5.0),
            trip("u", nov.plusSeconds(7200), 3.0, null),
        )
        val battery = mapOf("e" to BatteryUse(80.0, 60.0, 2.0, false, null))
        val prices = Prices(fuelPerLiter = 2.0, powerPerKwh = 0.5, chargeLossPct = 10.0)
        val m = History.monthly(trips, battery, prices, ZoneOffset.UTC).single()
        assertEquals(YearMonth.of(2026, 11), m.month)
        assertEquals(10.0, m.kmElectric, 0.0)
        assertEquals(100.0, m.kmFuel, 0.0)
        assertEquals(3.0, m.kmUnknown, 0.0)
        assertEquals(5.0 / 110.0 * 100, m.avgFuel!!, 1e-9)
        assertEquals(10.0, m.costFuel, 1e-9)
        assertEquals(2.0 * 1.1 * 0.5, m.costPower, 1e-9)
        assertEquals(10.0 / 110.0, m.electricShare!!, 1e-9)

        assertEquals(1.1, History.tripCost(trips[0], battery["e"], prices)!!, 1e-9)
        assertNull(History.tripCost(trips[2], null, prices))
    }

    @Test
    fun median_isRobust() {
        assertEquals(100.0, History.median(listOf(99.0, 100.0, 5000.0))!!, 0.0)
        assertNull(History.median(emptyList()))
    }

    // ------------------------------------------------------------------ Orte & Regeln

    private val home = LatLon(53.92085, 10.81911)
    private val work = LatLon(53.89997, 10.79254)
    private val places = listOf(Place("h", "Zuhause", home.lat, home.lon), Place("w", "Arbeit", work.lat, work.lon))

    @Test
    fun placeAt_respectsRadius() {
        assertEquals("h", PlaceBook.placeAt(places, LatLon(53.9210, 10.8193))?.id) // ~ 20 m
        assertNull(PlaceBook.placeAt(places, LatLon(53.9300, 10.8193)))           // ~ 1 km
    }

    @Test
    fun categoryFor_prefersSpecificRule_andHonoursDirection() {
        val rules = listOf(
            CategoryRule("1", "h", "w", TripCategory.COMMUTE, bothWays = false),
            CategoryRule("2", null, "w", TripCategory.BUSINESS, bothWays = false),
        )
        assertEquals(TripCategory.COMMUTE, PlaceBook.categoryFor(trip("a", t0, 4.6, 0.0, home, work), places, rules))
        // Rückfahrt: Regel 1 gilt nur in eine Richtung, Regel 2 nur mit Ziel Arbeit → keine Zuordnung
        assertNull(PlaceBook.categoryFor(trip("b", t0, 4.6, 0.0, work, home), places, rules))
        // irgendwo → Arbeit = Regel 2
        assertEquals(TripCategory.BUSINESS, PlaceBook.categoryFor(trip("c", t0, 9.0, 0.0, LatLon(53.96, 10.86), work), places, rules))
    }

    @Test
    fun categoryFor_conflictingRules_giveNoCategory() {
        val rules = listOf(
            CategoryRule("1", "h", "w", TripCategory.COMMUTE),
            CategoryRule("2", "w", "h", TripCategory.PRIVATE),
        )
        assertNull(PlaceBook.categoryFor(trip("a", t0, 4.6, 0.0, home, work), places, rules))
    }

    @Test
    fun suggestions_listFrequentUnnamedSpots() {
        val shop = LatLon(53.9135, 10.7320)
        val trips = (0 until 4).map { trip("t$it", t0, 5.0, 0.0, home, shop) }
        val s = PlaceBook.suggestions(trips, listOf(places.first()))
        assertEquals(1, s.size)          // Zuhause ist schon benannt
        assertEquals(4, s.single().visits)
    }
}
