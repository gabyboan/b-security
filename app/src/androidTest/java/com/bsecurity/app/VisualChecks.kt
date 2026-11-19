package com.bsecurity.app

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.min

/** Optional real-activity tests. Only the local device diagnostic is started; Familia is not synchronized. */
internal object VisualChecks {
    fun run(instrumentation: Instrumentation, case: (String, () -> Unit) -> Unit) {
        val session = Session(instrumentation)
        try {
            case("Interfaz: inicio con marca y carga que termina") {
                session.start()
                session.checkLaunch()
            }
            case("Interfaz: las cinco pestañas muestran contenido y selección") {
                for (index in 0..4) session.navigate(index)
            }
            case("Interfaz: cambios rápidos no dejan páginas duplicadas ni invisibles") {
                session.changeQuickly()
            }
            case("Interfaz: desplazamiento conservado al navegar y recrear") {
                session.checkScrollAndRecreation()
            }
            case("Interfaz: volver desde Inicio reanuda sin repetir la carga") {
                session.checkBackgroundAndResume()
            }
            case("Interfaz: comprobación local muestra progreso, termina y permite actualizar") {
                session.checkDeviceProgress()
            }
        } finally {
            session.close()?.let { problem ->
                case("Interfaz: restauración de la prueba") { error(problem) }
            }
        }
    }

    private class Session(private val instrumentation: Instrumentation) {
        private val context = instrumentation.targetContext
        private val application = context.applicationContext as Application
        private val preferences = context.getSharedPreferences("interface", Context.MODE_PRIVATE)
        private var hadEntered = false
        private var originalEntered = false
        private var restoreEntered = false
        private var registered = false
        private var activity: MainActivity? = null
        private var foreground: MainActivity? = null
        private var creations = 0
        private var resumptions = 0
        private var stops = 0
        private var firstStartRecorded = false
        private var firstStartAnimated = false
        private var firstBrand: String? = null
        private var repeatedIntro = false
        private val navigationIds = intArrayOf(
            R.id.nav_status, R.id.nav_network, R.id.nav_gps, R.id.nav_control, R.id.nav_family,
        )
        private val headings = arrayOf(
            "Tu teléfono, a la vista", "Conexiones", "Señal GPS en vivo",
            "Control parental", "Cuidarse en familia",
        )

        private val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(created: Activity, state: Bundle?) {
                if (created is MainActivity) {
                    activity = created
                    creations++
                }
            }

            override fun onActivityStarted(started: Activity) {
                if (started !is MainActivity) return
                val splash = started.findViewById<View>(R.id.splash_screen)
                if (!firstStartRecorded) {
                    firstStartRecorded = true
                    firstStartAnimated = ValueAnimator.areAnimatorsEnabled()
                    firstBrand = started.findViewById<TextView>(R.id.splash_brand)?.text?.toString()
                } else if (splash != null) repeatedIntro = true
            }

            override fun onActivityResumed(resumed: Activity) {
                if (resumed is MainActivity) {
                    foreground = resumed
                    resumptions++
                }
            }

            override fun onActivityPaused(paused: Activity) {
                if (foreground === paused) foreground = null
            }

            override fun onActivityStopped(stopped: Activity) {
                if (stopped is MainActivity) stops++
            }
            override fun onActivitySaveInstanceState(saved: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(destroyed: Activity) {
                if (activity === destroyed) activity = null
                if (foreground === destroyed) foreground = null
            }
        }

        fun start() {
            hadEntered = preferences.contains("entered")
            originalEntered = if (hadEntered) preferences.getBoolean("entered", false) else false
            restoreEntered = true
            check(preferences.edit().putBoolean("entered", true).commit()) {
                "No se pudo preparar la entrada temporal a la app."
            }
            main {
                application.registerActivityLifecycleCallbacks(callbacks)
                registered = true
            }
            // MIUI can block starts from this background test process. Start through the
            // external driver and let checkLaunch enforce the existing bounded wait.
            launchFromShell("0x10008000")
        }

        fun checkLaunch() {
            await("La carga no dio paso a una pantalla usable.") { ready(0) }
            main {
                check(firstStartRecorded) { "No se observó el arranque real de la actividad." }
                check(!firstStartAnimated || firstBrand == "B-Software") {
                    "La carga animada no mostró la marca B-Software."
                }
                check(requireActivity().findViewById<View>(R.id.splash_screen) == null) {
                    "La carga sigue interceptando la pantalla."
                }
            }
        }

        fun navigate(index: Int) {
            main { click(index) }
            await("La pestaña ${headings[index]} no quedó visible y seleccionada.") { ready(index) }
        }

