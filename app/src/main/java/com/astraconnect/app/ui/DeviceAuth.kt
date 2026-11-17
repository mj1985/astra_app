package com.astraconnect.app.ui

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.hardware.biometrics.BiometricManager
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.CancellationSignal
import androidx.activity.compose.ManagedActivityResultLauncher
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.RequiresApi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Bestätigung per Fingerabdruck/Gesicht oder Geräte-PIN vor sicherheitskritischen Fernbefehlen
 * (z. B. Entriegeln). Schützt davor, dass jemand mit dem entsperrten Handy das Auto öffnet.
 *
 *  - ab Android 10: System-Dialog (BiometricPrompt) mit Geräte-PIN als Alternative
 *  - Android 8/9:   Abfrage der Displaysperre (PIN/Muster/Passwort) über den Keyguard
 */
class DeviceAuth internal constructor(
    private val ctx: Context,
    private val launcher: ManagedActivityResultLauncher<Intent, ActivityResult>,
    private val pending: MutableState<(() -> Unit)?>,
) {
    private val keyguard = ctx.getSystemService(KeyguardManager::class.java)

    /** false, wenn das Gerät keine Displaysperre hat – dann ist keine Prüfung möglich. */
    val available: Boolean get() = keyguard?.isDeviceSecure == true

    fun authenticate(title: String, subtitle: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        if (!available) {
            onError("Auf diesem Gerät ist keine Displaysperre eingerichtet.")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) prompt(title, subtitle, onSuccess, onError)
        else legacy(title, subtitle, onSuccess, onError)
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun prompt(title: String, subtitle: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        val builder = BiometricPrompt.Builder(ctx).setTitle(title).setSubtitle(subtitle)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setAllowedAuthenticators(
                BiometricManager.Authenticators.BIOMETRIC_STRONG or BiometricManager.Authenticators.DEVICE_CREDENTIAL
            )
        } else {
            @Suppress("DEPRECATION")
            builder.setDeviceCredentialAllowed(true)
        }
        builder.build().authenticate(CancellationSignal(), ctx.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                // Abbruch durch den Nutzer ist kein Fehler
                if (errorCode != BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED &&
                    errorCode != BiometricPrompt.BIOMETRIC_ERROR_CANCELED
                ) onError(errString.toString())
            }
        })
    }

    private fun legacy(title: String, subtitle: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        @Suppress("DEPRECATION")
        val intent = keyguard?.createConfirmDeviceCredentialIntent(title, subtitle)
            ?: return onError("Die Displaysperre konnte nicht abgefragt werden.")
        pending.value = onSuccess
        launcher.launch(intent)
    }
}

@Composable
fun rememberDeviceAuth(): DeviceAuth {
    val ctx = LocalContext.current
    val pending = remember { mutableStateOf<(() -> Unit)?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val action = pending.value
        pending.value = null
        if (result.resultCode == Activity.RESULT_OK) action?.invoke()
    }
    return remember(ctx, launcher) { DeviceAuth(ctx, launcher, pending) }
}
