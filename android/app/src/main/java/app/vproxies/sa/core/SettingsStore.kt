package app.vproxies.sa.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Settings are kept in memory as a StateFlow and persisted to settings.json in app-private storage.
 * The JSON uses the same PascalCase field names as the Windows edition so exported files look alike.
 */
object SettingsStore {
    private lateinit var file: File
    private val state = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = state.asStateFlow()

    fun init(context: Context) {
        file = File(context.filesDir, "settings.json")
        state.value = runCatching { parse(JSONObject(file.readText()), fromExport = false).settings }.getOrDefault(AppSettings())
    }

    val current: AppSettings get() = state.value

    fun update(transform: (AppSettings) -> AppSettings) {
        state.update(transform)
        save(state.value)
    }

    fun replace(settings: AppSettings) = update { settings }

    @Synchronized
    private fun save(settings: AppSettings) {
        val json = toJson(settings, forExport = false, includePasswords = false).toString(2)
        val temporary = File(file.parentFile, file.name + ".tmp")
        temporary.writeText(json)
        if (!temporary.renameTo(file)) {
            file.writeText(json)
            temporary.delete()
        }
    }

    fun toJson(settings: AppSettings, forExport: Boolean, includePasswords: Boolean): JSONObject {
        val proxies = JSONArray()
        for (p in settings.proxies) {
            val o = JSONObject()
                .put("Id", p.id).put("Name", p.name).put("Protocol", p.protocol.name)
                .put("Host", p.host).put("Port", p.port).put("Username", p.username).put("Sni", p.sni)
            if (forExport) {
                o.put("ProtectedPassword", "")
                if (includePasswords && p.password.isNotEmpty()) o.put("Password", p.password)
            } else {
                o.put("ProtectedPassword", if (settings.savePasswords) SecretStore.protect(p.password) else "")
            }
            proxies.put(o)
        }
        val rules = JSONArray()
        for (r in settings.rules) {
            rules.put(JSONObject().put("Id", r.id).put("PackageName", r.packageName).put("Label", r.label).put("Target", r.target))
        }
        return JSONObject()
            .put("Edition", "VProxies SA Android")
            .put("Proxies", proxies)
            .put("AndroidRules", rules)
            .put("DefaultTarget", settings.defaultTarget)
            .put("RemoteDns", settings.remoteDns)
            .put("DnsServer", settings.dnsServer)
            .put("StrictRoute", settings.strictRoute)
            .put("SavePasswords", settings.savePasswords)
            .put("Theme", settings.theme.name)
    }

    data class ParseResult(val settings: AppSettings, val skippedWindowsRules: Int, val proxiesWithoutPassword: Int)

    /** Parses this app's own file, an Android export, or a Windows VProxies SA export. */
    fun parse(json: JSONObject, fromExport: Boolean): ParseResult {
        fun JSONObject.str(name: String, default: String = ""): String =
            optString(name, optString(name.replaceFirstChar { it.lowercase() }, default)).let { if (it == "null") default else it }

        val proxies = mutableListOf<LocalProxy>()
        var withoutPassword = 0
        val proxyArray = json.optJSONArray("Proxies") ?: json.optJSONArray("proxies") ?: JSONArray()
        for (i in 0 until proxyArray.length()) {
            val o = proxyArray.optJSONObject(i) ?: continue
            val protocol = parseProtocol(o.opt("Protocol") ?: o.opt("protocol"))
            val plain = o.str("Password")
            val protected = o.str("ProtectedPassword")
            val password = plain.ifEmpty { SecretStore.unprotect(protected) }
            if (fromExport && password.isEmpty() && (protected.isNotEmpty() || o.str("Username").isNotEmpty())) withoutPassword++
            proxies += LocalProxy(
                id = o.str("Id").ifBlank { newId() },
                name = o.str("Name", "Proxy").ifBlank { "Proxy" },
                protocol = protocol,
                host = o.str("Host").trim(),
                port = o.optInt("Port", o.optInt("port", 0)),
                username = o.str("Username"),
                password = password,
                sni = o.str("Sni"),
            )
        }
        val proxyIds = proxies.map { it.id.lowercase() }.toSet()
        fun cleanTarget(target: String): String = when {
            target == TARGET_DIRECT || target == TARGET_BLOCK -> target
            target.lowercase() in proxyIds -> proxies.first { it.id.equals(target, true) }.id
            else -> TARGET_DIRECT
        }

        val rules = mutableListOf<AppRule>()
        val ruleArray = json.optJSONArray("AndroidRules") ?: JSONArray()
        for (i in 0 until ruleArray.length()) {
            val o = ruleArray.optJSONObject(i) ?: continue
            val pkg = o.str("PackageName").trim()
            if (pkg.isEmpty()) continue
            rules += AppRule(id = o.str("Id").ifBlank { newId() }, packageName = pkg, label = o.str("Label"), target = cleanTarget(o.str("Target", TARGET_DIRECT)))
        }
        val skippedWindowsRules = json.optJSONArray("Rules")?.length() ?: 0

        val theme = runCatching { ThemeMode.valueOf(json.str("Theme", "System")) }.getOrDefault(ThemeMode.System)
        return ParseResult(
            AppSettings(
                proxies = proxies,
                rules = rules.distinctBy { it.packageName.lowercase() },
                defaultTarget = cleanTarget(json.str("DefaultTarget", TARGET_DIRECT)),
                strictRoute = json.optBoolean("StrictRoute", true),
                remoteDns = json.optBoolean("RemoteDns", false),
                dnsServer = json.str("DnsServer", "1.1.1.1").ifBlank { "1.1.1.1" },
                savePasswords = json.optBoolean("SavePasswords", true),
                theme = theme,
            ),
            skippedWindowsRules,
            withoutPassword,
        )
    }

    /** System.Text.Json writes enums as numbers by default (HTTP=0 … SOCKS5=3); also accept names. */
    private fun parseProtocol(value: Any?): ProxyProtocol = when (value) {
        is Number -> ProxyProtocol.entries.getOrElse(value.toInt()) { ProxyProtocol.SOCKS5 }
        is String -> value.toIntOrNull()?.let { ProxyProtocol.entries.getOrElse(it) { ProxyProtocol.SOCKS5 } }
            ?: runCatching { ProxyProtocol.valueOf(value.uppercase()) }.getOrDefault(ProxyProtocol.SOCKS5)
        else -> ProxyProtocol.SOCKS5
    }
}
