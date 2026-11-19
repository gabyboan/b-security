package com.bsecurity.app

import android.Manifest
import android.app.AlertDialog
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Html
import android.text.method.LinkMovementMethod
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** Native map. Coordinates come only from the authenticated, session-checked home RPC. */
class HomeMapActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var mapView: MapView
    private lateinit var statusView: TextView
    private lateinit var detailView: TextView
    private lateinit var mapNotice: TextView
    private lateinit var deviceButtons: LinearLayout
    private lateinit var followButton: Button
    private var map: MapLibreMap? = null
    private var pointsSource: GeoJsonSource? = null
    private var selectedSource: GeoJsonSource? = null
    private var client: HomeClient? = null // Accessed only by worker.
    private var state: HomeMapState? = null
    private var selectedId: String? = null
    private var focusSelfOnNextRefresh = false
    private var follow = true
    private var visible = false
    private var busy = false
    private var terminalError = false
    private var generation = 0
    private var nextRequestAt = 0L
    private var lastSuccessAt = 0L
    private var message = "Conectando con tu hogar…"
    private var lastFeatures = ""
    private var lastSelection = ""
    private var lastCameraTarget: LatLng? = null
    private var buttonsKey = ""
    private var styleLoaded = false
    private var cameraRestored = false
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val tick = object : Runnable {
        override fun run() {
            if (!visible) return
            val now = SystemClock.elapsedRealtime()
            if (!terminalError && now >= nextRequestAt && !busy) refresh()
            updateDisplay(now)
            handler.postDelayed(this, 1_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MapLibre.getInstance(this)
        selectedId = savedInstanceState?.getString("selected") ?: intent.getStringExtra(DEVICE)
        follow = savedInstanceState?.getBoolean("follow") ?: true
        cameraRestored = savedInstanceState != null
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(244, 244, 242))
        }
        if (Build.VERSION.SDK_INT >= 35) root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        val heading = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(6), dp(12), 0) }
        heading.addView(control("Volver") { finish() })
        heading.addView(label("Mapa en vivo", 21f).apply { setTypeface(null, Typeface.BOLD) }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(heading)
        statusView = label(message, 13f).apply { id = R.id.home_map_status; setPadding(dp(16), dp(4), dp(16), dp(8)) }
        root.addView(statusView)
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val content = LinearLayout(this).apply { orientation = if (landscape) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL }
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        val mapHost = FrameLayout(this)
        mapView = MapView(this).apply { id = R.id.home_map_view }
        mapView.onCreate(savedInstanceState)
        mapHost.addView(mapView, FrameLayout.LayoutParams(-1, -1))
        val myLocationButton = android.widget.ImageButton(this).apply {
            id = R.id.home_map_my_location
            setImageResource(R.drawable.ic_my_location)
            setColorFilter(Color.rgb(22, 119, 200))
            contentDescription = "Compartir o centrar la ubicación GPS de este teléfono en el hogar"
            isFocusable = true
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = dp(28).toFloat()
            }
            setPadding(dp(12), dp(12), dp(12), dp(12))
            elevation = dp(4).toFloat()
            setOnClickListener { shareOrCenterThisPhone() }
        }
        mapHost.addView(myLocationButton, FrameLayout.LayoutParams(dp(52), dp(52), Gravity.END or Gravity.BOTTOM).apply {
            marginEnd = dp(16)
            bottomMargin = dp(16)
        })
        mapNotice = label("Cargando calles…", 13f).apply {
            gravity = Gravity.CENTER; setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(Color.rgb(255, 250, 228))
            setOnClickListener { loadStyle() }
        }
        mapHost.addView(mapNotice, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))
        content.addView(mapHost, if (landscape) LinearLayout.LayoutParams(0, -1, 1f) else LinearLayout.LayoutParams(-1, 0, 1f))
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (landscape) content.addView(ScrollView(this).apply { addView(panel) }, LinearLayout.LayoutParams(dp(280), -1))
        else content.addView(panel, LinearLayout.LayoutParams(-1, -2))
        deviceButtons = LinearLayout(this).apply { setPadding(dp(8), dp(4), dp(8), 0) }
        panel.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(deviceButtons) })
        detailView = label("Elegí un teléfono o tocá el botón GPS para compartir tu ubicación con el hogar.", 14f)
            .apply { setPadding(dp(16), dp(6), dp(16), dp(4)); minLines = 2; maxLines = 3 }
        panel.addView(detailView)
        val controls = LinearLayout(this).apply { setPadding(dp(8), 0, dp(8), 0) }
        followButton = control("Seguir teléfono ✓") {
            follow = !follow; lastCameraTarget = null
            updateDisplay(SystemClock.elapsedRealtime())
        }.apply { id = R.id.home_map_follow }
        controls.addView(followButton, LinearLayout.LayoutParams(0, -2, 1f))
        controls.addView(control("Actualizar") { terminalError = false; nextRequestAt = 0; if (!busy) refresh() }, LinearLayout.LayoutParams(0, -2, 1f))
        panel.addView(controls)
        panel.addView(label("Solo posiciones compartidas. Actualización aproximada: 5 s.", 11f).apply {
            gravity = Gravity.CENTER; setPadding(dp(8), 0, dp(8), dp(2))
        })
        panel.addView(label("", 10f).apply {
            gravity = Gravity.CENTER; setPadding(dp(8), 0, dp(8), dp(6))
            text = Html.fromHtml("<a href='https://openfreemap.org/'>OpenFreeMap</a> · © <a href='https://openmaptiles.org/'>OpenMapTiles</a> · © <a href='https://www.openstreetmap.org/copyright'>OpenStreetMap</a><br>El proveedor recibe tu IP y las zonas que cargás.", Html.FROM_HTML_MODE_LEGACY)
            movementMethod = LinkMovementMethod.getInstance()
        })
        setContentView(root)
        mapView.addOnDidFailLoadingMapListener {
            if (!isDestroyed) {
                styleLoaded = false
                mapNotice.text = "No se pudo cargar el mapa. Revisá Internet y tocá aquí para reintentar."
                mapNotice.visibility = View.VISIBLE
            }
        }
        mapView.getMapAsync { ready ->
            if (isDestroyed) return@getMapAsync
            map = ready
            if (!cameraRestored) ready.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(-38.4, -63.6), 3.5))
            ready.addOnCameraMoveStartedListener { reason ->
                if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) { follow = false; updateFollowButton() }
            }
            loadStyle()
        }
    }

    private fun loadStyle() {
        val ready = map ?: return
        if (isDestroyed) return
        styleLoaded = false; pointsSource = null; selectedSource = null
        lastFeatures = ""; lastSelection = ""
        mapNotice.text = "Cargando calles…"; mapNotice.visibility = View.VISIBLE
        ready.setStyle(Style.Builder().fromUri(STYLE)) { style ->
            if (isDestroyed) return@setStyle
            pointsSource = GeoJsonSource(POINTS, emptyFeatures()).also { style.addSource(it) }
            selectedSource = GeoJsonSource(SELECTED, emptyFeatures()).also { style.addSource(it) }
            style.addLayer(CircleLayer("home-halo", SELECTED).withProperties(
                circleRadius(20f), circleColor("#1677C8"), circleOpacity(0.20f)))
            style.addLayer(CircleLayer("home-dots", POINTS).withProperties(
                circleRadius(8f), circleColor("#15202B"), circleStrokeColor("#FFFFFF"), circleStrokeWidth(3f)))
            style.addLayer(CircleLayer("home-chosen", SELECTED).withProperties(
                circleRadius(10f), circleColor("#1677C8"), circleStrokeColor("#FFFFFF"), circleStrokeWidth(3f)))
            style.addLayer(SymbolLayer("home-labels", POINTS).withProperties(
                textField(Expression.get("name")), textSize(12f), textFont(arrayOf("Noto Sans Regular")),
                textOffset(arrayOf(0f, 1.8f)), textColor("#15202B"), textHaloColor("#FFFFFF"), textHaloWidth(2f)))
            styleLoaded = true; mapNotice.visibility = View.GONE
            updateDisplay(SystemClock.elapsedRealtime())
        }
        handler.postDelayed({
            if (!styleLoaded && !isDestroyed) {
                mapNotice.text = "El mapa está demorando. Revisá Internet o tocá aquí para reintentar."
                mapNotice.visibility = View.VISIBLE
            }
        }, 20_000)
    }

    private fun refresh() {
        if (!visible || busy || isDestroyed) return
        busy = true
        nextRequestAt = SystemClock.elapsedRealtime() + 5_000
        val requestedAt = SystemClock.elapsedRealtime()
        val requestGeneration = generation
        worker.execute {
            val result = runCatching {
                val current = client ?: HomeClient(this).also { client = it }
                HomeMapState.parse(current.request("status"), requestedAt)
            }
            handler.post {
                if (isDestroyed) return@post
                busy = false
                if (!visible || generation != requestGeneration) return@post
                result.onSuccess { response ->
                    state = response
                    lastSuccessAt = SystemClock.elapsedRealtime()
                    if (response == null) {
                        terminalError = true; message = "Este teléfono ya no está vinculado. Volvé a Familia."
                    } else {
                        message = "${response.name} · actualización automática cada 5 s"
                        val self = response.devices.firstOrNull { it.self }
                        if (focusSelfOnNextRefresh && self != null) {
                            selectedId = self.id
                            follow = true
                            focusSelfOnNextRefresh = false
                            lastCameraTarget = null
                        } else if (selectedId == null || response.devices.none { it.id == selectedId }) {
                            selectedId = response.devices.firstOrNull { !it.self && it.visibleFix(lastSuccessAt) != null }?.id
                                ?: response.devices.firstOrNull { it.visibleFix(lastSuccessAt) != null }?.id
                                ?: response.devices.firstOrNull()?.id
                            lastCameraTarget = null
                        }
                    }
                }.onFailure { error ->
                    // Never leave an old position visible after authorization fails.
                    state = null
                    terminalError = error is HomeHttpException && (error.status == 401 || error.reason in setOf("BSEC_AUTH", "BSEC_DEVICE", "BSEC_HOME")) || error is IllegalArgumentException
                    message = if (error is java.io.IOException) "Sin conexión. No se está actualizando la ubicación."
                        else error.message ?: "No se pudo actualizar el hogar."
                }
                updateDisplay(SystemClock.elapsedRealtime())
            }
        }
    }

    private fun updateDisplay(now: Long) {
        if (isDestroyed || !::statusView.isInitialized) return
        statusView.text = message + if (state != null) " · consulta hace ${(now - lastSuccessAt).coerceAtLeast(0) / 1000} s" else ""
        val devices = state?.devices.orEmpty()
        val key = devices.joinToString("|") { "${it.id}:${it.name}:${it.visibleFix(now) != null}:${it.sharing}" } + ":$selectedId"
        if (key != buttonsKey) {
            buttonsKey = key; deviceButtons.removeAllViews()
            for (device in devices) {
                val available = device.visibleFix(now) != null
                deviceButtons.addView(control("${if (device.id == selectedId) "● " else ""}${device.name}${if (device.self) " · yo" else ""}${if (available) "" else " · sin posición"}") {
                    selectedId = device.id; follow = true; lastCameraTarget = null
                    updateDisplay(SystemClock.elapsedRealtime())
                })
            }
        }
        val selected = devices.firstOrNull { it.id == selectedId }
        val fix = selected?.visibleFix(now)
        detailView.text = when {
            selected == null -> if (state == null) "Las posiciones aparecen al conectar con tu hogar." else "No hay teléfonos disponibles."
            fix == null -> "${selected.name}\n${if (selected.sharing) "Sin posición reciente. Revisá GPS e Internet en ese teléfono." else "No está compartiendo ubicación o perdió la conexión."}"
            else -> "${selected.name}${if (fix.simulated) " · ubicación simulada" else ""}\nGPS ${clock.format(Date(fix.capturedAt))} · precisión ±${fix.point.accuracy.toInt()} m"
        }
        updateFollowButton()
        val features = featureCollection(devices, now)
        val chosen = featureCollection(devices.filter { it.id == selectedId }, now)
        if (styleLoaded) {
            if (features != lastFeatures) { pointsSource?.setGeoJson(features); lastFeatures = features }
            if (chosen != lastSelection) { selectedSource?.setGeoJson(chosen); lastSelection = chosen }
        }
        if (fix == null) lastCameraTarget = null
        if (follow && fix != null && map != null) {
            val target = LatLng(fix.point.lat, fix.point.lon)
            if (target != lastCameraTarget) {
                val zoom = if (lastCameraTarget == null) 16.0 else map!!.cameraPosition.zoom
                map!!.animateCamera(CameraUpdateFactory.newLatLngZoom(target, zoom), 800)
                lastCameraTarget = target
            }
        }
    }

    private fun featureCollection(devices: List<HomeMapDevice>, now: Long): String {
        val features = JSONArray()
        for (device in devices) {
            val fix = device.visibleFix(now) ?: continue
            features.put(JSONObject().put("type", "Feature").put("geometry", JSONObject()
                .put("type", "Point").put("coordinates", JSONArray().put(fix.point.lon).put(fix.point.lat)))
                .put("properties", JSONObject().put("name", device.name)))
        }
        return JSONObject().put("type", "FeatureCollection").put("features", features).toString()
    }

    private fun shareOrCenterThisPhone() {
        if (HomeLocationService.active) {
            focusSelfLocation()
            return
        }
        AlertDialog.Builder(this).setTitle("Compartir ubicación GPS")
            .setMessage("La ubicación de este teléfono se compartirá con los integrantes de tu hogar y se actualizará mientras el servicio esté activo. Podés detenerla desde la notificación o desde Familia.")
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Compartir") { _, _ -> requestHomeLocationSharing() }
            .show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != SHARE_PERMISSION_REQUEST) return
        val hasLocation = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasNotifications = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!hasLocation || !hasNotifications) {
            Toast.makeText(this, "Para compartir, concedé ubicación y notificaciones en los permisos de B-Security.", Toast.LENGTH_LONG).show()
            return
        }
        requestHomeLocationSharing()
    }

    private fun requestHomeLocationSharing() {
        val hasLocation = checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasNotifications = Build.VERSION.SDK_INT < 33 ||
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!hasLocation || !hasNotifications) {
            val requested = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) requested += Manifest.permission.POST_NOTIFICATIONS
            requestPermissions(requested.toTypedArray(), SHARE_PERMISSION_REQUEST)
            return
        }
        if (!getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled()) {
            Toast.makeText(this, "Habilitá las notificaciones de B-Security para compartir ubicación.", Toast.LENGTH_LONG).show()
            return
        }
        focusSelfOnNextRefresh = true
        focusSelfLocation()
        try {
            startForegroundService(Intent(this, HomeLocationService::class.java).setAction(HomeLocationService.START))
        } catch (error: Exception) {
            focusSelfOnNextRefresh = false
            Toast.makeText(this, error.message ?: "No se pudo iniciar la ubicación compartida.", Toast.LENGTH_LONG).show()
        }
    }

    private fun focusSelfLocation() {
        val self = state?.devices?.firstOrNull { it.self }
        if (self != null) selectedId = self.id
        else focusSelfOnNextRefresh = true
        follow = true
        lastCameraTarget = null
        updateDisplay(SystemClock.elapsedRealtime())
    }

    private fun updateFollowButton() { followButton.text = if (follow) "Seguir teléfono ✓" else "Centrar y seguir" }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun label(value: String, size: Float) = TextView(this).apply { text = value; textSize = size; setTextColor(Color.rgb(21, 32, 43)) }
    private fun control(value: String, action: () -> Unit) = Button(this).apply {
        text = value; textSize = 12f; isAllCaps = false; setOnClickListener { action() }
    }

    override fun onStart() { super.onStart(); mapView.onStart() }
    override fun onResume() {
        super.onResume(); mapView.onResume()
        visible = true; terminalError = false; nextRequestAt = 0
        handler.removeCallbacks(tick); handler.post(tick)
    }
    override fun onPause() {
        visible = false; generation++; handler.removeCallbacks(tick)
        state = null; lastCameraTarget = null
        // Do not retain a visible location while backgrounded or after revocation.
        pointsSource?.setGeoJson(emptyFeatures()); selectedSource?.setGeoJson(emptyFeatures())
        lastFeatures = ""; lastSelection = ""
        mapView.onPause(); super.onPause()
    }
    override fun onStop() { mapView.onStop(); super.onStop() }
    override fun onLowMemory() { super.onLowMemory(); mapView.onLowMemory() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("selected", selectedId); outState.putBoolean("follow", follow)
        super.onSaveInstanceState(outState); mapView.onSaveInstanceState(outState)
    }
    override fun onDestroy() {
        visible = false; handler.removeCallbacksAndMessages(null); worker.shutdownNow()
        mapView.onDestroy(); map = null; pointsSource = null; selectedSource = null
        super.onDestroy()
    }
    companion object {
        const val DEVICE = "device"
        const val STYLE = "https://tiles.openfreemap.org/styles/liberty"
        private const val POINTS = "home-points"
        private const val SELECTED = "home-selected"
        private const val SHARE_PERMISSION_REQUEST = 120
        private fun emptyFeatures() = "{\"type\":\"FeatureCollection\",\"features\":[]}"
    }
}
