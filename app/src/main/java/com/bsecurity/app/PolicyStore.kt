package com.bsecurity.app

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.provider.Settings
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import android.util.Base64

class PolicyStore(val context: Context) {
    private val prefs = context.getSharedPreferences("parental", Context.MODE_PRIVATE)
    val hasPin get() = prefs.contains("pinHash")
    val enabled get() = prefs.getBoolean("enabled", false)
    fun enable(value: Boolean) { prefs.edit().putBoolean("enabled", value).apply() }
    fun setPin(pin: String) {
        require(pin.matches(Regex("[0-9]{6,12}")))
        val salt = ByteArray(24).also { SecureRandom().nextBytes(it) }
        prefs.edit().putString("pinSalt", enc(salt)).putString("pinHash", enc(hash(pin, salt)))
            .remove("failures").remove("lockedUntil").commit()
    }
    fun verifyPin(pin: String): Boolean {
        if (System.currentTimeMillis() < prefs.getLong("lockedUntil", 0)) return false
        if (!hasPin) return false
        val salt = Base64.decode(prefs.getString("pinSalt", ""), Base64.NO_WRAP)
        val expected = Base64.decode(prefs.getString("pinHash", ""), Base64.NO_WRAP)
        val ok = MessageDigest.isEqual(expected, hash(pin, salt))
        val failures = if (ok) 0 else prefs.getInt("failures", 0) + 1
        prefs.edit().putInt("failures", failures)
            .putLong("lockedUntil", if (failures >= 5) System.currentTimeMillis() + 30_000 else 0)
            .apply()
        return ok
    }
    private fun hash(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, 120_000, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }
    private fun enc(value: ByteArray) = Base64.encodeToString(value, Base64.NO_WRAP)
    fun rules(): Map<String, AppRule> {
        val obj = runCatching { JSONObject(prefs.getString("rules", "{}")!!) }.getOrDefault(JSONObject())
        return obj.keys().asSequence().associateWith { key ->
            val rule = obj.optJSONObject(key) ?: JSONObject()
            AppRule(rule.optBoolean("blocked"), rule.optInt("minutes").coerceIn(0, 1440))
        }
    }
    fun setRemoteRules(value: JSONObject) {
        prefs.edit().putString("remoteRules", value.toString()).apply()
    }
    fun effectiveRules(): Map<String, AppRule> {
        val combined = rules().toMutableMap()
        if (!FamilyClient(context).acceptControls) return combined
        val remote = runCatching { JSONObject(prefs.getString("remoteRules", "{}")!!) }.getOrDefault(JSONObject())
        remote.keys().forEach { pkg ->
            if (!protectedPackage(pkg)) {
                val item = remote.optJSONObject(pkg) ?: return@forEach
                val local = combined[pkg] ?: AppRule()
                val remoteLimit = item.optInt("minutes").coerceIn(0, 1440)
                combined[pkg] = local.merge(AppRule(item.optBoolean("blocked"), remoteLimit))
            }
        }
        return combined
    }
    fun putRule(pkg: String, rule: AppRule) {
        require(!protectedPackage(pkg))
        val obj = JSONObject(prefs.getString("rules", "{}")!!)
        if (!rule.blocked && rule.minutes == 0) obj.remove(pkg)
        else obj.put(pkg, JSONObject().put("blocked", rule.blocked).put("minutes", rule.minutes.coerceIn(0, 1440)))
        prefs.edit().putString("rules", obj.toString()).apply()
    }
    fun protectedPackage(pkg: String): Boolean {
        if (pkg == context.packageName || pkg == "com.android.settings" || pkg == "com.android.systemui" ||
            pkg.contains("permissioncontroller") || pkg.contains("dialer") || pkg.contains("emergency")) return true
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return context.packageManager.queryIntentActivities(home, 0).any { it.activityInfo.packageName == pkg }
    }
    fun usageGranted(): Boolean {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        return ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName) == AppOpsManager.MODE_ALLOWED
    }
    fun serviceEnabled(): Boolean = Settings.Secure.getString(context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)?.split(':')?.any {
        it.equals("${context.packageName}/${ParentalService::class.java.name}", true)
    } == true

    fun usageToday(): Map<String, Long> {
        if (!usageGranted()) return emptyMap()
        val now = System.currentTimeMillis()
        val start = BudgetMath.dayStart(now)
        val events = (context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager)
            .queryEvents(start - 86_400_000, now)
        val timeline = UsageTimeline(start, now)
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> timeline.resume(event.packageName, event.timeStamp)
                UsageEvents.Event.ACTIVITY_PAUSED -> timeline.pause(event.packageName, event.timeStamp)
                UsageEvents.Event.SCREEN_NON_INTERACTIVE, UsageEvents.Event.DEVICE_SHUTDOWN -> timeline.idle(event.timeStamp)
            }
        }
        return timeline.result()
    }
}
