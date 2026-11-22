package com.astraconnect.app.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset

class ChargeLimitTest {
    private val on = ChargeLimitPrefs(enabled = true, percent = 80)
    private val zone = ZoneOffset.UTC
    /** 21:40 Uhr – 20 Min. vor dem programmierten Start um 22:00. */
    private val now = Instant.parse("2026-11-22T21:40:00Z")
    private val fresh = ChargeLimitState()

    private fun status(
        soc: Double,
        status: String = "InProgress",
        remaining: String? = "PT2H",
        type: String? = null,
        mode: String? = "Slow",
        reportedMinAgo: Long = 2,
        start: String? = "PT22H00M",
    ): VehicleStatus {
        val charging = JSONObject().put("status", status).put("plugged", true)
        remaining?.let { charging.put("remainingTime", it) }
        type?.let { charging.put("type", it) }
        mode?.let { charging.put("chargingMode", it) }
        start?.let { charging.put("nextDelayedTime", it) }
        val el = JSONObject().put("type", "Electric").put("level", soc)
            .put("createdAt", now.minus(Duration.ofMinutes(reportedMinAgo)).toString())
            .put("extension", JSONObject().put("electric", JSONObject().put("charging", charging)))
        return VehicleStatus.parse(JSONObject().put("energies", JSONArray().put(el)))
    }

    private fun decide(s: VehicleStatus, state: ChargeLimitState = fresh, prefs: ChargeLimitPrefs = on) =
        ChargeLimit.decide(prefs, s, state, now, zone)

    // ------------------------------------------------------------------ Pausieren

    @Test
    fun stopsWhenLimitReachedWhileCharging() {
        assertEquals(ChargeLimit.Decision.Stop, decide(status(80.0)))
        assertEquals(ChargeLimit.Decision.Stop, decide(status(83.0, reportedMinAgo = 30))) // auch mit alten Daten
    }

    @Test
    fun doesNothingWhenOffNotChargingAlreadySentOrNativeLimit() {
        assertEquals(ChargeLimit.Decision.None, decide(status(90.0), prefs = on.copy(enabled = false)))
        assertEquals(ChargeLimit.Decision.None, decide(status(90.0, status = "Disconnected")))
        assertEquals(ChargeLimit.Decision.None, decide(status(90.0), ChargeLimitState(stopSent = true)))
        assertEquals(ChargeLimit.Decision.None, decide(status(90.0, type = "Partial")))
    }

    @Test
    fun schedulesExtraCheckShortlyBeforeLimit() {
        // 78 % → 80 % in 2 h × 2/22 ≈ 10,9 min → zusätzliche Prüfung ca. 1 min danach
        val d = decide(status(78.0))
        assertEquals(ChargeLimit.Decision.CheckIn::class, d::class)
        assertEquals(11L, (d as ChargeLimit.Decision.CheckIn).delay.toMinutes())
        // weit weg vom Limit: nichts
        assertEquals(ChargeLimit.Decision.None, decide(status(40.0)))
    }

    // ------------------------------------------------------------------ Wecken

    @Test
    fun wakesWhenNearLimitAndDataStale() {
        assertEquals(ChargeLimit.Decision.WakeUp, decide(status(75.0, reportedMinAgo = 12)))
        // Schnellladen: schon nach 5 Min. veraltet
        assertEquals(ChargeLimit.Decision.WakeUp, decide(status(75.0, mode = "Quick", reportedMinAgo = 6)))
    }

    @Test
    fun neverWakesWhenWakeUpDisabled() {
        val noWake = on.copy(wakeUp = false)
        // statt zu wecken: nur die normale Zusatzprüfung bzw. nichts
        assertEquals(ChargeLimit.Decision.None, decide(status(75.0, remaining = "PT4H", reportedMinAgo = 30), prefs = noWake))
        assertEquals(ChargeLimit.Decision.Stop, decide(status(80.0, reportedMinAgo = 30), prefs = noWake))
    }

    @Test
    fun doesNotWakeWhenFreshFarOrBudgetUsed() {
        assertEquals(ChargeLimit.Decision.None, decide(status(75.0, remaining = "PT4H", reportedMinAgo = 8)))
        assertEquals(ChargeLimit.Decision.None, decide(status(40.0, reportedMinAgo = 30))) // weit unter dem Limit
        val recent = ChargeLimitState(wakeups = listOf(now.minus(Duration.ofMinutes(5))))
        assertEquals(ChargeLimit.Decision.None, decide(status(75.0, remaining = "PT4H", reportedMinAgo = 12), recent))
        val many = ChargeLimitState(wakeups = (1..ChargeLimit.MAX_WAKEUPS_PER_DAY).map { now.minus(Duration.ofHours(it.toLong())) })
        assertEquals(ChargeLimit.Decision.None, decide(status(75.0, remaining = "PT4H", reportedMinAgo = 12), many))
    }

    // ------------------------------------------------------------------ Startzeit verlegen / wiederherstellen

    @Test
    fun postponesStartShortlyBeforeScheduledTime() {
        assertEquals(ChargeLimit.Decision.Postpone(21, 39), decide(status(81.0, status = "Stopped")))
        // Start noch zu weit weg (23:00) oder Akku unter Limit → nichts
        assertEquals(ChargeLimit.Decision.None, decide(status(81.0, status = "Stopped", start = "PT23H00M")))
        assertEquals(ChargeLimit.Decision.None, decide(status(70.0, status = "Stopped")))
        // gerade erst verlegt → nicht noch einmal
        val just = ChargeLimitState(lastPostpone = now.minus(Duration.ofMinutes(20)))
        assertEquals(ChargeLimit.Decision.None, decide(status(81.0, status = "Stopped"), just))
    }

    @Test
    fun restoresOriginalStartWhenBelowLimitOrDisabled() {
        val moved = ChargeLimitState(originalStart = 22 to 0)
        assertEquals(ChargeLimit.Decision.Restore(22, 0), decide(status(60.0, status = "Disconnected"), moved))
        assertEquals(ChargeLimit.Decision.Restore(22, 0), decide(status(85.0, status = "Stopped"), moved, on.copy(enabled = false)))
        // noch am Limit → bleibt verlegt; beim Laden nie
        assertEquals(ChargeLimit.Decision.None, decide(status(85.0, status = "Stopped", start = "PT23H00M"), moved))
        assertEquals(ChargeLimit.Decision.Stop, decide(status(85.0), moved))
        // letzter Versuch vor weniger als 1 h → warten
        val tried = moved.copy(lastRestoreTry = now.minus(Duration.ofMinutes(30)))
        assertEquals(ChargeLimit.Decision.None, decide(status(60.0, status = "Disconnected"), tried))
    }

    @Test
    fun minutesHelpers() {
        assertEquals(Duration.ofMinutes(60), ChargeLimit.minutesTo(80, 60.0, Duration.ofMinutes(120)))
        assertNull(ChargeLimit.minutesTo(80, 85.0, Duration.ofMinutes(30)))
        assertNull(ChargeLimit.minutesTo(80, 60.0, null))
        assertEquals(20L, ChargeLimit.minutesUntil(LocalTime.of(21, 40), LocalTime.of(22, 0)))
        assertEquals(1439L, ChargeLimit.minutesUntil(LocalTime.of(22, 1), LocalTime.of(22, 0)))
    }
}
