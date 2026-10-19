package pl.openfilepower

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.*
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID

data class FolderTab(val id: String = UUID.randomUUID().toString(), val place: Place, val history: List<Place> = emptyList())
data class Details(val item: Item, val values: List<Pair<String, String>>, val preview: Uri? = null)
class ExplorerModel(app: Application) : AndroidViewModel(app) {
    val prefs = Preferences(app)
    var roots by mutableStateOf(prefs.roots()); private set
    val files = Files(app) { roots }
    private val updater = Updates(app)
    var tabs by mutableStateOf(prefs.places("tabs").filter { p -> roots.any { it.id == p.root } }.map { FolderTab(place = it) }); private set
    var active by mutableStateOf(0); private set
    val place get() = tabs.getOrNull(active)?.place
    var entries by mutableStateOf<List<Item>>(emptyList()); private set
    var favorites by mutableStateOf(prefs.places("favorites")); private set
    var selected by mutableStateOf<Set<Place>>(emptySet()); private set
    var clipboard by mutableStateOf<Clip?>(null); private set
    var details by mutableStateOf<Details?>(null); private set
    var loading by mutableStateOf(false); private set
    var busy by mutableStateOf(false); private set
    var cancellable by mutableStateOf(false); private set
    var progress by mutableStateOf(""); private set
    var error by mutableStateOf<String?>(null)
    var notice by mutableStateOf<String?>(null)
    var query by mutableStateOf("")
    var grid by mutableStateOf(false)
    var showHidden by mutableStateOf(false)
    var sort by mutableStateOf("Nazwa")
    var descending by mutableStateOf(false)
    var release by mutableStateOf<Release?>(null)
    var updateBusy by mutableStateOf(false)
    var downloaded by mutableStateOf<File?>(null)
    var drag: Item? = null
    val tree = mutableStateMapOf<Place, List<Item>>()
    val expanded = mutableStateMapOf<Place, Boolean>()
    @Volatile private var cancelTransfer = false
    private var loadJob: Job? = null
    private var detailJob: Job? = null
    val visible: List<Item> get() {
        val filtered = entries.filter { (showHidden || !it.name.startsWith('.')) && it.name.contains(query, true) }
        val comparator: Comparator<Item> = when (sort) { "Rozmiar" -> compareBy { it.size }; "Data" -> compareBy { it.modified }; "Typ" -> compareBy { it.mime }; else -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.name } }
        return filtered.sortedWith(compareByDescending<Item> { it.directory }.then(if (descending) comparator.reversed() else comparator))
    }
    init { refresh(); if (prefs.autoUpdate) checkUpdates(false) }
    private fun saveTabs() = prefs.savePlaces("tabs", tabs.map { it.place })
    fun title(p: Place) = p.parts.lastOrNull() ?: roots.find { it.id == p.root }?.name ?: "Folder"
    fun navigate(p: Place, newTab: Boolean = false) {
        if (newTab || tabs.isEmpty()) { tabs = tabs + FolderTab(place = p); active = tabs.lastIndex }
        else { tabs = tabs.mapIndexed { i, t -> if (i == active) t.copy(place = p, history = t.history + t.place) else t } }
        query = ""; saveTabs(); refresh()
    }
    fun switch(index: Int) { active = index; query = ""; refresh() }
    fun closeTab(index: Int) {
        tabs = tabs.filterIndexed { i, _ -> i != index }
        active = (if (index < active) active - 1 else active).coerceIn(0, (tabs.size - 1).coerceAtLeast(0)); saveTabs(); refresh()
    }
    fun back(): Boolean {
        val tab = tabs.getOrNull(active) ?: return false
        val previous = tab.history.lastOrNull() ?: return false
        tabs = tabs.mapIndexed { i, t -> if (i == active) t.copy(place = previous, history = t.history.dropLast(1)) else t }; saveTabs(); refresh(); return true
    }
    fun refresh() {
        loadJob?.cancel(); detailJob?.cancel(); entries = emptyList(); selected = emptySet(); details = null; loading = false
        val current = place ?: return
        loadJob = viewModelScope.launch {
            loading = true
            try { entries = withContext(Dispatchers.IO) { files.list(current) } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = "Nie można otworzyć folderu: ${e.message}" }
            finally { loading = false }
        }
    }
    fun addLocal(uri: Uri) {
        val app = getApplication<Application>()
        try {
            app.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            val existing = roots.find { it.address == uri.toString() }
            if (existing != null) { navigate(Place(existing.id)); return }
            val name = androidx.documentfile.provider.DocumentFile.fromTreeUri(app, uri)?.name ?: "Pamięć urządzenia"
            val root = Root(UUID.randomUUID().toString(), name, uri.toString())
            roots = roots + root; prefs.saveRoots(roots); navigate(Place(root.id))
        } catch (e: Exception) { error = e.message }
    }
    fun addNetwork(name: String, host: String, share: String, user: String, password: String, domain: String) {
        operation {
            require(name.isNotBlank() && host.matches(Regex("[a-zA-Z0-9.:-]+")) && share.isNotBlank()) { "Podaj nazwę, adres serwera i udział" }
            require(!share.contains('/') && !share.contains('\\')) { "Podaj nazwę pojedynczego udziału" }
            val address = "smb://${host.trim()}/${Uri.encode(share.trim())}/"
            require(roots.none { it.address == address }) { "Ten udział jest już dodany" }
            val root = Root(UUID.randomUUID().toString(), name.trim(), address, user, domain, password)
            withContext(Dispatchers.IO) { Files(getApplication()) { listOf(root) }.list(Place(root.id)) }
            roots = roots + root; prefs.saveRoots(roots); navigate(Place(root.id)); notice = "Połączono z NAS"
        }
    }
    fun removeRoot(root: Root) {
        if (busy) return
        roots = roots - root; prefs.saveRoots(roots)
        tabs = tabs.filterNot { it.place.root == root.id }; active = 0; saveTabs()
        favorites = favorites.filterNot { it.root == root.id }; prefs.savePlaces("favorites", favorites); refresh()
    }
    fun toggleFavorite(p: Place) { favorites = if (p in favorites) favorites - p else favorites + p; prefs.savePlaces("favorites", favorites) }
    fun toggleTree(p: Place) {
        expanded[p] = expanded[p] != true
        if (expanded[p] == true) viewModelScope.launch {
            try { tree[p] = withContext(Dispatchers.IO) { files.list(p).filter { it.directory }.sortedBy { it.name.lowercase() } } }
            catch (e: Exception) { if (e !is CancellationException) error = e.message }
        }
    }
    fun select(item: Item) { selected = if (item.place in selected) selected - item.place else selected + item.place; inspect(item) }
    fun selectAll() { selected = if (selected.size == visible.size) emptySet() else visible.map { it.place }.toSet() }
    fun clip(move: Boolean) { clipboard = Clip(entries.filter { it.place in selected }, move); notice = if (move) "Wybrano do przeniesienia" else "Skopiowano do schowka" }
    fun clearClip() { clipboard = null }
    fun paste(destination: Place? = place, dropped: Item? = null) {
        val target = destination ?: return
        val clip = dropped?.let { Clip(listOf(it), false) } ?: clipboard ?: return
        operation {
            cancellable = true
            cancelTransfer = false
            var lastProgress = 0L
            withContext(Dispatchers.IO) { files.transfer(clip, target, { cancelTransfer }) { name, bytes ->
                val now = android.os.SystemClock.elapsedRealtime()
                if (now - lastProgress >= 100) {
                    lastProgress = now
                    viewModelScope.launch { progress = "$name · ${formatSize(bytes)}" }
                }
            } }
            if (clip.move) clipboard = null
            tree.clear(); refresh(); notice = if (clip.move) "Przeniesiono pliki" else "Skopiowano pliki"
        }
    }
    fun cancel() { cancelTransfer = true }
    fun mkdir(name: String) { val p = place ?: return; operation { withContext(Dispatchers.IO) { files.mkdir(p, name) }; tree.clear(); refresh() } }
    fun rename(item: Item, name: String) = operation { withContext(Dispatchers.IO) { files.rename(item, name) }; tree.clear(); refresh() }
    fun deleteSelected() {
        val items = entries.filter { it.place in selected }
        operation { withContext(Dispatchers.IO) { items.forEach { files.delete(it) } }; tree.clear(); refresh(); notice = "Usunięto ${items.size} elementów" }
    }
    private fun operation(block: suspend () -> Unit) {
        if (busy) return
        viewModelScope.launch {
            busy = true; progress = "Pracuję…"
            try { block() } catch (e: Exception) { error = e.message ?: "Operacja nie powiodła się"; refresh() }
            finally { busy = false; cancellable = false; progress = "" }
        }
    }
    fun inspect(item: Item) {
        detailJob?.cancel()
        details = Details(item, emptyList())
        detailJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                val info = mutableListOf("Nazwa" to item.name, "Rodzaj" to if (item.directory) "Folder" else item.mime, "Rozmiar" to formatSize(item.size), "Zmodyfikowano" to formatDate(item.modified), "Lokalizacja" to (listOf(title(Place(item.place.root))) + item.place.parts).joinToString(" / "))
                if (!item.directory && item.mime.startsWith("image/")) runCatching {
                    files.input(item).use { stream ->
                        val exif = ExifInterface(stream)
                        listOf("Aparat" to ExifInterface.TAG_MODEL, "Producent" to ExifInterface.TAG_MAKE, "Obiektyw" to ExifInterface.TAG_LENS_MODEL, "Wykonano" to ExifInterface.TAG_DATETIME_ORIGINAL, "Szerokość" to ExifInterface.TAG_IMAGE_WIDTH, "Wysokość" to ExifInterface.TAG_IMAGE_LENGTH, "ISO" to ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, "Przysłona" to ExifInterface.TAG_F_NUMBER, "Ekspozycja" to ExifInterface.TAG_EXPOSURE_TIME, "Ogniskowa" to ExifInterface.TAG_FOCAL_LENGTH).forEach { (label, tag) -> exif.getAttribute(tag)?.let { info += label to it } }
                        exif.latLong?.let { info += "GPS" to "${it[0]}, ${it[1]}" }
                    }
                }.onFailure { info += "EXIF" to "Brak metadanych lub nieobsługiwany format" }
                Details(item, info, if (item.mime.startsWith("image/")) runCatching { files.uri(item) }.getOrNull() else null)
            }
            details = result
        }
    }
    fun open(item: Item, share: Boolean = false) {
        if (item.directory) { navigate(item.place); return }
        operation {
            val app = getApplication<Application>()
            val uri = withContext(Dispatchers.IO) {
                files.uri(item) ?: run {
                    val folder = File(app.cacheDir, "exports/${UUID.randomUUID()}").apply { mkdirs() }
                    val target = File(folder, item.name)
                    files.input(item).use { source -> target.outputStream().use { source.copyTo(it) } }
                    FileProvider.getUriForFile(app, "${app.packageName}.files", target)
                }
            }
            val intent = if (share) Intent(Intent.ACTION_SEND).setType(item.mime).putExtra(Intent.EXTRA_STREAM, uri) else Intent(Intent.ACTION_VIEW).setDataAndType(uri, item.mime)
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            app.startActivity(Intent.createChooser(intent, if (share) "Udostępnij" else "Otwórz w aplikacji").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
    fun checkUpdates(manual: Boolean = true) {
        if (updateBusy) return
        viewModelScope.launch {
            updateBusy = true
            try { release = withContext(Dispatchers.IO) { updater.check() }; if (manual && release == null) notice = "Masz najnowszą wersję ${BuildConfig.VERSION_NAME}" }
            catch (e: Exception) { if (manual) error = "Aktualizacje: ${e.message}" }
            finally { updateBusy = false }
        }
    }
    fun installUpdate() {
        val r = release ?: return
        if (updateBusy) return
        viewModelScope.launch {
            updateBusy = true
            try { val apk = downloaded ?: withContext(Dispatchers.IO) { updater.download(r) }.also { downloaded = it }; updater.install(apk) }
            catch (e: Exception) { error = e.message }
            finally { updateBusy = false }
        }
    }
}
fun formatSize(size: Long): String = when { size < 1024 -> "$size B"; size < 1024 * 1024 -> "%.1f KB".format(size / 1024.0); size < 1024L * 1024 * 1024 -> "%.1f MB".format(size / 1048576.0); else -> "%.2f GB".format(size / 1073741824.0) }
fun formatDate(time: Long): String = if (time <= 0) "—" else java.text.SimpleDateFormat("dd MMM yyyy, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(time))
