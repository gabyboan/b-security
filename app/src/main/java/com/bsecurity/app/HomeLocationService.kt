package com.bsecurity.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** User-started, visible sharing. Never restarts itself after reboot or process death. */
class HomeLocationService : Service(), LocationListener {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val ending = AtomicBoolean(false)
    private val sending = AtomicBoolean(false)
    private lateinit var manager: LocationManager
    private var client: HomeClient? = null
    private var latest: Location? = null
    private val tick = object : Runnable {
        override fun run() {
            if (ending.get()) return
            send()
            handler.postDelayed(this, 5_000)
        }
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        manager = getSystemService(LocationManager::class.java)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Ubicación del hogar", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { finishSharing(); return START_NOT_STICKY }
        if (active || ending.get() || intent?.action != START) { if (!active) stopSelf(); return START_NOT_STICKY }
        try {
            val notices = getSystemService(NotificationManager::class.java)
            check(notices.areNotificationsEnabled()) { "Habilitá las notificaciones para compartir ubicación." }
            check(notices.getNotificationChannel(CHANNEL).importance != NotificationManager.IMPORTANCE_NONE) { "Habilitá las notificaciones de ubicación del hogar." }
            check(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) { "Falta el permiso de ubicación." }
            check(manager.isProviderEnabled(LocationManager.GPS_PROVIDER) || manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) { "Activá la ubicación del teléfono." }
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTICE, notification("Conectando con tu hogar…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            else startForeground(NOTICE, notification("Conectando con tu hogar…"))
            active = true; status = "Iniciando ubicación compartida…"
            worker.execute {
                if (ending.get()) return@execute
                // Auth can be rotating a token on another worker. Never wait for
                // its lock (or decrypt Keystore state) on Android's main thread.
                val result = runCatching {
                    client = HomeClient(this)
                    client!!.request("start", JSONObject().put("consent", true))
                }
                handler.post {
                    if (ending.get()) return@post
                    result.onSuccess { beginUpdates() }.onFailure { finishSharing(it.message ?: "No se pudo iniciar.") }
                }
            }
        } catch (error: Exception) { finishSharing(error.message ?: "No se pudo iniciar la ubicación.") }
        return START_NOT_STICKY
    }
    @Suppress("MissingPermission")
    private fun beginUpdates() {
        try {
            for (provider in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (manager.isProviderEnabled(provider)) {
                    manager.requestLocationUpdates(provider, 3_000L, 0f, this, Looper.getMainLooper())
                    manager.getLastKnownLocation(provider)?.let { consider(it) }
                }
            }
            status = "Compartiendo · esperando una posición reciente"
            updateNotice(status)
            handler.post(tick)
        } catch (error: Exception) { finishSharing(error.message ?: "Se perdió el permiso de ubicación.") }
    }
    override fun onLocationChanged(location: Location) { consider(location) }
    private fun consider(location: Location) {
        if (!location.hasAccuracy()) return
        if (latest == null || location.elapsedRealtimeNanos > latest!!.elapsedRealtimeNanos) latest = Location(location)
    }
    private fun send() {
        val notices = getSystemService(NotificationManager::class.java)
        if (!notices.areNotificationsEnabled() || notices.getNotificationChannel(CHANNEL).importance == NotificationManager.IMPORTANCE_NONE) {
            finishSharing("Se desactivaron las notificaciones. Volvé a habilitarlas para compartir."); return
        }
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            finishSharing("Se retiró el permiso de ubicación."); return
        }
        if (ending.get() || !sending.compareAndSet(false, true)) return
        val fix = latest?.let { Location(it) }
        worker.execute {
            try {
                if (ending.get()) return@execute
                val point = fix?.let { HomeLocationPoint(it.latitude, it.longitude, it.accuracy.toDouble(),
                    (SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos) / 1_000_000_000) }
                if (point?.valid == true) client!!.request("location", JSONObject().put("lat", point.lat).put("lon", point.lon)
                    .put("accuracy", point.accuracy).put("age_seconds", point.ageSeconds)
                    .put("mock", if (Build.VERSION.SDK_INT >= 31) fix.isMock else fix.isFromMockProvider))
                else client!!.request("ping")
                handler.post {
                    if (!ending.get()) {
                        status = if (point?.valid == true) "Ubicación enviada · ±${point.accuracy.toInt()} m" else "Compartiendo · esperando una posición reciente"
                        updateNotice(status)
                    }
                }
            } catch (error: Exception) {
                handler.post {
                    if (!ending.get()) {
                        status = "Sin actualizar: ${error.message ?: "revisá Internet"}"
                        updateNotice("Sin actualizar · abrí B-Security para revisar")
                        if (error is HomeHttpException && (error.status == 401 || error.reason in setOf("BSEC_AUTH", "BSEC_HOME", "BSEC_CONSENT", "BSEC_DEVICE"))) finishSharing(status)
                        else if (!SupabaseAuth(this@HomeLocationService).hasStoredSession) finishSharing("La sesión ya no está disponible. Volvé a ingresar con Google.")
                    }
                }
            } finally { sending.set(false) }
        }
    }
    private fun notification(detail: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, HomeLocationService::class.java).setAction(STOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_shield).setContentTitle("B-Security · compartiendo ubicación")
            .setContentText(detail).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "Detener", stop).build()).build()
    }
    private fun updateNotice(detail: String) { getSystemService(NotificationManager::class.java).notify(NOTICE, notification(detail)) }
    private fun finishSharing(reason: String? = null) {
        if (!ending.compareAndSet(false, true)) return
        active = false
        status = reason ?: "Deteniendo ubicación…"
        handler.removeCallbacksAndMessages(null)
        runCatching { manager.removeUpdates(this) }
        worker.execute {
            val stopped = runCatching { client?.request("stop") }
            handler.post {
                status = listOfNotNull(reason, if (stopped.isSuccess) "Ubicación detenida." else "Detenida en este teléfono. Sin Internet, el último punto vence en hasta 90 segundos.").joinToString("\n")
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            }
        }
    }
    override fun onDestroy() {
        active = false
        handler.removeCallbacksAndMessages(null)
        runCatching { manager.removeUpdates(this) }
        if (ending.compareAndSet(false, true)) {
            status = "Ubicación detenida. Abrí la app para volver a compartir."
            worker.execute { runCatching { client?.request("stop") } }
        }
        worker.shutdown()
        super.onDestroy()
    }
    @Deprecated("Legacy location callback") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    companion object {
        private const val CHANNEL = "home_location"
        private const val NOTICE = 410
        const val START = "com.bsecurity.app.home.START"
        const val STOP = "com.bsecurity.app.home.STOP"
        @Volatile var active = false; private set
        @Volatile var status = "Ubicación detenida."; private set
        fun stop(context: Context) { if (active) context.startService(Intent(context, HomeLocationService::class.java).setAction(STOP)) }
    }
}
