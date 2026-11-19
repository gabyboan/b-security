package com.bsecurity.app

import android.Manifest
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build

class AndroidProtectionPolicies(private val context: Context) : ProtectionPolicies {
    private val dpm = context.getSystemService(DevicePolicyManager::class.java)
    private val admin = ComponentName(context, ProtectionAdminReceiver::class.java)
    override val deviceOwner get() = dpm.isDeviceOwnerApp(context.packageName)
    override var uninstallBlocked: Boolean
        get() = deviceOwner && dpm.isUninstallBlocked(admin, context.packageName)
        set(value) { dpm.setUninstallBlocked(admin, context.packageName, value) }
    override var userControlDisabled: Boolean?
        get() = if (deviceOwner && Build.VERSION.SDK_INT >= 30)
            context.packageName in dpm.getUserControlDisabledPackages(admin) else null
        set(value) {
            if (Build.VERSION.SDK_INT >= 30 && value != null) {
                val packages = dpm.getUserControlDisabledPackages(admin).toMutableSet()
                if (value) packages.add(context.packageName) else packages.remove(context.packageName)
                dpm.setUserControlDisabledPackages(admin, packages.toList())
            }
        }
    override var notificationGrant: Int?
        get() = if (deviceOwner && Build.VERSION.SDK_INT >= 33)
            dpm.getPermissionGrantState(admin, context.packageName, Manifest.permission.POST_NOTIFICATIONS) else null
        set(value) {
            if (Build.VERSION.SDK_INT >= 33 && value != null) check(dpm.setPermissionGrantState(
                admin, context.packageName, Manifest.permission.POST_NOTIFICATIONS, value)) {
                "Android no permitió fijar el permiso de notificaciones."
            }
        }
}

fun managedProtection(context: Context) = ManagedProtection(AndroidProtectionPolicies(context),
    ProtectionStore(context)) { PolicyStore(context).hasPin }

/** Becoming an administrator never silently enables restrictions or location sharing. */
class ProtectionAdminReceiver : DeviceAdminReceiver() {
    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        // The user configures the PIN and enables policies in the visible protection screen.
        context.startActivity(Intent(context, ProtectionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

class ProtectionBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        runCatching { managedProtection(context).reconcile() }
        if (ProtectionStore(context).monitorRequested) ProtectionService.start(context)
    }
}