        fun changeQuickly() {
            main { listOf(0, 3, 1, 4, 2, 0, 4, 1, 3, 2).forEach(::click) }
            await("Los cambios rápidos dejaron una pantalla incompleta.") { ready(2) }
            // A further click checks that canceled animations did not disable navigation.
            navigate(0)
        }

        fun checkScrollAndRecreation() {
            navigate(3)
            val expected = main {
                val scroll = currentScroll()
                // A large display can show the whole page. Its valid retained position is zero.
                val range = (scroll.getChildAt(0).height + scroll.paddingTop + scroll.paddingBottom - scroll.height)
                    .coerceAtLeast(0)
                min(range, (120 * context.resources.displayMetrics.density).toInt()).also {
                    scroll.scrollTo(0, it)
                }
            }
            await("No se pudo desplazar el contenido de Control.") { abs(currentScroll().scrollY - expected) <= 1 }
            navigate(2)
            navigate(3)
            await("Se perdió el desplazamiento al volver a Control.") { abs(currentScroll().scrollY - expected) <= 1 }

            val previous = main { requireActivity() }
            val previousCreations = main { creations }
            main { previous.recreate() }
            await("La recreación no recuperó Control con su desplazamiento.") {
                activity !== previous && creations > previousCreations && ready(3) &&
                    abs(currentScroll().scrollY - expected) <= 1
            }
            main { check(!repeatedIntro) { "La recreación volvió a mostrar la carga inicial." } }
        }

