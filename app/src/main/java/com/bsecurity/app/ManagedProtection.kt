package com.bsecurity.app

/** Only policies concerning this package. Other packages and device restrictions stay untouched. */
interface ProtectionPolicies {
    val deviceOwner: Boolean
    var uninstallBlocked: Boolean
    var userControlDisabled: Boolean?
    var notificationGrant: Int?
}

data class ProtectionBaseline(val uninstall: Boolean, val control: Boolean?, val notifications: Int?)
data class ManagedProtectionState(
    val requested: Boolean = false,
    val baseline: ProtectionBaseline? = null,
    val error: String = ""
)

interface ManagedProtectionStorage {
    var managed: ManagedProtectionState
}

/** Save intent and the original policy before changing Android, including across partial failures. */
class ManagedProtection(
    private val policies: ProtectionPolicies,
    private val storage: ManagedProtectionStorage,
    private val hasPin: () -> Boolean
) {
    fun enable() {
        check(policies.deviceOwner) { "Este teléfono no tiene B-Security como Device Owner." }
        check(hasPin()) { "Primero configurá el PIN del responsable." }
        val before = storage.managed
        val baseline = before.baseline ?: ProtectionBaseline(
            policies.uninstallBlocked, policies.userControlDisabled, policies.notificationGrant)
        storage.managed = ManagedProtectionState(true, baseline)
        reconcile()
    }

    fun disable() {
        val before = storage.managed
        if (before.baseline == null) return
        check(policies.deviceOwner) { "Android ya no reconoce a B-Security como Device Owner." }
        storage.managed = before.copy(requested = false, error = "")
        reconcile()
    }

    fun fullyApplied(): Boolean = policies.deviceOwner && storage.managed.requested &&
        storage.managed.baseline != null && policies.uninstallBlocked &&
        policies.userControlDisabled != false && policies.notificationGrant.let { it == null || it == GRANTED }

    fun reconcile() {
        val state = storage.managed
        val baseline = state.baseline ?: return // Enrollment alone never enables policies.
        try {
            check(policies.deviceOwner) { "No se puede comprobar la administración del dispositivo." }
            if (state.requested) {
                check(hasPin()) { "Falta el PIN del responsable; no se aplicaron nuevas políticas." }
                policies.uninstallBlocked = true
                if (policies.userControlDisabled != null) policies.userControlDisabled = true
                if (policies.notificationGrant != null) policies.notificationGrant = GRANTED
                check(fullyApplied()) { "Android no confirmó todas las políticas solicitadas." }
                storage.managed = state.copy(error = "")
            } else {
                // Try every restoration even if one policy fails. Keep the baseline for a retry.
                val errors = mutableListOf<Throwable>()
                fun restore(action: () -> Unit) { runCatching(action).onFailure { errors += it } }
                restore { policies.uninstallBlocked = baseline.uninstall }
                baseline.control?.let { restore { policies.userControlDisabled = it } }
                baseline.notifications?.let { restore { policies.notificationGrant = it } }
                restore {
                    check(policies.uninstallBlocked == baseline.uninstall &&
                        (baseline.control == null || policies.userControlDisabled == baseline.control) &&
                        (baseline.notifications == null || policies.notificationGrant == baseline.notifications)) {
                        "Android no confirmó la restauración de las políticas."
                    }
                }
                if (errors.isNotEmpty()) throw IllegalStateException(errors.first().message, errors.first())
                storage.managed = ManagedProtectionState()
            }
        } catch (error: Exception) {
            storage.managed = storage.managed.copy(error = error.message ?: "No se pudo actualizar la administración.")
            throw error
        }
    }

    companion object { const val GRANTED = 1 }
}
