package com.bsecurity.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import java.io.DataInputStream
import java.io.InterruptedIOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** One snapshot and one interface for every packet, including ICMP and multicast. */
class LanScanner(context: Context) : AutoCloseable {
    private val cm = context.getSystemService(ConnectivityManager::class.java)
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    private val network: Network = cm.allNetworks.firstOrNull { value ->
        val caps = cm.getNetworkCapabilities(value)
        caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    } ?: error("Conectate a una Wi-Fi para reconocer su red local.")
    private val link = cm.getLinkProperties(network) ?: error("Android no informa la configuración de esta Wi-Fi.")
    private val localLink = link.linkAddresses.firstOrNull { LanScope.privateAddress(it.address) }
        ?: error("La Wi-Fi no tiene una IPv4 privada. El reconocimiento IPv6 todavía no está implementado.")
    private val local = localLink.address as Inet4Address
    private val prefix = localLink.prefixLength
    private val gateway = link.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway as? Inet4Address
    private val targets = LanScope.targets(local, prefix, gateway)
    private val pool = Executors.newFixedThreadPool(12)
    private val active = ConcurrentHashMap.newKeySet<AutoCloseable>()
    private val random = SecureRandom()
    @Volatile private var cancelled = false
    @Volatile private var multicastNote = ""
    private var deadline = Long.MAX_VALUE
    val scopeKey: String get() = "${network.networkHandle}|${local.hostAddress}/$prefix|${gateway?.hostAddress}|${link.interfaceName}"

    fun scopeDescription(): String = "Teléfono ${local.hostAddress}/$prefix · interfaz ${link.interfaceName}\n" +
        "Puerta de enlace: ${gateway?.hostAddress ?: "no informada"}. ${targets.size} direcciones como máximo." +
        if (prefix < 24) " Se cubrirá solo el bloque de 256 direcciones que contiene al teléfono y la puerta de enlace; la subred es más amplia." else ""

    private fun guard() {
        if (cancelled || Thread.currentThread().isInterrupted) throw CancellationException("Reconocimiento detenido.")
        check(SystemClock.elapsedRealtime() < deadline) { "Se alcanzó el límite de 90 segundos; repetí con una red más pequeña." }
        val current = cm.getLinkProperties(network)
        val caps = cm.getNetworkCapabilities(network)
        check(caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true && current != null &&
            current.interfaceName == link.interfaceName && current.linkAddresses.contains(localLink) &&
            current.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway == gateway) {
            "La Wi-Fi, su dirección o la puerta de enlace cambió. Reconocimiento cancelado."
        }
    }

    private fun <T : AutoCloseable, R> resource(value: T, action: (T) -> R): R {
        active.add(value)
        try { guard(); return action(value) } finally { active.remove(value); runCatching { value.close() } }
    }

