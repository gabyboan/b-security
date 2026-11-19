package com.bsecurity.app

import android.app.Activity
import android.os.CancellationSignal
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CredentialManagerCallback
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import androidx.credentials.exceptions.ClearCredentialException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executor

/** Google shows its own account chooser; the app never asks for a Google password. */
class GoogleSignInFlow(private val activity: Activity) {
    private val manager = CredentialManager.create(activity)
    private val main = Executor { command -> activity.runOnUiThread(command) }
    private var pending: CancellationSignal? = null

    fun start(clientId: String, success: (String, String) -> Unit, failure: (String) -> Unit) {
        cancel()
        val rawNonce = UUID.randomUUID().toString()
        val hashedNonce = MessageDigest.getInstance("SHA-256").digest(rawNonce.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        val option = GetSignInWithGoogleOption.Builder(clientId).setNonce(hashedNonce).build()
        val request = GetCredentialRequest.Builder().addCredentialOption(option).build()
        val signal = CancellationSignal().also { pending = it }
        manager.getCredentialAsync(activity, request, signal, main,
            object : CredentialManagerCallback<GetCredentialResponse, GetCredentialException> {
                override fun onResult(result: GetCredentialResponse) {
                    if (pending !== signal || activity.isDestroyed) return
                    pending = null
                    val token = runCatching {
                        val credential = result.credential
                        check(credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL)
                        GoogleIdTokenCredential.createFrom(credential.data).idToken
                    }.getOrNull()
                    if (token.isNullOrBlank()) failure("Google no devolvió una credencial válida. Volvé a intentar.")
                    else success(token, rawNonce)
                }
                override fun onError(e: GetCredentialException) {
                    if (pending !== signal || activity.isDestroyed) return
                    pending = null
                    failure(when (e) {
                        is GetCredentialCancellationException -> "Ingreso cancelado."
                        is NoCredentialException -> "Agregá una cuenta de Google al teléfono y revisá los clientes OAuth."
                        is GetCredentialProviderConfigurationException -> "Este teléfono necesita Google Play Services actualizado para ingresar con Google."
                        else -> "No se pudo ingresar con Google. Revisá la conexión y la configuración OAuth del paquete y su firma."
                    })
                }
            })
    }

    fun clearState(done: () -> Unit) {
        manager.clearCredentialStateAsync(ClearCredentialStateRequest(), null, main,
            object : CredentialManagerCallback<Void?, ClearCredentialException> {
                override fun onResult(result: Void?) { done() }
                override fun onError(e: ClearCredentialException) { done() }
            })
    }
    fun cancel() { val signal = pending; pending = null; signal?.cancel() }
}
