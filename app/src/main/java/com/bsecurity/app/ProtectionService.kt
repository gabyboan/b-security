package com.bsecurity.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper

/** Visible, opt-in continuity for local parental control; no GPS, network or screen collection. */
class ProtectionService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var previousText = ""
    private val tick = object : Runnable {
        override fun run() {
            if (!ProtectionStore(this@ProtectionService).monitorRequested) { stopSelf(); return }
            val text = summary(this@ProtectionService)
            if (text != previousText) {
                getSystemService(NotificationManager::class.java).notify(NOTICE, notification(text))
                previousText = text
            }
            handler.postDelayed(this, 30_000)
        }
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val store = ProtectionStore(this)
        if (!store.monitorRequested || !PolicyStore(this).hasPin) { stopSelf(); return START_NOT_STICKY }
        try {
            ensureChannel(this)
            val text = summary(this)
            if (Build.VERSION.SDK_INT >= 34) startForeground(NOTICE, notification(text), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(NOTICE, notification(text))
            active = true
            previousText = text
            store.monitorError = ""
            handler.removeCallbacks(tick)
            handler.postDelayed(tick, 30_000)
        } catch (error: Exception) {
            store.monitorError = "No se pudo iniciar la continuidad parental: ${error.javaClass.simpleName}. Abrí Protección para reintentar."
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }
    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 411, Intent(this, ProtectionActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_shield)
            .setContentTitle("B-Security · protección parental").setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text)).setContentIntent(open)
            .setOngoing(true).setOnlyAlertOnce(true).setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PRIVATE).setCategory(Notification.CATEGORY_SERVICE)
            .apply { if (Build.VERSION.SDK_INT >= 31) setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE) }
            .build()
    }
    override fun onDestroy() {
        active = false
        handler.removeCallbacksAndMessages(null)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }
    companion object {
        const val CHANNEL = "parental_protection"
        private const val NOTICE = 411
        @Volatile var active = false; private set
        fun ensureChannel(context: Context) {
            val channel = NotificationChannel(CHANNEL, "Protección parental", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Estado del control parental y avisos cuando faltan permisos."
                setSound(null, null)
                enableVibration(false)
                if (Build.VERSION.SDK_INT >= 33) setBlockable(false)
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        fun notificationsVisible(context: Context): Boolean {
            val manager = context.getSystemService(NotificationManager::class.java)
            return manager.areNotificationsEnabled() && manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
        }
        fun summary(context: Context): String {
            val policy = PolicyStore(context)
            return when {
                !policy.enabled -> "Reglas locales pausadas. Abrí la app para configurarlas."
                !policy.serviceEnabled() -> "Atención: falta habilitar Accesibilidad; las reglas no se están aplicando."
                !policy.usageGranted() -> "Falta acceso al uso; los límites diarios no se pueden comprobar."
                else -> "Control local habilitado. Tocá para revisar permisos y protección."
            }
        }
        fun start(context: Context): Result<Unit> = runCatching {
            check(PolicyStore(context).hasPin) { "Primero configurá el PIN del responsable." }
            ensureChannel(context)
            check(notificationsVisible(context)) { "Primero habilitá las notificaciones de Protección parental." }
            val store = ProtectionStore(context)
            store.monitorRequested = true
            try { context.startForegroundService(Intent(context, ProtectionService::class.java)) }
            catch (error: Exception) {
                store.monitorError = "Android impidió iniciar la continuidad parental. Abrí Protección para reintentar."
                throw error
            }
        }
        @android.annotation.SuppressLint("ImplicitSamInstance") // Android matches services by Intent component, not object identity.
        fun stop(context: Context) {
            ProtectionStore(context).apply { monitorRequested = false; monitorError = "" }
            context.stopService(Intent(context, ProtectionService::class.java))
        }
    }
}
