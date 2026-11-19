import java.util.Properties

plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

// Personal server addresses stay in an ignored, local-only properties file.
val pilotProperties = Properties().apply {
    val config = rootProject.file("pilot.local.properties")
    if (config.isFile) config.inputStream().use { load(it) }
}
val demoBuild = providers.gradleProperty("bsecurityDemo").orNull == "true"
val defaultFamilyEndpoint = if (demoBuild) "https://family.example.invalid"
    else pilotProperties.getProperty("family.endpoint", "https://family.example.invalid")
// Only public client configuration belongs in the APK. Never add OAuth secrets or service keys.
val googleProperties = Properties().apply {
    val config = rootProject.file("google.properties")
    if (config.isFile) config.inputStream().use { load(it) }
}
require(googleProperties.getProperty("supabase.publishableKey", "").let {
    it.isEmpty() || it.matches(Regex("sb_publishable_[A-Za-z0-9_-]{8,}"))
}) { "supabase.publishableKey solo admite una clave pública sb_publishable_." }

android {
    namespace = "com.bsecurity.app"
    compileSdk = 35
    defaultConfig { applicationId = "com.bsecurity.app"; minSdk = 26; targetSdk = 35; versionCode = 3; versionName = "0.2.0-demo"; testInstrumentationRunner = "com.bsecurity.app.PilotInstrumentation" }
    defaultConfig { resValue("string", "family_endpoint", defaultFamilyEndpoint) }
    defaultConfig {
        resValue("string", "supabase_url", googleProperties.getProperty("supabase.url", ""))
        resValue("string", "supabase_publishable_key", googleProperties.getProperty("supabase.publishableKey", ""))
        resValue("string", "google_web_client_id", googleProperties.getProperty("google.webClientId", ""))
    }
    buildFeatures { resValues = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    buildTypes { release { isMinifyEnabled = false } }
}
dependencies {
    implementation("androidx.credentials:credentials:1.6.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.6.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    implementation("org.maplibre.gl:android-sdk-opengl:13.7.1")
    testImplementation("junit:junit:4.13.2")
}
