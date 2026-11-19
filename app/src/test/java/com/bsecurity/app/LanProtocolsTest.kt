package com.bsecurity.app

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

class LanProtocolsTest {
    @Test fun echoCorrelatesPayloadAndSequenceRatherThanKernelAssignedId() {
        val request = LanProtocols.echoRequest(513, ByteArray(16) { it.toByte() })
        val reply = request.copyOf().apply { this[0] = 0; this[4] = 10; this[5] = 20 }
        assertTrue(LanProtocols.echoReply(reply, request))
        assertFalse(LanProtocols.echoReply(request, request))
        assertFalse(LanProtocols.echoReply(reply.copyOf(12), request))
        assertFalse(LanProtocols.echoReply(reply.copyOf().apply { this[6] = 9 }, request))
        assertFalse(LanProtocols.echoReply(reply.copyOf().apply { this[10] = 99 }, request))
        var sum = 0
        request.indices.step(2).forEach { sum += ((request[it].toInt() and 255) shl 8) + (request[it + 1].toInt() and 255) }
        while (sum shr 16 != 0) sum = (sum and 65535) + (sum shr 16)
        assertEquals(65535, sum)
    }

    @Test fun httpBasicRequiresAnActual401AndBasicScheme() {
        fun parse(text: String) = LanProtocols.http(80, text.toByteArray())
        val basic = parse("HTTP/1.1 401 Unauthorized\r\nServer: nginx/1.24\r\nWWW-Authenticate: Basic realm=router\r\n\r\n")!!
        assertEquals(1, basic.issues.size); assertEquals("risk", basic.issues.single().level)
        assertTrue(basic.detail.contains("nginx/1.24"))
        assertTrue(parse("HTTP/1.1 200 OK\r\nWWW-Authenticate: Basic realm=test\r\n\r\n")!!.issues.isEmpty())
        assertTrue(parse("HTTP/1.0 401 Nope\r\nWWW-Authenticate: BasicBearer realm=test\r\n\r\n")!!.issues.isEmpty())
        assertTrue(parse("HTTP/1.1 302 Found\r\nLocation: https://example.invalid/\r\n\r\n")!!.issues.isEmpty())
        assertNull(parse("banner WWW-Authenticate: Basic"))
        assertNull(parse("HTTP/1.1 401 Unauthorized\r\nWWW-Authenticate: Basic realm=x\r\n"))
        assertNull(parse("HTTP/1.1 401 Unauthorized\r\nWWW-Authenticate: Basic realm=x\r\ninvalid header\r\n\r\n"))
    }

    @Test fun telnetRequiresProtocolNegotiationNotPortOrLoginText() {
        assertNull(LanProtocols.banner(23, "login:".toByteArray()))
        assertNull(LanProtocols.banner(23, byteArrayOf(255.toByte(), 251.toByte())))
        assertEquals("Telnet", LanProtocols.banner(23, byteArrayOf(255.toByte(), 253.toByte(), 1))!!.protocol)
        assertEquals("SSH", LanProtocols.banner(22, "SSH-2.0-OpenSSH_9.6\r\n".toByteArray())!!.protocol)
        assertNull(LanProtocols.banner(22, "220 FTP".toByteArray()))
    }

    private fun smb(): ByteArray = ByteArray(69).apply {
        byteArrayOf(255.toByte(), 0x53, 0x4d, 0x42, 0x72).copyInto(this)
        this[9] = 0x80.toByte(); this[30] = 42; this[32] = 17; this[35] = 3
    }

    @Test fun smb1RequiresSuccessfulMatchedDialectNegotiation() {
        assertNotNull(LanProtocols.smb1(smb(), 42))
        assertEquals("risk", LanProtocols.smb1(smb(), 42)!!.issues.single().level)
        assertNull(LanProtocols.smb1(smb(), 99))
        assertNull(LanProtocols.smb1(smb().apply { this[5] = 1 }, 42))
        assertNull(LanProtocols.smb1(smb().apply { this[33] = 255.toByte(); this[34] = 255.toByte() }, 42))
        assertNull(LanProtocols.smb1(smb().apply { this[0] = 0xfe.toByte() }, 42))
        assertNull(LanProtocols.smb1(smb().apply { this[9] = 0 }, 42))
        assertNull(LanProtocols.smb1(smb().apply { this[67] = 20 }, 42))
        (0..68).forEach { assertNull(LanProtocols.smb1(smb().copyOf(it), 42)) }
        val request = LanProtocols.smb1Request(42)
        assertEquals(request.size - 4, request[3].toInt() and 255)
        assertTrue(request.toString(Charsets.ISO_8859_1).contains("NT LM 0.12"))
    }

