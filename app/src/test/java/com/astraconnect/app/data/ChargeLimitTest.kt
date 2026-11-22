package com.astraconnect.app.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration

class ChargeLimitTest {
    private val on = ChargeLimitPrefs(enabled = true, percent = 80)

    private fun status(soc: Double, status: String = "InProgress", remaining: String? = "PT2H", type: String? = null): VehicleStatus {
        val charging = JSONObject().put("status", status).put("plugged", true)
        remaining?.let { charging.put("remainingTime", it) }
        type?.let { charging.put("type", it) }
        val el = JSONObject().put("type", "Electric").put("level", soc)
            .put("extension", JSONObject().put("electric", JSONObject().put("charging", charging)))
        return VehicleStatus.parse(JSONObject().put("energies", JSONArray().put(el)))
    }

    @Test
    fun stopsWhenLimitReachedWhileCharging() {
        assertEquals(ChargeLimit.Decision.Stop, ChargeLimit.decide(on, status(80.0), alreadySent = false))
        assertEquals(ChargeLimit.Decision.Stop, ChargeLimit.decide(on, status(83.0), alreadySent = false))
    }

    @Test
    fun doesNothingWhenOffNotChargingAlreadySentOrNativeLimit() {
        assertEquals(ChargeLimit.Decision.None, ChargeLimit.decide(on.copy(enabled = false), status(90.0), false))
        assertEquals(ChargeLimit.Decision.None, ChargeLimit.decide(on, status(90.0, status = "Disconnected"), false))
        assertEquals(ChargeLimit.Decision.None, ChargeLimit.decide(on, status(90.0), alreadySent = true))
        assertEquals(ChargeLimit.Decision.None, ChargeLimit.decide(on, status(90.0, type = "Partial"), false))
    }

    @Test
    fun schedulesExtraCheckShortlyBeforeLimit() {
        // 76 % → 100 % in 2 h ⇒ 80 % in 2 h × 4/24 = 20 min → nach der Routine-Prüfung, nichts extra
        assertEquals(ChargeLimit.Decision.None, ChargeLimit.decide(on, status(76.0), false))
        // 78 % → 80 % in 2 h × 2/22 ≈ 10,9 min → zusätzliche Prüfung ca. 1 min danach
        val d = ChargeLimit.decide(on, status(78.0), false)
        assertEquals(ChargeLimit.Decision.CheckIn::class, d::class)
        assertEquals(11L, (d as ChargeLimit.Decision.CheckIn).delay.toMinutes())
    }

    @Test
    fun minutesTo_isLinearOnRemainingTime() {
        assertEquals(Duration.ofMinutes(60), ChargeLimit.minutesTo(80, 60.0, Duration.ofMinutes(120)))
        assertNull(ChargeLimit.minutesTo(80, 85.0, Duration.ofMinutes(30)))
        assertNull(ChargeLimit.minutesTo(80, 60.0, null))
    }
}
