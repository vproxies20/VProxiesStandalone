package app.vproxies.sa.bg

import android.app.Notification as AndroidNotification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager.NameNotFoundException
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.IpPrefix
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.provider.Settings
import android.system.OsConstants
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.vproxies.sa.R
import app.vproxies.sa.core.SettingsStore
import app.vproxies.sa.core.SingBoxConfigBuilder
import app.vproxies.sa.core.TARGET_BLOCK
import app.vproxies.sa.core.TARGET_DIRECT
import app.vproxies.sa.ui.MainActivity
import io.nekohasekai.libbox.BridgeOptions
import io.nekohasekai.libbox.BridgeSession
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NeighborUpdateListener
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.PlatformUser
import io.nekohasekai.libbox.RoutePrefix
import io.nekohasekai.libbox.ShellSession
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.RandomAccessFile
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.InterfaceAddress
import java.net.NetworkInterface
import io.nekohasekai.libbox.NetworkInterface as LibboxNetworkInterface

/**
 * VpnService that hosts the sing-box core (libbox). It replaces the Windows TUN/Wintun process:
 * one TUN interface, several proxy outbounds, per-app (package name) routing rules.
 */
class VProxiesVpnService : VpnService(), PlatformInterface, CommandServerHandler {

    companion object {
        const val ACTION_START = "app.vproxies.sa.action.START"
        const val ACTION_STOP = "app.vproxies.sa.action.STOP"
        private const val CHANNEL_ID = "routing"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, VProxiesVpnService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(context: Context) {
            if (ServiceState.status.value == RoutingStatus.Stopped) return
            context.startService(Intent(context, VProxiesVpnService::class.java).setAction(ACTION_STOP))
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private var commandServer: CommandServer? = null
    private var tunFd: ParcelFileDescriptor? = null
    private var logJob: Job? = null
    private val connectivity by lazy { getSystemService(ConnectivityManager::class.java) }
    private val notifications by lazy { getSystemService(NotificationManager::class.java) }
    private val logFile by lazy { File(cacheDir, "core.log") }

    // ---------------------------------------------------------------- lifecycle

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> scope.launch { stopRouting(null) }
            else -> {
                showForeground("Starting routing…")
                scope.launch { startOrReload() }
            }
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        ServiceState.log("VPN permission was revoked by Android or another VPN app.", LogLevel.WARN)
        scope.launch { stopRouting(null) }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun startOrReload() = lock.withLock {
        val reloading = commandServer != null && ServiceState.status.value == RoutingStatus.Running
        ServiceState.setStatus(RoutingStatus.Starting)
        try {
            if (prepare(this) != null) error("VPN permission has not been granted.")
            val settings = SettingsStore.current
            ServiceState.setSensitiveValues(settings.proxies.flatMap { listOf(it.username, it.password) })
            logFile.parentFile?.mkdirs()
            if (!reloading) runCatching { logFile.writeText("") }
            val config = SingBoxConfigBuilder.build(settings, packageName, logFile.absolutePath)

            var server = commandServer
            if (server == null) {
                server = CommandServer(this, this)
                server.start()
                commandServer = server
            }
            DefaultNetworkMonitor.start(connectivity)
            server.startOrReloadService(config, OverrideOptions())
            startLogTail()

            val proxyRoutes = settings.rules.count { it.target != TARGET_DIRECT && it.target != TARGET_BLOCK }
            val detail = "${settings.rules.size} app rule(s) · default: ${settings.targetName(settings.defaultTarget)}"
            ServiceState.setStatus(RoutingStatus.Running, detail)
            ServiceState.log(
                "Routing ${if (reloading) "reloaded" else "active"}: ${settings.rules.size} app rule(s), " +
                    "$proxyRoutes proxy route(s), ${settings.proxies.size} proxy server(s) available simultaneously.",
            )
            showForeground(detail)
        } catch (e: Exception) {
            ServiceState.log("Start failed: ${e.message ?: e.javaClass.simpleName}", LogLevel.ERROR)
            stopRoutingLocked()
        }
    }

    private suspend fun stopRouting(reason: String?) = lock.withLock {
        if (reason != null) ServiceState.log(reason, LogLevel.WARN)
        stopRoutingLocked()
    }

    private suspend fun stopRoutingLocked() {
        if (ServiceState.status.value == RoutingStatus.Stopped && commandServer == null) {
            finishService()
            return
        }
        ServiceState.setStatus(RoutingStatus.Stopping)
        commandServer?.let { server ->
            runCatching { server.closeService() }
            runCatching { server.close() }
        }
        commandServer = null
        closeTun()
        DefaultNetworkMonitor.setListener(null)
        DefaultNetworkMonitor.stop()
        delay(300)
        logJob?.cancel()
        logJob = null
        ServiceState.setSensitiveValues(emptyList())
        ServiceState.setStatus(RoutingStatus.Stopped, "Apps use this device connection")
        ServiceState.log("Core stopped and network state released.")
        finishService()
    }

    private fun finishService() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun closeTun() {
        runCatching { tunFd?.close() }
        tunFd = null
    }

    /** Streams the core log file into the in-app Activity Log. */
    private fun startLogTail() {
        if (logJob?.isActive == true) return
        logJob = scope.launch {
            var position = 0L
            var pending = ""
            while (isActive) {
                runCatching {
                    if (logFile.exists()) {
                        val length = logFile.length()
                        if (length < position) position = 0L
                        if (length > position) {
                            RandomAccessFile(logFile, "r").use { file ->
                                file.seek(position)
                                val bytes = ByteArray((length - position).toInt().coerceAtMost(256 * 1024))
                                file.readFully(bytes)
                                position += bytes.size
                                val text = pending + String(bytes, Charsets.UTF_8)
                                val lines = text.split('\n')
                                pending = lines.last()
                                lines.dropLast(1).forEach { ServiceState.log(it) }
                            }
                        }
                    }
                }
                delay(500)
            }
        }
    }

    private fun showForeground(text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notifications.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Routing status", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, VProxiesVpnService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: AndroidNotification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("VProxies SA")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED
        } else {
            0
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
        } catch (e: Exception) {
            // Android 14+ only allows the systemExempted type for an approved VPN app; fall back to the manifest type.
            ServiceState.log("Foreground notification type rejected: ${e.message}", LogLevel.WARN)
            runCatching { ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, 0) }
        }
    }

