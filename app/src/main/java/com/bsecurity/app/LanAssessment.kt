package com.bsecurity.app

import java.net.Inet4Address
import java.net.InetAddress
import java.util.Locale

/** Never resolves a hostname, accepts abbreviated IPv4, or expands a user supplied CIDR. */
object LanScope {
    fun ipv4(text: String): Inet4Address {
        val parts = text.trim().split('.')
        require(parts.size == 4 && parts.all { it.matches(Regex("0|[1-9][0-9]{0,2}")) && it.toInt() <= 255 }) {
            "Ingresá una IPv4 completa, por ejemplo 192.168.2.10."
        }
        return InetAddress.getByAddress(parts.map { it.toInt().toByte() }.toByteArray()) as Inet4Address
    }

    fun privateAddress(address: InetAddress) = address is Inet4Address &&
        (address.isSiteLocalAddress || address.isLinkLocalAddress)

    fun number(address: Inet4Address): Long = address.address.fold(0L) { n, b -> (n shl 8) or (b.toInt() and 255).toLong() }
    private fun address(value: Long) = InetAddress.getByAddress(ByteArray(4) { (value shr (24 - 8 * it)).toByte() }) as Inet4Address
    fun sameSubnet(a: Inet4Address, b: Inet4Address, prefix: Int): Boolean {
        require(prefix in 0..32)
        val mask = (0xffffffffL shl (32 - prefix)) and 0xffffffffL
        return (number(a) and mask) == (number(b) and mask)
    }

    fun targets(local: Inet4Address, prefix: Int, gateway: Inet4Address?): List<Inet4Address> {
        require(privateAddress(local) && prefix in 1..32) { "Se necesita una IPv4 privada y un prefijo válido." }
        val mask = (0xffffffffL shl (32 - prefix)) and 0xffffffffL
        val first = number(local) and mask
        val last = first or (mask xor 0xffffffffL)
        val window = number(local) and 0xffffff00L
        val start = maxOf(first + if (prefix <= 30) 1 else 0, window)
        val end = minOf(last - if (prefix <= 30) 1 else 0, window + 255)
        val hosts = (start..end).map(::address).filter { it != local && privateAddress(it) }.toMutableList()
        if (gateway != null && privateAddress(gateway) && gateway != local && gateway !in hosts) hosts += gateway
        return hosts.take(256)
    }

    fun otherTargets(text: String): List<Inet4Address> {
        val values = text.trim().split(Regex("[,;\\s]+")).filter { it.isNotBlank() }
        require(values.size in 1..3) { "Ingresá entre una y tres IP privadas, separadas por comas." }
        return values.map(::ipv4).onEach {
            require(privateAddress(it)) { "Las pruebas admiten únicamente IP privadas o de enlace local." }
        }.distinct()
    }
}

data class EchoSample(val millis: Double? = null, val error: String? = null, val sent: Boolean = true)
data class EchoSummary(val samples: List<EchoSample>) {
    val sent get() = samples.count { it.sent }
    val replies get() = samples.mapNotNull { it.millis }
    fun text(): String {
        val loss = if (sent == 0) "no medible" else "${(sent - replies.size) * 100 / sent}%"
        val latency = if (replies.isEmpty()) "sin latencia medible" else String.format(Locale.US,
            "RTT mín/prom/máx %.1f/%.1f/%.1f ms", replies.min(), replies.average(), replies.max())
        val errors = samples.mapNotNull { it.error }.distinct().joinToString("; ")
        return "ICMP Echo: $sent enviados, ${replies.size} respuestas, pérdida $loss · $latency" +
            if (errors.isEmpty()) "" else "\n${LanAssessment.safe(errors)}"
    }
}

enum class LanDeviceKind { ROUTER, PHONE, COMPUTER, PRINTER, MEDIA, OTHER }

data class ServiceEvidence(val port: Int, val protocol: String, val detail: String, val issues: List<Finding> = emptyList())
data class ServiceAnnouncement(val name: String, val target: String, val type: String, val text: List<String> = emptyList()) {
    fun description() = "$name → $target" + if (text.isEmpty()) "" else text.joinToString("; ", prefix = "; ")
}
data class HostObservation(
    val address: Inet4Address,
    val echo: EchoSummary,
    val probes: List<RouterProbe>,
    val services: List<ServiceEvidence>,
    val announcements: List<ServiceAnnouncement> = emptyList(),
    val netbios: NetbiosStatus? = null,
) {
    val visible get() = echo.replies.isNotEmpty() || probes.any { it.outcome == RouterProbeOutcome.ACCEPTED } ||
        announcements.isNotEmpty() || netbios != null
}

object LanAssessment {
    val ports = linkedMapOf(21 to "FTP", 22 to "SSH", 23 to "Telnet", 80 to "HTTP", 443 to "HTTPS",
        445 to "SMB", 3389 to "RDP", 8080 to "HTTP alternativo", 8443 to "HTTPS alternativo", 9100 to "impresión TCP")
    private val redmiNote11 = Regex("\\b(redmi[\\s_-]+note[\\s_-]+11(?![\\s_-]*(?:pro|s|se|t))|2201117(?:tg|ti|ty|tl|si)|spesn?)\\b")

    fun safe(text: String, limit: Int = 600) = text.filter { !it.isISOControl() || it == '\n' }.take(limit)

