package com.bsecurity.app

import org.junit.Assert.*
import org.junit.Test

class ManagedProtectionTest {
    private class Policies : ProtectionPolicies {
        override var deviceOwner = true
        override var uninstallBlocked = false
        override var userControlDisabled: Boolean? = false
        var failNotification = false
        private var grant: Int? = 0
        override var notificationGrant: Int?
            get() = grant
            set(value) { if (failNotification) error("permission refused"); grant = value }
    }
    private class Storage : ManagedProtectionStorage {
        override var managed = ManagedProtectionState()
    }
    private val policies = Policies()
    private val storage = Storage()
    private var pin = true
    private fun engine() = ManagedProtection(policies, storage) { pin }

    @Test fun normalInstallationCannotApplyPolicies() {
        policies.deviceOwner = false
        assertTrue(runCatching { engine().enable() }.isFailure)
        assertFalse(policies.uninstallBlocked)
        assertNull(storage.managed.baseline)
    }
    @Test fun enrollmentWithoutExplicitActivationDoesNothing() {
        engine().reconcile()
        assertFalse(policies.uninstallBlocked)
        assertFalse(engine().fullyApplied())
    }
    @Test fun activationRequiresAnExistingPin() {
        pin = false
        assertTrue(runCatching { engine().enable() }.isFailure)
        assertNull(storage.managed.baseline)
    }
    @Test fun failedDurableSavePreventsPolicyChanges() {
        val failing = object : ManagedProtectionStorage {
            override var managed: ManagedProtectionState
                get() = ManagedProtectionState()
                set(value) { error("disk full") }
        }
        assertTrue(runCatching { ManagedProtection(policies, failing) { true }.enable() }.isFailure)
        assertFalse(policies.uninstallBlocked)
        assertEquals(false, policies.userControlDisabled)
    }
    @Test fun activationChecksAndroidAndRetainsOriginalState() {
        engine().enable()
        assertTrue(engine().fullyApplied())
        assertEquals(ProtectionBaseline(false, false, 0), storage.managed.baseline)
    }
    @Test fun reapplyingDoesNotReplaceTheOriginalBaseline() {
        engine().enable()
        engine().enable()
        engine().disable()
        assertFalse(policies.uninstallBlocked)
        assertEquals(false, policies.userControlDisabled)
        assertEquals(0, policies.notificationGrant)
    }
    @Test fun previouslyAppliedPoliciesArePreservedOnRelease() {
        policies.uninstallBlocked = true
        policies.userControlDisabled = true
        policies.notificationGrant = 2
        engine().enable()
        engine().disable()
        assertTrue(policies.uninstallBlocked)
        assertEquals(true, policies.userControlDisabled)
        assertEquals(2, policies.notificationGrant)
    }
    @Test fun unsupportedPoliciesAreNotClaimedOrApplied() {
        policies.userControlDisabled = null
        policies.notificationGrant = null
        engine().enable()
        assertEquals(ProtectionBaseline(false, null, null), storage.managed.baseline)
        engine().disable()
        assertNull(policies.userControlDisabled)
        assertNull(policies.notificationGrant)
    }
    @Test fun partialActivationIsVisibleAndCanBeRestored() {
        policies.failNotification = true
        assertTrue(runCatching { engine().enable() }.isFailure)
        assertFalse(engine().fullyApplied())
        assertEquals("permission refused", storage.managed.error)
        assertEquals(ProtectionBaseline(false, false, 0), storage.managed.baseline)
        policies.failNotification = false
        engine().disable()
        assertFalse(policies.uninstallBlocked)
        assertNull(storage.managed.baseline)
    }
    @Test fun interruptedReleaseRestoresOtherPoliciesAndRemainsRetryable() {
        engine().enable()
        policies.failNotification = true
        assertTrue(runCatching { engine().disable() }.isFailure)
        assertFalse(policies.uninstallBlocked)
        assertEquals(false, policies.userControlDisabled)
        assertFalse(storage.managed.requested)
        assertNotNull(storage.managed.baseline)
        policies.failNotification = false
        engine().reconcile()
        assertEquals(0, policies.notificationGrant)
        assertEquals(ManagedProtectionState(), storage.managed)
    }
    @Test fun persistedRequestCanRecoverAfterProcessDeath() {
        storage.managed = ManagedProtectionState(true, ProtectionBaseline(false, false, 0))
        engine().reconcile()
        assertTrue(engine().fullyApplied())
    }
    @Test fun losingOwnershipDoesNotSilentlyEraseRecoveryState() {
        engine().enable()
        policies.deviceOwner = false
        assertTrue(runCatching { engine().reconcile() }.isFailure)
        assertNotNull(storage.managed.baseline)
        assertFalse(engine().fullyApplied())
    }
}
