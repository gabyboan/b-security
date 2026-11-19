package com.bsecurity.app

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.URL
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.HttpsURLConnection

data class GoogleAccount(val id: String, val email: String, val name: String)

class AuthHttpException(val status: Int, val code: String) : IllegalStateException(when {
    code == "provider_disabled" || code == "validation_failed" -> "Revisá el proveedor Google y sus clientes OAuth en Supabase."
    status == 429 -> "Demasiados intentos. Esperá un momento y volvé a probar."
    status >= 500 -> "Supabase no está disponible en este momento. Intentá nuevamente."
    status == 401 || code.startsWith("refresh_token") || code.startsWith("session_") -> "La sesión venció o fue revocada. Volvé a ingresar con Google."
    else -> "No se pudo completar el ingreso (HTTP $status). Revisá la configuración de Google en Supabase."
})

/** Native Supabase Auth REST API. All network methods run on the activity's worker, never the UI. */
class SupabaseAuth internal constructor(
    private val context: Context,
    private val connect: (URL) -> HttpsURLConnection,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 }
) {
    constructor(context: Context) : this(context, { it.openConnection() as HttpsURLConnection })
    private val prefs = context.getSharedPreferences("google_auth", Context.MODE_PRIVATE)
    // Serializes refresh-token rotation across instances, including a recreated Activity.
    companion object { private val lock = Any() }
    val hasStoredSession: Boolean get() = prefs.contains("session")
    val config: AuthConfig? get() = runCatching {
        AuthConfig.parse(
            prefs.getString("url", context.getString(R.string.supabase_url)).orEmpty(),
            prefs.getString("key", context.getString(R.string.supabase_publishable_key)).orEmpty(),
            prefs.getString("client", context.getString(R.string.google_web_client_id)).orEmpty()
        )
    }.getOrNull()

    fun configure(value: AuthConfig) = synchronized(lock) {
        check(!prefs.contains("session")) { "Cerrá la sesión antes de cambiar de proyecto." }
        check(prefs.edit().putString("url", value.url).putString("key", value.publishableKey)
            .putString("client", value.webClientId).commit()) { "No se pudo guardar la configuración." }
    }

    fun cachedAccount(): GoogleAccount? = synchronized(lock) { session()?.account }

    fun signIn(idToken: String, rawNonce: String): GoogleAccount = synchronized(lock) {
        check(!prefs.contains("session")) { "Ya hay una sesión guardada. Cerrala antes de cambiar de cuenta." }
        require(idToken.isNotBlank() && rawNonce.isNotBlank()) { "No se recibió una credencial de Google válida." }
        val response = call("/token?grant_type=id_token", "POST", JSONObject()
            .put("provider", "google").put("id_token", idToken).put("nonce", rawNonce))
        val created = parseSession(response)
        save(created)
        created.account
    }

    /** A cached profile alone is never treated as a freshly verified identity. */
    fun verify(): GoogleAccount? = synchronized(lock) {
        var current = session() ?: return@synchronized null
        if (current.expiresAt <= nowSeconds() + 60) current = refresh(current)
        val user = try {
            call("/user", "GET", token = current.accessToken)
        } catch (error: AuthHttpException) {
            if (error.status != 401) throw error
            current = refresh(current)
            try { call("/user", "GET", token = current.accessToken) }
            catch (retry: AuthHttpException) {
                if (retry.status == 401) clear()
                throw retry
            }
        }
        val account = parseAccount(user)
        if (account.id != current.account.id) {
            clear()
            error("La identidad de la sesión cambió. Volvé a ingresar con Google.")
        }
        save(current.copy(account = account))
        account
    }

    /** Revoke this Supabase session, preserving sessions on the user's other phones. */
    fun signOut() = synchronized(lock) {
        var current = session() ?: return@synchronized
        if (current.expiresAt <= nowSeconds() + 60) current = refresh(current)
        try { call("/logout?scope=local", "POST", token = current.accessToken) }
        catch (error: AuthHttpException) {
            if (error.status != 401) throw error
            current = refresh(current)
            call("/logout?scope=local", "POST", token = current.accessToken)
        }
        clear()
    }

    /** Explicit offline option; the caller explains that server revocation has not happened. */
    fun forgetLocalSession() = synchronized(lock) { clear() }

    /** Keep tokens and refresh rotation inside Auth, including foreground-service calls. */
    internal fun homeRequest(payload: JSONObject, expectedAccount: String): JSONObject = synchronized(lock) {
        var current = session() ?: error("Ingresá con Google para usar tu hogar.")
        check(current.account.id == expectedAccount) { "La cuenta cambió. Volvé a abrir Familia." }
        if (current.expiresAt <= nowSeconds() + 60) current = refresh(current)
        try {
            call("/rpc/bsecurity_home", "POST", JSONObject().put("payload", payload), current.accessToken, home = true)
        } catch (error: HomeHttpException) {
            if (error.status != 401) throw error
            current = refresh(current)
            call("/rpc/bsecurity_home", "POST", JSONObject().put("payload", payload), current.accessToken, home = true)
        }
    }

    private data class Session(val accessToken: String, val refreshToken: String, val expiresAt: Long, val account: GoogleAccount)
    private fun refresh(previous: Session): Session {
        val response = try {
            call("/token?grant_type=refresh_token", "POST", JSONObject().put("refresh_token", previous.refreshToken))
        } catch (error: AuthHttpException) {
            val revoked = setOf("refresh_token_not_found", "refresh_token_already_used", "session_not_found", "session_expired", "invalid_grant")
            if (error.status == 401 || (error.status == 400 && error.code in revoked)) clear()
            throw error
        }
        val updated = parseSession(response)
        if (updated.account.id != previous.account.id) {
            clear(); error("La identidad de la sesión cambió. Volvé a ingresar con Google.")
        }
        save(updated)
        return updated
    }
    private fun parseAccount(user: JSONObject): GoogleAccount {
        val id = user.getString("id")
        require(UUID.fromString(id).toString() == id) { "Supabase devolvió una identidad inválida." }
        check(!user.optBoolean("is_anonymous", false)) { "Se requiere una cuenta de Google." }
        val email = user.getString("email")
        require(email.isNotBlank()) { "La cuenta no tiene un correo disponible." }
        // Display only. User-editable metadata must never determine an admin role.
        val name = user.optJSONObject("user_metadata")?.optString("full_name").orEmpty().take(160)
        return GoogleAccount(id, email.take(320), name)
    }
    private fun parseSession(response: JSONObject): Session {
        val access = response.getString("access_token")
        val refresh = response.getString("refresh_token")
        val lifetime = response.getLong("expires_in")
        require(access.isNotBlank() && refresh.isNotBlank() && lifetime in 1..604800) { "Supabase devolvió una sesión incompleta." }
        return Session(access, refresh, nowSeconds() + lifetime, parseAccount(response.getJSONObject("user")))
    }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = "bsecurity.supabase.auth.v1"
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun aad() = requireNotNull(config) { "Configurá Google y Supabase primero." }
        .let { "${it.url}\n${it.publishableKey}\n${it.webClientId}".toByteArray(Charsets.UTF_8) }
    private fun save(session: Session) {
        val json = JSONObject().put("access", session.accessToken).put("refresh", session.refreshToken)
            .put("expires", session.expiresAt).put("id", session.account.id).put("email", session.account.email).put("name", session.account.name)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()); updateAAD(aad()) }
        val encrypted = cipher.iv + cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString("session", Base64.encodeToString(encrypted, Base64.NO_WRAP)).commit()) { "No se pudo guardar la sesión." }
    }
    private fun session(): Session? {
        val stored = prefs.getString("session", null) ?: return null
        return try {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            require(bytes.size > 28)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12))); updateAAD(aad())
            }
            val json = JSONObject(String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8))
            Session(json.getString("access"), json.getString("refresh"), json.getLong("expires"),
                GoogleAccount(json.getString("id"), json.getString("email"), json.getString("name")))
        } catch (_: Exception) {
            clear()
            error("No se pudo recuperar la sesión guardada. Volvé a ingresar con Google.")
        }
    }
    private fun clear() { check(prefs.edit().remove("session").commit()) { "No se pudo quitar la sesión local." } }

    private fun call(path: String, method: String, body: JSONObject? = null, token: String? = null, home: Boolean = false): JSONObject {
        val config = requireNotNull(config) { "Configurá Google y Supabase primero." }
        val connection = connect(URL(config.url + (if (home) "/rest/v1" else "/auth/v1") + path))
        return try {
            connection.connectTimeout = 8000; connection.readTimeout = 10000
            connection.instanceFollowRedirects = false; connection.requestMethod = method
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("apikey", config.publishableKey)
            token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { input ->
                val result = ByteArrayOutputStream(); val buffer = ByteArray(4096)
                while (true) {
                    val count = input.read(buffer); if (count < 0) break
                    check(result.size() + count <= 256 * 1024) { "Respuesta de autenticación demasiado grande." }
                    result.write(buffer, 0, count)
                }
                result.toByteArray()
            } ?: byteArrayOf()
            val json = runCatching { JSONObject(String(bytes, Charsets.UTF_8)) }.getOrNull()
            if (status !in 200..299 && home) throw HomeHttpException(status, json?.optString("message").orEmpty())
            if (status !in 200..299) throw AuthHttpException(status, json?.optString("error_code")
                ?.takeIf { it.isNotBlank() } ?: json?.optString("error").orEmpty())
            if (status == 204) JSONObject() else checkNotNull(json) { "Supabase devolvió una respuesta inválida." }
        } finally { connection.disconnect() }
    }
}