    fun scan(other: List<Inet4Address> = emptyList(), progress: (Int, Int, String) -> Unit): List<Finding> {
        require(other.size <= 3 && other.all { LanScope.privateAddress(it) && it != local }) { "Los destinos deben ser hasta tres IP privadas de otros equipos." }
        deadline = SystemClock.elapsedRealtime() + 90_000
        guard()
        val selected = other.isNotEmpty()
        val candidates = if (selected) other.distinct() else targets
        val total = candidates.size + 2
        progress(0, total, if (selected) "Comprobando destinos autorizados…" else "Buscando anuncios mDNS…")
        val announcements = if (selected) emptyMap() else mdns(candidates.toSet())
        progress(1, total, "Enviando Echo Request y conexiones TCP…")
        val completion = ExecutorCompletionService<HostObservation>(pool)
        val discoveryPorts = setOf(22, 80, 443, 445, 3389, 9100)
        candidates.forEach { address -> completion.submit(Callable {
            guard()
            val echo = echo(address, if (selected || address == gateway) 4 else 1)
            val probes = (if (selected) LanAssessment.ports.keys else discoveryPorts).map { probe(address, it, 200) }
            val nb = netbios(address)
            HostObservation(address, echo, probes, emptyList(), announcements[address].orEmpty(), nb)
        }) }
        val observations = mutableListOf<HostObservation>()
        repeat(candidates.size) { index ->
            guard()
            val future = completion.poll(1, TimeUnit.SECONDS)
            // Keep cancellation and a changing interface observable while waiting for workers.
            val observation = if (future != null) future.get() else {
                var next = completion.poll(1, TimeUnit.SECONDS)
                while (next == null) { guard(); next = completion.poll(1, TimeUnit.SECONDS) }
                next.get()
            }
            observations += observation
            progress(index + 2, total, "${index + 1}/${candidates.size} direcciones · ${observations.count { it.visible }} con respuesta")
        }
        val visible = observations.filter { selected || it.visible }.sortedBy { LanScope.number(it.address) }
        // Bound application handshakes even on a LAN with many responding addresses.
        val enriched = visible.take(64).map { host -> pool.submit(Callable { enrich(host) }) }
        progress(total - 1, total, "Identificando servicios en ${enriched.size} equipos…")
        val hosts = enriched.map { future ->
            while (!future.isDone) { guard(); try { future.get(250, TimeUnit.MILLISECONDS) } catch (_: java.util.concurrent.TimeoutException) { } }
            future.get()
        } + visible.drop(64)
        guard()
        val issues = hosts.flatMap { it.services }.sumOf { it.issues.size }
        val findings = mutableListOf(
            Finding(if (selected) "Acceso a destinos de otras redes" else "Reconocimiento de la red local",
                scopeDescription() + "\n${candidates.size} direcciones probadas; ${hosts.count { it.visible }} con respuesta; $issues hallazgos de configuración con evidencia de protocolo.\n" +
                    "ICMP y TCP ligados a esta Wi-Fi; sin usar datos móviles como alternativa."),
            Finding("Router, switch y segmentación", "La puerta de enlace indica una función de router. Un switch de capa 2 no aparece como salto IP: su presencia, puertos físicos y VLAN requieren LLDP/SNMP o acceso de administración. No se infieren a partir de la latencia.\n" +
                if (selected) "Se probaron IP concretas; alcanzar un destino de otra subred no demuestra acceso al resto de esa red ni que el aislamiento sea incorrecto." else "Para comprobar otra subred, indicá hasta tres IP de equipos autorizados en «Probar otras subredes»."),
            Finding("Grupos de trabajo y software", "Se consultó NetBIOS/UDP 137 para nombres y grupos anunciados. Grupo de trabajo/dominio no equivale a Grupo Hogar de Windows. Se muestran servicios, versiones y modelos que anuncian los equipos; el inventario completo de programas requiere un agente o credenciales de administración."),
        )
        if (selected) hosts.forEach { host ->
            val reached = host.echo.replies.isNotEmpty() || host.probes.any { it.outcome == RouterProbeOutcome.ACCEPTED } || host.netbios != null
            findings += Finding("${host.address.hostAddress} · ${if (LanScope.sameSubnet(local, host.address, prefix)) "misma subred" else "otra subred"}",
                if (reached) "El destino respondió desde esta Wi-Fi. Ver los protocolos y resultados abajo." else "Acceso no confirmado. Los filtros, el aislamiento o un equipo apagado pueden impedir la respuesta; no prueba aislamiento correcto.", if (reached) "info" else "unknown")
        }
        hosts.forEach { findings += LanAssessment.hostFindings(it, gateway) }
        findings += LanAssessment.localDeviceFinding(local, Build.MANUFACTURER, Build.MODEL)
        if (multicastNote.isNotEmpty()) findings += Finding("Descubrimiento mDNS", multicastNote, "unknown")
        if (visible.size > 64) findings += Finding("Identificación parcial", "Solo los primeros 64 equipos recibieron identificación de servicios; los restantes conservan sus resultados ICMP/TCP/NetBIOS.", "unknown")
        findings += Finding("Cobertura y evidencia", "Descubrimiento: 1 Echo por IP (4 al gateway), TCP ${discoveryPorts.joinToString()} y NetBIOS; 4 Echo y los ${LanAssessment.ports.size} puertos en destinos explícitos. En equipos detectados se amplían los puertos y se leen HTTP, SSH/FTP/Telnet y negociación SMB1. mDNS durante 2,5 s.\n" +
            "No responder no equivale a no existir: algunos móviles duermen y las redes invitadas aíslan clientes. No se verifican CVE por un banner ni se accede a archivos. IPv6, MAC/OUI, firmware, inventario autenticado y switches físicos quedan pendientes.", "unknown")
        progress(total, total, "Reconocimiento terminado")
        return findings
    }

