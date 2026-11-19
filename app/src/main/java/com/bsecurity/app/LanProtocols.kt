package com.bsecurity.app

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.Inet4Address
import java.net.InetAddress

data class DnsRecord(val name: String, val type: Int, val target: String = "", val text: List<String> = emptyList(), val address: Inet4Address? = null)
data class NetbiosStatus(val names: List<String>, val groups: List<String>)

/** Small, bounded wire parsers. All input here is untrusted network data. */
object LanProtocols {
    private fun u8(data: ByteArray, offset: Int): Int {
        require(offset in data.indices) { "Paquete truncado" }
        return data[offset].toInt() and 255
    }
    private fun u16(data: ByteArray, offset: Int) = (u8(data, offset) shl 8) or u8(data, offset + 1)
    private fun le16(data: ByteArray, offset: Int) = u8(data, offset) or (u8(data, offset + 1) shl 8)

    private fun name(data: ByteArray, offset: Int): Pair<String, Int> {
        var cursor = offset
        var next = -1
        val seen = mutableSetOf<Int>()
        val labels = mutableListOf<String>()
        var length = 0
        repeat(64) {
            require(seen.add(cursor)) { "Bucle de compresión DNS" }
            val size = u8(data, cursor)
            if (size == 0) return labels.joinToString(".") to if (next >= 0) next else cursor + 1
            if (size and 0xc0 == 0xc0) {
                if (next < 0) next = cursor + 2
                cursor = ((size and 63) shl 8) or u8(data, cursor + 1)
            } else {
                require(size <= 63 && cursor + 1 + size <= data.size)
                length += size + 1
                require(length <= 255)
                labels += data.copyOfRange(cursor + 1, cursor + 1 + size).toString(Charsets.UTF_8)
                cursor += size + 1
            }
        }
        error("Nombre DNS demasiado complejo")
    }

