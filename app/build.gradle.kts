plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Version und Signierung kommen aus der build.gradle.kts im Hauptordner (gemeinsam mit der Uhr-App)
val appVersion: String by rootProject.extra
val appVersionCode: Int by rootProject.extra
val debugVersionCode: Int by rootProject.extra
val releaseStoreFile: String by rootProject.extra
val releaseStorePassword: String by rootProject.extra
val releaseKeyAlias: String by rootProject.extra
val releaseKeyPassword: String by rootProject.extra

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

    signingConfigs {
        if (releaseStoreFile.isNotEmpty()) {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        // Eigene Paket-ID: Debug- und Release-Version lassen sich nebeneinander installieren
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
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

// Debug-Builds: fester, hoher versionCode – verhindert „INSTALL_FAILED_VERSION_DOWNGRADE“ beim Neuinstallieren
androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        variant.outputs.forEach { it.versionCode.set(debugVersionCode) }
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
    // Uhr-App (Wear OS): Status an die Uhr, Befehle von der Uhr
    implementation(project(":wear-shared"))
    implementation("com.google.android.gms:play-services-wearable:19.0.0")

    testImplementation("junit:junit:4.13.2")
    // Echte org.json-Implementierung für Unit-Tests (Android-Stub liefert nur Defaultwerte)
    testImplementation("org.json:json:20240303")
}
