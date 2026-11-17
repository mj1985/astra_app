package com.astraconnect.app.data

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PageLinkTest {
    private val base = "https://api.groupe-psa.com/connectedcar/v4/user/vehicles/abc/trips?client_id=cid&locale=de-DE".toHttpUrl()

    @Test
    fun relativeLink_staysOnApiHost_andKeepsClientId() {
        val u = StellantisApi.resolvePageLink(base, "/connectedcar/v4/user/vehicles/abc/trips?pageToken=xyz", "cid")!!
        assertEquals("api.groupe-psa.com", u.host)
        assertEquals("xyz", u.queryParameter("pageToken"))
        assertEquals("cid", u.queryParameter("client_id"))
    }

    @Test
    fun absoluteLink_sameHost_isAccepted() {
        val u = StellantisApi.resolvePageLink(base, "https://api.groupe-psa.com/connectedcar/v4/user/vehicles/abc/trips?pageToken=2&client_id=cid", "cid")!!
        assertEquals("2", u.queryParameter("pageToken"))
        assertEquals(listOf("cid"), u.queryParameterValues("client_id"))
    }

    @Test
    fun foreignHost_isRejected() {
        assertThrows(ApiException::class.java) {
            StellantisApi.resolvePageLink(base, "https://evil.example.com/trips?pageToken=1", "cid")
        }
    }

    @Test
    fun lookalikeHost_isRejected() {
        assertThrows(ApiException::class.java) {
            StellantisApi.resolvePageLink(base, "https://api.groupe-psa.com.evil.example/trips", "cid")
        }
    }

    @Test
    fun plainHttp_isRejected() {
        assertThrows(ApiException::class.java) {
            StellantisApi.resolvePageLink(base, "http://api.groupe-psa.com/connectedcar/v4/user/vehicles/abc/trips", "cid")
        }
    }

    @Test
    fun protocolRelativeLink_toForeignHost_isRejected() {
        assertThrows(ApiException::class.java) {
            StellantisApi.resolvePageLink(base, "//evil.example.com/trips", "cid")
        }
    }
}
