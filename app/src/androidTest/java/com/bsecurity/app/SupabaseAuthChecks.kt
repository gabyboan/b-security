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

/** Isolated Android Keystore + simulated HTTPS. Never signs in or contacts a real project. */
object SupabaseAuthChecks {
    private const val userId = "24f8a08b-d1c1-4c3c-8dba-13385fa2c6b8"
    private val config = AuthConfig.parse("https://auth.fixture.invalid", "sb_publishable_fixture_only", "123456-fixture.apps.googleusercontent.com")

    fun run(context: Context, case: (String, () -> Unit) -> Unit) {
        case("Google: solicitud con nonce, clave pública y persistencia cifrada") {
            val fixture = Fixture(context)
            fixture.route = { url ->
                check(url.toString() == "${config.url}/auth/v1/token?grant_type=id_token")
                Response(url, 200, tokens()) { request, body ->
                    check(request.requestMethod == "POST")
                    check(request.getRequestProperty("apikey") == config.publishableKey)
                    check(!request.instanceFollowRedirects)
                    val json = JSONObject(body)
                    check(json.getString("provider") == "google")
                    check(json.getString("id_token") == "google-id-fixture")
                    check(json.getString("nonce") == "raw-nonce-fixture")
                }
            }
            check(fixture.auth.signIn("google-id-fixture", "raw-nonce-fixture").id == userId)
            val saved = fixture.stored()!!
            check(!saved.contains("access-fixture") && !saved.contains("refresh-fixture") && !saved.contains("fixture@example.com"))
            check(fixture.newClient().cachedAccount()?.email == "fixture@example.com")
            check(fixture.calls == 1)
        }
        case("Google: rechazo del proveedor no crea una sesión ficticia") {
            val fixture = Fixture(context)
            fixture.route = { Response(it, 400, "{\"error_code\":\"provider_disabled\"}") }
            check(runCatching { fixture.signIn() }.exceptionOrNull() is AuthHttpException)
            check(fixture.auth.cachedAccount() == null && fixture.stored() == null && fixture.calls == 1)
        }
        case("Google: renovación conserva el token rotado al recrear el cliente") {
            val fixture = Fixture(context)
            fixture.signIn()
            fixture.time += 3601
            var rotations = 0
            fixture.route = { url ->
                if (url.path.endsWith("/token")) {
                    rotations++
                    val previous = if (rotations == 1) "refresh-fixture" else "refresh-rotated-1"
                    Response(url, 200, tokens("access-rotated-$rotations", "refresh-rotated-$rotations")) { _, body ->
                        check(url.query == "grant_type=refresh_token")
                        check(JSONObject(body).getString("refresh_token") == previous)
                    }
                } else Response(url, 200, user().toString()) { request, _ ->
                    check(request.requestMethod == "GET")
                    check(request.getRequestProperty("Authorization") == "Bearer access-rotated-$rotations")
                }
            }
            check(fixture.auth.verify()?.id == userId)
            fixture.time += 3601
            check(fixture.newClient().verify()?.id == userId)
            check(rotations == 2 && fixture.calls == 5)
        }
        case("Google: fallas transitorias no borran ni reemplazan la sesión") {
            val fixture = Fixture(context)
            fixture.signIn(); fixture.time += 3601
            val stored = fixture.stored()
            for (status in listOf(400, 403, 429, 503)) {
                fixture.route = { Response(it, status, "respuesta sin JSON") }
                check(runCatching { fixture.auth.verify() }.exceptionOrNull() is AuthHttpException)
                check(fixture.stored() == stored && fixture.auth.cachedAccount()?.id == userId)
            }
            fixture.route = { throw IOException("offline fixture") }
            check(runCatching { fixture.auth.verify() }.exceptionOrNull() is IOException)
            check(fixture.stored() == stored)
        }
        case("Google: refresh revocado elimina la sesión sin repetir el ingreso") {
            val fixture = Fixture(context)
            fixture.signIn(); fixture.time += 3601
            fixture.route = { Response(it, 400, "{\"error_code\":\"refresh_token_not_found\"}") }
            check(runCatching { fixture.auth.verify() }.exceptionOrNull() is AuthHttpException)
            check(fixture.auth.cachedAccount() == null && fixture.calls == 2)
            check(fixture.auth.verify() == null && fixture.calls == 2)
        }
        case("Google: 401 del usuario renueva una vez y descarta una sesión revocada") {
            val fixture = Fixture(context)
            fixture.signIn()
            fixture.route = { url ->
                if (url.path.endsWith("/token")) Response(url, 200, tokens("new-access", "new-refresh"))
                else Response(url, 401, "unauthorized")
            }
            check(runCatching { fixture.auth.verify() }.exceptionOrNull() is AuthHttpException)
            check(fixture.calls == 4 && fixture.stored() == null)
        }
        case("Google: cierre revoca solo esta sesión y conserva datos ante fallas de red") {
            val fixture = Fixture(context)
            fixture.signIn()
            val stored = fixture.stored()
            fixture.route = { throw IOException("offline fixture") }
            check(runCatching { fixture.auth.signOut() }.exceptionOrNull() is IOException)
            check(fixture.stored() == stored)
            fixture.route = { url -> Response(url, 204, "") { request, _ ->
                check(url.path == "/auth/v1/logout" && url.query == "scope=local")
                check(request.requestMethod == "POST")
                check(request.getRequestProperty("Authorization") == "Bearer access-fixture")
            } }
            fixture.auth.signOut()
            check(fixture.stored() == null && fixture.auth.cachedAccount() == null)
        }
        case("Google: otro proyecto requiere cerrar sesión; borrado local no usa red") {
            val fixture = Fixture(context)
            fixture.signIn()
            val other = AuthConfig.parse("https://other.fixture.invalid", config.publishableKey, config.webClientId)
            check(runCatching { fixture.auth.configure(other) }.isFailure)
            check(fixture.auth.config == config && fixture.auth.cachedAccount()?.id == userId)
            fixture.auth.forgetLocalSession()
            check(fixture.calls == 1 && fixture.stored() == null)
            fixture.auth.configure(other)
            check(fixture.auth.config == other)
        }
        case("Hogar: RPC usa sesión real y fija la identidad de instalación") {
            val fixture = Fixture(context); fixture.signIn()
            val client = HomeClient(context, fixture.auth)
            fixture.route = { url -> Response(url, 200, "{\"home\":null}") { request, body ->
                check(url.path == "/rest/v1/rpc/bsecurity_home")
                check(request.getRequestProperty("Authorization") == "Bearer access-fixture")
                val payload = JSONObject(body).getJSONObject("payload")
                check(payload.getString("device_id") == client.deviceId)
                check(payload.getString("action") == "status")
            } }
            client.request("status", JSONObject().put("device_id", "forged").put("action", "delete"))
            check(HomeClient(context, fixture.newClient()).deviceId == client.deviceId)
            fixture.auth.forgetLocalSession()
            val calls = fixture.calls
            check(runCatching { client.request("status") }.isFailure)
            check(fixture.calls == calls)
        }
        case("Hogar: RPC renueva el token y no repite errores de permisos") {
            val fixture = Fixture(context); fixture.signIn(); fixture.time += 3601
            fixture.route = { url ->
                if (url.path.endsWith("/token")) Response(url, 200, tokens("home-access", "home-refresh"))
                else Response(url, 400, "{\"message\":\"BSEC_ADMIN\"}") { request, _ ->
                    check(request.getRequestProperty("Authorization") == "Bearer home-access")
                }
            }
            val error = runCatching { HomeClient(context, fixture.auth).request("invite") }.exceptionOrNull()
            check(error is HomeHttpException && error.reason == "BSEC_ADMIN")
            check(fixture.calls == 3 && fixture.auth.hasStoredSession)
        }
        case("Hogar: errores del servidor no muestran datos internos") {
            val fixture = Fixture(context); fixture.signIn()
            fixture.route = { Response(it, 500, "{\"message\":\"private SQL and secret fixture\"}") }
            val error = runCatching { HomeClient(context, fixture.auth).request("status") }.exceptionOrNull()
            check(error is HomeHttpException && !error.message.orEmpty().contains("secret"))
            check(fixture.calls == 2 && fixture.auth.hasStoredSession)
        }
        case("Google: sesión cifrada queda vinculada al proyecto configurado") {
            val fixture = Fixture(context)
            fixture.signIn()
            context.getSharedPreferences("google_auth", 0).edit().putString("url", "https://different.fixture.invalid").commit()
            check(runCatching { fixture.newClient().cachedAccount() }.isFailure)
            check(fixture.stored() == null && fixture.calls == 1)
        }
    }

