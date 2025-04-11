package net.rpcsx

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import net.rpcsx.ui.navigation.AppNavHost
import net.rpcsx.utils.GitHub
import kotlin.concurrent.thread

private const val ACTION_USB_PERMISSION = "net.rpcsx.USB_PERMISSION"

class MainActivity : ComponentActivity() {
    private lateinit var unregisterUsbEventListener: () -> Unit
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        WindowCompat.setDecorFitsSystemWindows(window, false)
        
        setContent {
            RPCSXTheme {
                AppNavHost()
            }
        }

        RPCSX.rootDirectory = applicationContext.getExternalFilesDir(null).toString()
        if (!RPCSX.rootDirectory.endsWith("/")) {
            RPCSX.rootDirectory += "/"
        }

        if (!RPCSX.initialized) {
            lifecycleScope.launch { GameRepository.load() }
            FirmwareRepository.load()
        }

        Permission.PostNotifications.requestPermission(this)

        with(getSystemService(NOTIFICATION_SERVICE) as NotificationManager) {
            val channel = NotificationChannel(
                "rpcsx-progress",
                "Installation progress",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                setShowBadge(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            }

            createNotificationChannel(channel)
        }

        if (!RPCSX.initialized) {
            GitHub.initialize(this)
            RPCSX.instance.initialize(RPCSX.rootDirectory)
            val nativeLibraryDir = packageManager.getApplicationInfo(packageName, 0).nativeLibraryDir
            RPCSX.instance.settingsSet("Video@@Vulkan@@Custom Driver@@Hook Directory", "\"" + nativeLibraryDir + "\"")
            RPCSX.initialized = true

            thread {
                RPCSX.instance.startMainThreadProcessor()
            }

            thread {
                RPCSX.instance.processCompilationQueue()
            }
        }

        unregisterUsbEventListener = listenUsbEvents(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterUsbEventListener()
    }
}
