package app.vproxies.sa.bg

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class RoutingStatus { Stopped, Starting, Running, Stopping }

enum class LogLevel { INFO, WARN, ERROR }

data class LogEntry(val id: Long, val time: String, val level: LogLevel, val message: String)

/** Process-wide routing status and activity log shared by the VPN service and the UI. */
object ServiceState {
    private const val MAX_LINES = 1000

    private val statusFlow = MutableStateFlow(RoutingStatus.Stopped)
    val status: StateFlow<RoutingStatus> = statusFlow.asStateFlow()

    private val detailFlow = MutableStateFlow("Apps use this device connection")
    val detail: StateFlow<String> = detailFlow.asStateFlow()

    private val logFlow = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = logFlow.asStateFlow()

    private var nextId = 0L
    private val sensitive = mutableSetOf<String>()
    private val ansi = Regex("\u001B\\[[0-?]*[ -/]*[@-~]")
    private val coreTimestamp = Regex("^[+-]\\d{4} \\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\s+")

    fun setStatus(status: RoutingStatus, detail: String? = null) {
        statusFlow.value = status
        if (detail != null) detailFlow.value = detail
    }

    @Synchronized
    fun setSensitiveValues(values: Collection<String>) {
        sensitive.clear()
        sensitive.addAll(values.filter { it.length >= 3 })
    }

    @Synchronized
    fun log(raw: String, forcedLevel: LogLevel? = null) {
        var message = ansi.replace(raw, "").replace(coreTimestamp, "").trim()
        if (message.isEmpty()) return
        sensitive.forEach { message = message.replace(it, "••••") }
        val level = forcedLevel ?: when {
            message.contains("ERROR", true) || message.contains("FATAL", true) -> LogLevel.ERROR
            message.contains("WARN", true) -> LogLevel.WARN
            else -> LogLevel.INFO
        }
        val entry = LogEntry(nextId++, SimpleDateFormat("HH:mm:ss", Locale.US).format(Date()), level, message)
        logFlow.update { (it + entry).takeLast(MAX_LINES) }
    }

    fun clearLog() {
        logFlow.value = emptyList()
    }
}
