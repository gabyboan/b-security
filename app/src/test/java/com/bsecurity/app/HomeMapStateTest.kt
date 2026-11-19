package com.bsecurity.app

import org.junit.Assert.*
import org.junit.Test

class HomeMapStateTest {
    private val point = HomeLocationPoint(-34.6037, -58.3816, 8.0, 0)
    @Test fun positionDisappearsAtExactExpiryEvenWithoutAnotherResponse() {
        val fix = HomeMapFix(point, 1_000, 10_000, 5_000.0, false)
        assertTrue(fix.fresh(14_999))
        assertFalse(fix.fresh(15_000))
        assertFalse(fix.fresh(9_999))
        assertNull(HomeMapDevice("id", "Phone", false, false, fix).visibleFix(10_000))
    }
    @Test fun delayConsumesLeaseRatherThanExtendingIt() {
        val fix = HomeMapFix(point, 1_000, 10_000, 5_000.0, false)
        assertNotNull(HomeMapDevice("id", "Phone", false, true, fix).visibleFix(12_000))
        assertNull(HomeMapDevice("id", "Phone", false, true, fix).visibleFix(16_000))
    }
    @Test fun malformedLeaseAndCoordinatesNeverAppear() {
        for (ttl in listOf(Double.NaN, Double.POSITIVE_INFINITY, -1.0, 0.0, 120_001.0)) {
            assertFalse(HomeMapFix(point, 0, 0, ttl, false).fresh(0))
        }
        assertFalse(HomeMapFix(point.copy(lat = Double.NaN), 0, 0, 5_000.0, false).fresh(0))
        assertFalse(HomeMapFix(point.copy(lon = 181.0), 0, 0, 5_000.0, false).fresh(0))
    }
}
