package com.bsecurity.app

import android.content.Context
import java.io.DataInputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Real Android transports, confined to this device. Requires a stable Wi-Fi snapshot. */
object LanTransportChecks {
    private val loopback = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)) as Inet4Address

    fun run(context: Context, case: (String, () -> Unit) -> Unit) {
        case("LAN: cuatro Echo Request reales a loopback") {
            LanScanner(context).use { scanner ->
                val result = scanner.echo(loopback, 4)
                check(result.sent == 4 && result.replies.size == 4) { "Echo no disponible: $result" }
                check(result.replies.all { it >= 0 })
            }
        }
        case("LAN: HTTP fragmentado y Basic con evidencia") {
            fixture({ socket ->
                val head = readHead(socket)
                check(head.startsWith("HEAD / HTTP/1.1\r\n"))
                check(!head.contains("Authorization:", true) && !head.contains("Cookie:", true))
                val out = socket.getOutputStream()
                out.write("HTTP/1.1 401 Unauthorized\r\nServer: Fixture/1\r\n".toByteArray()); out.flush()
                Thread.sleep(25)
                out.write("WWW-Authenticate: Basic realm=test\r\n\r\n".toByteArray()); out.flush()
            }) { port ->
                LanScanner(context).use { scanner ->
                    val evidence = scanner.service(loopback, port, 80)
                    check(evidence?.port == port && evidence.issues.single().level == "risk")
                    check(evidence.detail.contains("Fixture/1"))
                }
            }
        }
        case("LAN: negociación SMB1 positiva correlacionada") {
            smbFixture(false) { port ->
                LanScanner(context).use { scanner ->
                    val evidence = scanner.service(loopback, port, 445)
                    check(evidence?.port == port && evidence.issues.single().level == "risk")
                }
            }
        }
        case("LAN: SMB2 no se informa como SMB1") {
            smbFixture(true) { port ->
                LanScanner(context).use { check(it.service(loopback, port, 445) == null) }
            }
        }
        case("LAN: detener cierra sockets y evita nuevas pruebas") {
            val received = CountDownLatch(1)
            val release = CountDownLatch(1)
            try {
                fixture({ socket -> readHead(socket); received.countDown(); check(release.await(3, TimeUnit.SECONDS)) }) { port ->
                    LanScanner(context).use { scanner ->
                        val worker = Executors.newSingleThreadExecutor()
                        try {
                            val pending = worker.submit<ServiceEvidence?> { scanner.service(loopback, port, 80) }
                            check(received.await(2, TimeUnit.SECONDS))
                            scanner.close()
                            val error = runCatching { pending.get(2, TimeUnit.SECONDS) }.exceptionOrNull()
                            check(error is ExecutionException && pending.isDone)
                            check(runCatching { scanner.echo(loopback, 1) }.isFailure)
                        } finally { release.countDown(); worker.shutdownNow() }
                    }
                }
            } finally { release.countDown() }
        }
    }

    private fun readHead(socket: Socket): String {
        val out = java.io.ByteArrayOutputStream()
        var ending = 0
        while (out.size() < 8192) {
            val value = socket.getInputStream().read()
            check(value >= 0) { "HEAD incompleto" }
            out.write(value); ending = (ending shl 8) or value
            if (ending == 0x0d0a0d0a) return out.toString("US-ASCII")
        }
        error("HEAD excedido")
    }

    private fun smbFixture(smb2: Boolean, action: (Int) -> Unit) = fixture({ socket ->
        val input = DataInputStream(socket.getInputStream())
        val header = ByteArray(4); input.readFully(header)
        val size = ((header[1].toInt() and 255) shl 16) or ((header[2].toInt() and 255) shl 8) or (header[3].toInt() and 255)
        check(header[0] == 0.toByte() && size in 35..1024)
        val request = ByteArray(size); input.readFully(request)
        check(request.toString(Charsets.ISO_8859_1).contains("NT LM 0.12"))
        val response = ByteArray(69).apply {
            byteArrayOf(if (smb2) 0xfe.toByte() else 0xff.toByte(), 0x53, 0x4d, 0x42, 0x72).copyInto(this)
            this[9] = 0x80.toByte(); this[30] = request[30]; this[31] = request[31]
            this[32] = 17; this[35] = 3
        }
        socket.getOutputStream().write(byteArrayOf(0, 0, 0, response.size.toByte()) + response)
    }, action)

    private fun fixture(serve: (Socket) -> Unit, action: (Int) -> Unit) {
        val server = ServerSocket(0, 1, loopback).apply { soTimeout = 3000 }
        val client = AtomicReference<Socket>()
        val worker = Executors.newSingleThreadExecutor()
        try {
            val served = worker.submit {
                server.accept().use { socket ->
                    client.set(socket); socket.soTimeout = 3000; serve(socket)
                }
            }
            action(server.localPort)
            served.get(3, TimeUnit.SECONDS)
        } finally {
            runCatching { client.get()?.close() }; server.close(); worker.shutdownNow()
        }
    }
}