    private fun probe(address: Inet4Address, port: Int, timeout: Int): RouterProbe {
        guard()
        val outcome = try {
            resource(Socket()) { socket -> network.bindSocket(socket); socket.connect(InetSocketAddress(address, port), timeout) }
            RouterProbeOutcome.ACCEPTED
        } catch (error: Exception) { guard(); RouterAssessment.failureOutcome(error) }
        return RouterProbe(port, outcome)
    }

    internal fun echo(address: Inet4Address, count: Int): EchoSummary {
        require(count in 1..4)
        val samples = mutableListOf<EchoSample>()
        repeat(count) { sequence ->
            guard()
            var sent = false
            val sample = try {
                // Android documents O_NONBLOCK as the pre-29 equivalent of SOCK_NONBLOCK.
                val fd = Os.socket(OsConstants.AF_INET, OsConstants.SOCK_DGRAM or OsConstants.O_NONBLOCK, OsConstants.IPPROTO_ICMP)
                val closed = AtomicBoolean(false)
                resource(AutoCloseable { if (closed.compareAndSet(false, true)) Os.close(fd) }) {
                    network.bindSocket(fd)
                    Os.connect(fd, address, 0)
                    val request = LanProtocols.echoRequest(sequence, ByteArray(16).also(random::nextBytes))
                    val started = SystemClock.elapsedRealtimeNanos()
                    val until = SystemClock.elapsedRealtime() + 250
                    Os.write(fd, request, 0, request.size); sent = true
                    var reply: Double? = null
                    while (SystemClock.elapsedRealtime() < until && reply == null) {
                        guard()
                        val poll = StructPollfd().apply { this.fd = fd; events = OsConstants.POLLIN.toShort() }
                        if (Os.poll(arrayOf(poll), (until - SystemClock.elapsedRealtime()).coerceAtLeast(1).toInt()) == 0) break
                        guard()
                        val buffer = ByteArray(512)
                        val size = Os.read(fd, buffer, 0, buffer.size)
                        if (LanProtocols.echoReply(buffer.copyOf(size), request)) reply = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0
                    }
                    EchoSample(reply)
                }
            } catch (error: ErrnoException) {
                guard()
                if (error.errno in setOf(OsConstants.EAGAIN, OsConstants.ETIMEDOUT)) EchoSample(sent = sent)
                else EchoSample(error = if (!sent) "ICMP no disponible en este Android (${error.errno})." else "Error ICMP (${error.errno}).", sent = sent)
            } catch (error: Exception) {
                guard(); if (error is InterruptedIOException) throw error
                EchoSample(error = "No se pudo medir ICMP (${error.javaClass.simpleName}).", sent = sent)
            }
            samples += sample
            if (!sample.sent) return EchoSummary(samples)
        }
        return EchoSummary(samples)
    }

    private fun netbios(address: Inet4Address): NetbiosStatus? {
        guard()
        return try { resource(DatagramSocket(null)) { socket ->
            network.bindSocket(socket); socket.bind(InetSocketAddress(local, 0)); socket.soTimeout = 180
            socket.connect(address, 137)
            val transaction = random.nextInt(65536)
            val query = LanProtocols.netbiosQuery(transaction)
            socket.send(DatagramPacket(query, query.size))
            val reply = DatagramPacket(ByteArray(2048), 2048); socket.receive(reply)
            LanProtocols.netbios(reply.data.copyOf(reply.length), transaction)
        } } catch (_: Exception) { guard(); null }
    }