    // ---------------------------------------------------------------- CommandServerHandler

    override fun serviceStop() {
        scope.launch { stopRouting("Core requested stop.") }
    }

    override fun serviceReload() {
        scope.launch { startOrReload() }
    }

    override fun getSystemProxyStatus(): SystemProxyStatus = SystemProxyStatus().apply {
        available = false
        enabled = false
    }

    override fun setSystemProxyEnabled(isEnabled: Boolean) {
    }

    override fun triggerNativeCrash() {
    }

    override fun writeDebugMessage(message: String?) {
        if (!message.isNullOrBlank()) ServiceState.log(message)
    }

    override fun connectSSHAgent(): Int = -1

    // ---------------------------------------------------------------- PlatformInterface

    override fun localDNSTransport(): LocalDNSTransport = LocalResolver

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun autoDetectInterfaceControl(fd: Int) {
        protect(fd)
    }

    override fun openTun(options: TunOptions): Int {
        if (prepare(this) != null) error("android: missing vpn permission")
        val builder = Builder()
            .setSession("VProxies SA")
            .setMtu(options.mtu)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) builder.setMetered(false)

        val inet4Address = options.inet4Address
        while (inet4Address.hasNext()) {
            val address = inet4Address.next()
            builder.addAddress(address.address(), address.prefix())
        }
        val inet6Address = options.inet6Address
        while (inet6Address.hasNext()) {
            val address = inet6Address.next()
            builder.addAddress(address.address(), address.prefix())
        }

