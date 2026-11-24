import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Client-Secrets der MyOpel-App kommen aus local.properties (nicht im Repo).
// Fehlen sie, bleibt das Feld in der App leer und das Secret wird wie bisher von Hand eingetragen.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun secretField(key: String): String =
    "\"" + localProps.getProperty(key, "").trim().replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "com.astraconnect.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.astraconnect.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        buildConfigField("String", "MYOPEL_SECRET", secretField("myopel.clientSecret"))
        buildConfigField("String", "MYOPEL_SECRET_ALT", secretField("myopel.clientSecret.alt"))
    }

    // Release-Signierung: Keystore und Passwörter kommen aus local.properties (nicht im Repo).
    // Fehlen die Einträge, wird der Release-Build unsigniert gebaut.
    val releaseStoreFile = localProps.getProperty("release.storeFile")?.trim()?.takeIf { it.isNotEmpty() }
    signingConfigs {
        if (releaseStoreFile != null) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = localProps.getProperty("release.storePassword")
                keyAlias = localProps.getProperty("release.keyAlias")
                keyPassword = localProps.getProperty("release.keyPassword")
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
        buildConfig = true
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
