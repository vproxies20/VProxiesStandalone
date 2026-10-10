package app.vproxies.sa.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.vproxies.sa.bg.LogLevel
import app.vproxies.sa.bg.ServiceState
import app.vproxies.sa.core.AppSettings
import app.vproxies.sa.core.LocalProxy
import app.vproxies.sa.core.ProxyProtocol
import app.vproxies.sa.core.ProxyTester
import app.vproxies.sa.core.SettingsStore
import app.vproxies.sa.core.TARGET_DIRECT
import app.vproxies.sa.ui.theme.LocalSurfaces
import app.vproxies.sa.ui.theme.VColors
import kotlinx.coroutines.launch

private sealed interface CheckState {
    data object Running : CheckState
    data class Ok(val ms: Long) : CheckState
    data class Failed(val message: String) : CheckState
}

@Composable
fun ProxiesScreen(settings: AppSettings, contentPadding: PaddingValues) {
    var editing by remember { mutableStateOf<LocalProxy?>(null) }
    val checks = remember { mutableStateMapOf<String, CheckState>() }
    val scope = rememberCoroutineScope()
    val s = LocalSurfaces.current

    fun check(proxy: LocalProxy) {
        checks[proxy.id] = CheckState.Running
        scope.launch {
            checks[proxy.id] = try {
                val ms = ProxyTester.test(proxy)
                ServiceState.log("Proxy check '${proxy.name}' succeeded in $ms ms.")
                CheckState.Ok(ms)
            } catch (e: Exception) {
                val message = e.message ?: e.javaClass.simpleName
                ServiceState.log("Proxy check '${proxy.name}' failed: $message", LogLevel.WARN)
                CheckState.Failed(message)
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp, top = contentPadding.calculateTopPadding() + 12.dp,
            bottom = contentPadding.calculateBottomPadding() + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Proxy servers", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    MutedText("HTTP, HTTPS, SOCKS4A and SOCKS5 servers you own or are authorized to use.")
                }
                Button(onClick = { editing = LocalProxy(name = nextProxyName(settings)) }) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.padding(2.dp))
                    Text("Add")
                }
            }
        }
        if (settings.proxies.isEmpty()) {
            item {
                SectionCard {
                    Text("No proxy servers yet", fontWeight = FontWeight.SemiBold)
                    MutedText("Add a proxy, then route apps to it on the Apps tab or choose it as the default action.")
                }
            }
        }
        items(settings.proxies, key = { it.id }) { proxy ->
            val state = checks[proxy.id]
            SectionCard(Modifier.clickable { editing = proxy }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(proxy.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Text("${proxy.protocol} · ${proxy.endpointText}", color = s.muted, fontSize = 13.sp)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (proxy.username.isNotBlank()) {
                                Icon(Icons.Filled.Lock, contentDescription = null, tint = s.muted, modifier = Modifier.padding(end = 4.dp).size(14.dp))
                            }
                            Text(proxy.authenticationText, color = s.muted, fontSize = 12.sp)
                        }
                    }
                    OutlinedButton(onClick = { check(proxy) }, enabled = state != CheckState.Running) {
                        if (state == CheckState.Running) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        } else {
                            Text("Check")
                        }
                    }
                }
                when (state) {
                    is CheckState.Ok -> Text("Connected through proxy in ${state.ms} ms", color = VColors.Success, fontSize = 12.sp)
                    is CheckState.Failed -> Text(state.message, color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                    else -> Unit
                }
            }
        }
    }

    editing?.let { proxy ->
        ProxyEditor(
            initial = proxy,
            isNew = settings.proxies.none { it.id == proxy.id },
            onDismiss = { editing = null },
            onSave = { saved ->
                SettingsStore.update { current ->
                    val exists = current.proxies.any { it.id == saved.id }
                    current.copy(proxies = if (exists) current.proxies.map { if (it.id == saved.id) saved else it } else current.proxies + saved)
                }
                checks.remove(saved.id)
                editing = null
            },
            onDelete = { deleted ->
                SettingsStore.update { current ->
                    current.copy(
                        proxies = current.proxies.filterNot { it.id == deleted.id },
                        rules = current.rules.map { if (it.target == deleted.id) it.copy(target = TARGET_DIRECT) else it },
                        defaultTarget = if (current.defaultTarget == deleted.id) TARGET_DIRECT else current.defaultTarget,
                    )
                }
                ServiceState.log("Deleted proxy '${deleted.name}'. Rules that used it now go Direct.")
                editing = null
            },
            existingNames = settings.proxies.filter { it.id != proxy.id }.map { it.name.trim().lowercase() }.toSet(),
        )
    }
}

