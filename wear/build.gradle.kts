// Uhr-App (Wear OS): Tile und Complication. Daten und Befehle laufen über die Handy-App.
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Gleiche Version und gleicher Schlüssel wie die Handy-App (build.gradle.kts im Hauptordner)
val appVersion: String by rootProject.extra
val appVersionCode: Int by rootProject.extra
val releaseStoreFile: String by rootProject.extra
val releaseStorePassword: String by rootProject.extra
val releaseKeyAlias: String by rootProject.extra
val releaseKeyPassword: String by rootProject.extra

android {
    namespace = "com.astraconnect.wear"
    compileSdk = 36

    defaultConfig {
        // Muss der Paket-ID der Handy-App entsprechen – sonst stellt die Data Layer API nichts zu
        applicationId = "com.astraconnect.app"
        minSdk = 30 // Wear OS 3
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
        // Wie bei der Handy-App, damit Debug-Uhr und Debug-Handy zusammenfinden
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":wear-shared"))
    implementation("androidx.core:core-ktx:1.16.0")

    // Tile
    implementation("androidx.wear.tiles:tiles:1.5.0")
    implementation("androidx.wear.protolayout:protolayout:1.3.0")
    implementation("androidx.wear.protolayout:protolayout-material:1.3.0")
    implementation("androidx.concurrent:concurrent-futures:1.2.0")
    // Nur für die Tile-Vorschau im Debug-Build (TilePreviewActivity)
    debugImplementation("androidx.wear.tiles:tiles-renderer:1.5.0")
    // Der Renderer bringt Guava nur zur Laufzeit mit – ohne das fehlt ListenableFuture beim Kompilieren
    debugImplementation("com.google.guava:guava:33.2.1-android")

    // Complication
    implementation("androidx.wear.watchface:watchface-complications-data-source-ktx:1.2.1")

    // Verbindung zum Handy
    implementation("com.google.android.gms:play-services-wearable:19.0.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
}
