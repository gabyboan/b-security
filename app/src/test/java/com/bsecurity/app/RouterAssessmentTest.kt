package com.bsecurity.app

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException

class RouterAssessmentTest {
    private val gateway = ipv4(192, 168, 50, 1)

    @Test fun gatewayAllowsOnlyPrivateOrLinkLocalIpv4() {
        listOf(ipv4(10, 0, 0, 1), ipv4(172, 16, 0, 1), ipv4(172, 31, 255, 254),
            gateway, ipv4(169, 254, 10, 1)).forEach { assertTrue(RouterAssessment.permitsGateway(it)) }
        listOf(ipv4(172, 15, 255, 254), ipv4(172, 32, 0, 1), ipv4(8, 8, 8, 8),
            ipv4(127, 0, 0, 1), ipv4(0, 0, 0, 0), ipv4(224, 0, 0, 1),
            InetAddress.getByAddress(ByteArray(16).also { it[15] = 1 })).forEach {
            assertFalse(RouterAssessment.permitsGateway(it))
        }
    }

    @Test fun invalidGatewayCannotProduceAReport() {
        assertThrows(IllegalArgumentException::class.java) {
            RouterAssessment.findings(ipv4(8, 8, 8, 8), emptyList())
        }
    }

    @Test fun distinguishesTimeoutUnreachableRefusalAndUnexpectedFailure() {
        assertEquals(RouterProbeOutcome.TIMED_OUT, RouterAssessment.failureOutcome(SocketTimeoutException()))
        assertEquals(RouterProbeOutcome.UNREACHABLE, RouterAssessment.failureOutcome(NoRouteToHostException()))
        assertEquals(RouterProbeOutcome.NOT_ACCEPTED, RouterAssessment.failureOutcome(ConnectException()))
        assertEquals(RouterProbeOutcome.ERROR, RouterAssessment.failureOutcome(IOException()))
        assertEquals(RouterProbeOutcome.ERROR, RouterAssessment.failureOutcome(SecurityException()))
    }

    @Test fun interruptionIsPropagatedRatherThanReportedAsAClosedPort() {
        listOf(InterruptedException(), InterruptedIOException()).forEach { interruption ->
            val actual = assertThrows(interruption.javaClass) { RouterAssessment.failureOutcome(interruption) }
            assertSame(interruption, actual)
        }
    }

    @Test fun allTimeoutsNeverBecomeASecuritySuccess() {
        val report = RouterAssessment.findings(gateway, all(RouterProbeOutcome.TIMED_OUT))
        assertTrue(report.any { it.title == "Sin conexiones aceptadas" })
        assertTrue(report.any { it.title == "Resultados sin confirmar" })
        assertFalse(report.any { it.level == "ok" || it.level == "risk" })
        assertTrue(report.single { it.title == "Conexiones TCP" }.detail.contains("tiempo agotado: 9"))
    }

    @Test fun mixedResultsKeepFailedProbesVisible() {
        val probes = all(RouterProbeOutcome.NOT_ACCEPTED).map { probe ->
            when (probe.port) {
                80 -> probe.copy(outcome = RouterProbeOutcome.ACCEPTED)
                443 -> probe.copy(outcome = RouterProbeOutcome.TIMED_OUT)
                8443 -> probe.copy(outcome = RouterProbeOutcome.ERROR)
                else -> probe
            }
        }
        val report = RouterAssessment.findings(gateway, probes)
        val summary = report.single { it.title == "Conexiones TCP" }
        assertEquals("unknown", summary.level)
        assertTrue(summary.detail.contains("Aceptadas: 1; no aceptadas: 6; tiempo agotado: 1; inaccesibles: 0; con error: 1"))
        assertTrue(report.any { it.title == "Puerto 80 · posible HTTP" })
        assertFalse(report.any { it.title.startsWith("Puerto 443") || it.title == "Sin conexiones aceptadas" })
        assertTrue(report.single { it.title == "Resultados sin confirmar" }.detail.contains("443, 8443"))
    }

    @Test fun acceptingHttpsPortsDoesNotVerifyTlsOrIdentifyTheRouter() {
        val report = RouterAssessment.findings(gateway, listOf(RouterProbe(443, RouterProbeOutcome.ACCEPTED)))
        val accepted = report.single { it.title.startsWith("Puerto 443") }
        assertEquals("info", accepted.level)
        assertTrue(accepted.detail.contains("servicio no fue identificado"))
        assertTrue(accepted.detail.contains("no verifica HTTPS"))
        assertEquals("unknown", report.single { it.title == "Identificación del router" }.level)
        assertFalse(report.any { it.level == "ok" })
    }

    @Test fun partialAndEmptyReportsStayIncomplete() {
        listOf(emptyList(), listOf(RouterProbe(80, RouterProbeOutcome.NOT_ACCEPTED))).forEach { probes ->
            val report = RouterAssessment.findings(gateway, probes)
            assertTrue(report.any { it.title == "Prueba incompleta" && it.level == "unknown" })
            assertFalse(report.any { it.title == "Sin conexiones aceptadas" })
        }
    }

    @Test fun duplicateOrOutOfScopePortsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            RouterAssessment.findings(gateway, listOf(RouterProbe(80, RouterProbeOutcome.ACCEPTED), RouterProbe(80, RouterProbeOutcome.ERROR)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            RouterAssessment.findings(gateway, listOf(RouterProbe(65535, RouterProbeOutcome.ACCEPTED)))
        }
    }

    @Test fun openPortsStayOrderedAndDoNotImplyAVulnerability() {
        val report = RouterAssessment.findings(gateway, all(RouterProbeOutcome.ACCEPTED).reversed())
        val accepted = report.filter { it.title.startsWith("Puerto ") }
        assertEquals(9, accepted.size)
        assertTrue(accepted.first().title.startsWith("Puerto 21"))
        assertTrue(accepted.last().title.startsWith("Puerto 8443"))
        assertTrue(accepted.all { it.level == "info" && it.detail.contains("no fue identificado") })
        assertTrue(report.single { it.title == "Cobertura limitada" }.detail.contains("exposición desde Internet"))
    }

    @Test fun modelProfileDoesNotTurnUnreachablePortsIntoVerifiedProtection() {
        val report = RouterAssessment.findings(gateway, all(RouterProbeOutcome.TIMED_OUT), RouterProfile.FIBERHOME_HG6144F)
        assertTrue(report.any { it.title == "Sin conexiones aceptadas" })
        assertFalse(report.any { it.level == "ok" || it.level == "risk" || it.title.startsWith("Puerto ") })
        assertTrue(report.single { it.title == "Perfil de referencia" }.detail.contains("no confirma el modelo"))
        assertTrue(report.single { it.title == "Wi-Fi del HG6144F" }.detail.contains("no confirma que esté activo"))
        assertTrue(report.single { it.title == "Firmware del HG6144F" }.detail.contains("no verifica su estado"))
    }

    private fun all(outcome: RouterProbeOutcome) = RouterAssessment.ports.keys.map { RouterProbe(it, outcome) }
    private fun ipv4(vararg bytes: Int): InetAddress = InetAddress.getByAddress(bytes.map { it.toByte() }.toByteArray())
}