        if (options.autoRoute) {
            if (options.dnsMode.value != Libbox.DNSModeDisabled) {
                val dnsServerAddress = options.dnsServerAddress
                while (dnsServerAddress.hasNext()) builder.addDnsServer(dnsServerAddress.next())
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val inet4RouteAddress = options.inet4RouteAddress
                if (inet4RouteAddress.hasNext()) {
                    while (inet4RouteAddress.hasNext()) builder.addRoute(inet4RouteAddress.next().toIpPrefix())
                } else if (options.inet4Address.hasNext()) {
                    builder.addRoute("0.0.0.0", 0)
                }
                val inet6RouteAddress = options.inet6RouteAddress
                if (inet6RouteAddress.hasNext()) {
                    while (inet6RouteAddress.hasNext()) builder.addRoute(inet6RouteAddress.next().toIpPrefix())
                } else if (options.inet6Address.hasNext()) {
                    builder.addRoute("::", 0)
                }
                val inet4Exclude = options.inet4RouteExcludeAddress
                while (inet4Exclude.hasNext()) builder.excludeRoute(inet4Exclude.next().toIpPrefix())
                val inet6Exclude = options.inet6RouteExcludeAddress
                while (inet6Exclude.hasNext()) builder.excludeRoute(inet6Exclude.next().toIpPrefix())
            } else {
                val inet4RouteRange = options.inet4RouteRange
                while (inet4RouteRange.hasNext()) {
                    val address = inet4RouteRange.next()
                    builder.addRoute(address.address(), address.prefix())
                }
                val inet6RouteRange = options.inet6RouteRange
                while (inet6RouteRange.hasNext()) {
                    val address = inet6RouteRange.next()
                    builder.addRoute(address.address(), address.prefix())
                }
            }

            val includePackage = options.includePackage
            while (includePackage.hasNext()) {
                val name = includePackage.next()
                try {
                    builder.addAllowedApplication(name)
                } catch (_: NameNotFoundException) {
                    ServiceState.log("Skipped missing app $name", LogLevel.WARN)
                }
            }
            val excludePackage = options.excludePackage
            while (excludePackage.hasNext()) {
                val name = excludePackage.next()
                try {
                    builder.addDisallowedApplication(name)
                } catch (_: NameNotFoundException) {
                    ServiceState.log("Skipped missing app $name", LogLevel.WARN)
                }
            }
        }

