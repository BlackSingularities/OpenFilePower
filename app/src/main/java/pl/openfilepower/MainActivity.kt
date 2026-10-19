package pl.openfilepower

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import pl.openfilepower.ui.AppRoot
import pl.openfilepower.ui.AppTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        // Powiadomienie o postępie kopiowania w tle – pytamy raz, przy pierwszym uruchomieniu.
        val prefs = Preferences(this)
        if (Build.VERSION.SDK_INT >= 33 && !prefs.flag("askedNotifications", false) && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            prefs.setFlag("askedNotifications", true)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        setContent { AppTheme { AppRoot() } }
        handle(intent)
    }
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }
    /** Skrót folderu z ekranu głównego: model odbiera go przez [ShortcutBus]. */
    private fun handle(intent: android.content.Intent?) {
        if (intent?.action != ACTION_OPEN_FOLDER) return
        val root = intent.getStringExtra(EXTRA_ROOT) ?: return
        ShortcutBus.place.value = Place(root, intent.getStringArrayExtra(EXTRA_PARTS)?.toList() ?: emptyList())
    }
    companion object {
        const val ACTION_OPEN_FOLDER = "pl.openfilepower.OPEN_FOLDER"
        const val EXTRA_ROOT = "root"
        const val EXTRA_PARTS = "parts"
    }
}

object ShortcutBus { val place = kotlinx.coroutines.flow.MutableStateFlow<Place?>(null) }
