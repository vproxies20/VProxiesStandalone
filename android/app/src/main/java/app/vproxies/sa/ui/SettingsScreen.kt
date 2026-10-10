package app.vproxies.sa.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.vproxies.sa.BuildConfig
import app.vproxies.sa.bg.LogLevel
import app.vproxies.sa.bg.ServiceState
import app.vproxies.sa.core.AppSettings
import app.vproxies.sa.core.SettingsStore
import app.vproxies.sa.core.SingBoxConfigBuilder
import app.vproxies.sa.core.ThemeMode
import io.nekohasekai.libbox.Libbox
import org.json.JSONObject

@Composable
fun SettingsScreen(settings: AppSettings, contentPadding: PaddingValues) {
    val context = LocalContext.current
    var dnsServer by remember(settings.dnsServer) { mutableStateOf(settings.dnsServer) }
    var exportPasswords by remember { mutableStateOf(false) }
    var pendingImport by remember { mutableStateOf<SettingsStore.ParseResult?>(null) }
    var message by remember { mutableStateOf<String?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val json = SettingsStore.toJson(SettingsStore.current, forExport = true, includePasswords = exportPasswords).toString(2)
            context.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(json.toByteArray()) }
        }.onSuccess {
            ServiceState.log("Configuration exported${if (exportPasswords) " (with passwords)" else ""}.")
            message = "Configuration exported."
        }.onFailure { message = "Export failed: ${it.message}" }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val text = context.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
            SettingsStore.parse(JSONObject(text.trimStart('﻿')), fromExport = true)
        }.onSuccess { pendingImport = it }
            .onFailure { message = "This file is not a VProxies SA configuration: ${it.message}" }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = contentPadding.calculateTopPadding() + 12.dp, bottom = contentPadding.calculateBottomPadding() + 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionCard(title = "Network") {
            SwitchRow(
                "Strict route",
                "Keep app traffic inside the tunnel while routing is active.",
                settings.strictRoute,
            ) { v -> SettingsStore.update { it.copy(strictRoute = v) } }
            SwitchRow(
                "DNS through proxy (leak protection)",
                "Resolve DNS over TCP through the default proxy (or the first proxy). Off by default.",
                settings.remoteDns,
            ) { v -> SettingsStore.update { it.copy(remoteDns = v) } }
            if (settings.remoteDns) {
                OutlinedTextField(
                    value = dnsServer,
                    onValueChange = { v ->
                        dnsServer = v.trim()
                        if (SingBoxConfigBuilder.isIpLiteral(dnsServer)) SettingsStore.update { it.copy(dnsServer = dnsServer) }
                    },
                    label = { Text("DNS server (IPv4 or IPv6)") },
                    isError = !SingBoxConfigBuilder.isIpLiteral(dnsServer),
                    supportingText = { Text("For example 1.1.1.1 or 8.8.8.8") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            MutedText("Changes apply the next time you press Start / Apply.")
        }

        SectionCard(title = "Appearance") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { mode ->
                    FilterChip(
                        selected = settings.theme == mode,
                        onClick = { SettingsStore.update { it.copy(theme = mode) } },
                        label = { Text(mode.name) },
                    )
                }
            }
        }

        SectionCard(title = "Privacy") {
            SwitchRow(
                "Save proxy passwords",
                "Encrypted with a key kept in this device's Android Keystore. When off, passwords are forgotten when the app closes.",
                settings.savePasswords,
            ) { v -> SettingsStore.update { it.copy(savePasswords = v) } }
        }

        SectionCard(title = "Configuration transfer", subtitle = "JSON import and export, compatible with the Windows edition's proxy list") {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { exportPasswords = !exportPasswords }) {
                Checkbox(exportPasswords, { exportPasswords = it })
                Text("Include passwords in plain text when exporting", fontSize = 14.sp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { exportLauncher.launch("vproxies-sa-android.json") }) { Text("Export") }
                OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "*/*")) }) { Text("Import") }
            }
            MutedText("Windows exports keep passwords encrypted for that PC only, and Windows .exe rules do not apply on Android — re-enter passwords and choose apps after importing.")
        }

        SectionCard(title = "About") {
            Text("VProxies SA ${BuildConfig.VERSION_NAME} for Android", fontWeight = FontWeight.SemiBold)
            MutedText("Fully local: no account, API, telemetry or update requests.")
            MutedText("Routing core: sing-box ${runCatching { Libbox.version() }.getOrDefault("unavailable")} (GPL-3.0-or-later), https://github.com/SagerNet/sing-box")
            MutedText("Use only proxy servers that you own or are authorized to use.")
        }
    }

    pendingImport?.let { result ->
        val notes = buildList {
            add("${result.settings.proxies.size} proxy server(s), ${result.settings.rules.size} app rule(s).")
            if (result.skippedWindowsRules > 0) add("${result.skippedWindowsRules} Windows .exe rule(s) will be skipped.")
            if (result.proxiesWithoutPassword > 0) add("${result.proxiesWithoutPassword} proxy password(s) could not be read and must be re-entered.")
        }
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text("Import configuration?") },
            text = { Text("This replaces the current proxy list, rules and settings.\n\n" + notes.joinToString("\n")) },
            confirmButton = {
                TextButton(onClick = {
                    SettingsStore.replace(result.settings)
                    ServiceState.log("Configuration imported: ${notes.joinToString(" ")}", if (result.proxiesWithoutPassword > 0) LogLevel.WARN else null)
                    pendingImport = null
                }) { Text("Import") }
            },
            dismissButton = { TextButton(onClick = { pendingImport = null }) { Text("Cancel") } },
        )
    }

    message?.let {
        AlertDialog(
            onDismissRequest = { message = null },
            text = { Text(it) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } },
        )
    }
}

@Composable
private fun SwitchRow(title: String, description: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            MutedText(description)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
