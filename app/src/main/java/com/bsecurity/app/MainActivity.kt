package com.bsecurity.app

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.Animator
import android.animation.AnimatorSet
import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.animation.DecelerateInterpolator
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.Executors
import java.util.concurrent.Future

class MainActivity : Activity() {
    private val bg = Color.rgb(244,244,242)
    private val ink = Color.rgb(21,32,43)
    private val surface = Color.WHITE
    private val mint = Color.rgb(17,17,17)
    private val muted = Color.rgb(95,108,123)
    private val warning = Color.rgb(158,65,15)
    private lateinit var content: LinearLayout
    private lateinit var sensors: Sensors
    private lateinit var policy: PolicyStore
    private lateinit var family: FamilyClient
    private lateinit var auth: SupabaseAuth
    private lateinit var googleSignIn: GoogleSignInFlow
    private var googleAccount: GoogleAccount? = null
    private var authBusy = false
    private var authMessage = ""
    private var cloudState: JSONObject? = null
    private var cloudBusy = false
    private var cloudMessage = ""
    private var cloudFetchedAt = 0L
    private var legacyFamilyOpen = false
    private val worker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val output = mutableMapOf<String, String>()
    private val liveViews = mutableMapOf<String, TextView>()
    private val runningChecks = mutableSetOf<String>()
    private var deviceFindings: List<Finding>? = null
    private var deviceMeasuredAt = ""
    private var routerFindings: List<Finding>? = null
    private var routerMeasuredAt = ""
    private var routerReadingsDone = 0
    private var routerReadingsTotal = 0
    private val lanWorker = Executors.newSingleThreadExecutor()
    private var lanJob: Future<*>? = null
    @Volatile private var lanScanner: LanScanner? = null
    private var lanGeneration = 0
    private var lanFindings: List<Finding>? = null
    private var lanMeasuredAt = ""
    private var lanReadingsDone = 0
    private var lanReadingsTotal = 0
    private var tab = 0
    private var unlocked = false
    private var pending: (() -> Unit)? = null
    private var familyState: JSONObject? = null
    private var familyBusy = false
    private var resumed = false
    private var entered = false
    private val uiPrefs by lazy { getSharedPreferences("interface", MODE_PRIVATE) }
    private val checkViews = mutableMapOf<String, SecurityCheckView>()
    private var deviceReadingsDone = 0
    private var deviceReadingsTotal = 0
    private val scrollPositions = IntArray(5)
    private var currentScroll: ScrollView? = null
    private var restoringScroll: ScrollView? = null
    private var renderedTab: Int? = null
    private lateinit var appRoot: FrameLayout
    private lateinit var appShell: LinearLayout
    private lateinit var pageHost: FrameLayout
    private lateinit var navigation: SecurityNavigation
    private var launchView: BrandedLaunchView? = null
    private var launchHandled = false
    private var pageMotion: AnimatorSet? = null
    private var outgoingPage: View? = null
    private val familyTick = object : Runnable {
        override fun run() {
            if (resumed && tab == 4 && googleAccount != null && !authBusy && !cloudBusy) cloudAction("status")
            if (resumed && tab == 4 && familyState?.optJSONObject("family") != null && !familyBusy) syncFamily()
            if(resumed) handler.postDelayed(this, 30_000)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.AppTheme)
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 31) splashScreen.setOnExitAnimationListener { it.remove() }
        policy = PolicyStore(this); family = FamilyClient(this)
        auth = SupabaseAuth(this); googleSignIn = GoogleSignInFlow(this)
        sensors = Sensors(this, { key, busy ->
            if(busy) runningChecks.add(key) else runningChecks.remove(key)
            handler.post { if(resumed && !isDestroyed && tab==1) render() }
        }) { key, value ->
            runOnUiThread { output[key] = value; liveViews[key]?.text = value }
        }
        tab = (savedInstanceState?.getInt("tab") ?: 0).coerceIn(0, 4)
        entered = savedInstanceState?.getBoolean("entered") ?: uiPrefs.getBoolean("entered", false)
        savedInstanceState?.getIntArray("scrollPositions")?.let { saved ->
            saved.copyInto(scrollPositions, endIndex = minOf(saved.size, scrollPositions.size))
        }
        launchHandled = savedInstanceState?.getBoolean("launchHandled") ?: false
        createShell()
        render()
        if (auth.hasStoredSession) authTask("Comprobando la cuenta guardada…") { auth.verify() }
        if (!launchHandled) {
            launchHandled = true
            val intro = BrandedLaunchView(this)
            launchView = intro
            appShell.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            appShell.descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
            appRoot.addView(intro, FrameLayout.LayoutParams(-1, -1))
            intro.start {
                appRoot.removeView(intro)
                appShell.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_AUTO
                appShell.descendantFocusability = android.view.ViewGroup.FOCUS_BEFORE_DESCENDANTS
                if (launchView === intro) launchView = null
            }
        }
    }
    override fun onResume() { super.onResume(); resumed = true; render(); handler.removeCallbacks(familyTick); handler.postDelayed(familyTick,30_000) }
    override fun onStop() { resumed = false; unlocked = false; cancelLan(); launchView?.finishImmediately(); stopPageMotion(); navigation.dispose(); stopAnimations(); sensors.stop(); handler.removeCallbacks(familyTick); super.onStop() }
    override fun onDestroy() { googleSignIn.cancel(); cancelLan(); lanWorker.shutdownNow(); launchView?.dispose(); launchView = null; stopPageMotion(); navigation.dispose(); stopAnimations(); sensors.stop(); handler.removeCallbacksAndMessages(null); worker.shutdownNow(); super.onDestroy() }
    override fun onSaveInstanceState(outState: Bundle) {
        rememberScroll()
        outState.putInt("tab",tab); outState.putBoolean("entered",entered)
        outState.putBoolean("launchHandled",launchHandled); outState.putIntArray("scrollPositions",scrollPositions)
        super.onSaveInstanceState(outState)
    }
    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun text(value: String, size: Float = 15f, color: Int = ink) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color); setLineSpacing(dp(3).toFloat(), 1f)
    }
    private fun vertical() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun shape(color: Int, radius: Float = 18f) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius.toInt()).toFloat() }
    private fun createShell() {
        appRoot = FrameLayout(this).apply { setBackgroundColor(bg) }
        val root = vertical().apply { setBackgroundColor(bg); setPadding(dp(16),dp(12),dp(16),dp(8)) }
        appShell = root
        if (Build.VERSION.SDK_INT >= 35) root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.ime())
            view.setPadding(dp(16) + bars.left, dp(12) + bars.top, dp(16) + bars.right, dp(8) + bars.bottom)
            insets
        }
        val brand = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0,dp(2),0,dp(18)) }
        brand.addView(ImageView(this).apply {
            setImageResource(R.drawable.bsoftware_mark); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            background = shape(surface, 16f)
        }, LinearLayout.LayoutParams(dp(56),dp(56)))
        brand.addView(vertical().apply {
            setPadding(dp(12),0,0,0)
            addView(text("B-Software",11f,muted).apply { letterSpacing = .08f; typeface = Typeface.DEFAULT_BOLD })
            addView(text("B-Security",23f).apply { typeface = Typeface.DEFAULT_BOLD })
        })
        root.addView(brand)
        pageHost = FrameLayout(this).apply { clipChildren = true }
        root.addView(pageHost, LinearLayout.LayoutParams(-1,0,1f))
        navigation = SecurityNavigation(this) { index ->
            if (tab != index) {
                if (tab == 1) cancelLan()
                val direction = if (index > tab) 1 else -1
                tab = index
                render(direction)
            }
        }
        root.addView(navigation, LinearLayout.LayoutParams(-1,-2).apply { topMargin=dp(12) })
        appRoot.addView(root, FrameLayout.LayoutParams(-1,-1))
        setContentView(appRoot)
    }
    private fun rememberScroll() {
        val scroll = currentScroll?.takeIf { it.isLaidOut && it !== restoringScroll } ?: return
        renderedTab?.let { scrollPositions[it] = scroll.scrollY }
    }
    private fun render(direction: Int? = null) {
        rememberScroll()
        stopPageMotion()
        stopAnimations()
        liveViews.clear()
        val oldPage = currentScroll
        val animate = direction != null && resumed && launchView == null && ValueAnimator.areAnimatorsEnabled()
        val scroll = ScrollView(this).apply { isFillViewport = true; clipToPadding = false }
        currentScroll = scroll
        renderedTab = if (entered) tab else null
        content = vertical()
        content.id = R.id.screen_content
        scroll.addView(content)
        if (!entered) welcome() else when(tab) { 0 -> home(); 1 -> network(); 2 -> gps(); 3 -> parental(); 4 -> familyScreen() }
        navigation.visibility = if (entered) View.VISIBLE else View.GONE
        navigation.select(tab, animate)
        if (animate && oldPage != null) {
            outgoingPage = oldPage
            oldPage.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            scroll.alpha = 0f
            if (direction == 0) scroll.translationY = dp(18).toFloat()
            else scroll.translationX = dp(24).toFloat() * direction!!
        } else pageHost.removeAllViews()
        val desiredScroll = if (entered) scrollPositions[tab] else 0
        restoringScroll = scroll
        // An auth callback can render again before the first layout. Keep the saved
        // position until the content is measured, then restore it exactly once.
        scroll.addOnLayoutChangeListener(object : View.OnLayoutChangeListener {
            override fun onLayoutChange(view: View, left: Int, top: Int, right: Int, bottom: Int,
                oldLeft: Int, oldTop: Int, oldRight: Int, oldBottom: Int) {
                scroll.removeOnLayoutChangeListener(this)
                if (currentScroll !== scroll) return
                scroll.scrollTo(0, desiredScroll)
                restoringScroll = null
                if (animate && resumed) animatePage(scroll, oldPage, direction ?: 0)
                else { scroll.alpha = 1f; scroll.translationX = 0f; scroll.translationY = 0f }
            }
        })
        pageHost.addView(scroll, FrameLayout.LayoutParams(-1,-1))
    }
    private fun animatePage(page: ScrollView, previous: View?, direction: Int) {
        val motions = mutableListOf<Animator>(
            ObjectAnimator.ofFloat(page, View.ALPHA, 1f),
            ObjectAnimator.ofFloat(page, View.TRANSLATION_X, 0f),
            ObjectAnimator.ofFloat(page, View.TRANSLATION_Y, 0f)
        )
        if (previous != null) {
            motions += ObjectAnimator.ofFloat(previous, View.ALPHA, 0f)
            motions += ObjectAnimator.ofFloat(previous, View.TRANSLATION_X, -dp(12).toFloat() * direction)
        }
        if (page.scrollY == 0) {
            for (index in 0 until minOf(content.childCount, 6)) {
                val child = content.getChildAt(index)
                child.translationY = dp(9).toFloat()
                motions.add(ObjectAnimator.ofFloat(child, View.TRANSLATION_Y, 0f).apply { startDelay = index * 28L })
            }
        }
        pageMotion = AnimatorSet().apply {
            playTogether(motions); duration = 450; interpolator = DecelerateInterpolator(1.6f)
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (outgoingPage === previous) { pageHost.removeView(previous); outgoingPage = null }
                    if (pageMotion === animation) pageMotion = null
                }
            })
            start()
        }
    }
    private fun stopPageMotion() {
        pageMotion?.removeAllListeners(); pageMotion?.cancel(); pageMotion = null
        outgoingPage?.let { pageHost.removeView(it) }; outgoingPage = null
        currentScroll?.let { page ->
            page.alpha = 1f; page.translationX = 0f; page.translationY = 0f
            val children = page.getChildAt(0) as? LinearLayout
            if (children != null) for (index in 0 until children.childCount) children.getChildAt(index).translationY = 0f
        }
    }
    private fun welcome() {
        content.setPadding(dp(8),dp(24),dp(8),dp(24))
        content.addView(ImageView(this).apply {
            setImageResource(R.drawable.bsoftware_mark)
            contentDescription="Logo de B-Software"
        },LinearLayout.LayoutParams(dp(144),dp(144)).apply { gravity=Gravity.CENTER_HORIZONTAL })
        heading("Seguridad que empieza con vos", "Elegí cómo continuar en B-Security.")
        card("Tu dispositivo. Tu control.", "Comprobá indicadores de seguridad, revisá tus conexiones y gestioná el control parental desde un solo lugar.")
        button(getString(R.string.continue_without_account), viewId = R.id.welcome_continue, prominent = true) {
            entered=true; uiPrefs.edit().putBoolean("entered",true).apply(); render(0)
        }
        accountSection()
        content.addView(text("Sin cuenta podés usar el diagnóstico, las mediciones y el control parental local.",13f,muted).apply { setPadding(0,dp(12),0,0) })
    }
    private fun heading(title: String, sub: String) {
        content.addView(text(title,28f).apply { typeface = Typeface.DEFAULT_BOLD; setPadding(0,dp(8),0,dp(6)) })
        content.addView(text(sub,14f,muted).apply { setPadding(0,0,0,dp(16)) })
    }
    private fun section(title: String, detail: String) {
        content.addView(text(title,18f).apply {
            typeface = Typeface.DEFAULT_BOLD
            setPadding(0,dp(12),0,dp(3))
        })
        content.addView(text(detail,13f,muted).apply { setPadding(0,0,0,dp(9)) })
    }
    private fun card(title: String, detail: String, level: String = "info", deviceKind: LanDeviceKind? = null): LinearLayout {
        val box = vertical().apply {
            background = shape(surface).apply { setStroke(dp(1),Color.rgb(216,224,232)) }
            setPadding(dp(16),dp(15),dp(16),dp(15))
        }
        val color = when(level) { "risk" -> warning; "ok" -> Color.rgb(24,112,65); else -> muted }
        if(title.isNotEmpty()) {
            val heading = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            deviceKind?.let { kind ->
                heading.addView(ImageView(this).apply {
                    setImageResource(when (kind) {
                        LanDeviceKind.ROUTER -> R.drawable.ic_device_router
                        LanDeviceKind.PHONE -> R.drawable.ic_device_phone
                        LanDeviceKind.COMPUTER -> R.drawable.ic_device_computer
                        LanDeviceKind.PRINTER -> R.drawable.ic_device_printer
                        LanDeviceKind.MEDIA -> R.drawable.ic_device_media
                        LanDeviceKind.OTHER -> R.drawable.ic_device_other
                    })
                    setColorFilter(ink)
                    contentDescription = null
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                }, LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(10) })
            }
            heading.addView(text(title,16f,color).apply { typeface = Typeface.DEFAULT_BOLD },
                LinearLayout.LayoutParams(0, -2, 1f))
            box.addView(heading)
        }
        if(detail.isNotEmpty()) box.addView(text(detail,14f).apply { setPadding(0,dp(7),0,0); setTextIsSelectable(true) })
        content.addView(box,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(10) })
        return box
    }
    private fun button(label: String, parent: LinearLayout = content, enabled: Boolean = true, viewId: Int = View.NO_ID, prominent: Boolean = false, action: () -> Unit) {
        parent.addView(Button(this).apply {
            id=viewId; isEnabled=enabled; alpha=if(enabled) 1f else .5f
            text=label; isAllCaps=false; textSize=14f; setTextColor(if(prominent) surface else mint)
            background=android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(if(prominent) 0x33FFFFFF else 0x2215202B),
                shape(if(prominent) ink else surface,14f).apply { if(!prominent) setStroke(dp(1),Color.rgb(216,224,232)) },null)
            setPadding(dp(12),dp(8),dp(12),dp(8)); minimumHeight=dp(52)
            setOnClickListener { safely(action) }
        },LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(10) })
    }
    private fun live(key: String, default: String) {
        if(key in runningChecks) { checking(key); return }
        val box=card("", "")
        val view=text(output[key] ?: default,14f)
        box.addView(view); liveViews[key]=view
    }
    private fun checking(key: String) {
        val title = when(key) {
            "device" -> getString(R.string.security_check_title)
            "router" -> "Comprobando tu router"
            "lan" -> "Reconociendo equipos y servicios"
            "wifi" -> "Buscando redes Wi-Fi"
            "bluetooth" -> "Explorando Bluetooth"
            else -> "Comprobando indicadores"
        }
        val view = SecurityCheckView(this, title, output[key] ?: "Comprobando…", key == "device")
        if (key == "device") view.setProgress(deviceReadingsDone, deviceReadingsTotal)
        if (key == "router") view.setProgress(routerReadingsDone, routerReadingsTotal)
        if (key == "lan") view.setProgress(lanReadingsDone, lanReadingsTotal)
        view.setActive(resumed)
        checkViews[key] = view
        liveViews[key] = view.statusText
        content.addView(view, LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(10) })
    }
    private fun stopAnimations() { checkViews.values.forEach { it.dispose() }; checkViews.clear() }
    private fun safely(action: () -> Unit) {
        try { action() } catch(e: Exception) { message(e.message ?: "No se pudo completar la acción.") }
    }
    private fun message(value: String) { if(!isFinishing && !isDestroyed) AlertDialog.Builder(this).setTitle("B-Security").setMessage(value).setPositiveButton("Entendido",null).show() }
    private fun confirm(title: String, detail: String, action: () -> Unit) {
        AlertDialog.Builder(this).setTitle(title).setMessage(detail).setNegativeButton("Cancelar",null)
            .setPositiveButton("Continuar") { _,_ -> safely(action) }.show()
    }
    private fun permissions(values: Array<String>, action: () -> Unit) {
        val missing=values.filter { checkSelfPermission(it)!=PackageManager.PERMISSION_GRANTED }
        if(missing.isEmpty()) safely(action) else {
            pending=action; requestPermissions(missing.toTypedArray(),101)
        }
    }
    override fun onRequestPermissionsResult(code:Int, names:Array<out String>, results:IntArray) {
        super.onRequestPermissionsResult(code,names,results)
        val action=pending; pending=null
        if(code==101 && results.isNotEmpty() && results.all { it==PackageManager.PERMISSION_GRANTED }) action?.let { safely(it) }
        else message("El permiso no fue otorgado. Podés cambiarlo en Ajustes → Apps → B-Security → Permisos.")
    }
    private fun locationPermission(action: () -> Unit) {
        confirm("Ubicación para medir", "Android exige ubicación precisa para escanear Wi-Fi y medir GPS. Las mediciones quedan en el teléfono salvo que habilites compartirlas en Familia.") {
            permissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION),action)
        }
    }
    private fun home() {
        heading("Tu teléfono, a la vista", "Indicadores verificables y acciones para reducir exposición.")
        if("device" in runningChecks) { checking("device"); return }
        val findings=deviceFindings
        if(findings==null) {
            card("Tu centro de seguridad", "Revisá el parche de Android, bloqueo, cifrado y conexiones del dispositivo.")
            button("Comprobar seguridad del teléfono", viewId = R.id.check_device_button, prominent = true) { checkDevice() }
            output["device"]?.let { card("Diagnóstico pendiente",it,"unknown") }
            return
        }
        val risks=findings.count { it.level=="risk" }
        card(if(risks==0) "Sin alertas en estos indicadores" else "$risks indicadores para revisar",
            "Medido en este dispositivo · $deviceMeasuredAt\nLa cobertura es limitada; no es una certificación de seguridad.",if(risks>0) "risk" else "info")
        button("Actualizar diagnóstico", viewId = R.id.check_device_button) { checkDevice() }
        findings.forEach { card(it.title,it.detail,it.level) }
        card("Modo sin cuenta", "Podés usar las mediciones y reglas locales sin registrarte. Google está pendiente de configurar. Familia usa una identidad de dispositivo separada y requiere un servidor HTTPS.")
    }
    private fun checkDevice() {
        if(!runningChecks.add("device")) return
        deviceReadingsDone = 0; deviceReadingsTotal = 0
        output["device"]="Leyendo seguridad de Android…"; render()
        worker.execute {
            val result=runCatching { Diagnostics.device(this) { completed, total, label ->
                runOnUiThread {
                    if (!isDestroyed) {
                        deviceReadingsDone = completed; deviceReadingsTotal = total
                        output["device"] = if (completed == 0) label else getString(R.string.security_check_stage, label)
                        liveViews["device"]?.text = output["device"]
                        checkViews["device"]?.setProgress(completed, total)
                    }
                }
            } }
            runOnUiThread {
                if(isDestroyed) return@runOnUiThread
                runningChecks.remove("device")
                result.onSuccess {
                    deviceFindings=it
                    deviceMeasuredAt=DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
                }.onFailure { deviceFindings=null; output["device"]="No se pudo comprobar: ${it.message}" }
                checkViews["device"]?.complete(result.isSuccess, if (result.isFailure) output["device"] else null)
                if(resumed) render(if (tab == 0) 0 else null)
            }
        }
    }
    private fun network() {
        heading("Redes y dispositivos", "Señales cercanas, equipos detectados y seguridad del router.")
        if ("lan" in runningChecks) {
            checking("lan")
            button("Detener reconocimiento", viewId = R.id.cancel_lan_button) { cancelLan(); render() }
            return
        }
        if ("router" in runningChecks) { checking("router"); return }
        section("Wi-Fi", "Estado de la red conectada y puntos de acceso cercanos.")
        runCatching { Diagnostics.wifi(this) }.getOrElse { listOf(Finding("Wi-Fi",it.message.orEmpty(),"unknown")) }
            .forEach { card(it.title,it.detail,it.level) }
        button("Escanear redes Wi-Fi", enabled = "wifi" !in runningChecks) { locationPermission { sensors.scanWifi(); render() } }
        live("wifi","Todavía no se escanearon redes cercanas.")

        section("Dispositivos", "Identificación orientativa a partir de respuestas y servicios que los equipos anuncian.")
        button("Reconocer equipos de mi red", viewId = R.id.discover_lan_button, prominent = true) {
            // Resolve and display the actual scope before any packet is sent.
            val scope = LanScanner(this).use { it.scopeKey to it.scopeDescription() }
            confirm("Reconocimiento de tu red autorizada", "${scope.second}\n\nSe enviarán Echo Request, consultas mDNS/NetBIOS y conexiones a puertos comunes. En los equipos que respondan se leerán saludos y se negociará SMB1 para comprobar su presencia. Hasta 90 segundos, solo con esta pantalla abierta. Continuá si administrás la red o tenés permiso.") {
                checkLan(expectedScope = scope.first)
            }
        }
        button("Probar otras subredes", viewId = R.id.other_subnets_button) { otherSubnets() }
        button("Elegir otra Wi-Fi en Android") { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)) }
        lanFindings?.let { findings ->
            card("Último reconocimiento", "Medido desde este teléfono · $lanMeasuredAt. Repetí al cambiar de red.")
            findings.forEach { card(it.title, it.detail, it.level, it.deviceKind) }
        } ?: card("Inventario de red", output["lan"] ?: "Todavía no se inició el reconocimiento.", "unknown")

        section("Router y administración", "Revisá los puertos visibles y contrastá la configuración en el panel del fabricante.")
        button("Perfil de referencia: ${routerProfile().label}", viewId = R.id.router_profile_button) { chooseRouterProfile() }
        button("Comprobar mi router", viewId = R.id.check_router_button) {
            confirm("Solo tu red o una autorizada", "Se intentará una conexión TCP a nueve puertos de la puerta de enlace de la Wi-Fi actual, incluido SSH (22). No se inicia sesión, no se prueban contraseñas y no se ejecutan comandos. Continuá solo si administrás esta red o tenés permiso.") {
                checkRouter()
            }
        }
        button("Abrir panel web del router") { openRouterPanel() }
        card("Administración por SSH", "El diagnóstico solo verifica si el puerto 22 acepta conexiones y muestra el saludo SSH si está disponible. No se conecta automáticamente ni maneja credenciales: los comandos y opciones varían según el modelo y firmware. Para revisar ajustes, abrí el panel del router y autenticá allí.")
        val routerResult = routerFindings
        if (routerResult != null) {
            card("Última comprobación", "Medida desde este teléfono · $routerMeasuredAt. Repetí la prueba si cambiaste de red.")
            routerResult.forEach { card(it.title, it.detail, it.level) }
        } else {
            card("Router", output["router"] ?: "Prueba del router pendiente.", "unknown")
            RouterProfiles.findings(routerProfile()).forEach { card(it.title, it.detail, it.level) }
        }

        section("Bluetooth cercano", "Visibilidad no significa vulnerabilidad; no se intentará vincular equipos.")
        button("Explorar Bluetooth / BLE", enabled = "bluetooth" !in runningChecks) {
            confirm("Dispositivos cercanos", "Se listarán equipos vinculados y anuncios BLE durante diez segundos. Su visibilidad no demuestra vulnerabilidades. No se intentará vincularlos.") {
                val perms=if(Build.VERSION.SDK_INT>=31) arrayOf(Manifest.permission.BLUETOOTH_SCAN,Manifest.permission.BLUETOOTH_CONNECT)
                    else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
                permissions(perms) { sensors.scanBluetooth() }
            }
        }
        live("bluetooth","Bluetooth todavía no analizado.")
    }

    private fun openRouterPanel() {
        val gateway = Diagnostics.wifiGateway(this)
        if (gateway == null) {
            Toast.makeText(this, "Conectate a una Wi-Fi con puerta de enlace privada.", Toast.LENGTH_LONG).show()
            return
        }
        AlertDialog.Builder(this).setTitle("Panel local de ${gateway.hostAddress}")
            .setMessage("Se abrirá el navegador del sistema. Preferí HTTPS para iniciar sesión. B-Security no recibirá ni guardará tus credenciales.")
            .setItems(arrayOf("Probar HTTPS (recomendado)", "Probar HTTP (si HTTPS no está disponible)")) { _, index ->
                val scheme = if (index == 0) "https" else "http"
                val address = android.net.Uri.parse("$scheme://${gateway.hostAddress}/")
                val open = { safely { startActivity(Intent(Intent.ACTION_VIEW, address)) } }
                if (index == 0) open()
                else confirm("HTTP no cifra la sesión", "No ingreses contraseñas ni datos sensibles por HTTP: podrían ser observados en la red. Preferí HTTPS.") { open() }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }
    private fun otherSubnets() {
        val field = EditText(this).apply {
            hint = "192.168.2.10, 10.0.0.20"
            inputType = InputType.TYPE_CLASS_TEXT
            maxLines = 3
        }
        val dialog = AlertDialog.Builder(this).setTitle("IP de equipos autorizados")
            .setMessage("Ingresá hasta tres IPv4 privadas. Se probarán cuatro Echo Request, diez puertos TCP y NetBIOS por IP desde la Wi-Fi actual. Esto comprueba rutas y accesibilidad a esos equipos. Requiere autorización para los destinos.")
            .setView(field).setNegativeButton("Cancelar", null).setPositiveButton("Probar", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                try {
                    val targets = LanScope.otherTargets(field.text.toString())
                    dialog.dismiss(); checkLan(targets)
                } catch (error: IllegalArgumentException) { field.error = error.message }
            }
        }
        dialog.show()
    }

    private fun checkLan(targets: List<java.net.Inet4Address> = emptyList(), expectedScope: String? = null) {
        if (!runningChecks.add("lan")) return
        val generation = ++lanGeneration
        lanFindings = null; lanReadingsDone = 0; lanReadingsTotal = 0
        output["lan"] = "Preparando reconocimiento…"
        scrollPositions[1] = 0; currentScroll?.scrollTo(0, 0); render()
        lanJob = lanWorker.submit {
            val result = runCatching {
                LanScanner(this).use { scanner ->
                    check(expectedScope == null || scanner.scopeKey == expectedScope) { "La Wi-Fi cambió desde la confirmación. Volvé a iniciar el reconocimiento." }
                    lanScanner = scanner
                    scanner.scan(targets) { done, total, label ->
                        runOnUiThread {
                            if (!isDestroyed && generation == lanGeneration) {
                                lanReadingsDone = done; lanReadingsTotal = total
                                output["lan"] = label
                                liveViews["lan"]?.text = label
                                checkViews["lan"]?.setProgress(done, total)
                            }
                        }
                    }
                }
            }
            lanScanner = null
            runOnUiThread {
                if (isDestroyed || generation != lanGeneration) return@runOnUiThread
                runningChecks.remove("lan"); lanJob = null
                result.onSuccess {
                    lanFindings = it
                    lanMeasuredAt = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date())
                }.onFailure { output["lan"] = "No se completó el reconocimiento: ${it.cause?.message ?: it.message}" }
                if (resumed) render(if (tab == 1) 0 else null)
            }
        }
    }

    private fun cancelLan() {
        if ("lan" !in runningChecks) return
        ++lanGeneration
        lanScanner?.close(); lanJob?.cancel(true); lanJob = null
        runningChecks.remove("lan")
        output["lan"] = "Reconocimiento detenido. Ejecutá una nueva medición para obtener resultados completos."
    }
    private fun routerProfile(): RouterProfile = RouterProfile.entries.firstOrNull {
        it.name == uiPrefs.getString("routerProfile", null)
    } ?: RouterProfile.GENERIC

    private fun chooseRouterProfile() {
        val profiles = RouterProfile.entries
        AlertDialog.Builder(this).setTitle("Perfil del router")
            .setSingleChoiceItems(profiles.map { it.label }.toTypedArray(), profiles.indexOf(routerProfile())) { dialog, index ->
                uiPrefs.edit().putString("routerProfile", profiles[index].name).apply()
                routerFindings = null; output.remove("router")
                dialog.dismiss(); render()
            }
            .setNegativeButton("Cancelar", null).show()
    }

    private fun checkRouter() {
        if (!runningChecks.add("router")) return
        val profile = routerProfile()
        routerReadingsDone = 0; routerReadingsTotal = RouterAssessment.ports.size
        routerFindings = null; output["router"] = "Preparando conexiones TCP…"
        scrollPositions[1] = 0; currentScroll?.scrollTo(0, 0); render()
        worker.execute {
            val result = runCatching { Diagnostics.router(this, profile) { done, total, label ->
                runOnUiThread {
                    if (!isDestroyed) {
                        routerReadingsDone = done; routerReadingsTotal = total
                        output["router"] = if (done == 0) label else "Puerto $label · $done de $total comprobados"
                        liveViews["router"]?.text = output["router"]
                        checkViews["router"]?.setProgress(done, total)
                    }
                }
            } }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                runningChecks.remove("router")
                result.onSuccess {
                    routerFindings = it
                    routerMeasuredAt = DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())
                }.onFailure { output["router"] = it.message ?: "No se pudo comprobar el router." }
                checkViews["router"]?.complete(result.isSuccess, if (result.isFailure) output["router"] else null)
                if (resumed) render(if (tab == 1) 0 else null)
            }
        }
    }
    private fun gps() {
        heading("Señal GPS en vivo", "Posición, precisión y satélites; sin seguimiento oculto.")
        button(if(sensors.gpsRunning) "Detener medición" else "Iniciar medición GPS") {
            if(sensors.gpsRunning) { sensors.stopGps(); render() }
            else locationPermission { sensors.startGps(); render() }
        }
        live("gps","Iniciá la medición. Al salir de B-Security se detendrá; no pedimos ubicación en segundo plano.")
        button("Abrir ajustes de ubicación") { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
    }
    private fun pin(action: () -> Unit) {
        if(unlocked && policy.hasPin) { action(); return }
        val first=!policy.hasPin
        val fields=vertical().apply { setPadding(dp(24),dp(4),dp(24),0) }
        fun field(hintText:String)=EditText(this).apply {
            hint=hintText; inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            fields.addView(this)
        }
        val entry=field(if(first) "PIN del responsable: 6 a 12 dígitos" else "PIN del responsable")
        val repeat=if(first) field("Repetir PIN") else null
        val dialog=AlertDialog.Builder(this).setTitle(if(first) "Crear PIN parental" else "Desbloquear controles")
            .setView(fields).setNegativeButton("Cancelar",null).setPositiveButton("Continuar",null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value=entry.text.toString()
                if(first && (!value.matches(Regex("[0-9]{6,12}")) || repeat?.text.toString()!=value)) { entry.error="Usá entre 6 y 12 dígitos y repetilos igual"; return@setOnClickListener }
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled=false
                worker.execute {
                    val valid=runCatching { if(first) { policy.setPin(value); true } else policy.verifyPin(value) }.getOrDefault(false)
                    runOnUiThread {
                        if(isDestroyed || !resumed) { dialog.dismiss(); return@runOnUiThread }
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled=true
                        if(!dialog.isShowing) return@runOnUiThread
                        if(valid) { unlocked=true; dialog.dismiss(); safely(action) }
                        else entry.error="PIN incorrecto. Tras 5 intentos, esperá 30 segundos."
                    }
                }
            }
        }
        dialog.show()
    }
    private fun parental() {
        heading("Control parental", "Reglas visibles para este teléfono.")
        button("Protección de la app y segundo plano") { startActivity(Intent(this, ProtectionActivity::class.java)) }
        val usage=policy.usageGranted(); val accessibility=policy.serviceEnabled()
        card(if(policy.enabled && accessibility) "Control habilitado" else "Control pendiente de activar",
            "Servicio de accesibilidad: ${if(accessibility) "habilitado" else "pendiente"}\nAcceso al uso: ${if(usage) "habilitado" else "pendiente"}\nPIN: ${if(policy.hasPin) "configurado" else "pendiente"}",
            if(policy.enabled && accessibility && usage) "ok" else "unknown")
        card("Permisos y límites", "El servicio observa solo qué app pasa al frente; no lee textos, pantallas ni contraseñas. Bloquea volviendo al inicio. El límite se reinicia a medianoche del teléfono y se comprueba cada pocos segundos. En una instalación común, Android permite desactivar permisos, cambiar la hora o desinstalar. Consultá Protección de la app para revisar el modo administrado y la continuidad en segundo plano. Ventanas múltiples y algunos fabricantes pueden limitar el bloqueo.")
        button(if(policy.hasPin) "Desbloquear ajustes con PIN" else "Crear PIN del responsable") { pin { render() } }
        button("1 · Permitir acceso al uso") { pin { startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) } }
        button("2 · Habilitar servicio de control") { pin {
            confirm("Permiso de accesibilidad", "Activá únicamente «B-Security · control parental». Se usa para detectar cambios de app y volver al inicio al bloquear. No recoge contenido de pantalla. En algunos Android, un APK instalado necesita además «Permitir ajustes restringidos» en la ficha de la app.") {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        } }
        button(if(policy.enabled) "Pausar control parental" else "3 · Activar reglas locales") { pin {
            if(!policy.enabled && !policy.serviceEnabled()) message("Primero habilitá el servicio B-Security en Accesibilidad.")
            else { policy.enable(!policy.enabled); render() }
        } }
        button("Añadir bloqueo o tiempo diario") { pin { chooseApp(installedApps()) { pkg,name -> editRule(pkg,name) } } }
        button("Cambiar PIN") { pin {
            confirm("Cambiar PIN", "Se pedirá un nuevo PIN y su confirmación.") {
                unlocked=false
                // Retain existing hash until the new PIN has passed validation.
                newPin()
            }
        } }
        val rules=policy.effectiveRules()
        if(rules.isEmpty()) card("Sin reglas", "Añadí una app para bloquearla o limitar sus minutos diarios.")
        else {
            val use=if(usage) policy.usageToday() else emptyMap()
            rules.forEach { (pkg,rule) ->
                val name=appName(pkg)
                val box=card(name, "${if(rule.blocked) "Bloqueada" else "Permitida"} · ${if(rule.minutes>0) "${rule.minutes} min/día" else "sin límite diario"}\nUso hoy: ${(use[pkg] ?: 0)/60_000} min")
                button("Editar regla local",box) { pin { editRule(pkg,name) } }
            }
        }
    }
    private fun newPin() {
        val fields=vertical().apply { setPadding(dp(24),0,dp(24),0) }
        val one=EditText(this).apply { hint="Nuevo PIN (6 a 12 dígitos)"; inputType=18; fields.addView(this) }
        val two=EditText(this).apply { hint="Repetir PIN"; inputType=18; fields.addView(this) }
        val dialog=AlertDialog.Builder(this).setTitle("Nuevo PIN").setView(fields).setNegativeButton("Cancelar",null).setPositiveButton("Guardar",null).create()
        dialog.setOnShowListener { dialog.getButton(-1).setOnClickListener {
            val value=one.text.toString()
            if(!value.matches(Regex("[0-9]{6,12}")) || value!=two.text.toString()) one.error="Revisá ambos PIN"
            else {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled=false
                worker.execute {
                    val result=runCatching { policy.setPin(value) }
                    runOnUiThread {
                        if(isDestroyed || !resumed) { dialog.dismiss(); return@runOnUiThread }
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled=true
                        if(!dialog.isShowing) return@runOnUiThread
                        result.onSuccess { dialog.dismiss(); unlocked=true; render() }
                            .onFailure { one.error="No se pudo guardar el PIN. Intentá nuevamente." }
                    }
                }
            }
        } }; dialog.show()
    }
    @Suppress("DEPRECATION")
    private fun installedApps(): List<Pair<String,String>> =
        packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER),0)
            .map { it.activityInfo.packageName to it.loadLabel(packageManager).toString() }
            .distinctBy { it.first }.filter { !policy.protectedPackage(it.first) }.sortedBy { it.second.lowercase() }
    private fun appName(pkg:String)=runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg,0)).toString() }.getOrDefault(pkg)
    private fun chooseApp(apps:List<Pair<String,String>>, action:(String,String)->Unit) {
        if(apps.isEmpty()) { message("No hay apps disponibles para elegir."); return }
        AlertDialog.Builder(this).setTitle("Elegí una app").setItems(apps.map { it.second }.toTypedArray()) { _,index ->
            val app=apps[index]; safely { action(app.first,app.second) }
        }.setNegativeButton("Cancelar",null).show()
    }
    private fun ruleDialog(name:String, initial:AppRule, save:(AppRule)->Unit) {
        val fields=vertical().apply { setPadding(dp(24),dp(4),dp(24),0) }
        val block=CheckBox(this).apply { text="Bloquear esta app"; isChecked=initial.blocked; fields.addView(this) }
        val limit=EditText(this).apply { hint="Minutos por día (0 = sin límite)"; inputType=InputType.TYPE_CLASS_NUMBER; setText(initial.minutes.toString()); fields.addView(this) }
        val dialog=AlertDialog.Builder(this).setTitle(name).setView(fields).setNegativeButton("Cancelar",null).setPositiveButton("Guardar",null).create()
        dialog.setOnShowListener { dialog.getButton(-1).setOnClickListener {
            val minutes=limit.text.toString().toIntOrNull()
            if(minutes==null || minutes !in 0..1440) limit.error="Entre 0 y 1440 minutos"
            else { save(AppRule(block.isChecked,minutes)); dialog.dismiss() }
        } }; dialog.show()
    }
    private fun editRule(pkg:String,name:String) = ruleDialog(name,policy.rules()[pkg] ?: AppRule()) {
        policy.putRule(pkg,it); render()
    }
    private fun accountSection() {
        val account = googleAccount
        val configured = auth.config != null
        val detail = if (account == null) {
            if (configured) "Ingresá con tu cuenta de Google. Compartir ubicación requiere tu autorización aparte."
            else "Falta completar la conexión con Google. Las funciones locales están disponibles."
        } else listOf(account.name, account.email).filter { it.isNotBlank() }.joinToString("\n")
        val box = card(if (account == null) "Tu cuenta de B-Security" else "Cuenta de Google", detail)
        if (authMessage.isNotBlank()) box.addView(text(authMessage, 13f, muted).apply { setPadding(0, dp(8), 0, dp(8)) })
        if (account == null) {
            box.addView(Button(this).apply {
                id = R.id.google_sign_in
                text = "Continuar con Google"; isAllCaps = false; textSize = 14f; setTextColor(ink)
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                background = android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x2215202B),
                    shape(Color.WHITE, 4f).apply { setStroke(dp(1), Color.rgb(116, 119, 117)) }, null)
                val logo = getDrawable(R.drawable.google_g)!!.apply { setBounds(0, 0, dp(20), dp(20)) }
                setCompoundDrawablesRelative(logo, null, null, null); compoundDrawablePadding = dp(12)
                setPadding(dp(12), 0, dp(12), 0); minimumHeight = dp(48)
                isEnabled = !authBusy && configured; alpha = if (isEnabled) 1f else .5f
                setOnClickListener { safely { startGoogleSignIn() } }
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12); bottomMargin = dp(10) })
            if (!configured) button("Configurar acceso", box, enabled = !authBusy) { configureGoogleAuth() }
        } else {
            button("Comprobar sesión", box, enabled = !authBusy) {
                authTask("Comprobando sesión…") { auth.verify() }
            }
            button("Cerrar sesión en este teléfono", box, enabled = !authBusy) {
                confirm("Cerrar sesión", "Se cerrará la sesión de B-Security en este teléfono. Tu grupo actual y las reglas locales se conservan.") {
                    HomeLocationService.stop(this)
                    authTask("Cerrando sesión…", clearGoogleState = true) { auth.signOut(); null }
                }
            }
            button("Quitar sesión sin conexión", box, enabled = !authBusy) {
                confirm("Quitar la sesión guardada", "Se borrará la sesión de este teléfono. Si no hay conexión, no se revocará en Supabase. Para revocarla, usá el cierre de sesión con conexión.") {
                    HomeLocationService.stop(this)
                    authTask("Quitando sesión…", clearGoogleState = true) { auth.forgetLocalSession(); null }
                }
            }
        }
    }

    private fun configureGoogleAuth() {
        val current = auth.config
        val fields = vertical().apply { setPadding(dp(24), 0, dp(24), 0) }
        fun entry(label: String, value: String) = EditText(this).apply {
            hint = label; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            setText(value); fields.addView(this)
        }
        val url = entry("URL del proyecto Supabase", current?.url ?: getString(R.string.supabase_url))
        val key = entry("Clave pública sb_publishable_", current?.publishableKey ?: getString(R.string.supabase_publishable_key))
        val client = entry("ID OAuth de Aplicación web", current?.webClientId ?: getString(R.string.google_web_client_id))
        val dialog = AlertDialog.Builder(this).setTitle("Conectar Google y Supabase")
            .setMessage("Usá únicamente los datos públicos de tu proyecto. El secreto de Google se configura en Supabase.")
            .setView(ScrollView(this).apply { addView(fields) }).setNegativeButton("Cancelar", null).setPositiveButton("Guardar", null).create()
        dialog.setOnShowListener { dialog.getButton(-1).setOnClickListener {
            val result = runCatching { auth.configure(AuthConfig.parse(url.text.toString(), key.text.toString(), client.text.toString())) }
            result.onSuccess { authMessage = "Conexión configurada. Ya podés continuar con Google."; dialog.dismiss(); render() }
                .onFailure { client.error = it.message ?: "Revisá los datos de conexión." }
        } }
        dialog.show()
    }

    private fun startGoogleSignIn() {
        if (authBusy) return
        val config = auth.config ?: return
        authBusy = true; authMessage = "Elegí tu cuenta de Google…"; render()
        try {
            googleSignIn.start(config.webClientId, { token, nonce ->
                authBusy = false
                authTask("Validando el ingreso…", enterOnSuccess = true) { auth.signIn(token, nonce) }
            }, { explanation ->
                authBusy = false; authMessage = explanation
                if (!isDestroyed && resumed) render()
            })
        } catch (_: Exception) {
            authBusy = false; authMessage = "No se pudo abrir Google. Revisá Google Play Services y volvé a intentar."; render()
        }
    }

    private fun authTask(progress: String, clearGoogleState: Boolean = false, enterOnSuccess: Boolean = false, action: () -> GoogleAccount?) {
        if (authBusy) return
        authBusy = true; authMessage = progress
        if (resumed) render()
        worker.execute {
            val result = runCatching(action)
            val cached = runCatching { auth.cachedAccount() }.getOrNull()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (googleAccount?.id != cached?.id) {
                    if (googleAccount != null || cached == null) HomeLocationService.stop(this)
                    cloudState = null; cloudMessage = ""
                }
                googleAccount = cached
                authBusy = false
                authMessage = if (result.isSuccess) {
                    if (cached != null) "Sesión verificada con Supabase."
                    else if (clearGoogleState) "Sesión quitada de este teléfono." else ""
                } else when (val error = result.exceptionOrNull()) {
                    is java.io.IOException -> "No se pudo conectar con Supabase. Revisá tu conexión; la sesión guardada se conserva."
                    is AuthHttpException -> error.message.orEmpty()
                    is IllegalStateException -> error.message ?: "No se pudo completar la operación. Intentá nuevamente."
                    else -> "No se pudo completar la operación. Revisá la configuración e intentá nuevamente."
                }
                if (result.isSuccess && enterOnSuccess && cached != null) {
                    entered = true; uiPrefs.edit().putBoolean("entered", true).apply(); tab = 4
                }
                if (result.isSuccess && clearGoogleState) {
                    // Also clear Credential Manager's remembered account selection.
                    authBusy = true
                    try {
                        googleSignIn.clearState {
                            if (!isDestroyed) { authBusy = false; if (resumed) render() }
                        }
                    } catch (_: Exception) { authBusy = false }
                }
                if (resumed && (!entered || tab == 4)) render()
            }
        }
    }

    private fun familyScreen() {
        heading("Cuidarse en familia", "Tu hogar conectado, con ubicación compartida por permiso.")
        accountSection()
        cloudHomeSection()
        button(if (legacyFamilyOpen) "Ocultar grupo anterior" else "Opciones del grupo anterior") { legacyFamilyOpen = !legacyFamilyOpen; render() }
        if (legacyFamilyOpen) legacyFamilySection()
    }

    private fun cloudHomeSection() {
        if (googleAccount == null) { card("Mi hogar", "Ingresá con Google para crear tu hogar o unirte con un código. Funciona con Internet, sin Tailscale."); return }
        button(if (cloudBusy) "Actualizando hogar…" else "Actualizar hogar", enabled = !cloudBusy && !authBusy, viewId = R.id.home_refresh) { cloudAction("status") }
        if (cloudMessage.isNotBlank()) card("Estado del hogar", cloudMessage)
        if (cloudState == null) {
            if (cloudMessage.isBlank() && !cloudBusy && !authBusy) handler.post { if (resumed && tab == 4 && !cloudBusy && cloudMessage.isBlank()) cloudAction("status") }
            return
        }
        val group = cloudState?.optJSONObject("home")
        if (group == null) {
            card("Conectá tus teléfonos", "Creá el hogar en tu teléfono. En el otro Android, ingresá con Google y elegí «Unirme con código». Cada teléfono autoriza su ubicación por separado.")
            button("Crear o recuperar mi hogar", enabled = !cloudBusy && !authBusy, viewId = R.id.home_create) {
                confirm("Crear mi hogar", "Tu cuenta será la administradora y podrá ver las posiciones que los integrantes decidan compartir. Se registrará el modelo de este teléfono.") { cloudAction("create", JSONObject().put("name", "Mi hogar")) }
            }
            button("Unirme con código", enabled = !cloudBusy && !authBusy, viewId = R.id.home_join) {
                val code = EditText(this).apply { hint = "Código de invitación"; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS }
                AlertDialog.Builder(this).setTitle("Unirme al hogar")
                    .setMessage("El administrador verá el modelo y la última conexión de este teléfono. La ubicación empieza desactivada; podrás compartirla y detenerla desde aquí.")
                    .setView(code).setNegativeButton("Cancelar", null).setPositiveButton("Aceptar y unirme") { _, _ -> cloudAction("join", JSONObject().put("code", code.text.toString())) }.show()
            }
            return
        }
        val admin = group.optString("role") == "admin"
        card(group.optString("name"), if (admin) "Sos administrador de este hogar. Solo esta cuenta puede ver las posiciones de todos los integrantes." else "Sos integrante del hogar. Tu ubicación solo se comparte cuando la activás aquí.")
        if (admin) button("Invitar otro teléfono", enabled = !cloudBusy && !authBusy, viewId = R.id.home_invite) { cloudAction("invite") { response ->
            val raw = response.getString("code")
            AlertDialog.Builder(this).setTitle("Código de un solo uso · 15 minutos").setMessage(raw)
                .setNegativeButton("Cerrar", null).setPositiveButton("Compartir") { _, _ ->
                    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,
                        raw), "Compartir código"))
                }.show()
        } }
        button("Mapa en vivo del hogar", enabled = !authBusy, viewId = R.id.home_map, prominent = true) { openHomeMap() }
        card("Ubicación de este teléfono", HomeLocationService.status + "\nSe envía aproximadamente cada 5 segundos mientras el servicio está activo. Android o el ahorro de batería pueden interrumpirlo. Un punto vencido deja de mostrarse.")
        if (HomeLocationService.active) button("Detener mi ubicación", viewId = R.id.home_stop) { HomeLocationService.stop(this); handler.postDelayed({ if (resumed && tab == 4) cloudAction("status") }, 700); render() }
        else button("Compartir mi ubicación", enabled = !cloudBusy && !authBusy, viewId = R.id.home_share) { startHomeLocation() }
        val devices = group.optJSONArray("devices") ?: JSONArray()
        for (i in 0 until devices.length()) {
            val device = devices.getJSONObject(i)
            val own = device.optBoolean("self")
            val loc = device.optJSONObject("location")
            val recent = loc != null && cloudLocationFresh(loc)
            val detail = "${if (own) "Este teléfono · " else ""}${if (device.optBoolean("sharing")) "Compartiendo" else "Ubicación desactivada o sin conexión"}\nÚltima conexión: ${homeDate(device.optString("last_seen"))}" +
                if (recent) "\nPosición: ${homeDate(loc!!.optString("at"))} · ±${loc.optDouble("accuracy").toInt()} m${if (loc.optBoolean("mock")) " · ubicación simulada" else ""}" else "\nSin posición reciente."
            val box = card(device.optString("name"), detail)
            if (recent) button("Seguir en el mapa en vivo", box) { openHomeMap(device.getString("id")) }
            if (recent) button("Abrir punto en OpenStreetMap", box) {
                check(cloudLocationFresh(loc!!)) { "La posición venció. Actualizá el hogar." }
                val point = HomeLocationPoint(loc.getDouble("lat"), loc.getDouble("lon"), loc.getDouble("accuracy"), 0)
                startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(point.mapUrl())))
            }
            if (admin && !own) button("Quitar este teléfono del hogar", box, enabled = !cloudBusy) {
                confirm("Quitar teléfono", "Se quitará su acceso al hogar y se borrará su posición compartida. Necesitará otra invitación para volver.") { cloudAction("remove", JSONObject().put("target", device.getString("id"))) }
            }
        }
        button("Salir de este hogar", enabled = !cloudBusy && !authBusy) {
            confirm("Salir del hogar", "Se detendrá la ubicación y se borrará el vínculo de este teléfono. Los otros dispositivos siguen en el hogar.") {
                HomeLocationService.stop(this); cloudAction("leave")
            }
        }
        if (admin) button("Eliminar mi hogar", enabled = !cloudBusy && !authBusy) {
            confirm("Eliminar hogar", "Se quitarán todos los teléfonos, las posiciones y las invitaciones de este hogar.") { HomeLocationService.stop(this); cloudAction("delete") }
        }
    }
    private fun openHomeMap(deviceId: String? = null) {
        startActivity(Intent(this, HomeMapActivity::class.java).putExtra(HomeMapActivity.DEVICE, deviceId))
    }
    private fun homeDate(value: String): String = runCatching {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(java.time.Instant.parse(value).toEpochMilli()))
    }.getOrDefault("sin datos")
    private fun cloudLocationFresh(location: JSONObject): Boolean =
        SystemClock.elapsedRealtime() - cloudFetchedAt < location.optDouble("valid_for_seconds", 0.0) * 1000

    private fun cloudAction(action: String, fields: JSONObject = JSONObject(), after: ((JSONObject) -> Unit)? = null) {
        if (cloudBusy || authBusy || googleAccount == null) return
        val account = googleAccount!!.id
        cloudBusy = true; cloudMessage = "Conectando con tu hogar…"
        if (resumed && tab == 4) render()
        worker.execute {
            val requestedAt = SystemClock.elapsedRealtime()
            val result = runCatching { HomeClient(this, auth).request(action, fields) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                cloudBusy = false
                if (googleAccount?.id != account) return@runOnUiThread
                result.onSuccess {
                    if (action != "invite") { cloudState = it; cloudFetchedAt = requestedAt }
                    cloudMessage = if (action == "invite") "Invitación generada. Vence en 15 minutos." else "Hogar actualizado."
                    if (resumed && tab == 4) { render(); after?.invoke(it) }
                }.onFailure {
                    cloudState = null
                    cloudMessage = if (it is java.io.IOException) "Sin conexión. Revisá Internet y actualizá el hogar." else it.message ?: "No se pudo actualizar el hogar."
                    if (resumed && tab == 4) render()
                }
            }
        }
    }
    private fun startHomeLocation() {
        confirm("Compartir mi ubicación", "El administrador de tu hogar podrá ver tu posición reciente. El envío continúa al salir de la app, con una notificación visible para detenerlo. Necesita Internet, ubicación y notificaciones activadas. El mapa integrado usa OpenFreeMap y comunica al proveedor la zona visible. Si abrís el punto en OpenStreetMap, se enviará esa coordenada a su sitio.") {
            val required = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= 33) required.add(Manifest.permission.POST_NOTIFICATIONS)
            permissions(required.toTypedArray()) {
                check(getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled()) { "Habilitá las notificaciones de B-Security en Ajustes." }
                startForegroundService(Intent(this, HomeLocationService::class.java).setAction(HomeLocationService.START))
                handler.postDelayed({ if (resumed && tab == 4) { render(); cloudAction("status") } }, 1200)
            }
        }
    }

    private fun legacyFamilySection() {
        card("Grupo anterior · servidor privado", "Este vínculo conserva las reglas y los datos del servidor privado. El hogar conectado con Google se configura arriba.")
        button(if(family.configured) "Servidor de Familia · configurado" else "Configurar servidor HTTPS", enabled = !familyBusy) { pin {
            val entry=EditText(this).apply { hint="https://servidor:puerto"; inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI; setText(family.endpoint) }
            val dialog=AlertDialog.Builder(this).setTitle("Servidor HTTPS").setMessage("Usá tu servidor privado. Cambiar de servidor elimina la identidad local del grupo; salí del grupo anterior primero.").setView(entry).setNegativeButton("Cancelar",null).setPositiveButton("Guardar",null).create()
            dialog.setOnShowListener { dialog.getButton(-1).setOnClickListener {
                safely { family.configure(entry.text.toString()); familyState=null; dialog.dismiss(); render() }
            } }; dialog.show()
        } }
        if(!family.configured) {
            card("Configuración pendiente", "Todavía no hay un servicio de Familia conectado. No se está compartiendo ubicación ni datos del teléfono.", "unknown"); return
        }
        button(if(familyBusy) "Sincronizando…" else "Actualizar grupo y reglas", enabled = !familyBusy) { syncFamily() }
        live("family","Sin sincronizar con el servidor.")
        val state=familyState
        if(state == null) {
            card("Conectá tu grupo", "Actualizá para consultar tu vínculo con este servidor. Si no hay un grupo, vas a poder crearlo o unirte con una invitación.")
            return
        }
        val group=state.optJSONObject("family")
        if(group==null) {
            button("Crear mi grupo familiar", enabled = !familyBusy) { pin {
                confirm("Crear grupo", "Este dispositivo será el responsable del grupo. Se compartirá su nombre de modelo con los integrantes.") {
                    familyAction("/v1/family",JSONObject().put("name","Mi familia"))
                }
            } }
            button("Unirme mediante invitación", enabled = !familyBusy) {
                val code=EditText(this).apply { hint="Código de invitación"; inputType=InputType.TYPE_CLASS_TEXT }
                AlertDialog.Builder(this).setTitle("Unirme al grupo").setMessage("El responsable verá el modelo, alertas resumidas y última sincronización. GPS y reglas remotas empiezan desactivados y requieren tu permiso.").setView(code)
                    .setNegativeButton("Cancelar",null).setPositiveButton("Aceptar y unirme") { _,_ ->
                        familyAction("/v1/join",JSONObject().put("code",code.text.toString()))
                    }.show()
            }; return
        }
        val parent=group.optString("role")=="parent"
        card(group.optString("name"),if(parent) "Este dispositivo es responsable del grupo." else "Dispositivo integrante. Se informa el resumen al actualizar.")
        val location=CheckBox(this).apply {
            text="Compartir mi posición GPS reciente"; setTextColor(ink); isChecked=family.shareLocation; isEnabled=!familyBusy
            setOnCheckedChangeListener { _,checked ->
                family.shareLocation=checked
                syncFamily()
            }
        }; content.addView(location)
        if(!parent) {
            val controls=CheckBox(this).apply {
                text="Aceptar reglas remotas y compartir lista de apps"; setTextColor(ink); isChecked=family.acceptControls; isEnabled=!familyBusy
                setOnClickListener {
                    val requested=isChecked; isChecked=family.acceptControls
                    pin {
                        if(requested) confirm("Aceptar control remoto", "El responsable podrá proponer bloqueos y tiempos para las apps que compartas. Se aplican cuando sincronices con B-Security en primer plano y el servicio parental esté activo. Podés revocar este permiso aquí con el PIN local.") {
                            family.acceptControls=true; syncFamily(); render()
                        } else { family.acceptControls=false; syncFamily(); render() }
                    }
                }
            }; content.addView(controls)
        }
        card("Cuándo se comparte", "Al actualizar, y cada 30 segundos mientras Familia está abierta. GPS solo se envía si lo habilitaste, hay una posición de menos de 2 minutos y la medición sigue activa. No hay rastreo ni recepción de reglas en segundo plano. Al quitar permisos se reemplaza el informe en la próxima sincronización exitosa.")
        if(parent) button("Generar invitación de un uso (15 min)", enabled = !familyBusy) { pin {
            if(familyBusy) return@pin
            familyBusy=true
            output["family"]="Generando invitación privada…"; render()
            worker.execute {
                val result=runCatching { family.request("/v1/invite",JSONObject()).getString("code") }
                runOnUiThread {
                    if(isDestroyed) return@runOnUiThread
                    familyBusy=false
                    output["family"]=if(result.isSuccess) "Invitación generada. Caduca en 15 minutos." else "No se pudo generar la invitación."
                    if(tab==4 && resumed) render()
                    result.onSuccess { code ->
                    if(!resumed) return@onSuccess
                    AlertDialog.Builder(this).setTitle("Invitación privada").setMessage(code).setNegativeButton("Cerrar",null)
                        .setPositiveButton("Compartir") { _,_ ->
                            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain")
                                .putExtra(Intent.EXTRA_TEXT, code),"Compartir código"))
                        }.show()
                }.onFailure {
                    if(it is FamilyHttpException && it.status==401) { familyState=null; if(tab==4 && resumed) render() }
                    message(it.message.orEmpty())
                } }
            }
        } }
        val members=group.optJSONArray("members") ?: JSONArray()
        for(i in 0 until members.length()) {
            val member=members.getJSONObject(i); val report=member.optJSONObject("report")
            val stamp=report?.optLong("at") ?: 0L
            val info=if(report==null) "Aún sin informe compartido." else {
                val loc=report.optJSONObject("location")
                "Última actualización: ${DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(Date(stamp))}\n" +
                    "${report.optInt("risks")} indicadores para revisar\n" +
                    if(loc!=null) "Posición compartida: ${loc.optDouble("lat")}, ${loc.optDouble("lon")}\n±${loc.optDouble("accuracy")} m · dato de hace ${loc.optLong("ageSeconds")} s al enviarlo." else "Sin posición compartida."
            }
            val box=card(member.optString("name"),info)
            if(parent && member.optString("role")!="parent" && report?.optBoolean("controls")==true)
                button("Configurar regla para este integrante",box, enabled = !familyBusy) { pin {
                    val apps=report.optJSONArray("apps") ?: JSONArray()
                    chooseApp((0 until apps.length()).map { apps.getJSONObject(it).let { a -> a.getString("pkg") to a.getString("name") } }) { pkg,name ->
                        val rules=JSONObject(member.optJSONObject("policy")?.optJSONObject("rules")?.toString() ?: "{}")
                        val r=rules.optJSONObject(pkg)
                        ruleDialog(name,AppRule(r?.optBoolean("blocked") ?: false,r?.optInt("minutes") ?: 0)) { updated ->
                            rules.put(pkg,JSONObject().put("blocked",updated.blocked).put("minutes",updated.minutes))
                            familyAction("/v1/policy",JSONObject().put("member",member.getString("id")).put("rules",rules))
                        }
                    }
                } }
        }
        button(if(parent) "Eliminar grupo y datos compartidos" else "Salir y borrar mi informe del grupo", enabled = !familyBusy) { pin {
            confirm("Eliminar vínculo", if(parent) "Se disolverá el grupo y se eliminarán informes, reglas remotas e invitaciones del servidor." else "Se borrarán tu informe y tus reglas remotas del servidor. Las reglas locales se conservan.") {
                familyAction("/v1/leave",JSONObject()) { family.clearMembership() }
            }
        } }
    }
    private fun report(): JSONObject {
        val value=JSONObject().put("consent",true).put("model",Build.MODEL).put("android",Build.VERSION.RELEASE)
            .put("risks",Diagnostics.device(this).count { it.level=="risk" }).put("controls",family.acceptControls)
        if(family.acceptControls) value.put("apps",JSONArray().apply {
            installedApps().take(150).forEach { (pkg,name) -> put(JSONObject().put("pkg",pkg).put("name",name)) }
        })
        if(family.shareLocation) sensors.sharedLocation?.let { loc ->
            val age=(SystemClock.elapsedRealtimeNanos()-loc.elapsedRealtimeNanos)/1_000_000_000
            if(age in 0..120) value.put("location",JSONObject().put("lat",loc.latitude).put("lon",loc.longitude).put("accuracy",loc.accuracy).put("ageSeconds",age))
        }
        return value
    }
    private fun familyAction(path:String,body:JSONObject,after:()->Unit={}) {
        if(familyBusy) return
        familyBusy=true
        output["family"]="Guardando cambios en el grupo…"
        if(tab==4 && resumed) render()
        worker.execute {
            val result=runCatching { family.request(path,body) }
            runOnUiThread {
                if(isDestroyed) return@runOnUiThread
                familyBusy=false
                result.onSuccess { after(); syncFamily() }.onFailure {
                    if(it is FamilyHttpException && it.status==401) familyState=null
                    output["family"]="No se pudo guardar: ${it.message}"
                    if(tab==4 && resumed) render()
                    message(it.message.orEmpty())
                }
            }
        }
    }
    private fun syncFamily() {
        if(familyBusy || !family.configured) return
        familyBusy=true
        output["family"]="Consultando el grupo y actualizando reglas…"
        if(tab==4 && resumed) render()
        worker.execute {
            val result=runCatching { FamilySync(family,policy).sync { report() } }
            runOnUiThread {
                if(isDestroyed) return@runOnUiThread
                familyBusy=false
                result.onSuccess {
                    familyState=it
                    output["family"]="Sincronizado: ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())}"
                }.onFailure {
                    if(it is FamilyHttpException && it.status==401) familyState=null
                    output["family"]="Sin sincronizar: ${it.message}. Los datos remotos pueden estar desactualizados."
                }
                if(tab==4 && resumed) render()
            }
        }
    }
}
