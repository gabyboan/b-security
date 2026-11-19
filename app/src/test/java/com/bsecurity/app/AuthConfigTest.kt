package com.bsecurity.app

import org.junit.Assert.*
import org.junit.Test

class AuthConfigTest {
    private val key = "sb_publishable_fixture_public_key"
    private val client = "123456789-fixture.apps.googleusercontent.com"

    @Test fun normalizesOnlyPublicConfiguration() {
        val config = AuthConfig.parse(" HTTPS://EXAMPLE.COM:443/ ", " $key ", " $client ")
        assertEquals("https://example.com", config.url)
        assertEquals(key, config.publishableKey)
        assertEquals(client, config.webClientId)
    }
    @Test fun rejectsSecretsLegacyServiceTokensAndWhitespaceInjection() {
        listOf("", "sb_secret_never_on_a_phone", "eyJhbGciOiJIUzI1NiJ9.service_role", "$key\nInjected: value").forEach {
            assertThrows(IllegalArgumentException::class.java) { AuthConfig.parse("https://example.com", it, client) }
        }
    }
    @Test fun rejectsCleartextAndRedirectPathsBeforeAnyCredentialIsSent() {
        listOf("http://example.com", "https://user@example.com", "https://example.com/auth/v1", "https://example.com?key=value").forEach {
            assertThrows(IllegalArgumentException::class.java) { AuthConfig.parse(it, key, client) }
        }
    }
    @Test fun rejectsWrongClientValues() {
        listOf("", "client-secret", "https://123-fixture.apps.googleusercontent.com", "$client\nInjected").forEach {
            assertThrows(IllegalArgumentException::class.java) { AuthConfig.parse("https://example.com", key, it) }
        }
    }
}
