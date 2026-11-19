package com.bsecurity.app

import org.junit.Assert.*
import org.junit.Test

class HomeLocationPointTest {
    @Test fun buenosAiresPointOpensMarkedOpenStreetMap() {
        val point = HomeLocationPoint(-34.6037, -58.3816, 12.0, 30)
        assertTrue(point.valid)
        assertEquals("https://www.openstreetmap.org/?mlat=-34.6037&mlon=-58.3816#map=17/-34.6037/-58.3816", point.mapUrl())
    }
    @Test fun expiredFutureAndNonFiniteFixesAreRejected() {
        listOf(HomeLocationPoint(-34.0, -58.0, 5.0, 121), HomeLocationPoint(-34.0, -58.0, 5.0, -1),
            HomeLocationPoint(Double.NaN, 0.0, 5.0, 1), HomeLocationPoint(0.0, Double.POSITIVE_INFINITY, 5.0, 1),
            HomeLocationPoint(91.0, 0.0, 5.0, 1), HomeLocationPoint(0.0, 181.0, 5.0, 1),
            HomeLocationPoint(0.0, 0.0, -1.0, 1)).forEach {
            assertFalse(it.valid)
            assertThrows(IllegalArgumentException::class.java) { it.mapUrl() }
        }
    }
    @Test fun boundaryCoordinatesAndFreshnessAreAccepted() {
        assertTrue(HomeLocationPoint(-90.0, 180.0, 0.0, 120).valid)
        assertTrue(HomeLocationPoint(90.0, -180.0, 100000.0, 0).valid)
    }
}