        fun checkBackgroundAndResume() {
            navigate(2)
            val previous = main { requireActivity() }
            val previousResumptions = main { resumptions }
            val previousStops = main { stops }
            main {
                previous.startActivity(
                    Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            await("La app no pasó a segundo plano.") {
                stops > previousStops && foreground == null && !previous.hasWindowFocus()
            }
            // Return as a launcher would. A stopped app cannot launch itself on MIUI;
            // the test driver must perform this user action from outside the app.
            launchFromShell("0x30000000")
            await("La app no reanudó su pantalla anterior.") {
                resumptions > previousResumptions && ready(2)
            }
            main {
                check(!repeatedIntro && requireActivity().findViewById<View>(R.id.splash_screen) == null) {
                    "Volver desde Inicio repitió la carga."
                }
            }
        }

        fun checkDeviceProgress() {
            navigate(0)
            val initialProgress = main { beginDeviceCheck() }
            navigate(2)
            main {
                check(!initialProgress.isAttachedToWindow) {
                    "El progreso de seguridad siguió adjunto al salir de su pantalla."
                }
            }
            navigate(0)
            awaitDeviceResult()
            // Use the actual result button again: finishing must also restore a usable action.
            val refreshedProgress = main { beginDeviceCheck() }
            awaitDeviceResult()
            main {
                check(!refreshedProgress.isAttachedToWindow) {
                    "La actualización dejó una vista de progreso adjunta después del resultado."
                }
            }
        }

        /** The result callback cannot run in this UI turn, even on a very fast device. */
        private fun beginDeviceCheck(): View {
            val shown = requireActivity()
            currentScroll().scrollTo(0, 0)
            val button = shown.findViewById<View>(R.id.check_device_button)
            check(button != null && button.isShown && button.isEnabled && button.performClick()) {
                "La acción de comprobar seguridad no respondió."
            }
            val progress = descendants(shown.window.decorView)
                .filter { it.id == R.id.security_check_progress }.toList()
            check(progress.size == 1 && progress.single().isAttachedToWindow && progress.single().isShown) {
                "La comprobación no presentó una única vista de progreso visible."
            }
            val status = progress.single().findViewById<TextView>(R.id.security_check_status)
            check(status != null && status.isShown && status.text.isNotBlank()) {
                "El progreso no informó qué se está comprobando."
            }
            val count = progress.single().findViewById<TextView>(R.id.security_check_count)
            check(count != null && count.isShown && count.text.isNotBlank()) {
                "El progreso no presentó un estado legible de la lectura."
            }
            check(shown.findViewById<View>(R.id.check_device_button) == null) {
                "La comprobación permitió iniciar otra tarea mientras estaba en curso."
            }
            check(descendants(currentScroll()).none { it is TextView && it.text.toString() == "Parche de seguridad" }) {
                "La comprobación mezcló resultados anteriores con el progreso."
            }
            return progress.single()
        }

        private fun awaitDeviceResult() {
            await("La comprobación local no sustituyó el progreso por resultados utilizables.") {
                val shown = activity
                val button = shown?.findViewById<View>(R.id.check_device_button)
                ready(0) && shown?.findViewById<View>(R.id.security_check_progress) == null &&
                    button != null && button.isShown && button.isEnabled &&
                    descendants(currentScroll()).any { it is TextView && it.text.toString() == "Parche de seguridad" }
            }
        }

        private fun launchFromShell(flags: String) {
            val component = "${context.packageName}/${MainActivity::class.java.name}"
            ParcelFileDescriptor.AutoCloseInputStream(
                instrumentation.uiAutomation.executeShellCommand("am start -f $flags -n $component"),
            ).bufferedReader().use { response ->
                check(!response.readText().contains("Error:")) { "No se pudo abrir la app desde el lanzador." }
            }
        }

        /** Finish only this test's activity and restore only the preference that was changed. */
        fun close(): String? {
            val problems = mutableListOf<String>()
            runCatching {
                main { activity?.takeUnless { it.isFinishing || it.isDestroyed }?.finish() }
                instrumentation.waitForIdleSync()
            }.onFailure { problems += "No se pudo cerrar la actividad de prueba." }
            runCatching {
                main {
                    if (registered) application.unregisterActivityLifecycleCallbacks(callbacks)
                    registered = false
                }
            }.onFailure { problems += "No se pudo retirar el observador de la prueba." }
            if (restoreEntered) runCatching {
                val editor = preferences.edit()
                if (hadEntered) editor.putBoolean("entered", originalEntered) else editor.remove("entered")
                check(editor.commit())
                check(preferences.contains("entered") == hadEntered)
                check(!hadEntered || preferences.getBoolean("entered", !originalEntered) == originalEntered)
            }.onFailure { problems += "No se pudo restaurar interface.entered." }
            return problems.takeIf { it.isNotEmpty() }?.joinToString(" ")
        }

        private fun click(index: Int) {
            val target = requireActivity().findViewById<View>(navigationIds[index])
            check(target != null && target.isShown && target.isEnabled && target.performClick()) {
                "La navegación ${headings[index]} no respondió."
            }
        }

        /** Checks the user-visible final state rather than animator or activity implementation fields. */
        private fun ready(index: Int): Boolean {
            val shown = activity ?: return false
            if (shown.isDestroyed || shown.isFinishing || foreground !== shown || !shown.hasWindowFocus()) return false
            if (shown.findViewById<View>(R.id.splash_screen) != null) return false
            val content = descendants(shown.window.decorView).filter { it.id == R.id.screen_content }.toList()
            if (content.size != 1) return false
            val body = content.single()
            if (!body.isShown || body.width <= 0 || body.height <= 0 || abs(body.alpha - 1f) > .01f) return false
            val page = body.parent as? ScrollView ?: return false
            val host = page.parent as? ViewGroup ?: return false
            if (host.childCount != 1 || !page.isShown || page.width <= 0 || page.height <= 0) return false
            if (abs(page.alpha - 1f) > .01f || abs(page.translationX) > .5f || abs(page.translationY) > .5f) return false
            if (body is ViewGroup && (0 until body.childCount).any { item ->
                    val child = body.getChildAt(item)
                    abs(child.translationX) > .5f || abs(child.translationY) > .5f
                }) return false
            if (descendants(body).none { it is TextView && it.text.toString() == headings[index] }) return false
            return navigationIds.indices.all { item ->
                val view = shown.findViewById<View>(navigationIds[item])
                view != null && view.isShown && view.isSelected == (item == index)
            }
        }

        private fun currentScroll(): ScrollView {
            val contents = descendants(requireActivity().window.decorView).filter { it.id == R.id.screen_content }.toList()
            check(contents.size == 1) { "Se esperaba una sola página de contenido." }
            return contents.single().parent as? ScrollView ?: error("El contenido no se puede desplazar.")
        }

        private fun requireActivity() = activity?.takeUnless { it.isDestroyed || it.isFinishing }
            ?: error("La actividad de prueba no está disponible.")

        private fun descendants(view: View): Sequence<View> = sequence {
            yield(view)
            if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
        }

        private fun await(message: String, condition: () -> Boolean) {
            val deadline = SystemClock.uptimeMillis() + 8_000
            while (SystemClock.uptimeMillis() < deadline) {
                if (main(condition)) return
                SystemClock.sleep(25)
            }
            check(main(condition)) { message }
        }

        private fun <T> main(block: () -> T): T {
            var result: Result<T>? = null
            instrumentation.runOnMainSync { result = runCatching(block) }
            return checkNotNull(result).getOrThrow()
        }
    }
}