    private fun user() = JSONObject().put("id", userId).put("email", "fixture@example.com")
        .put("is_anonymous", false).put("user_metadata", JSONObject().put("full_name", "Cuenta de prueba").put("role", "admin"))
    private fun tokens(access: String = "access-fixture", refresh: String = "refresh-fixture") = JSONObject()
        .put("access_token", access).put("refresh_token", refresh).put("expires_in", 3600).put("user", user()).toString()

    private class Fixture(private val context: Context) {
        var time = 1000L
        var calls = 0
        var route: (URL) -> Response = { Response(it, 200, tokens()) }
        fun newClient() = SupabaseAuth(context, { calls++; route(it) }, { time })
        val auth = newClient().also { it.configure(config) }
        fun signIn() = auth.signIn("google-id-fixture", "raw-nonce-fixture")
        fun stored() = context.getSharedPreferences("google_auth", 0).getString("session", null)
    }
    private class Response(url: URL, private val status: Int, private val content: String,
        private val sent: (Response, String) -> Unit = { _, _ -> }) : HttpsURLConnection(url) {
        private val body = ByteArrayOutputStream()
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
        override fun getOutputStream() = body
        override fun getResponseCode(): Int { sent(this, body.toString("UTF-8")); return status }
        override fun getInputStream() = ByteArrayInputStream(content.toByteArray(Charsets.UTF_8))
        override fun getErrorStream() = inputStream
        override fun getCipherSuite() = "fixture"
        override fun getLocalCertificates(): Array<Certificate>? = null
        override fun getServerCertificates(): Array<Certificate> = emptyArray()
        override fun getPeerPrincipal(): Principal? = null
        override fun getLocalPrincipal(): Principal? = null
    }
}
