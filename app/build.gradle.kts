import java.util.Properties
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
val local = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
val tmdbKey = (local.getProperty("TMDB_API_KEY") ?: System.getenv("TMDB_API_KEY") ?: "")
val firebaseApiKey = (local.getProperty("FIREBASE_API_KEY") ?: "")
val firebaseAppId = (local.getProperty("FIREBASE_APP_ID") ?: "")
val firebaseProjectId = (local.getProperty("FIREBASE_PROJECT_ID") ?: "")
val updateRepo = (local.getProperty("UPDATE_REPO") ?: System.getenv("UPDATE_REPO") ?: "").trim()
val omdbKey = (local.getProperty("OMDB_API_KEY") ?: System.getenv("OMDB_API_KEY") ?: "")
// Optional: free Watchmode key (api.watchmode.com) for exact-title "Watch on" links.
val watchmodeKey = (local.getProperty("WATCHMODE_API_KEY") ?: System.getenv("WATCHMODE_API_KEY") ?: "").trim()
android {
    namespace = "com.cineglass.tv"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.cineglass.tv"
        minSdk = 26
        targetSdk = 35
        versionCode = 24
        versionName = "1.8.5"
        buildConfigField("String", "TMDB_API_KEY", "\"${tmdbKey.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        buildConfigField("String", "OMDB_API_KEY", "\"${omdbKey.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        buildConfigField("String", "WATCHMODE_API_KEY", "\"${watchmodeKey.replace("\\", "\\\\").replace("\"", "\\\"")}\"")
        buildConfigField("String", "FIREBASE_API_KEY", "\"${firebaseApiKey}\"")
        buildConfigField("String", "FIREBASE_APP_ID", "\"${firebaseAppId}\"")
        buildConfigField("String", "FIREBASE_PROJECT_ID", "\"${firebaseProjectId}\"")
        // GitHub "owner/repo" whose Releases hold update APKs; blank hides the updater.
        buildConfigField("String", "UPDATE_REPO", "\"${updateRepo}\"")
    }
    // Release signing comes from local.properties (never committed). Without it, release builds are unsigned.
    val releaseStore = local.getProperty("RELEASE_STORE_FILE")?.let { rootProject.file(it) }
    signingConfigs {
        if (releaseStore != null && releaseStore.exists()) create("release") {
            storeFile = releaseStore
            storePassword = local.getProperty("RELEASE_STORE_PASSWORD")
            keyAlias = local.getProperty("RELEASE_KEY_ALIAS")
            keyPassword = local.getProperty("RELEASE_KEY_PASSWORD")
        }
    }
    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.findByName("release")
            // Shrinking stays off: Gson/Retrofit models rely on reflection and have no keep rules yet.
            isMinifyEnabled = false
        }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    // JVM unit tests cover pure logic (parsing, routing, progress); Android stubs return defaults instead of throwing.
    testOptions { unitTests.isReturnDefaultValues = true }
}
dependencies {
    val torrentVersion = "2.0.12.9"
    implementation("com.frostwire:jlibtorrent:$torrentVersion")
    runtimeOnly("com.frostwire:jlibtorrent-android-arm:$torrentVersion")
    runtimeOnly("com.frostwire:jlibtorrent-android-arm64:$torrentVersion")
    runtimeOnly("com.frostwire:jlibtorrent-android-x86:$torrentVersion")
    runtimeOnly("com.frostwire:jlibtorrent-android-x86_64:$torrentVersion")
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    // In-app player for public-domain films (Internet Archive): play/pause/seek with a TV remote or touch.
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    // Adaptive streams for the hybrid player (same Media3 version as the core).
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-exoplayer-dash:1.4.1")
    testImplementation("junit:junit:4.13.2")
    implementation("org.apache.commons:commons-csv:1.11.0")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
