package com.bsecurity.app

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.Build
import android.os.Bundle

/** Android 12+ provisioning entry points; protected by BIND_DEVICE_ADMIN in the manifest. */
class ProtectionProvisioningActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 31 && intent.action == DevicePolicyManager.ACTION_GET_PROVISIONING_MODE) {
            val allowed = intent.getIntegerArrayListExtra(DevicePolicyManager.EXTRA_PROVISIONING_ALLOWED_PROVISIONING_MODES)
            if (allowed?.contains(DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE) == true) {
                setResult(RESULT_OK, Intent().putExtra(DevicePolicyManager.EXTRA_PROVISIONING_MODE,
                    DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE))
            } else setResult(RESULT_CANCELED)
        } else if (Build.VERSION.SDK_INT >= 31 && intent.action == DevicePolicyManager.ACTION_ADMIN_POLICY_COMPLIANCE &&
            getSystemService(DevicePolicyManager::class.java).isDeviceOwnerApp(packageName)) {
            // No restrictions yet. The visible setup screen asks for a PIN and explicit activation.
            setResult(RESULT_OK)
        } else setResult(RESULT_CANCELED)
        finish()
    }
}
