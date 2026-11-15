package com.astraconnect.app.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TripParseTest {

    @Test
    fun apiFuel_isConvertedFromCentiliters() {
        val raw = JSONObject()
            .put("id", "t1")
            .put("startedAt", "2026-11-12T15:59:30Z")
            .put("stoppedAt", "2026-11-12T17:17:37Z")
            .put("distance", 122.5)
            .put("startMileage", 36123.9)
            .put("energyConsumptions", JSONArray().put(
                JSONObject().put("type", "Fuel").put("consumption", 600.9).put("avgConsumption", 490.5)
            ))
        val t = Trip.parse(JSONObject().put("key", "t1").put("raw", raw))
        assertEquals(6.009, t.fuelLiters!!, 1e-9)
        assertEquals(4.905, t.avgFuel!!, 1e-9)
        // End-Kilometerstand fehlt in den Rohdaten → Start + Strecke
        assertEquals(36246.4, t.endMileage!!, 1e-6)
        assertEquals(TripSource.API, t.source)
    }

    @Test
    fun myopEntry_isParsed() {
        val mt = JSONObject("""
            {"idPims":340,
             "start":{"street":"Am Soll","streetNumber":"20","city":"Lambrechtshagen","postalCode":"18069","country":"Deutschland",
                      "mileage":36246.4,"longitude":12.020534,"latitude":54.110143,"date":"2026-11-15T17:19:13.001Z"},
             "end":{"street":"Tilsitstraße","streetNumber":"14","city":"Lübeck","postalCode":"23569","country":"Deutschland",
                    "mileage":36362.9,"longitude":10.81915,"latitude":53.920842,"date":"2026-11-15T18:45:30.001Z"},
             "otherEnergyLevel":19,"fuelConsumption":6008960,"travelTime":5177,"distance":116.4}
        """.trimIndent())
        val t = Trip.parse(JSONObject().put("key", "myop-1").put("myop", mt))
        assertEquals(TripSource.MYOPEL, t.source)
        assertEquals(116.4, t.distanceKm!!, 1e-9)
        assertEquals(36362.9, t.endMileage!!, 1e-9)
        assertEquals(6.00896, t.fuelLiters!!, 1e-9)
        assertEquals(6.00896 / 116.4 * 100, t.avgFuel!!, 1e-9)
        assertEquals(19.0, t.socEnd!!, 1e-9)
        assertEquals("Am Soll 20, 18069 Lambrechtshagen", t.startAddress)
        assertEquals(54.110143, t.startPos!!.lat, 1e-9)
    }

    @Test
    fun myopZeroFuel_isElectric() {
        val mt = JSONObject().put("fuelConsumption", 0).put("distance", 4.6)
            .put("start", JSONObject().put("date", "2026-11-11T07:58:51.001Z"))
        val t = Trip.parse(JSONObject().put("key", "k").put("myop", mt))
        assertEquals(0.0, t.fuelLiters!!, 0.0)
        assertEquals(true, History.isElectric(t))
    }

    @Test
    fun apiWithoutFuelEntry_isElectric() {
        val raw = JSONObject().put("id", "e").put("distance", 4.5)
            .put("energyConsumptions", JSONArray().put(JSONObject().put("type", "Electric").put("consumption", 1.1)))
        assertEquals(0.0, Trip.parse(JSONObject().put("key", "e").put("raw", raw)).fuelLiters!!, 0.0)
    }

    @Test
    fun apiWithoutConsumptions_isUnknown() {
        val raw = JSONObject().put("id", "u").put("distance", 4.5)
        assertNull(Trip.parse(JSONObject().put("key", "u").put("raw", raw)).fuelLiters)
    }
}
