package com.astraconnect.app.ui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.astraconnect.app.AppViewModel
import com.astraconnect.app.data.ApiConfig
import com.astraconnect.app.data.Currency

@Composable
fun LoginScreen(vm: AppViewModel) {
    var showWeb by rememberSaveable { mutableStateOf(false) }
    if (showWeb) {
        OAuthWebScreen(
            url = vm.auth.authorizeUrl(),
            onCode = { showWeb = false; vm.onAuthCode(it) },
            onError = { showWeb = false; vm.showError(it) },
            onCancel = { showWeb = false },
        )
        return
    }

    var country by rememberSaveable { mutableStateOf(vm.auth.country) }
    var clientId by rememberSaveable { mutableStateOf(vm.auth.clientId) }
    val market = ApiConfig.market(country)
    var secret by rememberSaveable { mutableStateOf(vm.auth.clientSecret) }
    var showSecret by remember { mutableStateOf(false) }
    var advanced by rememberSaveable { mutableStateOf(vm.auth.clientSecret.isBlank()) }
    var manualCode by rememberSaveable { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    val ui = vm.ui

    Scaffold { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Spacer(Modifier.height(24.dp))
            Icon(Icons.Filled.DirectionsCar, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(56.dp))
            Text("Astra Connect", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Text(
                "Melde dich mit deinem MyOpel-Konto an. Der Login läuft über die offizielle Opel-Anmeldeseite – " +
                    "dein Passwort wird nicht in der App gespeichert.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            CountryPicker(
                selected = market,
                enabled = !ui.loggingIn,
                onSelect = { m ->
                    // Client-ID und Secret nur mitziehen, wenn sie nicht von Hand geändert wurden.
                    if (clientId.isBlank() || ApiConfig.isMarketClientId(clientId.trim())) {
                        if (secret.isBlank() || secret.trim() == ApiConfig.builtInSecret(clientId)) {
                            secret = ApiConfig.builtInSecret(m.clientId)
                        }
                        clientId = m.clientId
                        if (secret.isBlank()) advanced = true
                    }
                    country = m.code
                },
            )

            if (ui.loggingIn) LinearProgressIndicator(Modifier.fillMaxWidth())
            ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            Button(
                onClick = {
                    vm.saveClientConfig(country, clientId, secret)
                    if (secret.isBlank()) { advanced = true; vm.showError("Bitte zuerst das Client-Secret eintragen.") }
                    else { vm.showError(null); showWeb = true }
                },
                enabled = !ui.loggingIn,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Mit MyOpel anmelden") }

            TextButton(onClick = { advanced = !advanced }) {
                Icon(if (advanced) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
                Text("  API-Zugang & manueller Login")
            }

            if (advanced) {
                Text(
                    "Die App spricht dieselbe Stellantis-API an wie die MyOpel-App. Dafür werden Client-ID und " +
                        "Client-Secret der MyOpel-App für dein Land benötigt. Diese findest du z. B. in der Datei configs.json " +
                        "des Open-Source-Projekts „homeassistant-stellantis-vehicles“ (Eintrag MyOpel → ${market.code}). " +
                        "Die Client-ID wird beim Länderwechsel automatisch angepasst – das Secret muss zur Client-ID passen." +
                        if (ApiConfig.builtInSecret(clientId).isNotBlank())
                            "\nDas Secret ist bereits in der App hinterlegt; ein eigener Eintrag hat Vorrang." else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = clientId, onValueChange = { clientId = it },
                    label = { Text("Client-ID") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = secret, onValueChange = { secret = it },
                    label = { Text("Client-Secret") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (showSecret) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { showSecret = !showSecret }) {
                            Icon(if (showSecret) Icons.Filled.VisibilityOff else Icons.Filled.Visibility, null)
                        }
                    },
                )

                Spacer(Modifier.height(8.dp))
                Text("Manueller Login (falls die Anmeldeseite in der App hakt)", fontWeight = FontWeight.SemiBold)
                Text(
                    "1. Link kopieren und im Browser (am PC mit geöffneten Entwicklertools → Netzwerk) öffnen.\n" +
                        "2. Einloggen. Der Browser versucht danach „${market.redirectUri}?code=…“ zu öffnen.\n" +
                        "3. Den Wert hinter „code=“ hier einfügen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = {
                    vm.saveClientConfig(country, clientId, secret)
                    clipboard.setText(AnnotatedString(vm.auth.authorizeUrl()))
                }) { Text("Anmelde-Link kopieren") }
                OutlinedTextField(
                    value = manualCode, onValueChange = { manualCode = it },
                    label = { Text("Code") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        vm.saveClientConfig(country, clientId, secret)
                        val code = manualCode.substringAfter("code=").substringBefore("&").trim()
                        vm.onAuthCode(code)
                    },
                    enabled = manualCode.isNotBlank() && !ui.loggingIn,
                ) { Text("Code verwenden") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun OAuthWebScreen(url: String, onCode: (String) -> Unit, onError: (String) -> Unit, onCancel: () -> Unit) {
    var loading by remember { mutableStateOf(true) }
    var handled by remember { mutableStateOf(false) }
    BackHandler(onBack = onCancel)

    fun intercept(uri: Uri): Boolean {
        if (uri.scheme != ApiConfig.SCHEME) return false
        if (!handled) {
            handled = true
            val code = uri.getQueryParameter("code")
            if (code != null) onCode(code)
            else onError(uri.getQueryParameter("error_description") ?: uri.getQueryParameter("error") ?: "Anmeldung abgebrochen.")
        }
        return true
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text("MyOpel-Anmeldung") },
            navigationIcon = { IconButton(onClick = onCancel) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") } },
        )
    }) { pad ->
        Box(Modifier.fillMaxSize().padding(pad)) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.userAgentString = settings.userAgentString.replace("; wv", "")
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) =
                                intercept(request.url)

                            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                                if (intercept(Uri.parse(url))) view.stopLoading() else loading = true
                            }

                            override fun onPageFinished(view: WebView, url: String) {
                                loading = false
                            }
                        }
                        loadUrl(url)
                    }
                },
            )
            if (loading) CircularProgressIndicator(Modifier.align(Alignment.Center))
        }
    }
}

/** Auswahl des MyOpel-Markts. Der Login-Server ist überall derselbe, Land bestimmt Locale/Redirect/Client-ID. */
@Composable
private fun CountryPicker(selected: ApiConfig.Market, enabled: Boolean, onSelect: (ApiConfig.Market) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = "${flag(selected.code)}  ${selected.name}",
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text("Land deines MyOpel-Kontos") },
            trailingIcon = { Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        // Unsichtbare Fläche über dem Feld: ein readOnly-Textfeld schluckt sonst den Klick.
        Box(Modifier.matchParentSize().clickable(enabled = enabled) { open = true })
        DropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            modifier = Modifier.heightIn(max = 420.dp),
        ) {
            ApiConfig.MARKETS.forEach { m ->
                DropdownMenuItem(
                    text = {
                        Text(
                            "${flag(m.code)}  ${m.name}" + if (m.currency != Currency.EUR) " · ${m.currency.name}" else "",
                            fontWeight = if (m.code == selected.code) FontWeight.Bold else null,
                        )
                    },
                    onClick = { open = false; onSelect(m) },
                )
            }
        }
    }
}

/** Flaggen-Emoji aus dem ISO-Ländercode (zwei Regional-Indicator-Zeichen). */
private fun flag(code: String): String =
    code.uppercase().map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")
