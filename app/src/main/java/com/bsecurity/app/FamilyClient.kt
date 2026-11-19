package com.bsecurity.app

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.HttpsURLConnection

class FamilyHttpException(val status: Int, message: String) : IllegalStateException(message)

class FamilyClient internal constructor(
    private val context: Context,
    private val connect: (URL) -> HttpsURLConnection
) {
    constructor(context: Context) : this(context, { it.openConnection() as HttpsURLConnection })
    private val prefs = context.getSharedPreferences("family", Context.MODE_PRIVATE)
    val endpoint get() = prefs.getString("endpoint", context.getString(R.string.family_endpoint)).orEmpty()
    val configured get() = endpoint.isNotEmpty()
    var shareLocation: Boolean
        get() = prefs.getBoolean("shareLocation", false)
        set(value) { prefs.edit().putBoolean("shareLocation", value).apply() }
    var acceptControls: Boolean
        get() = prefs.getBoolean("acceptControls", false)
        set(value) {
            val previous = acceptControls
            val edit = prefs.edit().putBoolean("acceptControls", value)
            if (previous && !value) edit.putBoolean("controlsRevocationPending", true)
            edit.apply()
            // Re-acceptance must wait for fresh rules, even after an offline revocation.
            if (!value || !previous) PolicyStore(context).setRemoteRules(JSONObject())
        }
    internal val controlsRevocationPending get() = prefs.getBoolean("controlsRevocationPending", false)

    internal fun clearMembership() {
        prefs.edit().putBoolean("shareLocation", false).putBoolean("acceptControls", false)
            .remove("controlsRevocationPending").apply()
        PolicyStore(context).setRemoteRules(JSONObject())
    }

    internal fun flushControlsRevocation() {
        if (!controlsRevocationPending) return
        request("/v1/report", JSONObject().put("consent", true).put("controls", false))
        // Keep the pending flag on any error so a later synchronization retries it first.
        prefs.edit().remove("controlsRevocationPending").apply()
    }
    fun configure(value: String) {
        val normalized = FamilyEndpoint.normalize(value)
        if (normalized != endpoint) {
            prefs.edit().clear().putString("endpoint", normalized).apply()
            PolicyStore(context).setRemoteRules(JSONObject())
        }
    }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val alias = "bsecurity.family.v1"
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    private fun saveToken(value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val bytes = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString("session", Base64.encodeToString(cipher.iv + bytes, Base64.NO_WRAP)).commit()
    }
    private fun token(): String? {
        val value = prefs.getString("session", null) ?: return null
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        }
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }
    @Synchronized
    fun request(path: String, body: JSONObject? = null): JSONObject {
        check(configured) { "Configurá primero el servidor HTTPS de Familia." }
        var session = token()
        if (session == null) {
            val created = call("/v1/session", JSONObject().put("name", "${Build.MANUFACTURER} ${Build.MODEL}"), null)
            session = created.getString("token")
            saveToken(session)
        }
        return try {
            call(path, body, session)
        } catch (error: FamilyHttpException) {
            if (error.status != 401) throw error
            prefs.edit().remove("session").apply()
            clearMembership()
            // Never replay a mutation under a newly created identity.
            throw FamilyHttpException(401, "La sesión de Familia venció. Actualizá para crear una nueva identidad y volver a vincularte.")
        }
    }
    private fun call(path: String, body: JSONObject?, token: String?): JSONObject {
        val connection = connect(URL(endpoint + path))
        return try {
            connection.connectTimeout = 8000; connection.readTimeout = 10000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", "application/json")
            token?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
            if (body != null) {
                connection.requestMethod = "POST"; connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { it.readBytesLimited(256 * 1024) } ?: byteArrayOf()
            val content = String(bytes, Charsets.UTF_8).ifEmpty { "{}" }
            if (status !in 200..299) {
                val message = runCatching { JSONObject(content).optString("error") }.getOrNull()
                throw FamilyHttpException(status, message?.takeIf { it.isNotBlank() } ?: "Servidor respondió HTTP $status")
            }
            JSONObject(content)
        } finally { connection.disconnect() }
    }
    private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val count = read(buffer); if (count < 0) break
            check(out.size() + count <= limit) { "Respuesta del servidor demasiado grande." }
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }
}
