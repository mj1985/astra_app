import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

/** Wert aus local.properties, sonst aus der Umgebungsvariablen (Release-Workflow auf GitHub). */
fun localOrEnv(key: String, env: String): String? =
    localProps.getProperty(key)?.takeIf { it.isNotBlank() } ?: System.getenv(env)?.takeIf { it.isNotBlank() }

// Version aus dem Git-Tag (Release-Workflow: -PappVersion=v1.2), sonst 1.0.
// versionCode = Haupt × 10000 + Neben × 100 + Patch, damit jede neue Version als Update installiert werden kann.
val appVersion = ((findProperty("appVersion") as String?) ?: "1.0").removePrefix("v")
val appVersionCode = Regex("""(\d+)\.(\d+)(?:\.(\d+))?""").matchEntire(appVersion)
    ?.destructured?.let { (major, minor, patch) -> major.toInt() * 10000 + minor.toInt() * 100 + (patch.toIntOrNull() ?: 0) }
    ?: error("Version „$appVersion“ hat nicht die Form 1.2 oder 1.2.3")

android {
    namespace = "com.astraconnect.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.astraconnect.app"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersion
    }

    // Release-Signierung: Keystore und Passwörter kommen aus local.properties (nicht im Repo),
    // im Release-Workflow aus den Umgebungsvariablen RELEASE_*. Fehlen sie, wird der Release-Build unsigniert gebaut.
    val releaseStoreFile = localOrEnv("release.storeFile", "RELEASE_STORE_FILE")?.trim()
    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = localOrEnv("release.storePassword", "RELEASE_STORE_PASSWORD")
                keyAlias = localOrEnv("release.keyAlias", "RELEASE_KEY_ALIAS")
                keyPassword = localOrEnv("release.keyPassword", "RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5")
    implementation("androidx.work:work-runtime-ktx:2.10.3")
    implementation("androidx.documentfile:documentfile:1.1.0")
    // Karten (OpenStreetMap)
    implementation("org.osmdroid:osmdroid-android:6.1.20")

    testImplementation("junit:junit:4.13.2")
    // Echte org.json-Implementierung für Unit-Tests (Android-Stub liefert nur Defaultwerte)
    testImplementation("org.json:json:20240303")
}
