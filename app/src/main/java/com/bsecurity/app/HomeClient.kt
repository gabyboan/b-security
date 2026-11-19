package com.bsecurity.app

import android.content.Context
import android.os.Build
import org.json.JSONObject
import java.util.UUID

class HomeHttpException(val status: Int, val reason: String) : IllegalStateException(when (reason) {
    "BSEC_AUTH" -> "La sesión fue revocada. Volvé a ingresar con Google."
    "BSEC_ADMIN" -> "Solo el administrador del hogar puede hacer esto."
    "BSEC_INVITE" -> "El código no es válido, ya se usó o venció. Pedí una nueva invitación."
    "BSEC_ALREADY_JOINED" -> "Este teléfono ya pertenece a un hogar. Salí primero para cambiarlo."
    "BSEC_HOME" -> "Este teléfono ya no pertenece al hogar."
    "BSEC_CONSENT" -> "Activá compartir ubicación desde este teléfono."
    "BSEC_DEVICE" -> "El vínculo de este teléfono ya no es válido. Actualizá el hogar."
    "BSEC_LIMIT" -> "Se alcanzó el límite de dispositivos o invitaciones."
    "BSEC_RETRY" -> "El hogar cambió. Actualizá y volvé a intentar."
    "BSEC_INPUT" -> "Revisá el código o los datos enviados."
    else -> when {
        status == 401 -> "La sesión venció. Volvé a ingresar con Google."
        status == 404 -> "El servicio de hogar todavía no está configurado en Supabase."
        status == 429 -> "Esperá un momento antes de volver a intentar."
        else -> "No se pudo conectar con el hogar (HTTP $status). Intentá nuevamente."
    }
})

class HomeClient(context: Context, private val auth: SupabaseAuth = SupabaseAuth(context)) {
    private val prefs = context.getSharedPreferences("cloud_home", Context.MODE_PRIVATE)
    val accountId = requireNotNull(auth.cachedAccount()) { "Ingresá con Google para usar tu hogar." }.id
    val deviceId: String
    init {
        synchronized(identityLock) {
            if (prefs.getString("account_id", null) != accountId || prefs.getString("device_id", null) == null) {
                check(prefs.edit().putString("account_id", accountId).putString("device_id", UUID.randomUUID().toString()).commit())
            }
            deviceId = requireNotNull(prefs.getString("device_id", null))
        }
    }
    fun request(action: String, fields: JSONObject = JSONObject()): JSONObject {
        // Copy first so callers cannot replace the identity/action via arbitrary fields.
        val payload = JSONObject(fields.toString()).put("action", action).put("device_id", deviceId)
            .put("device_name", "${Build.MANUFACTURER} ${Build.MODEL}".take(100))
        return auth.homeRequest(payload, accountId)
    }
    companion object { private val identityLock = Any() }
}
