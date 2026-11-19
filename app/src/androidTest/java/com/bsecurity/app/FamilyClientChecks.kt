package com.bsecurity.app

import android.content.Context
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URL
import java.security.Principal
import java.security.cert.Certificate
import javax.net.ssl.HttpsURLConnection

/** HTTP failures are simulated per connection; production TLS defaults remain unchanged. */
object FamilyClientChecks {
    fun run(context: Context, case: (String, () -> Unit) -> Unit) {
        case("Familia: revocar sin conexión no recupera reglas al reaceptar") {
            val policy = PolicyStore(context)
            val family = FamilyClient(context)
            family.acceptControls = true
            policy.putRule("com.example.game", AppRule(false, 30))
            policy.setRemoteRules(rule())
            check(policy.effectiveRules()["com.example.game"]!!.blocked)
            family.acceptControls = false
            check(family.controlsRevocationPending)
            FamilyClient(context).acceptControls = true
            check(FamilyClient(context).controlsRevocationPending)
            check(policy.effectiveRules()["com.example.game"] == AppRule(false, 30))
        }
        case("Familia: 401 sin JSON borra identidad sin repetir la operación") {
            var sessions = 0
            var mutations = 0
            var expired = true
            val family = FamilyClient(context) { url ->
                if (url.path == "/v1/session") {
                    sessions++
                    Response(url, 201, "{\"token\":\"isolated-session-$sessions\"}")
                } else {
                    mutations++
                    Response(url, if (expired) 401 else 200, if (expired) "unauthorized" else "{}")
                }
            }
            family.configure("https://fixture.invalid")
            family.acceptControls = true
            family.shareLocation = true
            val policy = PolicyStore(context)
            policy.setPin("918273")
            policy.enable(true)
            policy.putRule("com.example.game", AppRule(false, 30))
            policy.setRemoteRules(rule())
            expectHttp(401) { family.request("/v1/family", JSONObject()) }
            check(sessions == 1 && mutations == 1)
            check(!context.getSharedPreferences("family", 0).contains("session"))
            check(!family.shareLocation && !family.acceptControls && !family.controlsRevocationPending)
            check(family.endpoint == "https://fixture.invalid")
            check(policy.enabled && policy.verifyPin("918273"))
            family.acceptControls = true
            check(policy.effectiveRules()["com.example.game"] == AppRule(false, 30))
            expired = false
            family.request("/v1/family")
            check(sessions == 2 && mutations == 2)
        }
        case("Familia: errores de red, 403, 429 y 500 conservan la identidad") {
            var status = 403
            var sessions = 0
            var offline = false
            val family = FamilyClient(context) { url ->
                if (offline) throw IOException("fixture offline")
                if (url.path == "/v1/session") {
                    sessions++
                    Response(url, 201, "{\"token\":\"isolated-session\"}")
                } else Response(url, status, "error sin JSON")
            }
            family.acceptControls = true
            family.shareLocation = true
            val policy = PolicyStore(context)
            policy.setRemoteRules(rule())
            var saved: String? = null
            for (error in listOf(403, 429, 500)) {
                status = error
                expectHttp(error) { family.request("/v1/family") }
                val current = context.getSharedPreferences("family", 0).getString("session", null)
                check(current != null)
                if (saved == null) saved = current else check(saved == current)
                check(family.acceptControls && family.shareLocation)
                check(policy.effectiveRules()["com.example.game"]!!.blocked)
            }
            offline = true
            check(runCatching { family.request("/v1/family") }.exceptionOrNull() is IOException)
            check(sessions == 1 && family.acceptControls && family.shareLocation)
            check(context.getSharedPreferences("family", 0).getString("session", null) == saved)
        }
        case("Familia: una revocación fallida sigue pendiente y no aplica reglas viejas") {
            var reports = 0
            var reads = 0
            var failRevocation = true
            val family = FamilyClient(context) { url ->
                when (url.path) {
                    "/v1/session" -> Response(url, 201, "{\"token\":\"isolated-session\"}")
                    "/v1/report" -> Response(url, if (failRevocation) 500 else 200, "{}") {
                        val report = JSONObject(it)
                        check(report.getBoolean("consent"))
                        check(report.getBoolean("controls") == (reports > 0 && !failRevocation))
                        reports++
                    }
                    else -> {
                        reads++
                        Response(url, 200, "{\"family\":{},\"policy\":{}}")
                    }
                }
            }
            val policy = PolicyStore(context)
            family.acceptControls = true
            policy.setRemoteRules(rule())
            family.acceptControls = false
            family.acceptControls = true
            expectHttp(500) { FamilySync(family, policy).sync { error("No debe publicar el consentimiento actual todavía") } }
            check(family.controlsRevocationPending && reports == 1 && reads == 1)
            check(policy.effectiveRules().isEmpty())
            failRevocation = false
            reports = 0
            FamilySync(family, policy).sync { JSONObject().put("consent", true).put("controls", true) }
            check(!family.controlsRevocationPending && reports == 2 && reads == 3)
        }
        case("Familia: un grupo ausente reinicia los permisos y conserva reglas locales") {
            val family = FamilyClient(context) { url ->
                Response(url, 200, if (url.path == "/v1/session") "{\"token\":\"isolated-session\"}" else "{\"family\":null}")
            }
            family.acceptControls = true
            family.shareLocation = true
            val policy = PolicyStore(context)
            policy.putRule("com.example.game", AppRule(false, 30))
            policy.setRemoteRules(rule())
            FamilySync(family, policy).sync { error("Un dispositivo sin grupo no comparte informes") }
            check(!family.acceptControls && !family.shareLocation && !family.controlsRevocationPending)
            family.acceptControls = true
            check(policy.effectiveRules()["com.example.game"] == AppRule(false, 30))
        }
    }

    private fun rule() = JSONObject().put("com.example.game", JSONObject().put("blocked", true).put("minutes", 15))

    private fun expectHttp(status: Int, action: () -> Unit) {
        val error = runCatching(action).exceptionOrNull()
        check(error is FamilyHttpException && error.status == status) { "Se esperaba HTTP $status; resultado: $error" }
    }

    private class Response(
        url: URL, private val status: Int, private val content: String,
        private val sent: (String) -> Unit = {}
    ) : HttpsURLConnection(url) {
        private val body = ByteArrayOutputStream()
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
        override fun getOutputStream() = body
        override fun getResponseCode(): Int { sent(body.toString("UTF-8")); return status }
        override fun getInputStream() = ByteArrayInputStream(content.toByteArray(Charsets.UTF_8))
        override fun getErrorStream() = inputStream
        override fun getCipherSuite() = "fixture"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getPeerPrincipal(): Principal? = null
        override fun getLocalPrincipal(): Principal? = null
    }
}
