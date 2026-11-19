package com.bsecurity.app

import android.app.Activity
import android.app.Application
import android.app.Instrumentation
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import org.json.JSONObject
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.style.sources.GeoJsonSource

/** Explicit opt-in: downloads public map tiles and reads the signed-in user's home. Never enables GPS. */
internal object HomeMapChecks {
    fun run(instrumentation: Instrumentation, case: (String, () -> Unit) -> Unit) {
        val application = instrumentation.targetContext.applicationContext as Application
        var activity: HomeMapActivity? = null
        var map: MapLibreMap? = null
        fun <T> main(block: () -> T): T {
            var result: Result<T>? = null
            instrumentation.runOnMainSync { result = runCatching(block) }
            return checkNotNull(result).getOrThrow()
        }
        fun await(message: String, condition: () -> Boolean) {
            val until = SystemClock.uptimeMillis() + 40_000
            while (SystemClock.uptimeMillis() < until) {
                if (main(condition)) return
                SystemClock.sleep(100)
            }
            check(main(condition)) { message }
        }
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(created: Activity, state: Bundle?) {
                if (created is HomeMapActivity) {
                    activity = created; map = null
                }
            }
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(resumed: Activity) {
                if (resumed is HomeMapActivity) resumed.findViewById<MapView>(R.id.home_map_view).getMapAsync { map = it }
            }
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        }
        main { application.registerActivityLifecycleCallbacks(callbacks) }
        try {
            case("Mapa: datos inválidos, vencidos o sin consentimiento no generan posiciones") {
                val response = JSONObject("""{"home":{"name":"Fixture","devices":[
                    {"id":"off","name":"Off","sharing":false,"location":{"lat":-34.6,"lon":-58.38,"accuracy":10,"at":"2026-11-19T00:00:00Z","valid_for_seconds":60}},
                    {"id":"expired","name":"Expired","sharing":true,"location":{"lat":-34.6,"lon":-58.38,"accuracy":10,"at":"2026-11-19T00:00:00Z","valid_for_seconds":0}},
                    {"id":"bad","name":"Bad","sharing":true,"location":{"lat":91,"lon":-58.38,"accuracy":10,"at":"2026-11-19T00:00:00Z","valid_for_seconds":60}},
                    {"id":"valid","name":"Valid","sharing":true,"location":{"lat":-34.6,"lon":-58.38,"accuracy":10,"at":"2026-11-19T00:00:00Z","valid_for_seconds":60}}
                ]}}""")
                val state = requireNotNull(HomeMapState.parse(response, 1_000))
                check(state.devices.filter { it.visibleFix(2_000) != null }.map { it.id } == listOf("valid"))
                check(state.devices.none { it.visibleFix(61_000) != null })
                check(HomeMapState.parse(JSONObject("{\"home\":null}"), 1_000) == null)
            }
            case("Mapa: carga nativa de OpenFreeMap, atribución y control de seguimiento") {
                val component = "${instrumentation.targetContext.packageName}/${MainActivity::class.java.name}"
                ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand("am start -n $component"))
                    .bufferedReader().use { it.readText() }
                instrumentation.waitForIdleSync()
                instrumentation.startActivitySync(Intent(instrumentation.targetContext, HomeMapActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                await("No se cargó el mapa vectorial nativo.") { activity?.hasWindowFocus() == true && map?.style?.isFullyLoaded == true && map?.style?.getSource("home-points") != null }
                main {
                    val shown = requireNotNull(activity)
                    check(map!!.style!!.getSourceAs<GeoJsonSource>("home-points")!!.uri == null)
                    check(descendants(shown.window.decorView).filterIsInstance<TextView>().any { it.text.contains("OpenMapTiles") && it.text.contains("OpenStreetMap") })
                    val follow = shown.findViewById<Button>(R.id.home_map_follow)
                    check(follow.text.contains("✓")); follow.performClick()
                    check(follow.text.toString() == "Centrar y seguir"); follow.performClick()
                    check(follow.text.contains("✓"))
                    val locate = shown.findViewById<View>(R.id.home_map_my_location)
                    check(locate.isShown && locate.contentDescription.toString().contains("ubicación GPS"))
                }
            }
            case("Mapa: recreación conserva controles y vuelve a cargar la fuente local") {
                val previous = main { requireNotNull(activity) }
                main { previous.recreate() }
                await("La recreación dejó el mapa incompleto.") {
                    activity !== previous && activity?.hasWindowFocus() == true && map?.style?.isFullyLoaded == true && map?.style?.getSource("home-points") != null
                }
                main { check(requireNotNull(activity).findViewById<Button>(R.id.home_map_follow).text.contains("✓")) }
            }
            case("Mapa: en horizontal conserva un mapa visible y vuelve a vertical") {
                main { requireNotNull(activity).requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
                await("El mapa horizontal no tiene espacio visible.") {
                    val shown = activity
                    shown != null && shown.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
                        shown.hasWindowFocus() && map?.style?.isFullyLoaded == true &&
                        shown.findViewById<MapView>(R.id.home_map_view).height >= 100 * shown.resources.displayMetrics.density
                }
                main { requireNotNull(activity).requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT }
                await("El mapa no volvió a vertical.") {
                    activity?.resources?.configuration?.orientation == Configuration.ORIENTATION_PORTRAIT &&
                        activity?.hasWindowFocus() == true && map?.style?.isFullyLoaded == true
                }
            }
        } finally {
            main {
                activity?.takeUnless { it.isFinishing || it.isDestroyed }?.finish()
                application.unregisterActivityLifecycleCallbacks(callbacks)
            }
            instrumentation.waitForIdleSync()
        }
    }
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
}
