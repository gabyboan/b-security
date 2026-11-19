package com.bsecurity.app

import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException

/** An accepted TCP connection identifies reachability, not the application or a vulnerability. */
enum class RouterProbeOutcome { ACCEPTED, NOT_ACCEPTED, TIMED_OUT, UNREACHABLE, ERROR }

data class RouterProbe(val port: Int, val outcome: RouterProbeOutcome)

/** Interpretation is separate from Android/network I/O so failure handling can be tested offline. */
object RouterAssessment {
    val ports: Map<Int, String> = linkedMapOf(
        21 to "FTP", 22 to "SSH", 23 to "Telnet", 53 to "DNS TCP", 80 to "HTTP",
        443 to "HTTPS", 445 to "SMB", 8080 to "HTTP alternativo", 8443 to "HTTPS alternativo",
    )

    fun permitsGateway(address: InetAddress): Boolean = address is Inet4Address &&
        (address.isSiteLocalAddress || address.isLinkLocalAddress)

    fun failureOutcome(error: Exception): RouterProbeOutcome = when (error) {
        is SocketTimeoutException -> RouterProbeOutcome.TIMED_OUT
        // Cancellation is not a network result and must stop the remaining probes.
        is InterruptedException, is InterruptedIOException -> throw error
        is NoRouteToHostException -> RouterProbeOutcome.UNREACHABLE
        // ConnectException can also mean a connection failure other than an explicit refusal.
        is ConnectException -> RouterProbeOutcome.NOT_ACCEPTED
        else -> RouterProbeOutcome.ERROR
    }

    fun findings(gateway: InetAddress, probes: List<RouterProbe>, profile: RouterProfile = RouterProfile.GENERIC): List<Finding> {
        require(permitsGateway(gateway)) { "Solo se comprueban puertas de enlace IPv4 privadas de la Wi-Fi actual." }
        require(probes.all { it.port in ports }) { "La prueba contiene un puerto fuera de su alcance." }
        require(probes.map { it.port }.distinct().size == probes.size) { "Cada puerto debe tener un único resultado." }

        val outcomes = probes.groupingBy { it.outcome }.eachCount()
        fun count(outcome: RouterProbeOutcome) = outcomes[outcome] ?: 0
        val accepted = count(RouterProbeOutcome.ACCEPTED)
        val result = mutableListOf(
            Finding("Router de esta Wi-Fi", "Puerta de enlace ${gateway.hostAddress}. Las conexiones se comprueban desde esta red local."),
            Finding("Conexiones TCP", "${probes.size} de ${ports.size} puertos comprobados. Aceptadas: $accepted; " +
                "no aceptadas: ${count(RouterProbeOutcome.NOT_ACCEPTED)}; tiempo agotado: ${count(RouterProbeOutcome.TIMED_OUT)}; " +
                "inaccesibles: ${count(RouterProbeOutcome.UNREACHABLE)}; con error: ${count(RouterProbeOutcome.ERROR)}.",
                if (accepted == 0 || count(RouterProbeOutcome.ERROR) > 0) "unknown" else "info"),
        )
        ports.forEach { (port, label) ->
            if (probes.any { it.port == port && it.outcome == RouterProbeOutcome.ACCEPTED }) {
                result += Finding("Puerto $port · posible $label",
                    "Aceptó una conexión TCP. El servicio no fue identificado. " + review(port))
            }
        }
        if (probes.size < ports.size) {
            result += Finding("Prueba incompleta", "Faltan ${ports.size - probes.size} puertos por comprobar. No hay un resultado completo del router.", "unknown")
        } else if (accepted == 0) {
            result += Finding("Sin conexiones aceptadas", "Ninguno de los nueve intentos aceptó conexión. Un rechazo, un tiempo agotado o un error no demuestran que el router sea seguro.", "unknown")
        }
        val inconclusive = probes.filter { it.outcome in setOf(RouterProbeOutcome.TIMED_OUT, RouterProbeOutcome.UNREACHABLE, RouterProbeOutcome.ERROR) }
        if (inconclusive.isNotEmpty()) {
            result += Finding("Resultados sin confirmar", "Puertos ${inconclusive.map { it.port }.sorted().joinToString(", ")}: no se pudo confirmar su accesibilidad. Pueden influir filtros, la conectividad o el plazo de respuesta.", "unknown")
        }
        result += RouterProfiles.findings(profile)
        result += Finding("Cobertura limitada", "Solo TCP: ${ports.keys.joinToString(", ")}. No se verifican exposición desde Internet, UDP, firmware, contraseñas, certificados TLS, WPS, UPnP ni CVE. No se envían credenciales ni se ejecutan exploits.", "unknown")
        return result
    }

    private fun review(port: Int): String = when (port) {
        21 -> "Si se usa para FTP, revisá si es necesario y si exige un canal cifrado."
        22 -> "Si se usa para SSH, revisá quién puede administrar el router y mantené su firmware actualizado."
        23 -> "Si se usa para Telnet, deshabilitalo o sustituí su administración por un canal cifrado."
        53 -> "No se confirmó que responda consultas DNS ni qué clientes admite."
        80, 8080 -> "Si ofrece el panel por HTTP, comprobá que use HTTPS antes de introducir credenciales."
        443, 8443 -> "El puerto por sí solo no verifica HTTPS, el certificado ni la seguridad del panel."
        445 -> "Si comparte archivos, revisá los dispositivos y usuarios que pueden acceder."
        else -> error("Puerto no contemplado")
    }
}
