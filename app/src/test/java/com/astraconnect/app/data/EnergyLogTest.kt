package com.astraconnect.app.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class EnergyLogTest {
    private val t0 = Instant.parse("2026-11-10T08:00:00Z")
    private fun at(min: Long) = t0.plusSeconds(min * 60)

    private fun trip(key: String, startMin: Long, endMin: Long, km: Double, startKm: Double) = Trip(
        key = key, startedAt = at(startMin), stoppedAt = at(endMin), distanceKm = km,
        startMileage = startKm, endMileage = startKm + km, startPos = null, endPos = null,
        fuelLiters = null, electricKwh = null, avgFuel = null, avgElectric = null, socEnd = null,
        category = TripCategory.UNSET, note = "", startAddress = null, endAddress = null,
        source = TripSource.API, raw = JSONObject(), myop = null,
    )

    @Test
    fun singleTrip_usesSocDifference() {
        val snaps = listOf(
            EnergySnapshot(at(-60), 1000.0, 80.0, false),
            EnergySnapshot(at(31), 1020.0, 60.0, false),
        )
        val r = EnergyLog.compute(listOf(trip("a", 0, 30, 20.0, 1000.0)), snaps, 10.0)["a"]!!
        assertEquals(2.0, r.kwh!!, 1e-9)
        assertEquals(10.0, r.per100km(20.0)!!, 1e-9)
        assertTrue(!r.shared)
    }

    @Test
    fun twoTripsBetweenSameSnapshots_areSplitByDistance() {
        val snaps = listOf(
            EnergySnapshot(at(-10), 1000.0, 90.0, false),
            EnergySnapshot(at(100), 1040.0, 50.0, false),
        )
        val trips = listOf(trip("a", 0, 30, 10.0, 1000.0), trip("b", 50, 90, 30.0, 1010.0))
        val r = EnergyLog.compute(trips, snaps, 10.0)
        assertEquals(1.0, r["a"]!!.kwh!!, 1e-9)
        assertEquals(3.0, r["b"]!!.kwh!!, 1e-9)
        assertTrue(r["a"]!!.shared)
    }

    @Test
    fun chargingInBetween_isNotCalculated() {
        val snaps = listOf(
            EnergySnapshot(at(-60), 1000.0, 40.0, false),
            EnergySnapshot(at(-30), 1000.0, 70.0, true),
            EnergySnapshot(at(31), 1020.0, 60.0, false),
        )
        // Messpunkt -30 (lädt) ist der letzte vor Fahrtbeginn → ab dort gerechnet: 70 → 60
        val r = EnergyLog.compute(listOf(trip("a", 0, 30, 20.0, 1000.0)), snaps, 10.0)["a"]!!
        assertEquals(1.0, r.kwh!!, 1e-9)

        val snaps2 = listOf(
            EnergySnapshot(at(-60), 1000.0, 40.0, false),
            EnergySnapshot(at(31), 1020.0, 60.0, false),
        )
        val r2 = EnergyLog.compute(listOf(trip("a", 0, 30, 20.0, 1000.0)), snaps2, 10.0)["a"]!!
        assertNull(r2.kwh)
        assertNotNull(r2.note)
    }

    @Test
    fun lastReportBeforeOpelStopTime_countsByMileage() {
        // Auto meldet beim Abstellen um +22 (km-Stand schon am Ziel), Opel setzt das Fahrtende auf +30
        val snaps = listOf(
            EnergySnapshot(at(-60), 1000.0, 80.0, false),
            EnergySnapshot(at(22), 1020.0, 60.0, false),
        )
        val r = EnergyLog.compute(listOf(trip("a", 0, 30, 20.0, 1000.0)), snaps, 10.0)["a"]!!
        assertEquals(2.0, r.kwh!!, 1e-9)
    }

    @Test
    fun reportDuringTrip_isNotUsedAsEnd() {
        // Letzte Meldung unterwegs (km 1010 von 1020) → kein Endpunkt, Hinweis statt falschem Wert
        val snaps = listOf(
            EnergySnapshot(at(-60), 1000.0, 80.0, false),
            EnergySnapshot(at(15), 1010.0, 70.0, false),
        )
        val r = EnergyLog.compute(listOf(trip("a", 0, 30, 20.0, 1000.0)), snaps, 10.0)["a"]!!
        assertNull(r.kwh)
        assertEquals(80.0, r.socStart!!, 1e-9)
        assertTrue(r.note!!.startsWith("Noch kein Messpunkt nach"))
    }

    @Test
    fun chargingStartedRightAfterArrival_isCalculatedAsApprox() {
        // Zuhause sofort eingesteckt: erste Meldung nach der Fahrt ist schon „lädt“, Ladestand noch der bei Ankunft
        val snaps = listOf(
            EnergySnapshot(at(-60), 1000.0, 53.0, false),
            EnergySnapshot(at(33), 1020.0, 27.0, true),
            EnergySnapshot(at(60), 1020.0, 40.0, true),
        )
        val r = EnergyLog.compute(listOf(trip("a", 0, 30, 20.0, 1000.0)), snaps, 10.0)["a"]!!
        assertEquals(2.6, r.kwh!!, 1e-9)
        assertTrue(r.approx)
        assertTrue(r.estimated)
    }

    @Test
    fun chargingReportedLongAfterArrival_isNotCalculated() {
        // Erste Meldung erst 2 h später und schon beim Laden → Ladestand bei Ankunft unbekannt
        val snaps = listOf(
            EnergySnapshot(at(-60), 1000.0, 53.0, false),
            EnergySnapshot(at(150), 1020.0, 35.0, true),
        )
        val r = EnergyLog.compute(listOf(trip("a", 0, 30, 20.0, 1000.0)), snaps, 10.0)["a"]!!
        assertNull(r.kwh)
        assertTrue(r.note!!.contains("geladen"))
    }

    @Test
    fun noSnapshots_givesNote() {
        val r = EnergyLog.compute(listOf(trip("a", 0, 30, 20.0, 1000.0)), listOf(EnergySnapshot(at(40), 1020.0, 60.0, false)), 10.0)["a"]!!
        assertNull(r.kwh)
        assertNotNull(r.note)
    }
}