    fun mdnsQuery(services: List<String>): ByteArray {
        require(services.size in 1..16)
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeShort(0); out.writeShort(0); out.writeShort(services.size)
            repeat(3) { out.writeShort(0) }
            services.forEach { service ->
                service.split('.').forEach { label ->
                    val value = label.toByteArray(Charsets.UTF_8)
                    require(value.size in 1..63)
                    out.writeByte(value.size); out.write(value)
                }
                out.writeByte(0); out.writeShort(12); out.writeShort(0x8001) // PTR, IN + unicast response requested.
            }
        }
        return bytes.toByteArray()
    }

    fun dns(data: ByteArray): List<DnsRecord> {
        require(data.size in 12..65535 && u16(data, 2) and 0xfa0f == 0x8000) { "Respuesta DNS no válida" }
        val questions = u16(data, 4)
        val count = u16(data, 6) + u16(data, 8) + u16(data, 10)
        require(questions <= 32 && count <= 256)
        var cursor = 12
        repeat(questions) { cursor = name(data, cursor).second + 4; require(cursor <= data.size) }
        val result = mutableListOf<DnsRecord>()
        repeat(count) {
            val (owner, end) = name(data, cursor)
            val type = u16(data, end)
            val clazz = u16(data, end + 2) and 0x7fff
            val alive = (end + 4..end + 7).any { u8(data, it) != 0 }
            val size = u16(data, end + 8)
            val start = end + 10
            val stop = start + size
            require(stop <= data.size)
            if (clazz == 1 && alive) when (type) {
                1 -> if (size == 4) result += DnsRecord(owner, type, address = InetAddress.getByAddress(data.copyOfRange(start, stop)) as Inet4Address)
                12, 33 -> {
                    val targetStart = start + if (type == 33) 6 else 0
                    require(targetStart < stop)
                    val (target, consumed) = name(data, targetStart)
                    require(consumed == stop)
                    result += DnsRecord(owner, type, target)
                }
                16 -> {
                    var position = start
                    val text = mutableListOf<String>()
                    while (position < stop) {
                        val length = u8(data, position++)
                        require(position + length <= stop)
                        text += data.copyOfRange(position, position + length).toString(Charsets.UTF_8)
                        position += length
                    }
                    result += DnsRecord(owner, type, text = text.take(32))
                }
            }
            cursor = stop
        }
        return result
    }

    /** Associate TXT and service instances with their SRV target, never with the UDP sender. */
    fun announcements(records: List<DnsRecord>): Map<Inet4Address, List<ServiceAnnouncement>> {
        val addresses = records.filter { it.address != null }.groupBy { it.name.lowercase() }
        val texts = records.filter { it.type == 16 }.groupBy { it.name.lowercase() }
        val result = linkedMapOf<Inet4Address, MutableList<ServiceAnnouncement>>()
        records.filter { it.type == 33 }.forEach { service ->
            addresses[service.target.lowercase()].orEmpty().forEach { record ->
                val value = ServiceAnnouncement(service.name, service.target,
                    service.name.split('.').takeLast(3).joinToString("."),
                    texts[service.name.lowercase()].orEmpty().flatMap { it.text }.take(32))
                result.getOrPut(checkNotNull(record.address)) { mutableListOf() }.add(value)
            }
        }
        return result
    }

    fun echoRequest(sequence: Int, nonce: ByteArray): ByteArray {
        require(nonce.size in 8..64 && sequence in 0..65535)
        val bytes = ByteArray(8) + nonce
        bytes[0] = 8; bytes[6] = (sequence shr 8).toByte(); bytes[7] = sequence.toByte()
        var sum = 0
        bytes.indices.step(2).forEach { sum += (u8(bytes, it) shl 8) + if (it + 1 < bytes.size) u8(bytes, it + 1) else 0 }
        while (sum shr 16 != 0) sum = (sum and 65535) + (sum shr 16)
        val checksum = sum.inv() and 65535
        bytes[2] = (checksum shr 8).toByte(); bytes[3] = checksum.toByte()
        return bytes
    }

    fun echoReply(data: ByteArray, request: ByteArray): Boolean = data.size == request.size && data.size >= 16 &&
        u8(data, 0) == 0 && u8(data, 1) == 0 && data.copyOfRange(6, data.size).contentEquals(request.copyOfRange(6, request.size))

    fun netbiosQuery(transaction: Int): ByteArray {
        val out = ByteArrayOutputStream()
        DataOutputStream(out).use { d ->
            d.writeShort(transaction); d.writeShort(0); d.writeShort(1); repeat(3) { d.writeShort(0) }
            d.writeByte(32)
            val wildcard = ByteArray(16).apply { this[0] = '*'.code.toByte() }
            wildcard.forEach { d.writeByte('A'.code + ((it.toInt() and 255) shr 4)); d.writeByte('A'.code + (it.toInt() and 15)) }
            d.writeByte(0); d.writeShort(0x21); d.writeShort(1)
        }
        return out.toByteArray()
    }

    fun netbios(data: ByteArray, transaction: Int): NetbiosStatus {
        require(data.size >= 12 && u16(data, 0) == transaction && u16(data, 2) and 0xfa0f == 0x8000)
        require(u16(data, 4) <= 1 && u16(data, 6) == 1)
        var cursor = 12
        repeat(u16(data, 4)) { cursor = name(data, cursor).second + 4 }
        val end = name(data, cursor).second
        require(u16(data, end) == 0x21 && u16(data, end + 2) == 1)
        val size = u16(data, end + 8)
        cursor = end + 10
        require(size >= 1 && cursor + size <= data.size)
        val count = u8(data, cursor++)
        require(count <= 64 && 1 + count * 18 <= size)
        val names = mutableListOf<String>(); val groups = mutableListOf<String>()
        repeat(count) {
            val value = LanAssessment.safe(data.copyOfRange(cursor, cursor + 15).toString(Charsets.US_ASCII).trim(), 15)
            val suffix = u8(data, cursor + 15)
            val group = u16(data, cursor + 16) and 0x8000 != 0
            if (value.isNotEmpty()) {
                if (group && suffix == 0) groups += value
                else if (!group && suffix in setOf(0, 0x20)) names += value
            }
            cursor += 18
        }
        return NetbiosStatus(names.distinct(), groups.distinct())
    }

    fun smb1Request(messageId: Int): ByteArray {
        val dialect = byteArrayOf(2) + "NT LM 0.12".toByteArray(Charsets.US_ASCII) + byteArrayOf(0)
        val body = ByteArray(35) + dialect
        body[0] = 0xff.toByte(); body[1] = 'S'.code.toByte(); body[2] = 'M'.code.toByte(); body[3] = 'B'.code.toByte()
        body[4] = 0x72; body[9] = 0x18; body[10] = 1
        body[30] = messageId.toByte(); body[31] = (messageId shr 8).toByte()
        body[33] = dialect.size.toByte()
        return byteArrayOf(0, 0, 0, body.size.toByte()) + body
    }

    /** Input is an SMB payload without its four-byte NetBIOS session header. */
    fun smb1(data: ByteArray, messageId: Int): ServiceEvidence? {
        if (data.size < 69 || !data.copyOfRange(0, 5).contentEquals(byteArrayOf(0xff.toByte(), 0x53, 0x4d, 0x42, 0x72))) return null
        if ((5..8).any { u8(data, it) != 0 } || u8(data, 9) and 0x80 == 0 || le16(data, 30) != messageId) return null
        if (u8(data, 32) != 17 || le16(data, 33) != 0 || 69 + le16(data, 67) > data.size) return null
        val security = u8(data, 35)
        val required = security and 8 != 0
        return ServiceEvidence(445, "SMB1", "El servidor seleccionó NT LM 0.12 (SMB1). Firma ${if (required) "obligatoria" else "no obligatoria"}.", listOf(
            Finding("Protocolo SMB1 habilitado", "Confirmado mediante negociación SMB_COM_NEGOTIATE, dialecto NT LM 0.12. Deshabilitá SMB1 y usá SMB2/3; comprobá la compatibilidad de los clientes. No demuestra una CVE concreta ni acceso a archivos.", "risk"),
        ))
    }

    fun http(port: Int, data: ByteArray): ServiceEvidence? {
        val response = data.toString(Charsets.ISO_8859_1)
        val boundary = response.indexOf("\r\n\r\n")
        if (boundary < 0) return null // A partial challenge is not verified evidence.
        val head = response.substring(0, boundary)
        val lines = head.split("\r\n")
        if (!lines.first().matches(Regex("HTTP/1\\.[01] [0-9]{3}( .*)?"))) return null
        if (lines.drop(1).any { !it.matches(Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]+:[\\t\\x20-\\x7e\\x80-\\xff]*")) }) return null
        val headers = lines.drop(1).mapNotNull { line ->
            val split = line.indexOf(':')
            if (split < 1) null else line.take(split).lowercase() to line.substring(split + 1).trim()
        }
        val challenge = headers.filter { it.first == "www-authenticate" }.map { it.second }
        val basic = lines.first().substring(9, 12) == "401" && challenge.any { it.startsWith("Basic", true) && (it.length == 5 || it[5].isWhitespace()) }
        val detail = listOf(lines.first()) + headers.filter { it.first in setOf("server", "x-powered-by", "location", "www-authenticate") }
            .map { "${it.first}: ${it.second}" }
        val issues = if (basic) listOf(Finding("Autenticación Basic ofrecida por HTTP", "Respuesta 401 y WWW-Authenticate: Basic recibidas sin TLS. Las credenciales quedarían sin cifrar si un cliente las envía. Exigí HTTPS y retirala del servicio HTTP. No se enviaron credenciales.", "risk")) else emptyList()
        return ServiceEvidence(port, "HTTP", LanAssessment.safe(detail.joinToString("; ")), issues)
    }

    fun banner(port: Int, bytes: ByteArray): ServiceEvidence? {
        val text = bytes.toString(Charsets.ISO_8859_1)
        return when {
            port == 22 && text.lineSequence().take(8).any { it.startsWith("SSH-2.0-") || it.startsWith("SSH-1.99-") } ->
                ServiceEvidence(port, "SSH", LanAssessment.safe(text.trim(), 300))
            port == 21 && Regex("^220[ -]").containsMatchIn(text) -> ServiceEvidence(port, "FTP (saludo 220 compatible)", LanAssessment.safe(text.trim(), 300))
            port == 23 && bytes.indices.any { i -> i + 2 < bytes.size && u8(bytes, i) == 255 && u8(bytes, i + 1) in 251..254 } ->
                ServiceEvidence(port, "Telnet", "Recibida negociación IAC WILL/WONT/DO/DONT en TCP sin TLS.", listOf(
                    Finding("Telnet sin TLS accesible", "Negociación Telnet confirmada. Este transporte inicial no cifra la sesión. Sustituí la administración por SSH/HTTPS o restringí el servicio. No se comprobó el inicio de sesión ni el contenido posterior.", "risk")))
            else -> null
        }
    }
}
