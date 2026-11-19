package com.bsecurity.app

import org.junit.Assert.*
import org.junit.Test

class WifiRadioTest {
    @Test fun nearbyNetworksSortByStrongestSignalWithStableTies() {
        val points = listOf(
            WifiAccessPoint("weak", "00:00:00:03", -78, 2412, 0, "", 0),
            WifiAccessPoint("two", "00:00:00:02", -45, 5180, 0, "", 0),
            WifiAccessPoint("one", "00:00:00:01", -45, 2412, 0, "", 0),
        )
        assertEquals(listOf("one", "two", "weak"), WifiRadio.strongestFirst(points).map { it.ssid })
    }

    @Test fun accessPointsAreGroupedAndOrderedByBandThenChannel() {
        val points = listOf(
            WifiAccessPoint("5G", "00:00:00:03", -40, 5180, 0, "", 0),
            WifiAccessPoint("ch6", "00:00:00:02", -60, 2437, 0, "", 0),
            WifiAccessPoint("ch1", "00:00:00:01", -50, 2412, 0, "", 0),
            WifiAccessPoint("ch1 weak", "00:00:00:04", -70, 2412, 0, "", 0),
        )
        val groups = WifiRadio.byChannel(points)
        assertEquals(listOf("2,4 GHz" to 1, "2,4 GHz" to 6, "5 GHz" to 36),
            groups.map { it.band to it.channel })
        assertEquals(listOf(-50, -70), groups.first().accessPoints.map { it.level })
    }

    @Test fun signalCategoriesUseRssiThresholds() {
        assertEquals("Muy buena", WifiRadio.signalQuality(-50))
        assertEquals("Buena", WifiRadio.signalQuality(-51))
        assertEquals("Débil", WifiRadio.signalQuality(-68))
        assertEquals("Muy débil", WifiRadio.signalQuality(-76))
    }

    @Test fun frequenciesUseTheirBandsAndSpecialChannels() {
        mapOf(2412 to 1, 2437 to 6, 2472 to 13, 2484 to 14, 5180 to 36, 5745 to 149,
            5935 to 2, 5955 to 1, 6115 to 33, 7115 to 233, 58320 to 1, 60480 to 2).forEach { (frequency, channel) ->
            assertEquals("$frequency", channel, WifiRadio.channel(frequency))
        }
        listOf(0, -1, 2400, 2480, 5956, 7125).forEach { assertNull(WifiRadio.channel(it)) }
        assertEquals("2,4 GHz", WifiRadio.band(2484)); assertEquals("6 GHz", WifiRadio.band(5955))
    }

    @Test fun channelWidthIncludesBondedAndFutureUnknownValues() {
        assertEquals("80+80 MHz", WifiRadio.width(4)); assertEquals("320 MHz", WifiRadio.width(5))
        assertEquals("ancho no informado", WifiRadio.width(99))
    }
}
