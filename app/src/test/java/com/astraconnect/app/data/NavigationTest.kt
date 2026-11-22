package com.astraconnect.app.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NavigationTest {
    @Test
    fun remoteBody_isGeoJsonWithLongitudeFirst() {
        val body = Navigation.remoteBody(listOf(LatLon(52.52, 13.405)))
        val p = body.getJSONObject("navigation").getJSONArray("positions").getJSONObject(0)
        assertEquals("Point", p.getString("type"))
        assertEquals(13.405, p.getJSONArray("coordinates").getDouble(0), 0.0)
        assertEquals(52.52, p.getJSONArray("coordinates").getDouble(1), 0.0)
        assertFalse(body.getJSONObject("navigation").has("driverApproval"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun remoteBody_rejectsMoreThanTenPoints() {
        Navigation.remoteBody(List(11) { LatLon(50.0, 8.0) })
    }

    @Test
    fun callbackBody_usesUnresolvableWebhook() {
        val target = Navigation.callbackBody().getJSONObject("callback").getJSONObject("webhook").getString("target")
        assertTrue(target.contains(".invalid/"))
    }

    @Test
    fun findCallbackId_picksOwnRunningCallback() {
        val list = JSONObject(
            """{"_embedded":{"callbacks":[
                {"id":"other","label":"Fremd","status":"Running"},
                {"id":"broken","label":"AstraConnect","status":"Failed"},
                {"id":"mine","label":"AstraConnect","status":"Running"}]}}"""
        )
        assertEquals("mine", Navigation.findCallbackId(list))
        assertNull(Navigation.findCallbackId(JSONObject("{}")))
    }

    @Test
    fun supported_readsOnboardCapabilities() {
        val v = JSONObject("""{"_embedded":{"extension":{"onboardCapabilities":{"remote":{"navigation":{"supported":true}}}}}}""")
        assertEquals(true, Navigation.supported(v))
        assertNull(Navigation.supported(JSONObject("{}")))
    }

    @Test
    fun state_mapsFinalStates() {
        assertTrue(Navigation.state(JSONObject("""{"status":"Success"}""")).success)
        val failed = Navigation.state(JSONObject("""{"status":"Failed","failureCause":"MissingRights"}"""))
        assertTrue(failed.final)
        assertFalse(failed.success)
        assertTrue(failed.label.contains("Berechtigung"))
        assertFalse(Navigation.state(JSONObject("""{"status":"SentToVehicle"}""")).final)
    }
}
