package app.vproxies.sa.ui

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.graphics.drawable.toBitmap
import app.vproxies.sa.core.AppRule
import app.vproxies.sa.core.AppSettings
import app.vproxies.sa.core.SettingsStore
import app.vproxies.sa.core.TARGET_DIRECT
import app.vproxies.sa.ui.theme.LocalSurfaces
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

data class InstalledApp(val packageName: String, val label: String, val isSystem: Boolean)

private object AppCatalog {
    private val icons = ConcurrentHashMap<String, ImageBitmap>()

    suspend fun load(context: Context): List<InstalledApp> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val launchable = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.activityInfo.packageName }.toSet()
        @Suppress("DEPRECATION")
        pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
            .asSequence()
            .filter { it.packageName != context.packageName }
            // Only apps that can use the network matter for routing.
            .filter { it.requestedPermissions?.contains(android.Manifest.permission.INTERNET) == true }
            .mapNotNull { pkg ->
                val info = pkg.applicationInfo ?: return@mapNotNull null
                InstalledApp(
                    packageName = pkg.packageName,
                    label = info.loadLabel(pm).toString(),
                    isSystem = (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0 && pkg.packageName !in launchable,
                )
            }
            .sortedBy { it.label.lowercase() }
            .toList()
    }

    suspend fun icon(context: Context, packageName: String): ImageBitmap? {
        icons[packageName]?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                context.packageManager.getApplicationIcon(packageName).toBitmap(96, 96).asImageBitmap()
            }.getOrNull()?.also { icons[packageName] = it }
        }
    }

    fun label(context: Context, packageName: String): String? = runCatching {
        val pm = context.packageManager
        pm.getApplicationInfo(packageName, 0).loadLabel(pm).toString()
    }.getOrNull()
}

@Composable
private fun AppIcon(packageName: String) {
    val context = LocalContext.current
    val icon by produceState<ImageBitmap?>(null, packageName) { value = AppCatalog.icon(context, packageName) }
    val s = LocalSurfaces.current
    Box(Modifier.size(40.dp).background(s.altPanel, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
        icon?.let { Image(it, contentDescription = null, modifier = Modifier.size(34.dp)) }
    }
}

@Composable
fun AppsScreen(settings: AppSettings, contentPadding: PaddingValues) {
    val context = LocalContext.current
    var picking by remember { mutableStateOf(false) }
    val targets = remember(settings.proxies) { settings.targets() }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp, top = contentPadding.calculateTopPadding() + 12.dp,
            bottom = contentPadding.calculateBottomPadding() + 16.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            SectionCard(title = "Every app without a rule", subtitle = "Default action for all other applications") {
                TargetPicker(targets, settings.defaultTarget, onSelect = { id -> SettingsStore.update { it.copy(defaultTarget = id) } })
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Application rules", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    MutedText("Each app can use its own proxy, Direct, or Block — all at the same time.")
                }
                Button(onClick = { picking = true }) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Add apps")
                }
            }
        }
        if (settings.rules.isEmpty()) {
            item {
                SectionCard {
                    Text("No application rules", fontWeight = FontWeight.SemiBold)
                    MutedText("Example: Chrome → Proxy 1, Telegram → Proxy 2, a game → Direct, an untrusted app → Block.")
                }
            }
        }
        items(settings.rules, key = { it.id }) { rule ->
            val installed = remember(rule.packageName) { AppCatalog.label(context, rule.packageName) != null }
            SectionCard {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppIcon(rule.packageName)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(rule.label.ifBlank { rule.packageName }, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        MutedText(if (installed) rule.packageName else "${rule.packageName} · not installed")
                    }
                    IconButton(onClick = { SettingsStore.update { s -> s.copy(rules = s.rules.filterNot { it.id == rule.id }) } }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Remove rule")
                    }
                }
                TargetPicker(
                    targets, rule.target,
                    onSelect = { id -> SettingsStore.update { s -> s.copy(rules = s.rules.map { if (it.id == rule.id) it.copy(target = id) else it }) } },
                )
            }
        }
    }

    if (picking) {
        AppPicker(
            alreadyRouted = settings.rules.map { it.packageName }.toSet(),
            onDismiss = { picking = false },
            onAdd = { apps ->
                SettingsStore.update { s ->
                    s.copy(rules = s.rules + apps.map { AppRule(packageName = it.packageName, label = it.label, target = TARGET_DIRECT) })
                }
                picking = false
            },
        )
    }
}

@Composable
private fun AppPicker(alreadyRouted: Set<String>, onDismiss: () -> Unit, onAdd: (List<InstalledApp>) -> Unit) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<InstalledApp>?>(null) }
    var query by remember { mutableStateOf("") }
    var showSystem by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    LaunchedEffect(Unit) { apps = AppCatalog.load(context) }
    val s = LocalSurfaces.current

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(
            modifier = Modifier.fillMaxWidth().widthIn(max = 620.dp).heightIn(max = 760.dp).padding(16.dp),
            shape = RoundedCornerShape(20.dp),
            color = s.panel,
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Choose applications", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                OutlinedTextField(
                    query, { query = it }, singleLine = true, placeholder = { Text("Search apps") },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) }, modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { showSystem = !showSystem }) {
                    Checkbox(showSystem, { showSystem = it })
                    Text("Show system apps", fontSize = 14.sp)
                }
                val list = apps
                if (list == null) {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                } else {
                    val filtered = list.filter { app ->
                        app.packageName !in alreadyRouted && (showSystem || !app.isSystem) &&
                            (query.isBlank() || app.label.contains(query, true) || app.packageName.contains(query, true))
                    }
                    LazyColumn(Modifier.weight(1f, fill = false)) {
                        items(filtered, key = { it.packageName }) { app ->
                            val checked = app.packageName in selected
                            Row(
                                Modifier.fillMaxWidth()
                                    .clickable { selected = if (checked) selected - app.packageName else selected + app.packageName }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                AppIcon(app.packageName)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(app.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    MutedText(app.packageName)
                                }
                                Checkbox(checked, { selected = if (it) selected + app.packageName else selected - app.packageName })
                            }
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Button(
                        enabled = selected.isNotEmpty(),
                        onClick = { onAdd(apps.orEmpty().filter { it.packageName in selected }) },
                    ) { Text(if (selected.isEmpty()) "Add" else "Add ${selected.size}") }
                }
            }
        }
    }
}