    private fun mdns(allowed: Set<Inet4Address>): Map<Inet4Address, List<ServiceAnnouncement>> {
        val lock = wifi.createMulticastLock("bsecurity-discovery").apply { setReferenceCounted(false) }
        return try {
            lock.acquire()
            resource(DatagramSocket(null)) { socket ->
                network.bindSocket(socket); socket.bind(InetSocketAddress(local, 0)); socket.soTimeout = 200
                val services = listOf("_workstation._tcp.local", "_smb._tcp.local", "_device-info._tcp.local", "_airplay._tcp.local",
                    "_raop._tcp.local", "_companion-link._tcp.local", "_googlecast._tcp.local", "_adb-tls-connect._tcp.local",
                    "_adb-tls-pairing._tcp.local", "_ipp._tcp.local", "_printer._tcp.local")
                val query = LanProtocols.mdnsQuery(services)
                socket.send(DatagramPacket(query, query.size, InetAddress.getByAddress(byteArrayOf(224.toByte(), 0, 0, 251.toByte())), 5353))
                val until = SystemClock.elapsedRealtime() + 2500
                val records = mutableListOf<DnsRecord>()
                var packets = 0
                while (SystemClock.elapsedRealtime() < until && packets < 128) {
                    guard()
                    val response = DatagramPacket(ByteArray(9000), 9000)
                    try {
                        socket.receive(response); packets++
                        val sender = response.address as? Inet4Address
                        if (sender != null && LanScope.sameSubnet(local, sender, prefix) && response.port == 5353) {
                            runCatching { LanProtocols.dns(response.data.copyOf(response.length)) }.onSuccess {
                                if (records.size + it.size <= 2048) records += it
                            }
                        }
                    } catch (_: SocketTimeoutException) { }
                }
                LanProtocols.announcements(records).filterKeys { it in allowed }
            }
        } catch (error: Exception) {
            guard(); multicastNote = "No se pudo completar mDNS (${error.javaClass.simpleName}); se mantuvieron las pruebas por IP."; emptyMap()
        } finally { if (lock.isHeld) lock.release() }
    }

    private fun enrich(host: HostObservation): HostObservation {
        val previous = host.probes.associateBy { it.port }
        val probes = LanAssessment.ports.keys.map { previous[it] ?: probe(host.address, it, 250) }
        val services = probes.filter { it.outcome == RouterProbeOutcome.ACCEPTED && it.port in setOf(21, 22, 23, 80, 445, 8080) }.mapNotNull {
            service(host.address, it.port)
        }
        return host.copy(probes = probes, services = services)
    }

    internal fun service(address: Inet4Address, port: Int, protocolPort: Int = port): ServiceEvidence? {
        guard()
        return try { resource(Socket()) { socket ->
            network.bindSocket(socket); socket.connect(InetSocketAddress(address, port), 400); socket.soTimeout = 450
            val input = socket.getInputStream(); val output = socket.getOutputStream()
            when (protocolPort) {
                80, 8080 -> {
                    // Deliberate protocol diagnostic over a bound raw socket. Never follows redirects.
                    output.write("HEAD / HTTP/1.1\r\nHost: ${address.hostAddress}\r\nConnection: close\r\nUser-Agent: B-Security-Diagnostic/1\r\n\r\n".toByteArray(Charsets.US_ASCII))
                    val bytes = java.io.ByteArrayOutputStream()
                    val until = SystemClock.elapsedRealtime() + 900
                    var ending = 0
                    while (bytes.size() < 8192 && SystemClock.elapsedRealtime() < until) {
                        guard()
                        val next = input.read()
                        if (next < 0) break
                        bytes.write(next)
                        ending = (ending shl 8) or next
                        if (ending == 0x0d0a0d0a) break
                    }
                    LanProtocols.http(port, bytes.toByteArray())
                }
                445 -> {
                    val id = random.nextInt(65536)
                    output.write(LanProtocols.smb1Request(id))
                    val stream = DataInputStream(input)
                    val header = ByteArray(4); stream.readFully(header)
                    val length = ((header[1].toInt() and 255) shl 16) or ((header[2].toInt() and 255) shl 8) or (header[3].toInt() and 255)
                    if (header[0] != 0.toByte() || length !in 35..8192) null else {
                        val reply = ByteArray(length); stream.readFully(reply); LanProtocols.smb1(reply, id)?.copy(port = port)
                    }
                }
                else -> {
                    val bytes = ByteArray(1024); val count = input.read(bytes)
                    if (count > 0) LanProtocols.banner(protocolPort, bytes.copyOf(count))?.copy(port = port) else null
                }
            }
        } } catch (_: Exception) { guard(); null }
    }

    override fun close() {
        cancelled = true
        active.forEach { runCatching { it.close() } }
        pool.shutdownNow()
    }
}
