package com.bsecurity.app

/** Public client values; these never confer administrative privileges. */
@ConsistentCopyVisibility
data class AuthConfig private constructor(val url: String, val publishableKey: String, val webClientId: String) {
    companion object {
        fun parse(url: String, publishableKey: String, webClientId: String): AuthConfig {
            val origin = FamilyEndpoint.normalize(url)
            val key = publishableKey.trim()
            require(key.matches(Regex("sb_publishable_[A-Za-z0-9_-]{8,}"))) {
                "Usá la clave pública sb_publishable_ de Supabase. Las claves secretas no van en la app."
            }
            val client = webClientId.trim()
            require(client.matches(Regex("[0-9]+-[A-Za-z0-9_-]+\\.apps\\.googleusercontent\\.com"))) {
                "Ingresá el ID del cliente OAuth de tipo Aplicación web de Google."
            }
            return AuthConfig(origin, key, client)
        }
    }
}
