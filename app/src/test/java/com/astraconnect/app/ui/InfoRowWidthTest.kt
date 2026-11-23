package com.astraconnect.app.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class InfoRowWidthTest {
    private val width = 1000
    private val gap = 30

    @Test
    fun shortValue_labelGetsFullWidth() {
        // „Außentemperatur“ (600) + „5 °C“ (100) passen nebeneinander → nichts bricht um
        val (label, value) = infoRowWidths(width, gap, labelNeeds = 600, valueNeeds = 100)
        assertEquals(600, label)
        assertEquals(370, value)
    }

    @Test
    fun longValue_labelKeepsAtLeast40Percent() {
        // langer Wert (z. B. Ordnername) → Beschriftung 40 %, Wert bricht um
        val (label, value) = infoRowWidths(width, gap, labelNeeds = 600, valueNeeds = 2000)
        assertEquals(400, label)
        assertEquals(570, value)
    }

    @Test
    fun shortLabel_neverWiderThanNeeded() {
        val (label, _) = infoRowWidths(width, gap, labelNeeds = 150, valueNeeds = 2000)
        assertEquals(150, label)
    }

    @Test
    fun bothMedium_labelGetsWhatValueLeaves() {
        // 550 + 30 + 500 > 1000 → Beschriftung bekommt 470 (mehr als 40 %), Wert passt genau
        val (label, value) = infoRowWidths(width, gap, labelNeeds = 550, valueNeeds = 500)
        assertEquals(470, label)
        assertEquals(500, value)
    }
}
