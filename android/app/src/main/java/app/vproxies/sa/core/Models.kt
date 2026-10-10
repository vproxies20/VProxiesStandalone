package app.vproxies.sa.core

import java.util.UUID

enum class ProxyProtocol { HTTP, HTTPS, SOCKS4, SOCKS5 }

enum class ThemeMode { System, Light, Dark }

const val TARGET_DIRECT = "direct"
const val TARGET_BLOCK = "block"

fun newId(): String = UUID.randomUUID().toString().replace("-", "")

data class LocalProxy(
    val id: String = newId(),
    val name: String = "Proxy",
    val protocol: ProxyProtocol = ProxyProtocol.SOCKS5,
    val host: String = "",
    val port: Int = 0,
    val username: String = "",
    val password: String = "",
    val sni: String = "",
) {
    val endpointText: String get() = if (host.contains(':')) "[$host]:$port" else "$host:$port"
    val authenticationText: String get() = if (username.isBlank()) "None" else "Username/password"
    val outboundTag: String get() = "proxy-$id"
}

/** One Android application (by package name) routed to a target: a proxy id, "direct" or "block". */
data class AppRule(
    val id: String = newId(),
    val packageName: String = "",
    val label: String = "",
    val target: String = TARGET_DIRECT,
)

data class RouteTarget(val id: String, val name: String)

data class AppSettings(
    val proxies: List<LocalProxy> = emptyList(),
    val rules: List<AppRule> = emptyList(),
    val defaultTarget: String = TARGET_DIRECT,
    val strictRoute: Boolean = true,
    val remoteDns: Boolean = false,
    val dnsServer: String = "1.1.1.1",
    val savePasswords: Boolean = true,
    val theme: ThemeMode = ThemeMode.System,
) {
    fun targets(): List<RouteTarget> = buildList {
        add(RouteTarget(TARGET_DIRECT, "Direct (this device IP)"))
        add(RouteTarget(TARGET_BLOCK, "Block connection"))
        proxies.forEach { add(RouteTarget(it.id, "${it.name}  ·  ${it.protocol} ${it.endpointText}")) }
    }

    fun targetName(target: String): String = when (target) {
        TARGET_DIRECT -> "Direct"
        TARGET_BLOCK -> "Block"
        else -> proxies.firstOrNull { it.id.equals(target, ignoreCase = true) }?.name ?: "Missing proxy"
    }
}
