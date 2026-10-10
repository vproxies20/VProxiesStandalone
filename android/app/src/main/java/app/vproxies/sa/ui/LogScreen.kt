package app.vproxies.sa.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.vproxies.sa.bg.LogLevel
import app.vproxies.sa.bg.ServiceState
import app.vproxies.sa.ui.theme.LocalSurfaces
import app.vproxies.sa.ui.theme.VColors

@Composable
fun LogScreen(contentPadding: PaddingValues) {
    val logs by ServiceState.logs.collectAsState()
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val s = LocalSurfaces.current

    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) listState.scrollToItem(logs.lastIndex)
    }

    Column(
        Modifier.fillMaxSize().padding(
            start = 16.dp, end = 16.dp, top = contentPadding.calculateTopPadding() + 12.dp,
            bottom = contentPadding.calculateBottomPadding() + 12.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Activity log", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                MutedText("Credentials are masked. Last 1000 lines.")
            }
            OutlinedButton(onClick = {
                val text = logs.joinToString("\n") { "${it.time}  ${it.level}  ${it.message}" }
                context.getSystemService(ClipboardManager::class.java)
                    .setPrimaryClip(ClipData.newPlainText("VProxies SA log", text))
                Toast.makeText(context, "Log copied", Toast.LENGTH_SHORT).show()
            }) { Text("Copy") }
            OutlinedButton(onClick = { ServiceState.clearLog() }, modifier = Modifier.padding(start = 8.dp)) { Text("Clear") }
        }
        SelectionContainer(Modifier.weight(1f)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().background(s.logBackground, RoundedCornerShape(14.dp)),
                contentPadding = PaddingValues(12.dp),
            ) {
                items(logs, key = { it.id }) { entry ->
                    val color = when (entry.level) {
                        LogLevel.ERROR -> VColors.LogError
                        LogLevel.WARN -> VColors.Warn
                        LogLevel.INFO -> VColors.LogInfo
                    }
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = VColors.LogTime)) { append(entry.time) }
                            withStyle(SpanStyle(color = color, fontWeight = FontWeight.Bold)) { append("  ${entry.level.name.padEnd(5)}  ") }
                            withStyle(SpanStyle(color = s.logText)) { append(entry.message) }
                        },
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 17.sp,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 3.dp),
                    )
                }
            }
        }
    }
}
