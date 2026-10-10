package app.vproxies.sa.ui

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import app.vproxies.sa.BuildConfig
import app.vproxies.sa.R
import app.vproxies.sa.bg.LogLevel
import app.vproxies.sa.bg.RoutingStatus
import app.vproxies.sa.bg.ServiceState
import app.vproxies.sa.bg.VProxiesVpnService
import app.vproxies.sa.core.SettingsStore
import app.vproxies.sa.core.SingBoxConfigBuilder
import app.vproxies.sa.ui.theme.LocalSurfaces
import app.vproxies.sa.ui.theme.VColors
import app.vproxies.sa.ui.theme.VProxiesTheme

private enum class Page(val label: String) { Proxies("Proxies"), Apps("Apps"), Settings("Settings"), Log("Activity") }

@Composable
private fun PageIcon(page: Page) {
    when (page) {
        Page.Proxies -> Icon(painterResource(R.drawable.ic_nav_proxies), contentDescription = page.label)
        Page.Apps -> Icon(painterResource(R.drawable.ic_nav_apps), contentDescription = page.label)
        Page.Settings -> Icon(Icons.Filled.Settings, contentDescription = page.label)
        Page.Log -> Icon(Icons.Filled.Menu, contentDescription = page.label)
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val settings by SettingsStore.settings.collectAsState()
            VProxiesTheme(settings.theme) { MainScreen() }
        }
    }
}

@Composable
private fun MainScreen() {
    val context = LocalContext.current
    val settings by SettingsStore.settings.collectAsState()
    val status by ServiceState.status.collectAsState()
    val detail by ServiceState.detail.collectAsState()
    var page by rememberSaveable { mutableStateOf(Page.Proxies) }
    var error by remember { mutableStateOf<String?>(null) }
    val s = LocalSurfaces.current
    val wide = LocalConfiguration.current.screenWidthDp >= 840

    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val vpnPermission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            VProxiesVpnService.start(context)
        } else {
            ServiceState.log("VPN permission was not granted.", LogLevel.WARN)
            error = "Android did not grant the VPN permission. VProxies SA needs it to route app traffic."
        }
    }

    fun startRouting() {
        try {
            SingBoxConfigBuilder.validate(SettingsStore.current)
        } catch (e: IllegalArgumentException) {
            error = e.message
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        val prepare = VpnService.prepare(context)
        if (prepare != null) vpnPermission.launch(prepare) else VProxiesVpnService.start(context)
    }

    val header: @Composable () -> Unit = {
        Header(
            status = status,
            detail = detail,
            onStart = { startRouting() },
            onStop = { VProxiesVpnService.stop(context) },
        )
    }

    val content: @Composable (PaddingValues) -> Unit = { padding ->
        when (page) {
            Page.Proxies -> ProxiesScreen(settings, padding)
            Page.Apps -> AppsScreen(settings, padding)
            Page.Settings -> SettingsScreen(settings, padding)
            Page.Log -> LogScreen(padding)
        }
    }

    if (wide) {
        // Tablets, foldables and Samsung DeX: sidebar like the Windows edition.
        Row(Modifier.fillMaxSize().background(s.page).windowInsetsPadding(WindowInsets.safeDrawing)) {
            NavigationRail(containerColor = s.sidebar, modifier = Modifier.fillMaxHeight()) {
                Spacer(Modifier.height(12.dp))
                Image(painterResource(R.drawable.vproxies_logo), contentDescription = null, modifier = Modifier.size(36.dp))
                Text("VProxies SA", fontWeight = FontWeight.Bold, fontSize = 11.sp)
                Text(BuildConfig.VERSION_NAME, color = s.muted, fontSize = 10.sp)
                Spacer(Modifier.height(16.dp))
                Page.entries.forEach { p ->
                    NavigationRailItem(
                        selected = page == p,
                        onClick = { page = p },
                        icon = { PageIcon(p) },
                        label = { Text(p.label) },
                    )
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                header()
                HorizontalDivider(color = s.line)
                Box(Modifier.weight(1f)) { content(PaddingValues(0.dp)) }
            }
        }
    } else {
        Scaffold(
            containerColor = s.page,
            topBar = { Column(Modifier.background(s.panel).statusBarsPadding()) { header(); HorizontalDivider(color = s.line) } },
            bottomBar = {
                NavigationBar(containerColor = s.sidebar) {
                    Page.entries.forEach { p ->
                        NavigationBarItem(
                            selected = page == p,
                            onClick = { page = p },
                            icon = { PageIcon(p) },
                            label = { Text(p.label) },
                        )
                    }
                }
            },
        ) { padding -> content(padding) }
    }

    error?.let {
        AlertDialog(
            onDismissRequest = { error = null },
            title = { Text("VProxies SA") },
            text = { Text(it) },
            confirmButton = { TextButton(onClick = { error = null }) { Text("OK") } },
        )
    }
}

@Composable
private fun Header(status: RoutingStatus, detail: String, onStart: () -> Unit, onStop: () -> Unit) {
    val s = LocalSurfaces.current
    val active = status == RoutingStatus.Running
    val busy = status == RoutingStatus.Starting || status == RoutingStatus.Stopping
    Row(
        Modifier.fillMaxWidth().background(s.panel).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(painterResource(R.drawable.vproxies_logo), contentDescription = null, modifier = Modifier.size(34.dp))
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("VProxies SA", fontWeight = FontWeight.Bold, fontSize = 18.sp)
            val (label, color, bg) = when (status) {
                RoutingStatus.Running -> Triple("Routing active", VColors.SuccessText, Color(0xFF163D38))
                RoutingStatus.Starting -> Triple("Starting…", VColors.Warn, Color(0xFF3A3220))
                RoutingStatus.Stopping -> Triple("Stopping…", VColors.Warn, Color(0xFF3A3220))
                RoutingStatus.Stopped -> Triple("Routing stopped", VColors.DangerText, if (s.isDark) Color(0xFF2B3449) else Color(0xFFFBE3E8))
            }
            Spacer(Modifier.height(4.dp))
            Chip(label, color, bg)
            Text(detail, color = s.muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        }
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Button(
                onClick = onStart,
                enabled = !busy,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10CFE5), contentColor = Color(0xFF04131A)),
            ) { Text(if (active) "Apply" else "Start", fontWeight = FontWeight.Bold) }
            if (active || busy) {
                OutlinedButton(onClick = onStop, enabled = active, shape = RoundedCornerShape(10.dp)) {
                    Text("Stop", color = VColors.Danger)
                }
            }
        }
    }
}
