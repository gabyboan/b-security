package com.bsecurity.app

import org.junit.Assert.assertEquals
import org.junit.Test

class WifiSecurityTest {
    @Test fun openAndWepWarnAboutRisk() {
        assertEquals("risk", WifiSecurity.finding(0).level)
        assertEquals("risk", WifiSecurity.finding(1).level)
        assertEquals("ok", WifiSecurity.finding(2).level)
        assertEquals("ok", WifiSecurity.finding(4).level)
    }

    @Test fun subscriptionAndProvisioningTypesUseAndroidAssignedValues() {
        // Regression: OSEN was displayed as Passpoint, shifting every following label.
        assertEquals("OSEN", WifiSecurity.finding(10).detail)
        assertEquals("Passpoint R1/R2", WifiSecurity.finding(11).detail)
        assertEquals("Passpoint R3", WifiSecurity.finding(12).detail)
        assertEquals("DPP", WifiSecurity.finding(13).detail)
    }

    @Test fun missingOrFutureTypesNeverClaimProtection() {
        listOf(-1, 14, 999).forEach { assertEquals("unknown", WifiSecurity.finding(it).level) }
    }
}
