package com.bsecurity.app

import java.util.Locale

data class WifiAccessPoint(
    val ssid: String,
    val bssid: String,
    val level: Int,
    val frequency: Int,
    val width: Int,
    val capabilities: String,
    val ageSeconds: Long,
)

data class WifiChannelGroup(
    val band: String,
    val channel: Int?,
    val accessPoints: List<WifiAccessPoint>,
)

/** Frequency is the primary 20 MHz channel, not the centre of a bonded channel. */
object WifiRadio {
    fun signalQuality(level: Int): String = when {
        level >= -50 -> "Muy buena"
        level >= -67 -> "Buena"
        level >= -75 -> "Débil"
        else -> "Muy débil"
    }

    fun strongestFirst(points: List<WifiAccessPoint>): List<WifiAccessPoint> =
        points.sortedWith(compareByDescending<WifiAccessPoint> { it.level }
            .thenBy { bandOrder(it.frequency) }
            .thenBy { channel(it.frequency) ?: Int.MAX_VALUE }
            .thenBy { it.ssid.lowercase(Locale.ROOT) }
            .thenBy { it.bssid })

    fun byChannel(points: List<WifiAccessPoint>): List<WifiChannelGroup> =
        points.groupBy { band(it.frequency) to channel(it.frequency) }
            .map { (key, networks) ->
                WifiChannelGroup(key.first, key.second, strongestFirst(networks))
            }
            .sortedWith(compareBy<WifiChannelGroup>({ bandOrder(it.accessPoints.first().frequency) },
                { it.channel ?: Int.MAX_VALUE }))

    fun bandOrder(mhz: Int): Int = when (mhz) {
        in 2400..2500 -> 0
        in 4900..5895 -> 1
        in 5925..7125 -> 2
        in 58320..70200 -> 3
        else -> 4
    }

    fun channel(mhz: Int): Int? = when {
        mhz == 2484 -> 14
        mhz in 2412..2472 && (mhz - 2407) % 5 == 0 -> (mhz - 2407) / 5
        mhz in 4910..4980 && mhz % 5 == 0 -> (mhz - 4000) / 5
        mhz in 5160..5885 && mhz % 5 == 0 -> (mhz - 5000) / 5
        mhz == 5935 -> 2
        mhz in 5955..7115 && (mhz - 5950) % 5 == 0 -> (mhz - 5950) / 5
        mhz in 58320..70200 && (mhz - 58320) % 2160 == 0 -> (mhz - 56160) / 2160
        else -> null
    }

    fun band(mhz: Int): String = when (mhz) {
        in 2400..2500 -> "2,4 GHz"
        in 4900..5895 -> "5 GHz"
        in 5925..7125 -> "6 GHz"
        in 58320..70200 -> "60 GHz"
        else -> "banda no identificada"
    }

    fun width(value: Int): String = when (value) {
        0 -> "20 MHz"; 1 -> "40 MHz"; 2 -> "80 MHz"; 3 -> "160 MHz"
        4 -> "80+80 MHz"; 5 -> "320 MHz"; else -> "ancho no informado"
    }

    fun describe(mhz: Int) = "Canal ${channel(mhz)?.toString() ?: "no identificado"} · ${band(mhz)} · $mhz MHz"
}
