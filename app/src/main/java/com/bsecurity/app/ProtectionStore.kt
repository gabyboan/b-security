package com.bsecurity.app

import android.content.Context

class ProtectionStore(context: Context) : ManagedProtectionStorage {
    private val prefs = context.getSharedPreferences("protection", Context.MODE_PRIVATE)
    var monitorRequested: Boolean
        get() = prefs.getBoolean("monitor", false)
        set(value) { check(prefs.edit().putBoolean("monitor", value).commit()) }
    var monitorError: String
        get() = prefs.getString("monitorError", "").orEmpty()
        set(value) { prefs.edit().putString("monitorError", value).apply() }

    override var managed: ManagedProtectionState
        get() = ManagedProtectionState(prefs.getBoolean("managedRequested", false),
            if (!prefs.contains("baselineUninstall")) null else ProtectionBaseline(
                prefs.getBoolean("baselineUninstall", false),
                if (prefs.contains("baselineControl")) prefs.getBoolean("baselineControl", false) else null,
                if (prefs.contains("baselineNotifications")) prefs.getInt("baselineNotifications", 0) else null),
            prefs.getString("managedError", "").orEmpty())
        set(value) {
            val edit = prefs.edit().putBoolean("managedRequested", value.requested).putString("managedError", value.error)
                .remove("baselineUninstall").remove("baselineControl").remove("baselineNotifications")
            value.baseline?.let { baseline ->
                edit.putBoolean("baselineUninstall", baseline.uninstall)
                baseline.control?.let { edit.putBoolean("baselineControl", it) }
                baseline.notifications?.let { edit.putInt("baselineNotifications", it) }
            }
            check(edit.commit()) { "No se pudo guardar el estado de protección; no se cambiarán políticas." }
        }
}
