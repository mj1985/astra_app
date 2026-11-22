package com.astraconnect.app.data

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class PositionParseTest {
    private fun status(properties: String) = VehicleStatus.parse(
        JSONObject(
            """{"lastPosition":{"type":"Feature",
                "geometry":{"type":"Point","coordinates":[13.405,52.52,40]},
                "properties":$properties}}"""
        )
    )

    @Test
    fun positionTime_fromCreatedAt() {
        val s = status("""{"createdAt":"2026-11-22T08:15:00Z","type":"Acquire"}""")
        assertEquals(Instant.parse("2026-11-22T08:15:00Z"), s.positionAt)
        assertEquals(52.52, s.position!!.lat, 0.0)
        assertEquals(13.405, s.position!!.lon, 0.0)
    }

    @Test
    fun positionTime_fallsBackToUpdatedAt() {
        val s = status("""{"updatedAt":"2026-11-22T09:00:00Z"}""")
        assertEquals(Instant.parse("2026-11-22T09:00:00Z"), s.positionAt)
    }
}
