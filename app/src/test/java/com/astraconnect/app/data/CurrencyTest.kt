package com.astraconnect.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CurrencyTest {

    @Test
    fun marketsHaveTheirCurrency() {
        assertEquals(Currency.EUR, ApiConfig.market("DE").currency)
        assertEquals(Currency.EUR, ApiConfig.market("BG").currency)
        assertEquals(Currency.CHF, ApiConfig.market("CH").currency)
        assertEquals(Currency.PLN, ApiConfig.market("PL").currency)
        assertEquals(Currency.JPY, ApiConfig.market("JP").currency)
        assertEquals(Currency.NZD, ApiConfig.market("NZ").currency)
        assertEquals(Currency.TWD, ApiConfig.market("TW").currency)
        // ALT-Client-ID bleibt bei Märkten mit eigener Währung erhalten
        assertEquals(ApiConfig.market("EE").clientId, ApiConfig.market("NZ").clientId)
    }

    @Test
    fun defaultsAndRangesScaleWithCurrency() {
        assertEquals(1.75, Currency.EUR.fromEuro(1.75), 0.0)
        assertEquals(306.3, Currency.JPY.fromEuro(1.75), 0.0)
        assertTrue(180.0 in Currency.JPY.range(0.3, 5.0))
        assertTrue(1.8 in Currency.EUR.range(0.3, 5.0))
        assertTrue(18.0 !in Currency.EUR.range(0.3, 5.0))
    }
}