private fun nextProxyName(settings: AppSettings): String {
    var n = settings.proxies.size + 1
    val names = settings.proxies.map { it.name.lowercase() }.toSet()
    while ("proxy $n" in names) n++
    return "Proxy $n"
}

@Composable
private fun ProxyEditor(
    initial: LocalProxy,
    isNew: Boolean,
    existingNames: Set<String>,
    onDismiss: () -> Unit,
    onSave: (LocalProxy) -> Unit,
    onDelete: (LocalProxy) -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var protocol by remember { mutableStateOf(initial.protocol) }
    var host by remember { mutableStateOf(initial.host) }
    var port by remember { mutableStateOf(if (initial.port > 0) initial.port.toString() else "") }
    var username by remember { mutableStateOf(initial.username) }
    var password by remember { mutableStateOf(initial.password) }
    var sni by remember { mutableStateOf(initial.sni) }
    var showPassword by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var checkResult by remember { mutableStateOf<CheckState?>(null) }
    val scope = rememberCoroutineScope()

    fun build(): LocalProxy? {
        val p = port.trim().toIntOrNull()
        val trimmedHost = host.trim().removePrefix("[").removeSuffix("]")
        error = when {
            name.isBlank() -> "Enter a name."
            name.trim().lowercase() in existingNames -> "Another proxy already uses this name."
            trimmedHost.isEmpty() -> "Enter a proxy host or IP address."
            p == null || p !in 1..65535 -> "Port must be between 1 and 65535."
            protocol == ProxyProtocol.SOCKS4 && password.isNotEmpty() -> "SOCKS4A has no password; leave it empty."
            else -> null
        }
        if (error != null) return null
        return initial.copy(
            name = name.trim(), protocol = protocol, host = trimmedHost, port = p!!,
            username = username.trim(), password = password,
            sni = if (protocol == ProxyProtocol.HTTPS) sni.trim() else "",
        )
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth().widthIn(max = 560.dp).padding(16.dp).imePadding(),
            shape = RoundedCornerShape(20.dp),
            color = LocalSurfaces.current.panel,
        ) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(if (isNew) "Add proxy" else "Edit proxy", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Protocol", color = LocalSurfaces.current.muted, fontSize = 12.sp)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    ProxyProtocol.entries.forEach { p ->
                        FilterChip(
                            selected = protocol == p,
                            onClick = { protocol = p },
                            label = { Text(if (p == ProxyProtocol.SOCKS4) "SOCKS4A" else p.name) },
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        host, { host = it }, label = { Text("Host or IP") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        port, { v -> port = v.filter { it.isDigit() }.take(5) }, label = { Text("Port") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.widthIn(max = 110.dp),
                    )
                }
                OutlinedTextField(username, { username = it }, label = { Text("Username (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (protocol != ProxyProtocol.SOCKS4) {
                    OutlinedTextField(
                        password, { password = it }, label = { Text("Password (optional)") }, singleLine = true,
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = { TextButton(onClick = { showPassword = !showPassword }) { Text(if (showPassword) "Hide" else "Show") } },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (protocol == ProxyProtocol.HTTPS) {
                    OutlinedTextField(sni, { sni = it }, label = { Text("TLS server name / SNI (optional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp) }
                when (val r = checkResult) {
                    CheckState.Running -> MutedText("Checking proxy handshake…")
                    is CheckState.Ok -> Text("Proxy connection succeeded in ${r.ms} ms.", color = VColors.Success, fontSize = 13.sp)
                    is CheckState.Failed -> Text(r.message, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
                    null -> Unit
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (!isNew) {
                        TextButton(onClick = { confirmDelete = true }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                    }
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(
                        enabled = checkResult != CheckState.Running,
                        onClick = {
                            build()?.let { proxy ->
                                checkResult = CheckState.Running
                                scope.launch {
                                    checkResult = try {
                                        CheckState.Ok(ProxyTester.test(proxy))
                                    } catch (e: Exception) {
                                        CheckState.Failed(e.message ?: e.javaClass.simpleName)
                                    }
                                }
                            }
                        },
                    ) { Text("Check") }
                    Button(onClick = { build()?.let(onSave) }) { Text("Save") }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${initial.name}?") },
            text = { Text("App rules and the default action that use this proxy will switch to Direct.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete(initial) }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
