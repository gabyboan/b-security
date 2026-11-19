package com.bsecurity.app

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URL
import java.security.KeyStore
import java.security.cert.CertificateFactory
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.TrustManagerFactory

/** Real Android clients and Keystore against the disposable Node HTTPS fixture. */
object FamilyIntegrationChecks {
    fun run(target: Context, endpoint: String, ca: String, case: (String, () -> Unit) -> Unit) {
        val address = FamilyEndpoint.normalize(endpoint)
        require(URI(address).host == "localhost") { "El fixture debe ser HTTPS en localhost mediante ADB reverse." }
        val certificate = CertificateFactory.getInstance("X.509")
            .generateCertificate(Base64.decode(ca, Base64.DEFAULT).inputStream())
        val trust = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null)
            setCertificateEntry("disposable-fixture", certificate)
        }
        val managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }
        val tls = SSLContext.getInstance("TLS").apply { init(null, managers.trustManagers, null) }
        val connect: (URL) -> HttpsURLConnection = { url ->
            (url.openConnection() as HttpsURLConnection).apply { sslSocketFactory = tls.socketFactory }
        }
        val prefix = "verification.family.${UUID.randomUUID()}."
        val stores = mutableSetOf<String>()
        fun context(name: String): Context = object : ContextWrapper(target) {
            override fun getApplicationContext(): Context = this
            override fun getSharedPreferences(key: String, mode: Int): SharedPreferences {
                val isolatedName = prefix + name + "." + key
                stores += isolatedName
                return target.getSharedPreferences(isolatedName, mode)
            }
        }
        fun client(context: Context) = FamilyClient(context, connect).apply { configure(address) }
        fun report(client: FamilyClient): JSONObject = JSONObject().put("consent", true).put("controls", client.acceptControls)
            .put("model", "Dispositivo sintético de prueba").put("risks", 0).apply {
                if (client.acceptControls) put("apps", JSONArray().put(JSONObject().put("pkg", "com.example.game").put("name", "Juego de prueba")))
                if (client.shareLocation) put("location", JSONObject().put("lat", 0).put("lon", 0).put("accuracy", 1).put("ageSeconds", 0))
            }
        try {
            case("Familia HTTPS: el cliente normal rechaza el certificado temporal") {
                val isolated = context("untrusted")
                val untrusted = FamilyClient(isolated).apply { configure(address) }
                val failure = runCatching { untrusted.request("/v1/family") }.exceptionOrNull()
                check(generateSequence(failure) { it.cause }.any { it is SSLHandshakeException }) {
                    "Se esperaba un rechazo TLS por certificado no confiable; recibido: $failure"
                }
                check(!isolated.getSharedPreferences("family", 0).contains("session"))
            }
            case("Familia HTTPS: identidad cifrada y reutilizada al recrear el cliente") {
                val isolated = context("persistence")
                val family = client(isolated)
                family.request("/v1/family", JSONObject().put("name", "Persistencia temporal"))
                val original = family.request("/v1/family").getJSONObject("family").getJSONArray("members").getJSONObject(0).getString("id")
                val saved = isolated.getSharedPreferences("family", 0).getString("session", null)
                check(saved != null && Base64.decode(saved, Base64.NO_WRAP).size > 60)
                val restored = client(isolated)
                check(restored.request("/v1/family").getJSONObject("family").getJSONArray("members").getJSONObject(0).getString("id") == original)
                check(isolated.getSharedPreferences("family", 0).getString("session", null) == saved)
                restored.request("/v1/leave", JSONObject())
            }
            case("Familia HTTPS: invitación, reglas, revocación offline y grupo eliminado") {
                val owner = client(context("lifecycle.owner"))
                val memberContext = context("lifecycle.member")
                val member = client(memberContext)
                val policy = PolicyStore(memberContext)
                val sync = FamilySync(member, policy)
                policy.putRule("com.example.game", AppRule(false, 30))
                owner.request("/v1/family", JSONObject())
                val invitation = owner.request("/v1/invite", JSONObject()).getString("code")
                check(member.request("/v1/join", JSONObject().put("code", invitation)).getString("role") == "member")
                sync.sync { report(member) }
                val id = members(owner).single { it.getString("role") == "member" }.getString("id")
                val update = JSONObject().put("member", id).put("rules", remoteRule())
                expectHttp(409) { owner.request("/v1/policy", update) }
                member.acceptControls = true
                member.shareLocation = true
                sync.sync { report(member) }
                owner.request("/v1/policy", update)
                sync.sync { report(member) }
                check(policy.effectiveRules()["com.example.game"] == AppRule(true, 15))

                // Revoke and re-accept without sending anything between these local actions.
                member.acceptControls = false
                client(memberContext).acceptControls = true
                check(member.controlsRevocationPending)
                check(policy.effectiveRules()["com.example.game"] == AppRule(false, 30))
                sync.sync { report(member) }
                check(!member.controlsRevocationPending)
                check(policy.effectiveRules()["com.example.game"] == AppRule(false, 30))
                check(members(owner).single { it.getString("id") == id }.getJSONObject("policy").length() == 0)

                owner.request("/v1/policy", update)
                sync.sync { report(member) }
                check(policy.effectiveRules()["com.example.game"]!!.blocked)
                member.acceptControls = false
                member.shareLocation = false
                sync.sync { report(member) }
                val revoked = members(owner).single { it.getString("id") == id }.getJSONObject("report")
                check(!revoked.getBoolean("controls") && revoked.getJSONArray("apps").length() == 0 && !revoked.has("location"))
                check(policy.effectiveRules()["com.example.game"] == AppRule(false, 30))

                member.acceptControls = true
                member.shareLocation = true
                sync.sync { report(member) }
                owner.request("/v1/policy", update)
                sync.sync { report(member) }
                owner.request("/v1/leave", JSONObject())
                val absent = sync.sync { error("El grupo eliminado no debe recibir un informe") }
                check(absent.isNull("family") && !member.acceptControls && !member.shareLocation)
                member.acceptControls = true
                check(policy.effectiveRules()["com.example.game"] == AppRule(false, 30))
                member.acceptControls = false

                owner.request("/v1/family", JSONObject())
                val nextInvite = owner.request("/v1/invite", JSONObject()).getString("code")
                member.request("/v1/join", JSONObject().put("code", nextInvite))
                sync.sync { report(member) }
                val nextReport = members(owner).single { it.getString("id") == id }.getJSONObject("report")
                check(!nextReport.getBoolean("controls") && !nextReport.has("location"))
                member.request("/v1/leave", JSONObject())
                sync.sync { error("Un integrante que salió no debe compartir informes") }
                owner.request("/v1/leave", JSONObject())
            }
            case("Familia HTTPS: aislamiento entre grupos e integrantes y código de un uso") {
                val owner = client(context("privacy.owner"))
                val member = client(context("privacy.member"))
                val outsider = client(context("privacy.outsider"))
                owner.request("/v1/family", JSONObject())
                val invitation = owner.request("/v1/invite", JSONObject()).getString("code")
                member.request("/v1/join", JSONObject().put("code", invitation))
                member.acceptControls = true
                member.shareLocation = true
                member.request("/v1/report", report(member))
                val id = members(owner).single { it.getString("role") == "member" }.getString("id")
                outsider.request("/v1/family", JSONObject())
                check(members(outsider).size == 1)
                expectHttp(404) { outsider.request("/v1/policy", JSONObject().put("member", id).put("rules", remoteRule())) }
                outsider.request("/v1/leave", JSONObject())
                expectHttp(400) { outsider.request("/v1/join", JSONObject().put("code", invitation)) }
                val nextInvite = owner.request("/v1/invite", JSONObject()).getString("code")
                outsider.request("/v1/join", JSONObject().put("code", nextInvite))
                check(members(outsider).single { it.getString("id") == id }.isNull("report"))
                expectHttp(403) { outsider.request("/v1/policy", JSONObject().put("member", id).put("rules", remoteRule())) }
                owner.request("/v1/leave", JSONObject())
            }
            case("Familia HTTPS: sesión vencida elimina permisos y requiere una consulta nueva") {
                val isolated = context("expiry")
                val family = client(isolated)
                val policy = PolicyStore(isolated)
                family.request("/v1/family", JSONObject())
                family.acceptControls = true
                family.shareLocation = true
                policy.putRule("com.example.game", AppRule(false, 30))
                policy.setRemoteRules(remoteRule())
                family.request("/__verification__/expire", JSONObject())
                expectHttp(401) { family.request("/v1/family", JSONObject()) }
                check(!isolated.getSharedPreferences("family", 0).contains("session"))
                check(!family.acceptControls && !family.shareLocation)
                family.acceptControls = true
                check(policy.effectiveRules()["com.example.game"] == AppRule(false, 30))
                val recovered = FamilySync(family, policy).sync { error("Una identidad nueva no pertenece a un grupo") }
                check(recovered.isNull("family") && !family.acceptControls)
                check(isolated.getSharedPreferences("family", 0).contains("session"))
            }
        } finally {
            stores.forEach { target.deleteSharedPreferences(it) }
        }
    }

    private fun members(family: FamilyClient): List<JSONObject> {
        val values = family.request("/v1/family").getJSONObject("family").getJSONArray("members")
        return (0 until values.length()).map { values.getJSONObject(it) }
    }

    private fun remoteRule() = JSONObject().put("com.example.game", JSONObject().put("blocked", true).put("minutes", 15))

    private fun expectHttp(status: Int, action: () -> Unit) {
        val error = runCatching(action).exceptionOrNull()
        check(error is FamilyHttpException && error.status == status) { "Se esperaba HTTP $status; resultado: $error" }
    }
}
