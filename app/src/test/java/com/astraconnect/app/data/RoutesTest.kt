package com.astraconnect.app.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Instant

class RoutesTest {
    @get:Rule val tmp = TemporaryFolder()

    private val t0 = Instant.parse("2026-11-22T08:00:00Z")
    private fun at(min: Long) = t0.plusSeconds(min * 60)
    private val home = LatLon(52.50, 13.40)
    private val work = LatLon(52.55, 13.50)

    private fun trip(startMin: Long, endMin: Long) = Trip(
        key = "t", startedAt = at(startMin), stoppedAt = at(endMin), distanceKm = 12.0, startMileage = null, endMileage = null,
        startPos = home, endPos = work, fuelLiters = null, electricKwh = null, avgFuel = null, avgElectric = null, socEnd = null,
        category = TripCategory.UNSET, note = "", startAddress = null, endAddress = null, source = TripSource.API,
        raw = JSONObject(), myop = null,
    )

    private fun status(pos: LatLon?, posAt: Instant?, updatedAt: Instant = t0, moving: Boolean? = null, ignition: String? = null): VehicleStatus {
        val o = JSONObject().put("lastUpdate", updatedAt.toString())
        if (pos != null) {
            o.put(
                "lastPosition", JSONObject()
                    .put("geometry", JSONObject().put("type", "Point").put("coordinates", JSONArray().put(pos.lon).put(pos.lat)))
                    .put("properties", JSONObject().apply { posAt?.let { put("createdAt", it.toString()) } })
            )
        }
        moving?.let { o.put("kinetic", JSONObject().put("moving", it)) }
        ignition?.let { o.put("ignition", JSONObject().put("type", it)) }
        return VehicleStatus.parse(o)
    }

    @Test
    fun fromTrack_buildsStartPointsEnd() {
        val track = listOf(
            TrackPoint(at(-10), LatLon(1.0, 1.0)), // vor der Fahrt
            TrackPoint(at(6), LatLon(52.53, 13.47)),
            TrackPoint(at(3), LatLon(52.52, 13.44)),
            TrackPoint(at(30), LatLon(2.0, 2.0)), // nach der Fahrt
        )
        val r = TripRoute.fromTrack(trip(0, 10), track)!!
        assertEquals(listOf(home, LatLon(52.52, 13.44), LatLon(52.53, 13.47), work), r.points)
    }

    @Test
    fun pointsOf_keepsTimesSortedWithinTrip() {
        val track = listOf(
            TrackPoint(at(6), LatLon(52.53, 13.47)),
            TrackPoint(at(-5), LatLon(1.0, 1.0)),
            TrackPoint(at(11), LatLon(52.54, 13.49)), // 1 Min. nach Ankunft – zählt noch
            TrackPoint(at(3), LatLon(52.52, 13.44)),
        )
        assertEquals(listOf(at(3), at(6), at(11)), TripRoute.pointsOf(trip(0, 10), track).map { it.at })
    }

    @Test
    fun fromTrack_nullWithoutPointsDuringTrip() {
        assertNull(TripRoute.fromTrack(trip(0, 10), listOf(TrackPoint(at(20), LatLon(1.0, 1.0)))))
        assertNull(TripRoute.fromTrack(trip(0, 10), emptyList()))
    }

    @Test
    fun trackLog_recordsOncePerTimestampAndPersists() {
        val f = File(tmp.root, "positionen.json")
        val log = TrackLog(f)
        assertTrue(log.record(status(home, at(1)), now = at(2)))
        assertFalse(log.record(status(home, at(1)), now = at(2))) // gleicher Zeitpunkt
        assertFalse(log.record(status(null, null), now = at(2)))
        assertTrue(log.record(status(work, at(5)), now = at(6)))
        val reloaded = TrackLog(f)
        assertEquals(2, reloaded.points().size)
        assertEquals(listOf(work), reloaded.between(at(4), at(6)).map { it.pos })
    }

    @Test
    fun isDriving_needsFreshDataAndMotion() {
        val now = at(3)
        assertTrue(TripFollow.isDriving(status(home, at(2), updatedAt = at(2), moving = true), now))
        assertTrue(TripFollow.isDriving(status(home, at(2), updatedAt = at(2), ignition = "StartUp"), now))
        assertFalse(TripFollow.isDriving(status(home, at(2), updatedAt = at(2), moving = false, ignition = "Stop"), now))
        // veraltet (älter als 10 Min.) → nicht mehr „fährt gerade“
        assertFalse(TripFollow.isDriving(status(home, at(-20), updatedAt = at(-20), moving = true), now))
    }
}
