package com.bsecurity.app

import org.json.JSONObject
import java.time.Instant

/** Freshness uses the request's monotonic start, never the phone's wall clock. */
data class HomeMapFix(val point: HomeLocationPoint, val capturedAt: Long,
    val requestedAt: Long, val validForMillis: Double, val simulated: Boolean) {
    fun fresh(now: Long): Boolean = point.valid && validForMillis.isFinite() &&
        validForMillis > 0 && validForMillis <= 120_000 && now >= requestedAt &&
        now - requestedAt < validForMillis
}

data class HomeMapDevice(val id: String, val name: String, val self: Boolean,
    val sharing: Boolean, val fix: HomeMapFix?) {
    fun visibleFix(now: Long): HomeMapFix? = fix?.takeIf { sharing && it.fresh(now) }
}

data class HomeMapState(val name: String, val devices: List<HomeMapDevice>) {
    companion object {
        fun parse(response: JSONObject, requestedAt: Long): HomeMapState? {
            val home = response.optJSONObject("home") ?: return null
            val devices = home.optJSONArray("devices")
            return HomeMapState(home.optString("name", "Mi hogar"), (0 until (devices?.length() ?: 0)).map { index ->
                val device = devices!!.getJSONObject(index)
                val location = device.optJSONObject("location")
                val fix = location?.let { value ->
                    runCatching {
                        HomeMapFix(HomeLocationPoint(value.getDouble("lat"), value.getDouble("lon"), value.getDouble("accuracy"), 0),
                            Instant.parse(value.getString("at")).toEpochMilli(), requestedAt,
                            value.getDouble("valid_for_seconds") * 1000, value.optBoolean("mock"))
                    }.getOrNull()
                }
                HomeMapDevice(device.getString("id"), device.optString("name", "Teléfono"),
                    device.optBoolean("self"), device.optBoolean("sharing"), fix)
            })
        }
    }
}
