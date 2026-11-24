package com.astraconnect.app.widget

import org.junit.Assert.assertEquals
import org.junit.Test

class CarViewTest {

    @Test
    fun sideViewKeepsApiUrl() {
        val url = "https://visual3d.opel.com/V3DImage.ashx?client=MyMarque&version=AP&view=001&width=1200"
        assertEquals(url, CarView.SIDE.url(url))
    }

    @Test
    fun frontViewReplacesViewParameter() {
        assertEquals(
            "https://visual3d.opel.com/V3DImage.ashx?client=MyMarque&view=002&width=1200",
            CarView.FRONT.url("https://visual3d.opel.com/V3DImage.ashx?client=MyMarque&view=001&width=1200"),
        )
        assertEquals("https://x.test/img?a=1&view=002", CarView.FRONT.url("https://x.test/img?a=1&view=001"))
        assertEquals("https://x.test/img?view=002&a=1", CarView.FRONT.url("https://x.test/img?view=001&a=1"))
    }

    @Test
    fun frontViewAddsMissingParameter() {
        assertEquals("https://x.test/img?a=1&view=002", CarView.FRONT.url("https://x.test/img?a=1"))
        assertEquals("https://x.test/img?view=002", CarView.FRONT.url("https://x.test/img"))
        // „preview=“ ist kein view-Parameter
        assertEquals("https://x.test/img?preview=001&view=002", CarView.FRONT.url("https://x.test/img?preview=001"))
    }
}
