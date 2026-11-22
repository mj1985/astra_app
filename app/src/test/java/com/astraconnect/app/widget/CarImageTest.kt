package com.astraconnect.app.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class CarImageTest {
    @Test
    fun keepFraction_fillsBox() {
        // Bild 1000×400 (2,5:1), Fläche 1,5:1 → 60 % des Autos sichtbar
        assertEquals(0.6, CarImage.keepFraction(1000, 400, 1.5), 1e-9)
    }

    @Test
    fun keepFraction_isClamped() {
        assertEquals(0.55, CarImage.keepFraction(1000, 400, 0.8), 1e-9) // sehr hohe Fläche
        assertEquals(0.88, CarImage.keepFraction(1000, 400, 4.0), 1e-9) // sehr breite Fläche
        assertEquals(0.88, CarImage.keepFraction(0, 0, 1.5), 1e-9)
    }
}
