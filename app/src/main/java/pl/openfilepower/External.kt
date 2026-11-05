package pl.openfilepower

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import pl.openfilepower.ui.AppRoot
import pl.openfilepower.ui.AppTheme

/** Prośba z innej aplikacji: wybór pliku, zapis udostępnionych plików albo otwarcie pliku. */
sealed interface ExternalRequest {
    /** GET_CONTENT / OPEN_DOCUMENT / PICK – zwracamy wybrane pliki. */
    data class Pick(val multiple: Boolean, val mimes: List<String>) : ExternalRequest {
        fun accepts(item: Item) = !item.directory && (mimes.isEmpty() || mimes.any { m -> m == "*/*" || m == item.mime || (m.endsWith("/*") && item.mime.startsWith(m.dropLast(1))) })
    }
    /** SEND / SEND_MULTIPLE – użytkownik wybiera folder, do którego zapisujemy pliki. */
    data class Save(val uris: List<Uri>, val text: String?) : ExternalRequest
}

/** Plik otwarty z innej aplikacji: wirtualne źródło, w którym element ma tylko adres content://. */
const val EXTERNAL_ROOT_ID = "external"
val externalRoot = Root(EXTERNAL_ROOT_ID, "Otwarte pliki", "external:")

fun displayName(context: Context, uri: Uri): String {
    val fromProvider = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()
    return FileRules.sanitize(fromProvider ?: uri.lastPathSegment?.substringAfterLast('/') ?: "plik")
}
fun contentSize(context: Context, uri: Uri): Long = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L }
}.getOrNull() ?: -1L

/**
 * Wejście dla innych aplikacji (osobne od głównego okna, bo to ma launchMode singleTask,
 * a wybór pliku musi zwrócić wynik do aplikacji wywołującej).
 */
class ExternalActivity : androidx.fragment.app.FragmentActivity() {
    override fun onStart() { super.onStart(); AppLock.onStart(this) }
    override fun onStop() { AppLock.onStop(); super.onStop() }
    private val vm: ExplorerModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT), navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT))
        vm.finishExternal = { result -> if (result != null) setResult(RESULT_OK, result) else setResult(RESULT_CANCELED); finish() }
        if (savedInstanceState == null) handle(intent)
        pl.openfilepower.ui.I18n.load(this)
        setContent { AppTheme { AppRoot(vm) } }
    }
    override fun onDestroy() { vm.finishExternal = null; super.onDestroy() }

    @Suppress("DEPRECATION")
    private fun streams(intent: Intent): List<Uri> = when (intent.action) {
        Intent.ACTION_SEND -> listOfNotNull(if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else intent.getParcelableExtra(Intent.EXTRA_STREAM))
        Intent.ACTION_SEND_MULTIPLE -> (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java) else intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)) ?: emptyList()
        else -> emptyList()
    }

    private fun handle(intent: Intent) {
        when (intent.action) {
            Intent.ACTION_GET_CONTENT, Intent.ACTION_OPEN_DOCUMENT, Intent.ACTION_PICK -> {
                val extra = intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)?.toList().orEmpty()
                val mimes = (extra + listOfNotNull(intent.type)).filter { it.isNotBlank() }.distinct()
                vm.startExternal(ExternalRequest.Pick(intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false), mimes))
            }
            Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> {
                val uris = streams(intent)
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { uris.isEmpty() }
                if (uris.isEmpty() && text == null) { finish(); return }
                vm.startExternal(ExternalRequest.Save(uris, text))
            }
            Intent.ACTION_VIEW -> {
                val uri = intent.data ?: run { finish(); return }
                vm.openIncoming(uri, intent.type)
            }
            else -> finish()
        }
    }

    companion object {
        /** Wynik wyboru: adresy z prawem odczytu dla aplikacji wywołującej. */
        fun result(uris: List<Uri>): Intent {
            val intent = Intent().addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.data = uris.first()
            val clip = ClipData.newRawUri("", uris.first())
            uris.drop(1).forEach { clip.addItem(ClipData.Item(it)) }
            intent.clipData = clip
            return intent
        }
    }
}
