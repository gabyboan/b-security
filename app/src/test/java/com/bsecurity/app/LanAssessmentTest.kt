package com.bsecurity.app

import org.junit.Assert.*
import org.junit.Test

class LanAssessmentTest {
    @Test fun addressesAreStrictAndNeverResolved() {
        listOf("localhost", "127.1", "192.168.01.2", "1.2.3.256", "1.2.3.-1", "192.168.1.1/24", "0x7f.0.0.1", "1.2.3.4:80").forEach {
            assertTrue(it, runCatching { LanScope.ipv4(it) }.isFailure)
        }
        assertEquals("192.168.1.2", LanScope.ipv4(" 192.168.1.2 ").hostAddress)
    }

    @Test fun explicitTargetsAreBoundedAndPrivate() {
        listOf("", "8.8.8.8", "127.0.0.1", "224.0.0.1", "0.0.0.0", "10.0.0.1,10.0.0.2,10.0.0.3,10.0.0.4").forEach {
            assertTrue(it, runCatching { LanScope.otherTargets(it) }.isFailure)
        }
        assertEquals(listOf("10.0.0.1", "192.168.2.3"), LanScope.otherTargets("10.0.0.1; 192.168.2.3\n10.0.0.1").map { it.hostAddress })
    }

    @Test fun discoveryHonorsNetworkBoundariesAndExcludesSelf() {
        val hosts = LanScope.targets(LanScope.ipv4("192.168.1.6"), 29, LanScope.ipv4("192.168.1.1"))
        assertEquals((1..5).map { "192.168.1.$it" }, hosts.map { it.hostAddress })
        assertEquals(listOf("10.0.0.0"), LanScope.targets(LanScope.ipv4("10.0.0.1"), 31, null).map { it.hostAddress })
        assertTrue(LanScope.targets(LanScope.ipv4("10.0.0.1"), 32, null).isEmpty())
    }

    @Test fun wideSubnetsUseABoundedWindowAndKeepGateway() {
        val local = LanScope.ipv4("10.2.3.4")
        val gateway = LanScope.ipv4("10.2.0.1")
        val hosts = LanScope.targets(local, 16, gateway)
        assertEquals(256, hosts.size)
        assertFalse(hosts.contains(local)); assertTrue(hosts.contains(gateway))
        assertTrue(hosts.contains(LanScope.ipv4("10.2.3.0"))) // Valid /16 host, not a /24 network address.
        assertTrue(hosts.contains(LanScope.ipv4("10.2.3.255")))
        assertTrue(LanScope.sameSubnet(local, gateway, 16))
        assertFalse(LanScope.sameSubnet(local, gateway, 24))
    }

    @Test fun unavailableIcmpDoesNotBecomePacketLoss() {
        val unavailable = EchoSummary(listOf(EchoSample(error = "ICMP bloqueado", sent = false))).text()
        assertTrue(unavailable.contains("0 enviados")); assertTrue(unavailable.contains("pérdida no medible"))
        val summary = EchoSummary(listOf(EchoSample(5.0), EchoSample(), EchoSample(15.0), EchoSample())).text()
        assertTrue(summary.contains("pérdida 50%")); assertTrue(summary.contains("5.0/10.0/15.0"))
    }

    private fun host(announcements: List<ServiceAnnouncement> = emptyList(), services: List<ServiceEvidence> = emptyList(), probes: List<RouterProbe> = emptyList()) =
        HostObservation(LanScope.ipv4("192.168.1.10"), EchoSummary(emptyList()), probes, services, announcements)

    @Test fun portsAndUserChosenNamesDoNotProveAnOperatingSystem() {
        val observation = host(listOf(ServiceAnnouncement("iPhone de Ana; model=iPhone14,7._adb-tls-connect._tcp._airplay._tcp.local", "iphone.local", "_airplay._tcp.local")),
            probes = listOf(RouterProbe(445, RouterProbeOutcome.ACCEPTED)))
        val name = LanAssessment.identity(observation, null).first
        assertFalse(name.contains("iPhone")); assertFalse(name.contains("Windows")); assertFalse(name.contains("Android"))
        assertEquals("Dispositivo sin clasificar", LanAssessment.identity(host(probes = listOf(RouterProbe(445, RouterProbeOutcome.ACCEPTED))), null).first)
    }

    @Test fun advertisedModelsAndServiceTypesProvideQualifiedIdentity() {
        assertEquals("iPhone (probable)", LanAssessment.identity(host(listOf(ServiceAnnouncement("Phone", "host.local", "_airplay._tcp.local", listOf("model=iPhone14,7")))), null).first)
        assertEquals("Android (probable)", LanAssessment.identity(host(listOf(ServiceAnnouncement("random", "host.local", "_adb-tls-connect._tcp.local"))), null).first)
        assertEquals("Impresora (servicio anunciado)", LanAssessment.identity(host(listOf(ServiceAnnouncement("Office", "host.local", "_ipp._tcp.local", listOf("ty=Laser")))), null).first)
        assertEquals("Router / puerta de enlace", LanAssessment.identity(host(), LanScope.ipv4("192.168.1.10")).first)
    }

    @Test fun xiaomiRedmiNote11NeedsAnExplicitAnnouncedModel() {
        val identified = host(listOf(ServiceAnnouncement(
            "Phone", "phone.local", "_device-info._tcp.local", listOf("model=2201117TG"),
        )))
        assertEquals("Xiaomi Redmi Note 11 (probable)", LanAssessment.identity(identified, null).first)
        assertEquals(LanDeviceKind.PHONE, LanAssessment.hostFindings(identified, null).first().deviceKind)
        val proModel = host(listOf(ServiceAnnouncement(
            "Phone", "phone.local", "_device-info._tcp.local", listOf("model=Redmi Note 11 Pro"),
        )))
        assertEquals("Dispositivo sin clasificar", LanAssessment.identity(proModel, null).first)
        assertEquals("Dispositivo sin clasificar", LanAssessment.identity(host(), null).first)
    }

    @Test fun localPhoneUsesAndroidModelInsteadOfNetworkGuessing() {
        val local = LanAssessment.localDeviceFinding(LanScope.ipv4("192.168.1.10"), "Xiaomi", "2201117TG")
        assertTrue(local.title.contains("Este teléfono · Xiaomi Redmi Note 11"))
        assertEquals(LanDeviceKind.PHONE, local.deviceKind)
        assertTrue(local.detail.contains("no se sondeó"))
        val unrelated = LanAssessment.localDeviceFinding(LanScope.ipv4("192.168.1.10"), "Xiaomi", "Redmi Note 11 Pro")
        assertTrue(unrelated.title.endsWith("Xiaomi Redmi Note 11 Pro"))
    }

    @Test fun connectionFailuresNeverInventDevicesOrVulnerabilities() {
        val missing = host(probes = listOf(RouterProbe(80, RouterProbeOutcome.NOT_ACCEPTED), RouterProbe(445, RouterProbeOutcome.TIMED_OUT)))
        assertFalse(missing.visible)
        assertTrue(LanAssessment.hostFindings(missing, null).none { it.level == "risk" })
    }
}
