package com.astraconnect.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketTest {
    @Test
    fun germany_keepsPreviousValues() {
        val de = ApiConfig.market("DE")
        assertEquals("de-DE", de.locale)
        assertEquals("mymopsdk://oauth2redirect/de", de.redirectUri)
        assertEquals(ApiConfig.DEFAULT_CLIENT_ID, de.clientId)
    }

    @Test
    fun unknownOrMissingCountry_fallsBackToGermany() {
        assertEquals("DE", ApiConfig.market(null).code)
        assertEquals("DE", ApiConfig.market("XX").code)
        assertEquals("AT", ApiConfig.market("at").code)
    }

    @Test
    fun balticMarkets_useAlternativeClientId() {
        val lv = ApiConfig.market("LV")
        assertEquals("mymopsdk://oauth2redirect/lv", lv.redirectUri)
        assertTrue(lv.clientId != ApiConfig.DEFAULT_CLIENT_ID)
        assertTrue(ApiConfig.isMarketClientId(lv.clientId))
    }

    @Test
    fun marketCodes_areUnique() {
        val codes = ApiConfig.MARKETS.map { it.code }
        assertEquals(codes.size, codes.toSet().size)
    }
}
