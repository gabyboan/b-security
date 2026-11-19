package com.bsecurity.app

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import java.util.Locale

class Sensors(private val context: Context, private val checking: (String, Boolean) -> Unit = { _, _ -> }, private val update: (String, String) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private val location = context.getSystemService(LocationManager::class.java)
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    private val bluetooth = context.getSystemService(BluetoothManager::class.java)
    private var receiver: BroadcastReceiver? = null
    private var bleCallback: ScanCallback? = null
    private var lastLocation: Location? = null
    private var satelliteInfo = "Esperando satélites GNSS…"
    var gpsRunning = false
        private set
    var sharedLocation: Location? = null
        private set
    private val locationListener = LocationListener { value ->
        lastLocation = value; sharedLocation = value; gpsText()
    }
    private val gnss = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            val count = status.satelliteCount
            var used = 0; var sum = 0f
            for (i in 0 until count) { if (status.usedInFix(i)) used++; sum += status.getCn0DbHz(i) }
            satelliteInfo = "$count satélites visibles · $used usados\nC/N₀ promedio: " +
                if(count > 0) String.format(Locale.US, "%.1f dB-Hz", sum / count) else "sin datos"
            gpsText()
        }
    }
    private val gpsTick = object : Runnable {
        override fun run() { if (gpsRunning) { gpsText(); handler.postDelayed(this, 1000) } }
    }
    private fun gpsText() {
        val value = lastLocation
        val detail = if (value == null) "Sin posición nueva. Buscá un lugar con cielo abierto." else {
            val age = ((SystemClock.elapsedRealtimeNanos() - value.elapsedRealtimeNanos) / 1_000_000_000).coerceAtLeast(0)
            val mock = if (Build.VERSION.SDK_INT >= 31) value.isMock else value.isFromMockProvider
            String.format(Locale.US, "%.6f, %.6f\nPrecisión horizontal: ±%.1f m\nAntigüedad: %d s · Proveedor: %s\n", value.latitude, value.longitude, value.accuracy, age, value.provider) +
                (if (value.hasAltitude()) "Altitud: ${value.altitude.toInt()} m\n" else "") +
                (if (value.hasSpeed()) "Velocidad: ${"%.1f".format(value.speed * 3.6)} km/h\n" else "") +
                if (mock) "Ubicación marcada como simulada por Android." else ""
        }
        update("gps", "$satelliteInfo\n\n$detail\n\nSolo se mide mientras esta pantalla está en primer plano. La posición no se comparte automáticamente.")
    }
    fun startGps() {
        check(context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) { "Se necesita ubicación precisa para GNSS." }
        check(location.isProviderEnabled(LocationManager.GPS_PROVIDER)) { "Activá Ubicación/GPS en Android." }
        if (gpsRunning) return
        lastLocation = null; satelliteInfo = "Buscando señal GNSS…"; gpsRunning = true
        location.registerGnssStatusCallback(gnss, handler)
        location.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0f, locationListener, Looper.getMainLooper())
        handler.post(gpsTick)
    }
    fun stopGps() {
        if (!gpsRunning) {
            return
        }
        location.removeUpdates(locationListener); location.unregisterGnssStatusCallback(gnss)
        handler.removeCallbacks(gpsTick); gpsRunning = false
        update("gps", "Medición detenida. No se recopila ubicación en segundo plano.")
    }
    @Suppress("DEPRECATION")
    fun scanWifi() {
        if(receiver != null) return
        check(context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) { "Se necesita ubicación precisa para ver redes Wi-Fi." }
        val locationOn = if (Build.VERSION.SDK_INT >= 28) location.isLocationEnabled else location.isProviderEnabled(LocationManager.GPS_PROVIDER) || location.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        check(locationOn) { "Activá Ubicación en Android para escanear Wi-Fi." }
        clearWifi()
        receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                showWifi(intent?.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false) == true)
                clearWifi()
            }
        }
        val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else context.registerReceiver(receiver, filter)
        if (wifi.startScan()) {
            checking("wifi",true)
            update("wifi", "Escaneando redes cercanas…")
            handler.postDelayed({ if(receiver != null) { showWifi(false); clearWifi() } }, 12_000)
        } else { showWifi(false); clearWifi() }
    }
    private fun showWifi(fresh: Boolean) {
        val rows = try { wifi.scanResults.map { result ->
            val age = ((SystemClock.elapsedRealtime() - result.timestamp / 1000) / 1000).coerceAtLeast(0)
            WifiAccessPoint(result.SSID.ifBlank { "(SSID oculto)" }, result.BSSID.orEmpty(), result.level,
                result.frequency, result.channelWidth, result.capabilities, age)
        }.let(WifiRadio::strongestFirst).take(30) }
        catch (e: SecurityException) { update("wifi", "Ubicación precisa revocada. Volvé a conceder el permiso."); return }
        fun label(point: WifiAccessPoint) = point.ssid.filter { !it.isISOControl() }.take(32)
        fun security(point: WifiAccessPoint): String = when {
            point.capabilities.contains("WEP") -> "RIESGO · WEP"
            !point.capabilities.contains("PSK") && !point.capabilities.contains("EAP") &&
                !point.capabilities.contains("SAE") && !point.capabilities.contains("OWE") &&
                !point.capabilities.contains("WAPI") -> "REVISAR · podría ser abierta"
            else -> "Cifrado anunciado (no valida configuración)"
        }
        val grades = listOf(
            "Muy buena" to "≥ -50 dBm",
            "Buena" to "-51 a -67 dBm",
            "Débil" to "-68 a -75 dBm",
            "Muy débil" to "≤ -76 dBm",
        ).mapNotNull { (grade, range) ->
            val networks = rows.filter { WifiRadio.signalQuality(it.level) == grade }
            if (networks.isEmpty()) null else "$grade ($range) · ${networks.size}\n" + networks.joinToString("\n") {
                "${label(it)} · ${it.level} dBm · ${WifiRadio.describe(it.frequency)}"
            }
        }.joinToString("\n\n")
        val channels = WifiRadio.byChannel(rows).joinToString("\n\n") { group ->
            "${group.band} · canal ${group.channel ?: "desconocido"} · ${group.accessPoints.size} AP · " +
                "mejor señal ${group.accessPoints.maxOf { it.level }} dBm\n" +
                group.accessPoints.joinToString(" · ") { "${label(it)} ${it.level} dBm" }
            }
        val details = rows.joinToString("\n\n") { point ->
            "${label(point)} · ${point.level} dBm (${WifiRadio.signalQuality(point.level)})\n" +
                "${point.bssid} · ${WifiRadio.describe(point.frequency)}\n" +
                "Ancho anunciado: ${WifiRadio.width(point.width)} · visto hace ${point.ageSeconds} s\n" +
                "${security(point)} · ${point.capabilities}"
        }
        update("wifi", (if (fresh) "Resultados actualizados por Android." else "Resultados disponibles en caché; Android puede limitar escaneos.") +
            "\n\nSEÑAL · de mayor a menor\n${grades.ifEmpty { "Sin datos." }}" +
            "\n\nCANALES · por banda y canal\n${channels.ifEmpty { "Sin datos." }}\nEl conteo no mide tráfico ni interferencia; los canales anchos pueden superponerse." +
            "\n\nPUNTOS DE ACCESO · hasta 30\n${details.ifEmpty { "No hay redes visibles. Revisá permisos, Wi-Fi y ubicación." }}" +
            "\n\nNo se intenta conectar a estas redes ni probar contraseñas.")
    }
    private fun clearWifi() { receiver?.let { runCatching { context.unregisterReceiver(it) } }; receiver = null; checking("wifi",false) }
    fun scanBluetooth() {
        if(bleCallback != null) return
        if (Build.VERSION.SDK_INT >= 31) {
            if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED ||
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                error("Se necesitan permisos de dispositivos cercanos para Bluetooth.")
            }
        }
        val adapter = bluetooth.adapter ?: error("Este dispositivo no expone Bluetooth.")
        check(adapter.isEnabled) { "Activá Bluetooth en Android." }
        stopBluetooth()
        val devices = linkedMapOf<String, String>()
        val paired = adapter.bondedDevices.take(20).joinToString("\n") { "${it.name ?: "Sin nombre"} · vinculado" }
        update("bluetooth", "Vinculados:\n${paired.ifEmpty { "Ninguno informado" }}\n\nBuscando anuncios BLE durante 10 segundos…")
        val callback = object : ScanCallback() {
            override fun onScanResult(type: Int, result: ScanResult) {
                try {
                val name = result.scanRecord?.deviceName ?: runCatching { result.device.name }.getOrNull() ?: "Dispositivo sin nombre"
                val bonded = result.device.bondState == android.bluetooth.BluetoothDevice.BOND_BONDED
                devices[result.device.address] = "$name · ${result.rssi} dBm · ${if(bonded) "vinculado" else "sin vincular"}"
                render()
                } catch (e: SecurityException) { update("bluetooth", "Permiso de Bluetooth revocado."); stopBluetooth() }
            }
            override fun onScanFailed(errorCode: Int) { update("bluetooth", "Android rechazó el escaneo BLE (código $errorCode)."); stopBluetooth() }
            fun render() {
                update("bluetooth", "Vinculados:\n${paired.ifEmpty { "Ninguno informado" }}\n\nBLE cercano:\n" +
                    devices.values.take(30).joinToString("\n").ifEmpty { "Sin anuncios recibidos." } +
                    "\n\nLa visibilidad no demuestra una vulnerabilidad. No se analizan CVE ni tráfico; algunos beacons pueden estar filtrados por Android.")
            }
        }
        bleCallback = callback
        try {
            val scanner=adapter.bluetoothLeScanner ?: error("Escáner BLE no disponible.")
            scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
            checking("bluetooth",true)
        } catch(e: Exception) { stopBluetooth(); throw e }
        handler.postDelayed({ if (bleCallback === callback) { stopBluetooth(); callback.render() } }, 10_000)
    }
    private fun stopBluetooth() {
        bleCallback?.let { callback ->
            try { bluetooth.adapter?.bluetoothLeScanner?.stopScan(callback) }
            catch (_: SecurityException) { /* Android revocó el permiso durante el escaneo. */ }
        }
        bleCallback = null
        checking("bluetooth",false)
    }
    fun stop() { stopGps(); stopBluetooth(); clearWifi(); handler.removeCallbacksAndMessages(null) }
}
