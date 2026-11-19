package com.bsecurity.app

/** Monotonic ages prevent a wrong phone clock from making an old fix look current. */
data class HomeLocationPoint(val lat: Double, val lon: Double, val accuracy: Double, val ageSeconds: Long) {
    val valid: Boolean get() = lat.isFinite() && lon.isFinite() && accuracy.isFinite() &&
        lat in -90.0..90.0 && lon in -180.0..180.0 && accuracy in 0.0..100000.0 && ageSeconds in 0..120
    fun mapUrl(): String {
        require(valid)
        return "https://www.openstreetmap.org/?mlat=$lat&mlon=$lon#map=17/$lat/$lon"
    }
}