    private fun name(out: DataOutputStream, value: String) {
        value.split('.').forEach { val bytes = it.toByteArray(); out.writeByte(bytes.size); out.write(bytes) }
        out.writeByte(0)
    }

    private fun dnsFixture(): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeShort(0); out.writeShort(0x8400); out.writeShort(0); out.writeShort(3); out.writeInt(0)
            fun record(owner: String, type: Int, data: ByteArray) {
                name(out, owner); out.writeShort(type); out.writeShort(1); out.writeInt(120); out.writeShort(data.size); out.write(data)
            }
            val target = ByteArrayOutputStream(); DataOutputStream(target).use { it.writeShort(0); it.writeShort(0); it.writeShort(7000); name(it, "phone.local") }
            record("Device._airplay._tcp.local", 33, target.toByteArray())
            val text = "model=iPhone14,7".toByteArray()
            record("Device._airplay._tcp.local", 16, byteArrayOf(text.size.toByte()) + text)
            record("phone.local", 1, byteArrayOf(192.toByte(), 168.toByte(), 1, 10))
        }
        return bytes.toByteArray()
    }

    @Test fun mdnsCorrelatesSrvTxtAndAddresses() {
        val parsed = LanProtocols.dns(dnsFixture())
        val mapped = LanProtocols.announcements(parsed)
        assertEquals(setOf(LanScope.ipv4("192.168.1.10")), mapped.keys)
        assertEquals(listOf("model=iPhone14,7"), mapped.values.single().single().text)
        assertEquals("_airplay._tcp.local", mapped.values.single().single().type)
        assertTrue(LanProtocols.announcements(parsed.filter { it.type != 33 }).isEmpty())
        assertTrue(LanProtocols.announcements(parsed.filter { it.type != 1 }).isEmpty())
    }

    @Test fun dnsRejectsTruncationCompressionLoopsAndRequests() {
        val fixture = dnsFixture()
        (0 until fixture.size).forEach { assertTrue("length $it", runCatching { LanProtocols.dns(fixture.copyOf(it)) }.isFailure) }
        assertTrue(runCatching { LanProtocols.dns(fixture.copyOf().apply { this[2] = 0 }) }.isFailure)
        val loop = ByteArray(24).apply { this[2] = 0x80.toByte(); this[7] = 1; this[12] = 0xc0.toByte(); this[13] = 12 }
        assertTrue(runCatching { LanProtocols.dns(loop) }.isFailure)
        val invalidText = fixture.copyOf().apply {
            // A TXT value that exceeds its resource-data boundary must never read into the following A record.
            val start = toString(Charsets.ISO_8859_1).indexOf("model=iPhone14,7")
            this[start - 1] = 255.toByte()
        }
        assertTrue(runCatching { LanProtocols.dns(invalidText) }.isFailure)
    }

    @Test fun netbiosSeparatesWorkgroupFromMachineNamesAndCorrelatesId() {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.writeShort(1234); out.writeShort(0x8400); out.writeShort(0); out.writeShort(1); out.writeInt(0)
            name(out, "NODE"); out.writeShort(0x21); out.writeShort(1); out.writeInt(0); out.writeShort(37)
            out.writeByte(2)
            out.write("DESKTOP".padEnd(15).toByteArray()); out.writeByte(0x20); out.writeShort(0)
            out.write("WORKGROUP".padEnd(15).toByteArray()); out.writeByte(0); out.writeShort(0x8000)
        }
        val packet = bytes.toByteArray()
        val status = LanProtocols.netbios(packet, 1234)
        assertEquals(listOf("DESKTOP"), status.names); assertEquals(listOf("WORKGROUP"), status.groups)
        assertTrue(runCatching { LanProtocols.netbios(packet, 4321) }.isFailure)
        (0 until packet.size).forEach { assertTrue(runCatching { LanProtocols.netbios(packet.copyOf(it), 1234) }.isFailure) }
        assertEquals(50, LanProtocols.netbiosQuery(1234).size)
    }
}
