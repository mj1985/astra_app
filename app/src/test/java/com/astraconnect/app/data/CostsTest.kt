package com.astraconnect.app.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant

class CostsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val t0 = Instant.parse("2026-11-01T08:00:00Z")
    private fun day(d: Long) = t0.plusSeconds(d * 86400)
    private val prices = Prices(fuelPerLiter = 1.80, powerPerKwh = 0.40, chargeLossPct = 10.0)
    private val home = LatLon(53.92085, 10.81911)
    private val work = LatLon(53.89997, 10.79254)
    private val places = listOf(
        Place("h", "Zuhause", home.lat, home.lon, powerPrice = 0.30),
        Place("w", "Arbeit", work.lat, work.lon, powerPrice = 0.0),
    )

    private fun trip(key: String, start: Instant, fuel: Double?, to: LatLon? = null) = Trip(
        key = key, startedAt = start, stoppedAt = start.plusSeconds(600), distanceKm = 10.0, startMileage = null, endMileage = null,
        startPos = null, endPos = to, fuelLiters = fuel, electricKwh = null, avgFuel = null, avgElectric = null, socEnd = null,
        category = TripCategory.UNSET, note = "", startAddress = null, endAddress = null, source = TripSource.API,
        raw = JSONObject(), myop = null,
    )

    private fun session(start: Instant, kwh: Double) =
        ChargeSession(start, start.plusSeconds(3 * 3600), 20.0, 20.0 + kwh * 10, kwh, ongoing = false)

    @Test
    fun fuelPrice_appliesFromRefuelOnwards() {
        val m = CostModel(prices, listOf(FuelEntry("a", day(3), 1.70, 30.0), FuelEntry("b", day(10), 1.90, 35.0)))
        assertEquals(1.80, m.fuelPriceAt(day(1)), 1e-9)   // vor dem ersten Eintrag: Standard
        assertEquals(1.70, m.fuelPriceAt(day(5)), 1e-9)
        assertEquals(1.90, m.fuelPriceAt(day(12)), 1e-9)
        assertEquals(5 * 1.70, m.tripCost(trip("x", day(5), 5.0), null)!!, 1e-9)
    }

    @Test
    fun chargeRows_useEnteredCost_thenPlacePrice_thenDefault() {
        val trips = listOf(
            trip("toHome", day(1).minusSeconds(3600), 0.0, home),
            trip("toWork", day(2).minusSeconds(3600), 0.0, work),
            trip("elsewhere", day(3).minusSeconds(3600), 0.0, LatLon(53.0, 10.0)),
        )
        val sessions = listOf(session(day(1), 5.0), session(day(2), 5.0), session(day(3), 5.0), session(day(4), 5.0))
        val entries = listOf(ChargeEntry("c", day(4).plusSeconds(600), manual = false, cost = 4.0))
        val rows = CostModel.chargeRows(sessions, entries, trips, places, prices).associateBy { it.start }

        assertEquals("Zuhause", rows[day(1)]!!.place?.name)
        assertEquals(5.0 * 1.1 * 0.30, rows[day(1)]!!.cost, 1e-9)
        assertEquals(CostSource.PLACE, rows[day(1)]!!.costSource)
        assertEquals(0.0, rows[day(2)]!!.cost, 1e-9)                  // Arbeit: kostenlos
        assertNull(rows[day(3)]!!.place)
        assertEquals(5.0 * 1.1 * 0.40, rows[day(3)]!!.cost, 1e-9)     // Standardpreis
        assertEquals(CostSource.DEFAULT, rows[day(3)]!!.costSource)
        assertEquals(4.0, rows[day(4)]!!.cost, 1e-9)                  // eingetragen
        assertEquals(CostSource.ENTERED, rows[day(4)]!!.costSource)
    }

    @Test
    fun manualCharge_isAdded_andDrivesTripPowerPrice() {
        val manual = ChargeEntry("m", day(5), manual = true, kwh = 11.0, pricePerKwh = 0.59)
        val rows = CostModel.chargeRows(emptyList(), listOf(manual), emptyList(), places, prices)
        val r = rows.single()
        assertEquals(11.0 * 0.59, r.cost, 1e-9)
        assertEquals(10.0, r.kwhBattery, 1e-9)

        val m = CostModel(prices, emptyList(), rows)
        // Fahrt danach: 2 kWh aus dem Akku × (Kosten / im Akku gelandete kWh)
        assertEquals(2.0 * 11.0 * 0.59 / 10.0, m.powerCost(trip("e", day(6), 0.0), BatteryUse(80.0, 60.0, 2.0, false, null))!!, 1e-9)
        // Fahrt davor: Standardpreis inkl. Verluste
        assertEquals(2.0 * 0.40 * 1.1, m.powerCost(trip("f", day(4), 0.0), BatteryUse(80.0, 60.0, 2.0, false, null))!!, 1e-9)
    }

    @Test
    fun fuelRows_matchEntryToDetectedRefuel() {
        val refuel = Refuel(day(3), 20.0, 95.0, 30.0, 1000.0, false)
        val rows = CostModel.fuelRows(listOf(refuel), listOf(FuelEntry("a", day(3).plusSeconds(3600), 1.75, null), FuelEntry("b", day(9), 1.85, 20.0)))
        assertEquals(2, rows.size)
        val matched = rows.first { it.refuel != null }
        assertEquals(30.0 * 1.75, matched.cost!!, 1e-9)   // Liter aus der Schätzung
        assertEquals(20.0 * 1.85, rows.first { it.refuel == null }.cost!!, 1e-9)
    }

    @Test
    fun costBook_roundTrip_andRestore() {
        val book = CostBook(tmp.newFile("k.json").also { it.delete() })
        book.saveFuel(FuelEntry("a", day(1), 1.79, 30.0, "Aral"))
        book.saveCharge(ChargeEntry("c", day(2), manual = true, kwh = 10.0, cost = 5.5, placeId = "h"))
        assertEquals("Aral", book.fuel().single().note)
        assertEquals(5.5, book.charges().single().cost!!, 0.0)

        val other = CostBook(tmp.newFile("k2.json").also { it.delete() })
        other.restore(book.toJson())
        other.restore(book.toJson())
        assertEquals(1, other.fuel().size)
        assertEquals(1, other.charges().size)

        book.delete("a")
        assertEquals(0, book.fuel().size)
    }
}
