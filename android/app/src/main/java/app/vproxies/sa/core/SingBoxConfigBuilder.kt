package app.vproxies.sa.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Android port of the Windows SingBoxConfigBuilder.
 * Windows matches applications by .exe path; Android matches them by package name.
 */
object SingBoxConfigBuilder {

    fun build(settings: AppSettings, ownPackage: String, logPath: String): String {
        validate(settings)

        val outbounds = JSONArray()
        settings.proxies.forEach { outbounds.put(buildProxy(it)) }
        outbounds.put(JSONObject().put("type", "direct").put("tag", TARGET_DIRECT))
        outbounds.put(JSONObject().put("type", "block").put("tag", TARGET_BLOCK))

        val rules = JSONArray()
        rules.put(JSONObject().put("ip_is_private", true).put("action", "route").put("outbound", TARGET_DIRECT))
        for (proxy in settings.proxies) {
            if (isIpLiteral(proxy.host)) {
                val cidr = proxy.host + if (proxy.host.contains(':')) "/128" else "/32"
                rules.put(JSONObject().put("ip_cidr", JSONArray().put(cidr)).put("action", "route").put("outbound", TARGET_DIRECT))
            }
        }
        for (rule in settings.rules) {
            rules.put(
                JSONObject()
                    .put("package_name", JSONArray().put(rule.packageName))
                    .put("action", "route")
                    .put("outbound", resolveTarget(rule.target, settings.proxies)),
            )
        }

        val finalOutbound = resolveTarget(settings.defaultTarget, settings.proxies)
        val dnsDetour = if (finalOutbound.startsWith("proxy-")) finalOutbound else settings.proxies.firstOrNull()?.outboundTag

        val tun = JSONObject()
            .put("type", "tun")
            .put("tag", "tun-in")
            .put("address", JSONArray().put("172.19.0.1/30").put("fdfe:dcba:9876::1/126"))
            .put("mtu", 9000)
            .put("auto_route", true)
            .put("strict_route", settings.strictRoute)
            .put("stack", "mixed")
            .put("dns_mode", "hijack")
            // VProxies SA itself (proxy checker, UI) always uses the device connection.
            .put("exclude_package", JSONArray().put(ownPackage))

        val root = JSONObject()
            .put("log", JSONObject().put("level", "info").put("timestamp", true).put("output", logPath))
            .put("dns", buildDns(settings.remoteDns, dnsDetour, settings.dnsServer))
            .put("inbounds", JSONArray().put(tun))
            .put("outbounds", outbounds)
            .put(
                "route",
                JSONObject()
                    .put("rules", rules)
                    .put("final", finalOutbound)
                    .put("auto_detect_interface", true)
                    .put("find_process", true)
                    .put("default_domain_resolver", "dns-local"),
            )
        return root.toString(2)
    }

    private fun buildProxy(proxy: LocalProxy): JSONObject {
        val result = JSONObject()
        if (proxy.protocol == ProxyProtocol.SOCKS4 || proxy.protocol == ProxyProtocol.SOCKS5) {
            result.put("type", "socks").put("tag", proxy.outboundTag)
                .put("server", proxy.host).put("server_port", proxy.port)
                .put("version", if (proxy.protocol == ProxyProtocol.SOCKS4) "4a" else "5")
        } else {
            result.put("type", "http").put("tag", proxy.outboundTag)
                .put("server", proxy.host).put("server_port", proxy.port)
            if (proxy.protocol == ProxyProtocol.HTTPS) {
                result.put(
                    "tls",
                    JSONObject().put("enabled", true).put("server_name", proxy.sni.ifBlank { proxy.host }),
                )
            }
        }
        if (proxy.username.isNotEmpty()) result.put("username", proxy.username)
        if (proxy.password.isNotEmpty() && proxy.protocol != ProxyProtocol.SOCKS4) result.put("password", proxy.password)
        if (!isIpLiteral(proxy.host)) result.put("domain_resolver", "dns-local")
        return result
    }

    private fun buildDns(remote: Boolean, detour: String?, dnsServer: String): JSONObject {
        val servers = JSONArray().put(JSONObject().put("type", "local").put("tag", "dns-local"))
        val useProxy = remote && !detour.isNullOrBlank()
        if (useProxy) {
            servers.put(
                JSONObject().put("type", "tcp").put("tag", "dns-proxy")
                    .put("server", dnsServer).put("server_port", 53).put("detour", detour),
            )
        }
        return JSONObject().put("servers", servers).put("final", if (useProxy) "dns-proxy" else "dns-local")
    }

    private fun resolveTarget(target: String, proxies: List<LocalProxy>): String {
        if (target == TARGET_DIRECT || target == TARGET_BLOCK) return target
        val proxy = proxies.firstOrNull { it.id.equals(target, ignoreCase = true) }
            ?: throw IllegalArgumentException("A routing rule refers to a proxy that no longer exists.")
        return proxy.outboundTag
    }

    fun validate(settings: AppSettings) {
        settings.proxies.groupBy { it.name.trim().lowercase() }.values.firstOrNull { it.size > 1 }?.let {
            throw IllegalArgumentException("Proxy name '${it.first().name.trim()}' is duplicated.")
        }
        for (proxy in settings.proxies) {
            require(proxy.host.isNotBlank()) { "${proxy.name}: host is required." }
            require(proxy.port in 1..65535) { "${proxy.name}: port must be between 1 and 65535." }
        }
        settings.rules.groupBy { it.packageName.lowercase() }.values.firstOrNull { it.size > 1 }?.let {
            throw IllegalArgumentException("Only one routing rule is allowed for ${it.first().label.ifBlank { it.first().packageName }}.")
        }
        for (rule in settings.rules) {
            require(rule.packageName.isNotBlank()) { "Every application rule must point to an installed app." }
        }
        if (settings.remoteDns) {
            require(isIpLiteral(settings.dnsServer)) { "DNS server must be a valid IPv4 or IPv6 address." }
        }
        resolveTarget(settings.defaultTarget, settings.proxies)
        settings.rules.forEach { resolveTarget(it.target, settings.proxies) }
    }

    private val ipv4 = Regex("^((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)$")

    /** Literal IP check without DNS lookups (InetAddress.getByName would resolve host names). */
    fun isIpLiteral(value: String): Boolean {
        val v = value.trim()
        if (ipv4.matches(v)) return true
        if (!v.contains(':')) return false
        return v.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' } &&
            runCatching { java.net.InetAddress.getByName(v) }.isSuccess
    }
}