        val pfd = builder.establish() ?: error("android: the application is not prepared or is revoked")
        tunFd = pfd
        return pfd.fd
    }

    override fun useProcFS(): Boolean = false

    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int,
    ): ConnectionOwner {
        val uid = connectivity.getConnectionOwnerUid(
            ipProtocol,
            InetSocketAddress(sourceAddress, sourcePort),
            InetSocketAddress(destinationAddress, destinationPort),
        )
        if (uid == Process.INVALID_UID) error("android: connection owner not found")
        val packages = packageManager.getPackagesForUid(uid)?.toList().orEmpty()
        return ConnectionOwner().apply {
            userId = uid
            userName = packages.firstOrNull() ?: ""
            setAndroidPackageNames(StringArray(packages.iterator()))
        }
    }

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        DefaultNetworkMonitor.setListener(listener)
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        DefaultNetworkMonitor.setListener(null)
    }

    override fun getInterfaces(): NetworkInterfaceIterator {
        val javaInterfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        val result = mutableListOf<LibboxNetworkInterface>()
        @Suppress("DEPRECATION")
        for (network in connectivity.allNetworks) {
            val linkProperties = connectivity.getLinkProperties(network) ?: continue
            val capabilities = connectivity.getNetworkCapabilities(network) ?: continue
            val javaInterface = javaInterfaces.find { it.name == linkProperties.interfaceName } ?: continue
            val item = LibboxNetworkInterface()
            item.name = linkProperties.interfaceName
            item.dnsServer = StringArray(linkProperties.dnsServers.mapNotNull { it.hostAddress }.iterator())
            item.gateway = StringArray(
                linkProperties.routes
                    .filter { it.destination.prefixLength == 0 }
                    .mapNotNull { it.gateway }
                    .filterNot { it.isAnyLocalAddress }
                    .mapNotNull { it.hostAddress }
                    .iterator(),
            )
            item.type = when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Libbox.InterfaceTypeWIFI
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Libbox.InterfaceTypeCellular
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Libbox.InterfaceTypeEthernet
                else -> Libbox.InterfaceTypeOther
            }
            item.index = javaInterface.index
            runCatching { item.mtu = javaInterface.mtu }
            item.addresses = StringArray(javaInterface.interfaceAddresses.map { it.toPrefix() }.iterator())
            var interfaceFlags = 0
            if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
                interfaceFlags = OsConstants.IFF_UP or OsConstants.IFF_RUNNING
            }
            if (javaInterface.isLoopback) interfaceFlags = interfaceFlags or OsConstants.IFF_LOOPBACK
            if (javaInterface.isPointToPoint) interfaceFlags = interfaceFlags or OsConstants.IFF_POINTOPOINT
            if (javaInterface.supportsMulticast()) interfaceFlags = interfaceFlags or OsConstants.IFF_MULTICAST
            item.flags = interfaceFlags
            item.metered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
            result += item
        }
        return InterfaceArray(result.iterator())
    }

    override fun underNetworkExtension(): Boolean = false

    override fun includeAllNetworks(): Boolean = false

    override fun readWIFIState(): WIFIState? = null

    override fun clearDNSCache() {
    }

    override fun sendNotification(notification: Notification) {
        val channel = "core-${notification.typeID}"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notifications.createNotificationChannel(
                NotificationChannel(channel, notification.typeName ?: "Core", NotificationManager.IMPORTANCE_DEFAULT),
            )
        }
        val built = NotificationCompat.Builder(this, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(notification.title)
            .setContentText(notification.body)
            .setAutoCancel(true)
            .build()
        runCatching { notifications.notify(notification.identifier, notification.typeID, built) }
    }

    override fun cancelNotification(identifier: String, typeID: Int) {
        runCatching { notifications.cancel(identifier, typeID) }
    }

    override fun startNeighborMonitor(listener: NeighborUpdateListener?) {
    }

    override fun closeNeighborMonitor(listener: NeighborUpdateListener?) {
    }

    override fun registerMyInterface(name: String?) {
    }

    override fun usePlatformShell(): Boolean = false

    override fun checkPlatformShell() {
        error("not supported")
    }

    override fun openShellSession(
        user: PlatformUser?,
        command: String?,
        environ: StringIterator?,
        term: String?,
        rows: Int,
        cols: Int,
    ): ShellSession = error("not supported")

    override fun lookupUser(username: String?): PlatformUser = error("not supported")

    override fun lookupSFTPServer(): String = error("not supported")

    override fun readSystemSSHHostKey(): String = error("not supported")

    override fun tailscaleHostname(): String =
        Settings.Global.getString(contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() }
            ?: "${Build.MANUFACTURER} ${Build.MODEL}"

    override fun usePlatformBridge(): Boolean = false

    override fun createBridge(options: BridgeOptions?): BridgeSession = error("not supported")

    // ---------------------------------------------------------------- helpers

    private class InterfaceArray(private val iterator: Iterator<LibboxNetworkInterface>) : NetworkInterfaceIterator {
        override fun hasNext(): Boolean = iterator.hasNext()
        override fun next(): LibboxNetworkInterface = iterator.next()
    }

    class StringArray(private val iterator: Iterator<String>) : StringIterator {
        override fun len(): Int = 0
        override fun hasNext(): Boolean = iterator.hasNext()
        override fun next(): String = iterator.next()
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun RoutePrefix.toIpPrefix() = IpPrefix(InetAddress.getByName(address()), prefix())

    private fun InterfaceAddress.toPrefix(): String = if (address is Inet6Address) {
        "${Inet6Address.getByAddress(address.address).hostAddress}/$networkPrefixLength"
    } else {
        "${address.hostAddress}/$networkPrefixLength"
    }
}
