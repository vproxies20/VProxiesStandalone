package app.vproxies.sa

import android.app.Application
import android.os.Build
import app.vproxies.sa.bg.LogLevel
import app.vproxies.sa.bg.ServiceState
import app.vproxies.sa.core.SettingsStore
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.SetupOptions
import java.io.File

class VProxiesApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SettingsStore.init(this)

        val baseDir = filesDir.apply { mkdirs() }
        val workingDir = File(filesDir, "core").apply { mkdirs() }
        val tempDir = cacheDir.apply { mkdirs() }
        runCatching {
            Libbox.setup(
                SetupOptions().also {
                    it.basePath = baseDir.path
                    it.workingPath = workingDir.path
                    it.tempPath = tempDir.path
                    // https://github.com/golang/go/issues/68760 (same condition as the official client)
                    it.fixAndroidStack = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
                    it.logMaxLines = 3000
                    it.debug = BuildConfig.DEBUG
                    it.crashReportSource = "Application"
                    it.appVersion = BuildConfig.VERSION_CODE.toString()
                    it.appMarketingVersion = BuildConfig.VERSION_NAME
                },
            )
            ServiceState.log("VProxies SA ${BuildConfig.VERSION_NAME} ready · core ${Libbox.version()}")
        }.onFailure {
            ServiceState.log("Core setup failed: ${it.message}", LogLevel.ERROR)
        }
    }
}
