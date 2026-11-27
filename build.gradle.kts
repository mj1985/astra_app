import java.util.Properties

plugins {
    id("com.android.application") version "8.13.0" apply false
    id("com.android.library") version "8.13.0" apply false
    id("org.jetbrains.kotlin.android") version "2.2.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20" apply false
}

// Gemeinsam für Handy- und Uhr-App: gleiche Version und gleicher Release-Schlüssel –
// mit anderem Schlüssel könnten sich die beiden Apps nicht über die Data Layer API erreichen.

val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

/** Wert aus local.properties, sonst aus der Umgebungsvariablen (Release-Workflow auf GitHub); leer, wenn beides fehlt. */
fun localOrEnv(key: String, env: String): String =
    localProps.getProperty(key)?.takeIf { it.isNotBlank() } ?: System.getenv(env)?.takeIf { it.isNotBlank() } ?: ""

// Version aus dem Git-Tag (Release-Workflow: -PappVersion=v1.2), sonst 1.0.
// versionCode = Haupt × 10000 + Neben × 100 + Patch, damit jede neue Version als Update installiert werden kann.
val appVersion = ((findProperty("appVersion") as String?) ?: "1.0").removePrefix("v")
val appVersionCode = Regex("""(\d+)\.(\d+)(?:\.(\d+))?""").matchEntire(appVersion)
    ?.destructured?.let { (major, minor, patch) -> major.toInt() * 10000 + minor.toInt() * 100 + (patch.toIntOrNull() ?: 0) }
    ?: error("Version „$appVersion“ hat nicht die Form 1.2 oder 1.2.3")

extra["appVersion"] = appVersion
extra["appVersionCode"] = appVersionCode

// Debug-Builds bekommen einen festen, hohen versionCode (unabhängig vom Release-Tag).
// So ist ein erneutes Installieren nie ein Downgrade – egal, mit welchem -PappVersion gebaut wurde.
extra["debugVersionCode"] = 2_000_000_000

// Release-Signierung: Keystore und Passwörter aus local.properties (nicht im Repo), im Release-Workflow
// aus den Umgebungsvariablen RELEASE_*. Ohne Keystore werden Release-Builds unsigniert gebaut.
extra["releaseStoreFile"] = localOrEnv("release.storeFile", "RELEASE_STORE_FILE").trim()
extra["releaseStorePassword"] = localOrEnv("release.storePassword", "RELEASE_STORE_PASSWORD")
extra["releaseKeyAlias"] = localOrEnv("release.keyAlias", "RELEASE_KEY_ALIAS")
extra["releaseKeyPassword"] = localOrEnv("release.keyPassword", "RELEASE_KEY_PASSWORD")
