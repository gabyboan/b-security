package com.bsecurity.app

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent

class ParentalService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var store: PolicyStore
    private var foreground: String? = null
    private var lastBlock = 0L
    private var usageAt = 0L
    private var usage = emptyMap<String, Long>()
    private val tick = object : Runnable {
        override fun run() { enforce(); handler.postDelayed(this, 1500) }
    }
    override fun onServiceConnected() {
        store = PolicyStore(this)
        handler.removeCallbacks(tick)
        handler.post(tick)
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!::store.isInitialized || event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        foreground = event.packageName?.toString()
        enforce()
    }
    private fun enforce() {
        if (!::store.isInitialized || !store.enabled) return
        val pkg = foreground ?: return
        if (store.protectedPackage(pkg)) return
        val rule = store.effectiveRules()[pkg] ?: return
        if (rule.minutes > 0 && store.usageGranted() && android.os.SystemClock.elapsedRealtime() - usageAt > 5000) {
            usage = store.usageToday(); usageAt = android.os.SystemClock.elapsedRealtime()
        }
        val exceeded = rule.minutes > 0 && store.usageGranted() &&
            BudgetMath.exhausted(usage[pkg] ?: 0, rule.minutes)
        if (!rule.blocked && !exceeded) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastBlock < 2000) return
        lastBlock = now
        performGlobalAction(GLOBAL_ACTION_HOME)
        val intent = Intent(this, BlockedActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra("package", pkg).putExtra("limit", exceeded)
        runCatching { startActivity(intent) }
        foreground = null
    }
    override fun onInterrupt() { foreground = null }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy() }
}
