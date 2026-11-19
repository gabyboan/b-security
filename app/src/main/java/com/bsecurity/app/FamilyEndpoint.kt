package com.bsecurity.app

import java.net.URI
import java.util.Locale

object FamilyEndpoint {
    fun normalize(value: String): String {
        val message = "Usá una URL HTTPS sin ruta, credenciales ni parámetros y con un puerto válido."
        val uri = try { URI(value.trim()) } catch (_: Exception) { throw IllegalArgumentException(message) }
        require(uri.scheme.equals("https", true) && !uri.host.isNullOrEmpty() && uri.rawUserInfo == null &&
            uri.rawQuery == null && uri.rawFragment == null && (uri.path.isNullOrEmpty() || uri.path == "/") &&
            (uri.port == -1 || uri.port in 1..65535)) { message }
        return URI("https", null, uri.host.lowercase(Locale.ROOT), if (uri.port == 443) -1 else uri.port, null, null, null).toString()
    }
}