    fun localDeviceFinding(address: Inet4Address, manufacturer: String, model: String): Finding {
        val deviceModel = when {
            (manufacturer.equals("Xiaomi", ignoreCase = true) || manufacturer.equals("Redmi", ignoreCase = true)) &&
                redmiNote11.containsMatchIn(model.lowercase(Locale.ROOT)) ->
                "Xiaomi Redmi Note 11"
            else -> listOf(manufacturer, model).map { safe(it.trim(), 80) }.filter { it.isNotEmpty() }.distinct().joinToString(" ")
        }.ifEmpty { "Android" }
        return Finding(
            "${address.hostAddress} · Este teléfono · $deviceModel",
            "Modelo informado por Android: ${safe(manufacturer, 80)} ${safe(model, 80)}. Este teléfono no se sondeó; la identidad proviene del propio dispositivo.",
            deviceKind = LanDeviceKind.PHONE,
        )
    }

    /** Only explicit protocol metadata supports an OS label. Apple/SMB ports alone do not. */
    fun identity(host: HostObservation, gateway: Inet4Address?): Pair<String, String> {
        if (host.address == gateway) return "Router / puerta de enlace" to "Función de puerta de enlace configurada en Android; modelo sin confirmar."
        // An instance/host name (e.g. "iPhone de Ana") is user chosen. Classify using TXT
        // fields and service types, never merely that name or an open port.
        val advertised = host.announcements.flatMap { it.text + it.type }
        val metadata = (advertised + host.services.map { it.detail }).joinToString(" ").lowercase(Locale.ROOT)
        val explicit = when {
            redmiNote11.containsMatchIn(metadata) ->
                "Xiaomi Redmi Note 11 (probable)"
            Regex("\\biphone[0-9,]*\\b").containsMatchIn(metadata) -> "iPhone (probable)"
            Regex("\\bipad[0-9,]*\\b").containsMatchIn(metadata) -> "iPad (probable)"
            "android" in metadata || "_adb-tls-connect._tcp" in metadata || "_adb-tls-pairing._tcp" in metadata -> "Android (probable)"
            Regex("\\b(windows|microsoft-iis)\\b").containsMatchIn(metadata) -> "PC / servidor Windows (probable)"
            Regex("\\b(macbook|imac|macmini|macpro|macos)\\b").containsMatchIn(metadata) -> "PC Mac (probable)"
            Regex("\\b(ubuntu|debian|linux)\\b").containsMatchIn(metadata) -> "PC / equipo Linux (probable)"
            "_ipp._tcp" in metadata || "_printer._tcp" in metadata -> "Impresora (servicio anunciado)"
            "_googlecast._tcp" in metadata -> "Dispositivo multimedia / Cast"
            "_airplay._tcp" in metadata -> "Dispositivo AirPlay; sistema sin confirmar"
            host.netbios != null || host.services.any { it.protocol == "SMB1" } -> "Equipo con SMB / NetBIOS; PC o NAS sin confirmar"
            else -> "Dispositivo sin clasificar"
        }
        return explicit to "Identificación orientativa por metadatos anunciados; pueden ser incompletos o modificados."
    }

    fun hostFindings(host: HostObservation, gateway: Inet4Address?): List<Finding> {
        val (kind, certainty) = identity(host, gateway)
        val deviceKind = when {
            host.address == gateway -> LanDeviceKind.ROUTER
            kind.startsWith("Xiaomi ") || kind.startsWith("Android ") || kind.startsWith("iPhone") || kind.startsWith("iPad") -> LanDeviceKind.PHONE
            kind.startsWith("PC ") -> LanDeviceKind.COMPUTER
            kind.startsWith("Impresora") -> LanDeviceKind.PRINTER
            kind.startsWith("Dispositivo multimedia") || kind.startsWith("Dispositivo AirPlay") -> LanDeviceKind.MEDIA
            else -> LanDeviceKind.OTHER
        }
        val accepted = host.probes.filter { it.outcome == RouterProbeOutcome.ACCEPTED }.joinToString { "${it.port} (${ports[it.port]})" }
        val outcomes = host.probes.groupingBy { it.outcome }.eachCount()
        val details = buildList {
            add(certainty)
            add(host.echo.text())
            add("TCP aceptados: ${accepted.ifEmpty { "ninguno" }}. Un puerto no identifica el servicio.")
            add("No aceptados: ${outcomes[RouterProbeOutcome.NOT_ACCEPTED] ?: 0}; tiempo agotado: ${outcomes[RouterProbeOutcome.TIMED_OUT] ?: 0}; inaccesibles: ${outcomes[RouterProbeOutcome.UNREACHABLE] ?: 0}; errores: ${outcomes[RouterProbeOutcome.ERROR] ?: 0}.")
            host.services.forEach { add("${it.port} · ${it.protocol}: ${safe(it.detail)}") }
            host.announcements.distinct().take(12).forEach { add("mDNS: ${safe(it.description(), 240)}") }
            host.netbios?.let {
                add("NetBIOS: ${safe(it.names.joinToString())}. Grupo de trabajo/dominio anunciado: ${safe(it.groups.joinToString()).ifEmpty { "no informado" }}.")
            }
        }.joinToString("\n\n")
        return listOf(Finding("${host.address.hostAddress} · $kind", details, deviceKind = deviceKind)) + host.services.flatMap { service ->
            service.issues.map { it.copy(title = "${host.address.hostAddress}:${service.port} · ${it.title}") }
        }
    }
}
