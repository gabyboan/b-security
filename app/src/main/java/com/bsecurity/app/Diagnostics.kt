package com.bsecurity.app

import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class Finding(val title: String, val detail: String, val level: String = "info", val deviceKind: LanDeviceKind? = null)

object Diagnostics {
    fun device(context: Context, progress: (Int, Int, String) -> Unit = { _, _, _ -> }): List<Finding> {
        val result = mutableListOf<Finding>()
        val total = 8
        progress(0, total, "Leyendo indicadores de Android…")
        fun record(finding: Finding) {
            result += finding
            progress(result.size, total, finding.title)
        }
        record(Finding("Dispositivo", "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} · API ${Build.VERSION.SDK_INT}"))
        val patch = Build.VERSION.SECURITY_PATCH
        val age = runCatching { ChronoUnit.DAYS.between(LocalDate.parse(patch), LocalDate.now()) }.getOrNull()
        record(Finding("Parche de seguridad", if (age != null) "$patch · $age días desde el parche" else "No disponible",
            if (age == null || age < 0) "unknown" else if (age > 120) "risk" else "ok"))
        val secure = (context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isDeviceSecure
        record(Finding("Bloqueo de pantalla", if (secure) "Android informa PIN, patrón o contraseña configurados." else "Android no informa un bloqueo seguro.", if (secure) "ok" else "risk"))
        val encryption = (context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager).storageEncryptionStatus
        record(Finding("Cifrado del almacenamiento", when (encryption) {
            DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE, DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_PER_USER -> "Android informa cifrado activo."
            DevicePolicyManager.ENCRYPTION_STATUS_ACTIVE_DEFAULT_KEY -> "Cifrado activo con clave predeterminada: configurá un bloqueo seguro."
            DevicePolicyManager.ENCRYPTION_STATUS_INACTIVE -> "Android informa cifrado inactivo."
            else -> "No verificable con esta API."
        }, when(encryption) { 3, 5 -> "ok"; 1, 4 -> "risk"; else -> "unknown" }))
        val adb = Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1
        record(Finding("Depuración del dispositivo", if (adb) "Depuración ADB habilitada. Desactivala al terminar las pruebas." else "ADB USB no figura habilitado; no verifica todos los canales inalámbricos.", if (adb) "risk" else "info"))
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val vpn = cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
        record(Finding("VPN", if (vpn) "Android detecta una VPN activa. No prueba qué tráfico cubre ni la identidad del proveedor." else "Android no detecta una VPN activa.", if (vpn) "ok" else "info"))
        val privateDns = runCatching { Settings.Global.getString(context.contentResolver, "private_dns_mode") }.getOrNull()
        record(Finding("DNS privado", when(privateDns) { "hostname" -> "Proveedor de DNS privado configurado."; "opportunistic" -> "Modo automático: depende de la red."; "off" -> "Desactivado."; else -> "Android no expone este dato." }, if (privateDns == "off") "risk" else "info"))
        record(Finding("Alcance del diagnóstico", "Estos indicadores no certifican ausencia de malware ni vulnerabilidades. No se ejecutan exploits, no se leen contraseñas y no se analiza el contenido de otras apps.", "unknown"))
        return result
    }
    @Suppress("DEPRECATION")
    fun wifi(context: Context): List<Finding> {
        val manager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val info = manager.connectionInfo
        val result = mutableListOf<Finding>()
        result += Finding("Wi-Fi", if (manager.isWifiEnabled) "Radio habilitada" else "Wi-Fi apagado")
        val ssid = info.ssid?.trim('"')?.takeUnless { it == "<unknown ssid>" }.orEmpty()
        result += Finding("Red actual", ssid.ifEmpty { "SSID no disponible. Otorgá ubicación y activá el servicio de ubicación." })
        if (info.networkId != -1) {
            result += Finding("Señal y enlace", "${info.rssi} dBm · ${info.linkSpeed} Mbps\n${WifiRadio.describe(info.frequency)}")
            if (Build.VERSION.SDK_INT >= 31) {
                result += WifiSecurity.finding(info.currentSecurityType)
            }
        }
        val wifi = wifiNetwork(context)
        val gateway = wifi?.let { cm(context).getLinkProperties(it)?.routes?.firstOrNull { r -> r.isDefaultRoute && r.gateway is Inet4Address }?.gateway }
        result += Finding("Router / puerta de enlace", gateway?.hostAddress ?: "No disponible en una red Wi-Fi conectada.")
        return result
    }
    private fun cm(c: Context) = c.getSystemService(ConnectivityManager::class.java)
    private fun wifiNetwork(c: Context): Network? = cm(c).allNetworks.firstOrNull {
        val capabilities = cm(c).getNetworkCapabilities(it)
        capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true &&
            !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
    }
    fun wifiGateway(context: Context): Inet4Address? {
        val network = wifiNetwork(context) ?: return null
        val gateway = cm(context).getLinkProperties(network)?.routes?.firstOrNull {
            it.isDefaultRoute && it.gateway is Inet4Address
        }?.gateway as? Inet4Address ?: return null
        return gateway.takeIf(RouterAssessment::permitsGateway)
    }
    fun router(context: Context, profile: RouterProfile = RouterProfile.GENERIC, progress: (Int, Int, String) -> Unit = { _, _, _ -> }): List<Finding> {
        val network = wifiNetwork(context) ?: return listOf(Finding("Router", "Conectate a una red Wi-Fi.", "unknown"))
        val gateway = cm(context).getLinkProperties(network)?.routes?.firstOrNull {
            it.isDefaultRoute && it.gateway is Inet4Address
        }?.gateway ?: return listOf(Finding("Router", "Sin puerta de enlace IPv4.", "unknown"))
        require(RouterAssessment.permitsGateway(gateway)) { "Solo se comprueban puertas de enlace IPv4 privadas de la Wi-Fi actual." }
        val ports = RouterAssessment.ports
        val probes = mutableListOf<RouterProbe>()
        progress(0, ports.size, "Preparando conexiones TCP")
        ports.entries.forEachIndexed { index, (port, label) ->
            if(Thread.currentThread().isInterrupted) throw InterruptedException("Prueba cancelada")
            check(wifiNetwork(context) == network) { "La Wi-Fi cambió: prueba cancelada." }
            val currentGateway = cm(context).getLinkProperties(network)?.routes?.firstOrNull {
                it.isDefaultRoute && it.gateway is Inet4Address
            }?.gateway
            check(currentGateway == gateway) { "La puerta de enlace cambió: prueba cancelada." }
            val outcome = try {
                Socket().use { socket -> network.bindSocket(socket); socket.connect(InetSocketAddress(gateway, port), 650) }
                RouterProbeOutcome.ACCEPTED
            } catch (error: Exception) { RouterAssessment.failureOutcome(error) }
            probes += RouterProbe(port, outcome)
            progress(index + 1, ports.size, "$port ($label)")
        }
        check(wifiNetwork(context) == network) { "La Wi-Fi cambió: prueba cancelada." }
        check(cm(context).getLinkProperties(network)?.routes?.any { it.isDefaultRoute && it.gateway == gateway } == true) {
            "La puerta de enlace cambió: prueba cancelada."
        }
        return RouterAssessment.findings(gateway, probes, profile)
    }
}
