package com.bsecurity.app

import org.junit.Assert.*
import org.junit.Test

class FamilyEndpointTest {
    @Test fun normalizesCaseWhitespaceTrailingSlashAndDefaultPort() {
        assertEquals("https://example.com", FamilyEndpoint.normalize(" HTTPS://EXAMPLE.COM:443/ "))
    }
    @Test fun keepsPrivateHostAndCustomPort() {
        assertEquals("https://device.example.ts.net:9443", FamilyEndpoint.normalize("https://device.example.ts.net:9443/"))
        assertEquals("https://[::1]:9443", FamilyEndpoint.normalize("https://[::1]:9443"))
    }
    @Test fun rejectsCleartextAndMissingHosts() {
        listOf("", "example.com", "http://example.com", "https:///", "file:///tmp", "https://").forEach(::assertInvalid)
    }
    @Test fun rejectsCredentialsPathsQueriesAndFragments() {
        listOf("https://user:secret@example.com", "https://example.com/api", "https://example.com?x=1",
            "https://example.com#fragment", "https://example.com//", "https://example.com/%2f").forEach(::assertInvalid)
    }
    @Test fun rejectsInvalidPortsAndMalformedAddresses() {
        listOf("https://example.com:0", "https://example.com:65536", "https://example.com:-2",
            "https://exam ple.com", "https://example.com:abc").forEach(::assertInvalid)
    }
    private fun assertInvalid(value: String) {
        assertThrows(value, IllegalArgumentException::class.java) { FamilyEndpoint.normalize(value) }
    }
}
