package com.astraconnect.app.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Instant

class TripStoreTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var store: TripStore

    private val home = LatLon(53.92085, 10.81911)
    private val work = LatLon(53.89997, 10.79254)

    @Before
    fun setUp() {
        store = TripStore(tmp.newFile("fahrtenbuch.json").also { it.delete() }, tmp.newFolder("exports"))
    }

    private fun api(id: String, start: String, stop: String, km: Double, startKm: Double, from: LatLon = home, to: LatLon = work) =
        JSONObject().put("id", id).put("startedAt", start).put("stoppedAt", stop).put("distance", km)
            .put("startMileage", startKm)
            .put("startPosition", JSONObject().put("latitude", from.lat).put("longitude", from.lon))
            .put("stopPosition", JSONObject().put("latitude", to.lat).put("longitude", to.lon))

    private fun myopTrip(start: String, stop: String, km: Double, startKm: Double, fuelMicroL: Long = 0, fuelLevel: Int = 50) = JSONObject()
        .put("start", JSONObject().put("street", "Tilsitstraße").put("streetNumber", "6").put("postalCode", "23569")
            .put("city", "Lübeck").put("country", "Deutschland").put("mileage", startKm)
            .put("latitude", home.lat).put("longitude", home.lon).put("date", start))
        .put("end", JSONObject().put("street", "Seelandstraße").put("streetNumber", "14").put("postalCode", "23569")
            .put("city", "Lübeck").put("country", "Deutschland").put("mileage", startKm + km)
            .put("latitude", work.lat).put("longitude", work.lon).put("date", stop))
        .put("distance", km).put("fuelConsumption", fuelMicroL).put("fuelLevel", fuelLevel).put("otherEnergyLevel", 80)

    private fun myopFile(vin: String, vararg trips: JSONObject) =
        JSONArray().put(JSONObject().put("vin", vin).put("trips", JSONArray(trips.toList()))).toString()

    // ------------------------------------------------------------------ myOpel-Import

    @Test
    fun import_matchesByStartTime_andAddsUnknownTrips() {
        store.merge(listOf(api("a1", "2026-11-10T07:00:00Z", "2026-11-10T07:12:00Z", 4.6, 100.0)))
        val file = myopFile(
            "VIN1",
            myopTrip("2026-11-10T07:01:30.001Z", "2026-11-10T07:12:30.001Z", 4.6, 100.0),   // passt (±3 min)
            myopTrip("2026-11-11T07:00:00.001Z", "2026-11-11T07:12:00.001Z", 4.6, 104.6),   // neu
        )
        val r = store.importMyop(file, "VIN1")
        assertEquals(2, r.read); assertEquals(1, r.matched); assertEquals(1, r.added)

        val trips = store.load()
        assertEquals(2, trips.size)
        val matched = trips.first { it.key == "a1" }
        assertEquals(TripSource.BOTH, matched.source)
        assertEquals("Tilsitstraße 6, 23569 Lübeck", matched.startAddress)
        assertEquals(TripSource.MYOPEL, trips.first { it.key != "a1" }.source)
    }

    @Test
    fun import_matchesByMileage_within30Minutes() {
        store.merge(listOf(api("a1", "2026-11-10T07:00:00Z", "2026-11-10T07:12:00Z", 4.6, 100.0)))
        val r = store.importMyop(myopFile("VIN1", myopTrip("2026-11-10T07:20:00.001Z", "2026-11-10T07:30:00.001Z", 4.6, 100.4)), "VIN1")
        assertEquals(1, r.matched)
    }

    @Test
    fun import_twice_doesNotDuplicate() {
        val file = myopFile("VIN1", myopTrip("2026-11-11T07:00:00.001Z", "2026-11-11T07:12:00.001Z", 4.6, 104.6))
        store.importMyop(file, "VIN1")
        val r = store.importMyop(file, "VIN1")
        assertEquals(1, r.matched); assertEquals(0, r.added)
        assertEquals(1, store.load().size)
    }

    @Test
    fun import_otherVehicle_isSkipped() {
        val r = store.importMyop(myopFile("OTHER", myopTrip("2026-11-11T07:00:00.001Z", "2026-11-11T07:12:00.001Z", 4.6, 1.0)), "VIN1")
        assertEquals(1, r.skippedOtherVin); assertEquals(0, store.load().size)
    }

    @Test
    fun apiTrip_arrivingLater_replacesImportedTwin_andKeepsCategory() {
        store.importMyop(myopFile("VIN1", myopTrip("2026-11-11T07:00:00.001Z", "2026-11-11T07:12:00.001Z", 4.6, 104.6)), "VIN1")
        val key = store.load().single().key
        store.update(key, category = TripCategory.BUSINESS, note = "Kunde X")

        val added = store.merge(listOf(api("srv", "2026-11-11T07:00:40Z", "2026-11-11T07:12:10Z", 4.6, 104.6)))
        assertEquals(0, added)
        val t = store.load().single()
        assertEquals("srv", t.key)
        assertEquals(TripSource.BOTH, t.source)
        assertEquals(TripCategory.BUSINESS, t.category)
        assertEquals("Kunde X", t.note)
    }

    @Test
    fun newestStart_ignoresImportedTrips() {
        store.merge(listOf(api("a1", "2026-11-01T07:00:00Z", "2026-11-01T07:12:00Z", 4.6, 100.0)))
        store.importMyop(myopFile("VIN1", myopTrip("2026-11-14T07:00:00.001Z", "2026-11-14T07:12:00.001Z", 4.6, 200.0)), "VIN1")
        assertEquals(Instant.parse("2026-11-01T07:00:00Z"), store.newestStart())
    }

    // ------------------------------------------------------------------ Zusammenführen

    @Test
    fun mergeWithPrevious_combinesAndSplitRestores() {
        store.merge(
            listOf(
                api("p1", "2026-11-10T07:00:00Z", "2026-11-10T07:10:00Z", 4.0, 100.0),
                api("p2", "2026-11-10T07:12:00Z", "2026-11-10T07:14:00Z", 0.5, 104.0),
            )
        )
        store.update("p2", category = TripCategory.COMMUTE)
        val mergedKey = store.mergeWithPrevious("p2")
        assertEquals("p1", mergedKey)

        val m = store.load().single()
        assertTrue(m.isMerged)
        assertEquals(4.5, m.distanceKm!!, 1e-9)
        assertEquals(100.0, m.startMileage!!, 1e-9)
        assertEquals(104.5, m.endMileage!!, 1e-9)
        assertEquals(Instant.parse("2026-11-10T07:14:00Z"), m.stoppedAt)
        assertEquals(TripCategory.COMMUTE, m.category) // übernommen, weil p1 noch offen war

        // Erneuter API-Abruf darf die Teilfahrt nicht wieder als eigene Fahrt anlegen
        assertEquals(0, store.merge(listOf(api("p2", "2026-11-10T07:12:00Z", "2026-11-10T07:14:00Z", 0.5, 104.0))))
        assertEquals(1, store.load().size)

        store.split("p1")
        val parts = store.load()
        assertEquals(2, parts.size)
        assertTrue(parts.all { it.category == TripCategory.COMMUTE })
    }

    @Test
    fun mergeWithPrevious_withoutPredecessor_returnsNull() {
        store.merge(listOf(api("p1", "2026-11-10T07:00:00Z", "2026-11-10T07:10:00Z", 4.0, 100.0)))
        assertNull(store.mergeWithPrevious("p1"))
    }

    // ------------------------------------------------------------------ Regeln

    @Test
    fun applyRules_setsOnlyOpenTrips_andMarksAuto() {
        store.merge(
            listOf(
                api("r1", "2026-11-10T07:00:00Z", "2026-11-10T07:10:00Z", 4.6, 100.0),
                api("r2", "2026-11-10T15:00:00Z", "2026-11-10T15:10:00Z", 4.6, 104.6, from = work, to = home),
            )
        )
        store.update("r2", category = TripCategory.PRIVATE) // von Hand
        val places = listOf(Place("h", "Zuhause", home.lat, home.lon), Place("w", "Arbeit", work.lat, work.lon))
        val rules = listOf(CategoryRule("x", "h", "w", TripCategory.COMMUTE, bothWays = true))

        val n = store.applyRules { PlaceBook.categoryFor(it, places, rules) }
        assertEquals(1, n)
        val r1 = store.load().first { it.key == "r1" }
        assertEquals(TripCategory.COMMUTE, r1.category)
        assertTrue(r1.autoCategory)
        assertEquals(TripCategory.PRIVATE, store.load().first { it.key == "r2" }.category)

        // Manuelles Ändern hebt die Auto-Markierung auf
        store.update("r1", category = TripCategory.BUSINESS)
        assertFalse(store.load().first { it.key == "r1" }.autoCategory)
    }

    // ------------------------------------------------------------------ Sicherung

    @Test
    fun restore_addsMissingTrips_andFillsEmptyCategories() {
        store.merge(listOf(api("b1", "2026-11-10T07:00:00Z", "2026-11-10T07:10:00Z", 4.6, 100.0)))
        val other = TripStore(tmp.newFile("other.json").also { it.delete() }, tmp.newFolder("e2"))
        other.merge(
            listOf(
                api("b1", "2026-11-10T07:00:00Z", "2026-11-10T07:10:00Z", 4.6, 100.0),
                api("b2", "2026-11-11T07:00:00Z", "2026-11-11T07:10:00Z", 4.6, 104.6),
            )
        )
        other.update("b1", category = TripCategory.BUSINESS, note = "Termin")

        store.restore(other.rawEntries())
        val trips = store.load()
        assertEquals(2, trips.size)
        val b1 = trips.first { it.key == "b1" }
        assertEquals(TripCategory.BUSINESS, b1.category)
        assertEquals("Termin", b1.note)
    }

    @Test
    fun csvExport_containsCostAndPlaceName() {
        store.merge(listOf(api("c1", "2026-11-10T07:00:00Z", "2026-11-10T07:10:00Z", 4.6, 100.0)))
        val f = store.exportCsv(store.load(), cost = { 1.23 }, placeName = { if (it == home) "Zuhause" else null })
        val text = f.readText()
        assertTrue(text.contains("Kosten €"))
        assertTrue(text.contains("1,23"))
        assertTrue(text.contains("Zuhause – "))
        assertNotNull(text)
    }
}
