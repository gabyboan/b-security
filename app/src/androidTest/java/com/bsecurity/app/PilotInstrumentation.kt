package com.bsecurity.app

import android.app.Activity
import android.app.Instrumentation
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Bundle
import org.json.JSONObject
import java.net.URL
import java.util.UUID
import javax.net.ssl.HttpsURLConnection

/** Uses disposable stores by default; real-activity checks and network access are opt-in. */
class PilotInstrumentation : Instrumentation() {
    private var networkChecks = false
    private var visualChecks = false
    private var routerChecks = false
    private var lanChecks = false
    private var homeChecks = false
    private var mapChecks = false
    private var protectionChecks = false
    private var managedProtectionChecks = false
    private var protectionOnly = false
    private var familyEndpoint: String? = null
    private var familyCa: String? = null

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        networkChecks = arguments?.getString("network") == "true"
        visualChecks = arguments?.getString("visual") == "true"
        routerChecks = arguments?.getString("router") == "true"
        lanChecks = arguments?.getString("lan") == "true"
        homeChecks = arguments?.getString("home") == "true"
        mapChecks = arguments?.getString("map") == "true"
        protectionChecks = arguments?.getString("protection") == "true"
        managedProtectionChecks = arguments?.getString("managedProtection") == "true"
        protectionOnly = arguments?.getString("protectionOnly") == "true"
        familyEndpoint = arguments?.getString("familyEndpoint")
        familyCa = arguments?.getString("familyCa")
        start()
    }

    override fun onStart() {
        if (protectionOnly) { runProtectionOnly(); return }
        val prefix = "verification.${UUID.randomUUID()}."
        val stores = mutableSetOf<String>()
        val isolated = object : ContextWrapper(targetContext) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                stores += name
                return targetContext.getSharedPreferences(prefix + name, mode)
            }
        }
        val lines = mutableListOf<String>()
        var passed = 0
        var failed = 0
        fun case(name: String, block: () -> Unit) {
            try {
                stores.forEach { isolated.getSharedPreferences(it, 0).edit().clear().commit() }
                block()
                passed++
                lines += "OK: $name"
            } catch (error: Throwable) {
                failed++
                lines += "FALLO: $name: ${error.javaClass.simpleName}: ${error.message}"
            }
        }
        try {
            case("PIN: formato, alta y verificación") {
                val policy = PolicyStore(isolated)
                check(!policy.hasPin && !policy.verifyPin("918273"))
                listOf("", "12345", "1234567890123", "abcdef", "１２３４５６").forEach {
                    check(runCatching { policy.setPin(it) }.isFailure)
                }
                policy.setPin("918273")
                check(policy.hasPin && policy.verifyPin("918273"))
                check(!policy.verifyPin("000000"))
            }
            case("PIN: bloqueo tras cinco intentos y recuperación") {
                val policy = PolicyStore(isolated)
                val prefs = isolated.getSharedPreferences("parental", 0)
                policy.setPin("918273")
                repeat(5) { check(!policy.verifyPin("000000")) }
                check(prefs.getLong("lockedUntil", 0) > System.currentTimeMillis())
                check(!policy.verifyPin("918273"))
                // Advance only this fixture's deadline, without sleeping or changing Android's clock.
                prefs.edit().putLong("lockedUntil", System.currentTimeMillis() - 1).commit()
                check(policy.verifyPin("918273"))
                check(prefs.getInt("failures", -1) == 0)
            }
            case("PIN: cambio conserva las reglas locales") {
                val policy = PolicyStore(isolated)
                policy.setPin("918273")
                policy.putRule("com.example.game", AppRule(false, 30))
                policy.setPin("123456789012")
                check(!policy.verifyPin("918273"))
                check(policy.verifyPin("123456789012"))
                check(policy.rules()["com.example.game"] == AppRule(false, 30))
            }
            case("Protección de ajustes, permisos y emergencias") {
                val policy = PolicyStore(isolated)
                listOf(targetContext.packageName, "com.android.settings", "com.android.systemui",
                    "com.android.permissioncontroller", "com.android.dialer", "com.android.emergency").forEach {
                    check(policy.protectedPackage(it))
                    check(runCatching { policy.putRule(it, AppRule(true, 10)) }.isFailure)
                }
            }
            case("Reglas locales: persistencia, límites y eliminación") {
                val policy = PolicyStore(isolated)
                policy.enable(true)
                check(PolicyStore(isolated).enabled)
                policy.putRule("com.example.game", AppRule(false, 2000))
                check(PolicyStore(isolated).rules()["com.example.game"] == AppRule(false, 1440))
                policy.putRule("com.example.game", AppRule())
                check("com.example.game" !in policy.rules())
                policy.enable(false)
                check(!policy.enabled)
            }
            case("Consentimiento, combinación y revocación de controles") {
                val policy = PolicyStore(isolated)
                val family = FamilyClient(isolated)
                policy.putRule("com.example.game", AppRule(false, 30))
                policy.setRemoteRules(JSONObject()
                    .put("com.example.game", JSONObject().put("blocked", true).put("minutes", 15))
                    .put("com.android.settings", JSONObject().put("blocked", true)))
                check(policy.effectiveRules()["com.example.game"] == AppRule(false, 30))
                family.acceptControls = true
                policy.setRemoteRules(JSONObject()
                    .put("com.example.game", JSONObject().put("blocked", true).put("minutes", 15))
                    .put("com.android.settings", JSONObject().put("blocked", true)))
                check(policy.effectiveRules()["com.example.game"] == AppRule(true, 15))
                check("com.android.settings" !in policy.effectiveRules())
                family.acceptControls = false
                check(policy.effectiveRules()["com.example.game"] == AppRule(false, 30))
                policy.setRemoteRules(JSONObject())
                family.acceptControls = true
                check(policy.effectiveRules()["com.example.game"] == AppRule(false, 30))
            }
            case("Servidor: normalización y reinicio del consentimiento") {
                val policy = PolicyStore(isolated)
                val family = FamilyClient(isolated)
                family.configure("https://one.example")
                family.shareLocation = true
                family.acceptControls = true
                policy.putRule("com.example.game", AppRule(false, 30))
                policy.setRemoteRules(JSONObject().put("com.example.game", JSONObject().put("blocked", true)))
                family.configure(" HTTPS://ONE.EXAMPLE:443/ ")
                check(family.shareLocation && family.acceptControls)
                family.configure("https://two.example:9443/")
                check(!family.shareLocation && !family.acceptControls)
                family.acceptControls = true
                check(policy.effectiveRules()["com.example.game"] == AppRule(false, 30))
                listOf("http://unsafe.example", "https://two.example/path", "https://two.example:0").forEach {
                    check(runCatching { family.configure(it) }.isFailure)
                    check(family.endpoint == "https://two.example:9443")
                }
            }
            case("Presupuesto: minuto exacto y recorte del intervalo") {
                check(!BudgetMath.exhausted(59_999, 1))
                check(BudgetMath.exhausted(60_000, 1))
                check(!BudgetMath.exhausted(Long.MAX_VALUE, 0))
                check(BudgetMath.overlap(10, 30, 20, 25) == 5L)
                check(BudgetMath.overlap(0, 10, 20, 30) == 0L)
            }
            case("Diagnóstico de un dispositivo Android real") {
                check(Diagnostics.device(targetContext).any { it.title == "Parche de seguridad" })
            }
            FamilyClientChecks.run(isolated, ::case)
            ProtectionChecks.run(targetContext, isolated, ::case)
            if (protectionChecks || managedProtectionChecks) {
                ProtectionChecks.runEmulator(this, managedProtectionChecks, ::case)
            }
            SupabaseAuthChecks.run(isolated, ::case)
            if (lanChecks) {
                val previousPassed = passed
                val previousFailed = failed
                LanTransportChecks.run(targetContext, ::case)
                lines += "Transporte LAN en loopback: ${passed - previousPassed} aprobadas; ${failed - previousFailed} fallidas."
            } else {
                lines += "Transporte LAN omitido: habilitar con -e lan true; requiere Wi-Fi y solo usa loopback."
            }
            if (routerChecks) {
                val previousPassed = passed
                val previousFailed = failed
                case("Router: diagnóstico TCP local con avance completo") {
                    val completed = mutableListOf<Int>()
                    val findings = Diagnostics.router(targetContext) { done, total, _ ->
                        check(total == RouterAssessment.ports.size)
                        check(done in 0..total)
                        completed += done
                    }
                    check(completed == (0..RouterAssessment.ports.size).toList()) {
                        "Se requiere una Wi-Fi conectada con puerta de enlace IPv4 privada y estable."
                    }
                    check(findings.any { it.title == "Conexiones TCP" })
                    check(findings.any { it.title == "Cobertura limitada" })
                    check(findings.none { it.level == "ok" })
                }
                lines += "Router local: ${passed - previousPassed} aprobada; ${failed - previousFailed} fallidas."
            } else {
                lines += "Router local omitido: habilitar con -e router true solo en una red propia o autorizada."
            }
            if (visualChecks) {
                val previousPassed = passed
                val previousFailed = failed
                VisualChecks.run(this, ::case)
                lines += "Interfaz visual: ${passed - previousPassed} aprobadas; ${failed - previousFailed} fallidas."
            } else {
                lines += "Interfaz visual omitida: habilitar con -e visual true."
            }
            if (familyEndpoint != null || familyCa != null) {
                if (familyEndpoint.isNullOrBlank() || familyCa.isNullOrBlank()) {
                    case("Familia HTTPS: parámetros de la prueba aislada") { error("Se requieren familyEndpoint y familyCa.") }
                } else {
                    FamilyIntegrationChecks.run(targetContext, familyEndpoint!!, familyCa!!, ::case)
                    lines += "Familia HTTPS aislada: 5 pruebas ejecutadas."
                }
            } else {
                lines += "Familia HTTPS aislada omitida: ejecutar test-family.ps1."
            }
            if (mapChecks) {
                val previousPassed = passed
                val previousFailed = failed
                HomeMapChecks.run(this, ::case)
                lines += "Mapa nativo: ${passed - previousPassed} aprobadas; ${failed - previousFailed} fallidas."
            }
            if (homeChecks) {
                case("Hogar real: sesión Google y RPC autenticada de Supabase") {
                    val auth = SupabaseAuth(targetContext)
                    check(auth.verify() != null) { "Ingresá con Google antes de esta prueba opcional." }
                    val client = HomeClient(targetContext, auth)
                    val state = client.request("status")
                    check(state.getString("device_id") == client.deviceId && state.has("home"))
                    state.optJSONObject("home")?.let { home ->
                        check(home.getString("role") in setOf("admin", "member"))
                        val devices = home.getJSONArray("devices")
                        check((0 until devices.length()).any { devices.getJSONObject(it).getString("id") == client.deviceId })
                    }
                }
                lines += "Hogar real: consulta autenticada ejecutada; no crea hogar ni activa GPS."
            }
            if (networkChecks) {
                case("HTTPS: disponibilidad del servidor Familia configurado por defecto") {
                    val connection = URL(targetContext.getString(R.string.family_endpoint) + "/health")
                        .openConnection() as HttpsURLConnection
                    try {
                        connection.connectTimeout = 8000
                        connection.readTimeout = 8000
                        connection.instanceFollowRedirects = false
                        check(connection.responseCode == 200)
                        check(JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).getBoolean("ok"))
                    } finally { connection.disconnect() }
                }
            } else {
                lines += "HTTPS omitido: habilitar con -e network true."
            }
        } finally {
            stores.forEach { targetContext.deleteSharedPreferences(prefix + it) }
        }
        lines += "$passed aprobadas; $failed fallidas."
        finish(if (failed == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Bundle().apply { putString("stream", lines.joinToString("\n", prefix = "\n", postfix = "\n")) })
    }

    /** A focused rerun for disposable emulators; the normal suite remains unchanged. */
    private fun runProtectionOnly() {
        val lines = mutableListOf<String>()
        var passed = 0
        var failed = 0
        try {
            check(protectionChecks || managedProtectionChecks) { "Elegí protection o managedProtection." }
            ProtectionChecks.runEmulator(this, managedProtectionChecks) { name, block ->
                try { block(); passed++; lines += "OK: $name" }
                catch (error: Throwable) { failed++; lines += "FALLO: $name: ${error.javaClass.simpleName}: ${error.message}" }
            }
        } catch (error: Throwable) {
            failed++; lines += "FALLO: preparación del emulador: ${error.message}"
        }
        lines += "$passed aprobadas; $failed fallidas."
        finish(if (failed == 0) Activity.RESULT_OK else Activity.RESULT_CANCELED,
            Bundle().apply { putString("stream", lines.joinToString("\n", prefix = "\n", postfix = "\n")) })
    }
}
