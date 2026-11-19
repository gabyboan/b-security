package com.bsecurity.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors

/** This screen is not exported. Each policy change requires a fresh local PIN verification. */
class ProtectionActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var body: LinearLayout
    private lateinit var scroll: ScrollView
    private var resumed = false
    private var pinDialog: AlertDialog? = null
    private val refresh = Runnable { if (resumed && pinDialog == null) render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        ProtectionService.ensureChannel(this)
    }
    override fun onResume() { super.onResume(); resumed = true; render() }
    override fun onStop() {
        resumed = false
        pinDialog?.dismiss(); pinDialog = null
        handler.removeCallbacksAndMessages(null)
        super.onStop()
    }
    override fun onDestroy() { worker.shutdownNow(); super.onDestroy() }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun label(value: String, size: Float = 15f) = TextView(this).apply {
        text = value; textSize = size; setTextColor(Color.rgb(21, 32, 43)); setPadding(0, dp(7), 0, dp(7))
    }
    private fun section(title: String, value: String) {
        body.addView(label(title, 20f).apply { typeface = Typeface.DEFAULT_BOLD })
        body.addView(label(value))
    }
    private fun button(value: String, action: () -> Unit) {
        body.addView(Button(this).apply { text = value; isAllCaps = false; setOnClickListener { action() } })
    }
    private fun message(value: String) { AlertDialog.Builder(this).setMessage(value).setPositiveButton("Entendido", null).show() }
    private fun confirm(title: String, value: String, action: () -> Unit) {
        AlertDialog.Builder(this).setTitle(title).setMessage(value).setNegativeButton("Cancelar", null)
            .setPositiveButton("Continuar") { _, _ -> action() }.show()
    }
    private fun perform(action: () -> Unit) {
        runCatching(action).onFailure { message(it.message ?: "No se pudo completar el cambio.") }
        render()
    }
    private fun render() {
        val previousY = if (::scroll.isInitialized) scroll.scrollY else 0
        body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(24), dp(20), dp(32))
            setBackgroundColor(Color.rgb(244, 244, 242))
        }
        scroll = ScrollView(this).apply {
            isFillViewport = true; addView(body)
            if (Build.VERSION.SDK_INT >= 35) setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom); insets
            }
        }
        setContentView(scroll)
        button("Volver a B-Security") { finish() }
        section("Protección parental", "Configuración de este teléfono. Los permisos y las políticas se comprueban en Android; habilitar continuidad no activa las reglas locales ni comparte ubicación.")
        val policy = PolicyStore(this)
        val store = ProtectionStore(this)
        val policies = AndroidProtectionPolicies(this)
        val owner = runCatching { policies.deviceOwner }.getOrDefault(false)
        section(if (owner) "Modo administrado · Device Owner" else "Modo común",
            if (owner) "Android reconoce a B-Security como propietario del dispositivo. Las restricciones adicionales se activan con el PIN del responsable."
            else "Android permite revocar permisos, forzar detención y desinstalar. El PIN protege los cambios dentro de B-Security; no bloquea Ajustes del sistema.")
        section("Estado comprobado", listOf(
            "PIN del responsable: ${if (policy.hasPin) "configurado" else "pendiente"}",
            "Reglas locales: ${if (policy.enabled) "activadas" else "pausadas"}",
            "Accesibilidad: ${if (policy.serviceEnabled()) "habilitada" else "pendiente"}",
            "Acceso al uso: ${if (policy.usageGranted()) "habilitado" else "pendiente"}",
            "Notificaciones de protección: ${if (ProtectionService.notificationsVisible(this)) "habilitadas" else "bloqueadas"}",
            "Optimización de batería: ${if (getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)) "excluida por Android" else "activa; revisar ajustes"}"
        ).joinToString("\n"))
        if (!policy.hasPin) button("Crear PIN del responsable") { withPin { render() } }
        section("Continuidad en segundo plano", when {
            ProtectionService.active -> "Servicio visible en ejecución. ${ProtectionService.summary(this)}"
            store.monitorRequested -> "Continuidad solicitada, pero el servicio no está en ejecución. Tocá Reanudar para iniciarlo."
            else -> "Desactivada. Mantiene el proceso del control parental en primer plano y avisa en la notificación si faltan permisos."
        } + "\nNo evita todos los cierres del sistema. En modo común, Android conserva la opción de detener la app.")
        if (store.monitorError.isNotBlank()) body.addView(label(store.monitorError))
        button(if (ProtectionService.active) "Detener continuidad con PIN" else "${if (store.monitorRequested) "Reanudar" else "Activar"} continuidad con PIN") {
            withPin {
                perform {
                    if (ProtectionService.active) ProtectionService.stop(this)
                    else ProtectionService.start(this).getOrThrow()
                }
                handler.postDelayed(refresh, 800)
            }
        }
        if (!ProtectionService.active && store.monitorRequested) button("Cancelar reanudación automática con PIN") {
            withPin { perform { ProtectionService.stop(this) } }
        }
        button("Configurar notificaciones") {
            withPin {
                if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 411)
                else openSettings(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName).putExtra(Settings.EXTRA_CHANNEL_ID, ProtectionService.CHANNEL))
            }
        }
        button("Revisar optimización de batería") { withPin { openSettings(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } }
        button("Abrir ficha de B-Security") { withPin { openSettings(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) } }
        if (Build.MANUFACTURER.lowercase() in setOf("xiaomi", "redmi", "poco")) section("Configuración en Xiaomi / Redmi",
            "Revisá Inicio automático y Batería → Sin restricciones para B-Security. En Seguridad → Acelerar → Bloquear aplicaciones, activá el candado para excluirla de Limpiar todo. Los nombres cambian según MIUI/HyperOS. Android no permite confirmar desde esta app si esos ajustes del fabricante están activos.")
        section("Notificación visible", "La notificación es silenciosa y abre esta pantalla. En modo común, el usuario puede bloquearla desde Android. Con protección administrada se fija el permiso de notificaciones en Android 13 o superior. El comportamiento visual final depende de Android y del fabricante.")
        if (owner) {
            val status = runCatching {
                "Bloqueo de desinstalación solicitado: ${if (policies.uninstallBlocked) "confirmado" else "no aplicado"}\n" +
                    "Detención y borrado de datos: ${when (policies.userControlDisabled) { true -> "restringidos"; false -> "sin restricción adicional"; null -> "requiere Android 11 o superior" }}\n" +
                    "Permiso de notificaciones fijado: ${when (policies.notificationGrant) { ManagedProtection.GRANTED -> "sí"; null -> "requiere Android 13 o superior"; else -> "no" }}"
            }.getOrElse { "No se pudieron consultar todas las políticas: ${it.javaClass.simpleName}." }
            section("Políticas administradas", status)
            val state = store.managed
            if (state.error.isNotBlank()) body.addView(label("Cambio pendiente o parcial: ${state.error}"))
            button(if (state.requested) "Comprobar y reaplicar protección con PIN" else "Activar protección administrada con PIN") {
                confirm("Activar protección administrada", "Se restringirán la desinstalación, la detención forzada y el borrado de datos de B-Security según la versión de Android. En Android 13+ se fijará su permiso de notificaciones. No se restringen otras aplicaciones. Podés retirar estas políticas con el PIN; Device Owner permanece hasta salir de la administración del equipo.") {
                    withPin {
                        perform {
                            val channel = getSystemService(android.app.NotificationManager::class.java)
                                .getNotificationChannel(ProtectionService.CHANNEL)
                            check(channel?.importance != android.app.NotificationManager.IMPORTANCE_NONE) {
                                "Primero habilitá el canal Protección parental en Configurar notificaciones."
                            }
                            managedProtection(this).enable()
                        }
                    }
                }
            }
            if (state.baseline != null) button("Retirar políticas adicionales con PIN") {
                confirm("Retirar políticas adicionales", "Se restaurarán las políticas anteriores de B-Security. Esto no elimina Device Owner: Android seguirá considerando el equipo administrado y puede seguir impidiendo desinstalar su administrador. La continuidad y las reglas locales se configuran por separado.") {
                    withPin {
                        perform { managedProtection(this).disable() }
                    }
                }
            }
        }
        button("Cómo preparar o retirar el modo administrado") {
            message("Device Owner se configura al preparar un equipo dedicado, normalmente después de restablecerlo de fábrica. No se obtiene activando el antiguo permiso de administrador ni Accesibilidad. Esta pantalla no convierte ni restablece el teléfono.\n\nRetirar políticas con PIN no elimina la propiedad del dispositivo. Para salir completamente de una instalación administrada, respaldá tus datos y seguí el procedimiento de restablecimiento de Android. B-Security no bloquea ese ajuste ni ofrece un botón de borrado.")
        }
        val newScroll = scroll
        newScroll.post { newScroll.scrollTo(0, previousY) }
    }
    private fun openSettings(intent: Intent) {
        runCatching { startActivity(intent) }.onFailure { message("No se encontró ese ajuste. Abrí Ajustes → Aplicaciones → B-Security.") }
    }
    private fun withPin(action: () -> Unit) {
        val policy = PolicyStore(this)
        val first = !policy.hasPin
        val fields = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), 0) }
        fun field(hintText: String) = EditText(this).apply {
            hint = hintText; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            fields.addView(this)
        }
        val one = field(if (first) "Nuevo PIN: 6 a 12 dígitos" else "PIN del responsable")
        val two = if (first) field("Repetir PIN") else null
        val dialog = AlertDialog.Builder(this).setTitle(if (first) "Crear PIN del responsable" else "Autorizar cambio")
            .setView(fields).setNegativeButton("Cancelar", null).setPositiveButton("Continuar", null).create()
        pinDialog = dialog
        dialog.setOnDismissListener { if (pinDialog === dialog) pinDialog = null }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value = one.text.toString()
                if (!value.matches(Regex("[0-9]{6,12}")) || (first && two?.text.toString() != value)) {
                    one.error = if (first) "Usá entre 6 y 12 dígitos y repetilos igual." else "Usá entre 6 y 12 dígitos."
                    return@setOnClickListener
                }
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                worker.execute {
                    val valid = runCatching { if (first) { policy.setPin(value); true } else policy.verifyPin(value) }.getOrDefault(false)
                    runOnUiThread {
                        if (isDestroyed || !resumed || !dialog.isShowing) return@runOnUiThread
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                        if (valid) { dialog.dismiss(); action() }
                        else one.error = "PIN incorrecto. Tras cinco intentos, esperá 30 segundos."
                    }
                }
            }
        }
        dialog.show()
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 411) {
            render()
            if (!ProtectionService.notificationsVisible(this)) message("Las notificaciones siguen bloqueadas. Revisalas en Ajustes → Aplicaciones → B-Security → Notificaciones.")
        }
    }
}
