package com.bsecurity.app

import android.app.Instrumentation
import android.app.NotificationManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

object ProtectionChecks {
    fun run(context: Context, isolated: Context, case: (String, () -> Unit) -> Unit) {
        case("Protección: preferencias persistentes y recuperación de cambios parciales") {
            val store = ProtectionStore(isolated)
            check(!store.monitorRequested && store.managed.baseline == null)
            store.monitorRequested = true
            store.managed = ManagedProtectionState(false, ProtectionBaseline(false, true, 2), "pendiente")
            val restored = ProtectionStore(isolated)
            check(restored.monitorRequested)
            check(restored.managed == ManagedProtectionState(false, ProtectionBaseline(false, true, 2), "pendiente"))
            restored.managed = ManagedProtectionState()
            check(ProtectionStore(isolated).managed.baseline == null)
        }
        case("Protección: entrada y servicio privados, administración protegida por Android") {
            val pm = context.packageManager
            check(!pm.getActivityInfo(ComponentName(context, ProtectionActivity::class.java), 0).exported)
            check(!pm.getServiceInfo(ComponentName(context, ProtectionService::class.java), 0).exported)
            check(!pm.getReceiverInfo(ComponentName(context, ProtectionBootReceiver::class.java), 0).exported)
            val admin = pm.getReceiverInfo(ComponentName(context, ProtectionAdminReceiver::class.java), PackageManager.GET_META_DATA)
            check(admin.permission == "android.permission.BIND_DEVICE_ADMIN")
            check(admin.metaData.containsKey("android.app.device_admin"))
            val provisioning = pm.getActivityInfo(ComponentName(context, ProtectionProvisioningActivity::class.java), 0)
            check(provisioning.permission == "android.permission.BIND_DEVICE_ADMIN")
        }
        case("Protección: el aviso no afirma aplicar reglas pausadas o sin accesibilidad") {
            val policy = PolicyStore(isolated)
            check(ProtectionService.summary(isolated).contains("pausadas"))
            policy.enable(true)
            if (!policy.serviceEnabled()) check(ProtectionService.summary(isolated).contains("no se están aplicando"))
        }
    }

    /** Explicit opt-in, emulator only, and no pre-existing PIN/continuity/policy baseline. */
    fun runEmulator(instrumentation: Instrumentation, managed: Boolean, case: (String, () -> Unit) -> Unit) {
        val context = instrumentation.targetContext
        check(Build.HARDWARE in setOf("ranchu", "goldfish")) { "Estas pruebas solo se ejecutan en un emulador desechable." }
        check(!PolicyStore(context).hasPin && !ProtectionStore(context).monitorRequested &&
            ProtectionStore(context).managed.baseline == null) { "El emulador debe estar sin configuración parental previa." }
        val policies = AndroidProtectionPolicies(context)
        check(policies.deviceOwner == managed) { "El modo Device Owner del emulador no coincide con la prueba." }
        val activity = instrumentation.startActivitySync(Intent(context, ProtectionActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            PolicyStore(context).setPin("918273")
            if (managed) {
                case("Administración real: Android confirma las tres políticas y su restauración") {
                    val dpm = context.getSystemService(android.app.admin.DevicePolicyManager::class.java)
                    val admin = ComponentName(context, ProtectionAdminReceiver::class.java)
                    val originalPackages = if (Build.VERSION.SDK_INT >= 30) dpm.getUserControlDisabledPackages(admin) else emptyList()
                    val otherPackage = instrumentation.context.packageName
                    try {
                        if (Build.VERSION.SDK_INT >= 30) dpm.setUserControlDisabledPackages(admin, (originalPackages + otherPackage).distinct())
                        managedProtection(context).enable()
                        check(managedProtection(context).fullyApplied())
                        check(policies.uninstallBlocked)
                        if (Build.VERSION.SDK_INT >= 30) {
                            check(policies.userControlDisabled == true)
                            check(otherPackage in dpm.getUserControlDisabledPackages(admin)) { "Se modificó la restricción de otro paquete." }
                        }
                        if (Build.VERSION.SDK_INT >= 33) check(policies.notificationGrant == ManagedProtection.GRANTED)
                        managedProtection(context).disable()
                        if (Build.VERSION.SDK_INT >= 30) check(otherPackage in dpm.getUserControlDisabledPackages(admin))
                    } finally {
                        managedProtection(context).disable()
                        if (Build.VERSION.SDK_INT >= 30) dpm.setUserControlDisabledPackages(admin, originalPackages)
                    }
                    check(ProtectionStore(context).managed.baseline == null)
                }
            } else {
                case("Modo común real: rechaza políticas de Device Owner") {
                    check(runCatching { managedProtection(context).enable() }.isFailure)
                    check(ProtectionStore(context).managed.baseline == null)
                }
            }
            case("Continuidad real: servicio visible y detención explícita") {
                if (Build.VERSION.SDK_INT >= 33) instrumentation.uiAutomation.grantRuntimePermission(context.packageName, "android.permission.POST_NOTIFICATIONS")
                instrumentation.runOnMainSync { ProtectionService.start(context).getOrThrow() }
                await { ProtectionService.active }
                val manager = context.getSystemService(NotificationManager::class.java)
                await { manager.activeNotifications.any { it.notification.channelId == ProtectionService.CHANNEL } }
                instrumentation.runOnMainSync { activity.finishAndRemoveTask() }
                Thread.sleep(500)
                check(ProtectionService.active) { "Cerrar la tarea detuvo la continuidad." }
                check(manager.activeNotifications.any { it.notification.channelId == ProtectionService.CHANNEL }) {
                    "La notificación desapareció al cerrar la tarea."
                }
                instrumentation.runOnMainSync { ProtectionService.stop(context) }
                await { !ProtectionService.active }
                check(!ProtectionStore(context).monitorRequested) { "La detención no canceló la reanudación automática." }
            }
        } finally {
            instrumentation.runOnMainSync { ProtectionService.stop(context); activity.finish() }
            if (ProtectionStore(context).managed.baseline != null) managedProtection(context).disable()
            context.deleteSharedPreferences("parental")
            context.deleteSharedPreferences("protection")
        }
    }
    private fun await(condition: () -> Boolean) {
        val end = android.os.SystemClock.elapsedRealtime() + 10_000
        while (!condition()) {
            check(android.os.SystemClock.elapsedRealtime() < end) { "El servicio no alcanzó el estado esperado." }
            Thread.sleep(100)
        }
    }
}
