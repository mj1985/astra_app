package com.astraconnect.app.wear

import com.astraconnect.app.data.VehicleStatus
import com.astraconnect.app.ui.time
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class WearSyncTest {

    private fun status(charging: String) = VehicleStatus.parse(
        JSONObject(
            """{"lastUpdate":"2026-11-21T10:00:00Z","energies":[{"type":"Electric","level":60,"autonomy":40,
               "extension":{"electric":{"charging":$charging}}}]}"""
        )
    )

    @Test
    fun ladenZeigtEndzeitAbMeldezeitpunkt() {
        val s = status("""{"plugged":true,"status":"InProgress","remainingTime":"PT1H30M"}""")
        val text = WearSync.shortCharge(s, Instant.parse("2026-11-21T10:10:00Z"))
        assertEquals("Lädt bis ${Instant.parse("2026-11-21T11:30:00Z").time()}", text)
    }

    @Test
    fun kurzGenugFuerDenUhrrand() {
        // Unten auf einer 192-dp-Uhr passen etwa 15 Zeichen
        val charging = status("""{"plugged":true,"status":"InProgress","remainingTime":"PT1H30M"}""")
        val done = status("""{"plugged":true,"status":"InProgress","remainingTime":"PT5M"}""")
        val unknown = status("""{"plugged":true,"status":"InProgress"}""")
        val now = Instant.parse("2026-11-21T10:10:00Z")
        for (s in listOf(charging, done, unknown)) assertTrue(WearSync.shortCharge(s, now).length <= 15)
        assertEquals("Gleich voll", WearSync.shortCharge(done, now))
        assertEquals("Lädt", WearSync.shortCharge(unknown, now))
    }

    @Test
    fun ohneDatenLeer() {
        assertEquals("", WearSync.shortCharge(null, Instant.now()))
    }
}
