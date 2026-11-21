package com.astraconnect.app.widget

import com.astraconnect.app.data.VehicleStatus
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ChargeTextTest {

    private fun status(charging: String) = VehicleStatus.parse(
        JSONObject(
            """{"lastUpdate":"2026-11-21T10:00:00Z","energies":[{"type":"Electric","level":60,"autonomy":40,
               "extension":{"electric":{"charging":$charging}}}]}"""
        )
    )

    @Test
    fun restzeitWirdAbMeldezeitpunktHeruntergezaehlt() {
        val s = status("""{"plugged":true,"status":"InProgress","remainingTime":"PT1H30M"}""")
        val t = chargeText(s, Instant.parse("2026-11-21T10:10:00Z"))
        assertTrue(t.charging)
        assertEquals("Lädt · noch 1 h 20 min", t.state)
        assertTrue(t.detail.startsWith("fertig ca."))
    }

    @Test
    fun restzeitAbgelaufen() {
        val s = status("""{"plugged":true,"status":"InProgress","remainingTime":"PT30M"}""")
        assertEquals("Lädt · gleich voll", chargeText(s, Instant.parse("2026-11-21T11:00:00Z")).state)
    }

    @Test
    fun verzoegertesLadenZeigtStartzeit() {
        val s = status("""{"plugged":true,"status":"Stopped","chargingMode":"Delayed","nextDelayedTime":"PT22H30M"}""")
        val t = chargeText(s, Instant.parse("2026-11-21T10:10:00Z"))
        assertFalse(t.charging)
        assertEquals("Angesteckt", t.state)
        assertEquals("Start um 22:30 Uhr", t.detail)
    }

    @Test
    fun nichtAngesteckt() {
        val s = status("""{"plugged":false,"status":"Disconnected"}""")
        assertEquals("Nicht angesteckt", chargeText(s, Instant.now()).state)
    }

    @Test
    fun ohneDaten() {
        assertEquals("Noch keine Daten", chargeText(null, Instant.now()).state)
    }
}
