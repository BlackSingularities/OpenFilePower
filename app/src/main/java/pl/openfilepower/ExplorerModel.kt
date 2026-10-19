package pl.openfilepower

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.compose.runtime.*
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import java.io.File
import java.util.UUID

data class FolderTab(val id: String = UUID.randomUUID().toString(), val place: Place, val history: List<Place> = emptyList())
data class Details(val item: Item, val values: List<Pair<String, String>>, val preview: Uri? = null, val loading: Boolean = false)
data class PendingPaste(val clip: Clip, val destination: Place, val conflicts: List<Item>)
data class EditorState(val item: Item, val original: String, val text: String, val readOnly: Boolean = false) { val modified get() = text != original }
data class ArchiveState(val item: Item, val entries: List<Pair<String, Long>>)
data class Progress(val title: String, val text: String = "", val fraction: Float? = null, val cancellable: Boolean = false)

enum class Sort(val label: String) { NAME("Nazwa"), SIZE("Rozmiar"), DATE("Data"), TYPE("Typ") }

sealed interface View {
    data object Home : View
    data object Browse : View
    data class Category(val category: pl.openfilepower.Category) : View
    data object Trash : View
}

class ExplorerModel(app: Application) : AndroidViewModel(app) {
    private val context get() = getApplication<Application>()
    val prefs = Preferences(app)
    var roots by mutableStateOf(prefs.roots()); private set
    val files = Files(app) { roots }
    private val updater = Updates(app)
    private val media = MediaIndex(app)
    val trash = Trash()

    var view by mutableStateOf<View>(View.Home); private set
    var tabs by mutableStateOf(prefs.places("tabs").filter { p -> roots.any { it.id == p.root } }.map { FolderTab(place = it) }); private set
    var active by mutableStateOf(0); private set
    val place get() = if (view == View.Browse) tabs.getOrNull(active)?.place else null
    var entries by mutableStateOf<List<Item>>(emptyList()); private set
    var favorites by mutableStateOf(prefs.places("favorites")); private set
    var selected by mutableStateOf<Set<Place>>(emptySet()); private set
    val selecting get() = selected.isNotEmpty()
    var clipboard by mutableStateOf<Clip?>(null); private set
    var details by mutableStateOf<Details?>(null); private set
    var loading by mutableStateOf(false); private set
    var progress by mutableStateOf<Progress?>(null); private set
    val busy get() = progress != null
    var error by mutableStateOf<String?>(null)
    var notice by mutableStateOf<String?>(null)
    var query by mutableStateOf("")
    var searchResults by mutableStateOf<List<Item>?>(null); private set
    var searching by mutableStateOf(false); private set
    var pendingPaste by mutableStateOf<PendingPaste?>(null)
    var editor by mutableStateOf<EditorState?>(null)
    var archive by mutableStateOf<ArchiveState?>(null)
    var viewer by mutableStateOf<Pair<List<Item>, Int>?>(null)
    var trashEntries by mutableStateOf<List<TrashEntry>>(emptyList()); private set

    // Ustawienia widoku – zapamiętywane między uruchomieniami.
    var grid by mutableStateOf(prefs.flag("grid", false)); private set
    var showHidden by mutableStateOf(prefs.flag("hidden", false)); private set
    var foldersFirst by mutableStateOf(prefs.flag("foldersFirst", true)); private set
    var descending by mutableStateOf(prefs.flag("descending", false)); private set
    var useTrash by mutableStateOf(prefs.flag("useTrash", true)); private set
    var thumbnails by mutableStateOf(prefs.flag("thumbnails", true)); private set
    var sort by mutableStateOf(runCatching { Sort.valueOf(prefs.text("sort", Sort.NAME.name)) }.getOrDefault(Sort.NAME)); private set
    var autoUpdate by mutableStateOf(prefs.autoUpdate); private set

    // Ekran startowy.
    var access by mutableStateOf(StorageAccess.granted(app)); private set
    var spaces by mutableStateOf<List<Pair<StorageAccess.Volume, StorageAccess.Space>>>(emptyList()); private set
    var stats by mutableStateOf<List<CategoryStat>>(emptyList()); private set
    var recent by mutableStateOf<List<Item>>(emptyList()); private set

    // Aktualizacje.
    var release by mutableStateOf<Release?>(null)
    var updateBusy by mutableStateOf(false); private set
    var updateProgress by mutableStateOf<Float?>(null); private set
    var updateBannerHidden by mutableStateOf(false)
    var downloaded by mutableStateOf<File?>(null); private set

    var drag: Item? = null
    val tree = mutableStateMapOf<Place, List<Item>>()
    val expanded = mutableStateMapOf<Place, Boolean>()
    @Volatile private var cancelRequested = false
    private var loadJob: Job? = null
    private var detailJob: Job? = null
    private var searchJob: Job? = null

    val visible: List<Item> get() {
        val source = searchResults ?: entries
        val filtered = source.filter { (showHidden || !it.hidden) && (searchResults != null || it.name.contains(query, true)) }
        val comparator: Comparator<Item> = when (sort) {
            Sort.SIZE -> compareBy { it.size }
            Sort.DATE -> compareBy { it.modified }
            Sort.TYPE -> compareBy<Item> { it.extension }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
            Sort.NAME -> Comparator { a, b -> naturalCompare(a.name, b.name) }
        }
        val ordered = if (descending) comparator.reversed() else comparator
        return filtered.sortedWith(if (foldersFirst) compareByDescending<Item> { it.directory }.then(ordered) else ordered)
    }

    init {
        syncAccess()
        // Zapisane karty: start w ostatnim folderze; bez kart – ekran startowy.
        if (tabs.isNotEmpty()) view = View.Browse
        refresh()
        if (prefs.autoUpdate && BuildConfig.UPDATES_ENABLED) checkUpdates(false)
    }

    // ——— Źródła i dostęp do pamięci ———
    /** Po powrocie do aplikacji: dodaje lub usuwa źródła pamięci z pełnym dostępem. */
    fun syncAccess() {
        access = StorageAccess.granted(context)
        val directIds = roots.filter { it.direct }.map { it.id }.toSet()
        val wanted = if (access) StorageAccess.volumes(context).map { Root(it.id, it.name, Uri.fromFile(File(it.path)).toString()) } else emptyList()
        val updated = roots.filterNot { it.direct && wanted.none { w -> w.id == it.id } } + wanted.filter { it.id !in directIds }
        // Pamięć wewnętrzna zawsze na początku listy źródeł.
        val sorted = updated.sortedBy { if (it.direct) (if (it.id == StorageAccess.INTERNAL_ID) 0 else 1) else 2 }
        if (sorted != roots) {
            roots = sorted; prefs.saveRoots(roots)
            tabs = tabs.filter { t -> roots.any { it.id == t.place.root } }; active = active.coerceIn(0, (tabs.size - 1).coerceAtLeast(0)); saveTabs()
            if (tabs.isEmpty() && view == View.Browse) view = View.Home
        }
    }
    fun onResume() {
        val before = access
        syncAccess()
        // Właśnie nadano pełny dostęp: od razu otwórz katalog główny pamięci wewnętrznej.
        if (!before && access && tabs.none { it.place.root == StorageAccess.INTERNAL_ID }) { navigate(Place(StorageAccess.INTERNAL_ID), tabs.isNotEmpty()); return }
        if (before != access || view == View.Home) refresh()
    }
    /** Standardowe foldery pamięci wewnętrznej (DCIM, Pobrane, Dokumenty…), które istnieją na urządzeniu. */
    val mainFolders: List<Pair<String, Place>> get() {
        if (!access) return emptyList()
        val base = roots.firstOrNull { it.id == StorageAccess.INTERNAL_ID }?.path ?: return emptyList()
        return listOf("DCIM" to "Aparat", "Pictures" to "Obrazy", "Download" to "Pobrane", "Documents" to "Dokumenty", "Music" to "Muzyka", "Movies" to "Filmy", "Recordings" to "Nagrania", "Android" to "Android")
            .filter { File(base, it.first).isDirectory }.map { (dir, label) -> label to Place(StorageAccess.INTERNAL_ID, listOf(dir)) }
    }
    fun addLocal(uri: Uri) {
        try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            val existing = roots.find { it.address == uri.toString() }
            if (existing != null) { navigate(Place(existing.id)); return }
            val name = androidx.documentfile.provider.DocumentFile.fromTreeUri(context, uri)?.name ?: "Folder"
            val root = Root(UUID.randomUUID().toString(), name, uri.toString())
            roots = roots + root; prefs.saveRoots(roots); navigate(Place(root.id), true)
        } catch (e: Exception) { error = e.message }
    }
    fun addNetwork(name: String, host: String, share: String, user: String, password: String, domain: String) {
        operation("Łączenie z NAS") {
            require(name.isNotBlank() && host.matches(Regex("[a-zA-Z0-9.:-]+")) && share.isNotBlank()) { "Podaj nazwę, adres serwera i udział" }
            require(!share.contains('/') && !share.contains('\\')) { "Podaj nazwę pojedynczego udziału" }
            FileRules.validateName(share.trim())
            val address = "smb://${host.trim()}/${share.trim()}/"
            require(roots.none { it.address == address }) { "Ten udział jest już dodany" }
            val root = Root(UUID.randomUUID().toString(), name.trim(), address, user, domain, password)
            withContext(Dispatchers.IO) { Files(getApplication()) { listOf(root) }.list(Place(root.id)) }
            roots = roots + root; prefs.saveRoots(roots); navigate(Place(root.id), true); notice = "Połączono z NAS"
        }
    }
    fun removeRoot(root: Root) {
        if (busy || root.direct) return
        if (!root.network) runCatching { context.contentResolver.releasePersistableUriPermission(Uri.parse(root.address), Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
        roots = roots - root; prefs.saveRoots(roots)
        tabs = tabs.filterNot { it.place.root == root.id }; active = 0; saveTabs()
        favorites = favorites.filterNot { it.root == root.id }; prefs.savePlaces("favorites", favorites)
        if (tabs.isEmpty()) view = View.Home
        refresh()
    }
    fun rootOf(p: Place) = roots.find { it.id == p.root }

    // ——— Nawigacja ———
    private fun saveTabs() = prefs.savePlaces("tabs", tabs.map { it.place })
    fun title(p: Place) = p.parts.lastOrNull() ?: rootOf(p)?.name ?: "Folder"
    fun pathOf(p: Place) = (listOf(rootOf(p)?.name ?: "?") + p.parts).joinToString(" / ")
    private fun resetListing() { query = ""; searchJob?.cancel(); searchResults = null; searching = false; selected = emptySet() }
    fun navigate(p: Place, newTab: Boolean = false) {
        if (newTab || tabs.isEmpty()) { tabs = tabs + FolderTab(place = p); active = tabs.lastIndex }
        else if (view != View.Browse) tabs = tabs.mapIndexed { i, t -> if (i == active) t.copy(place = p, history = t.history + t.place) else t }
        else if (tabs[active].place != p) tabs = tabs.mapIndexed { i, t -> if (i == active) t.copy(place = p, history = (t.history + t.place).takeLast(50)) else t }
        view = View.Browse
        resetListing(); saveTabs(); refresh()
    }
    fun switch(index: Int) { active = index; view = View.Browse; resetListing(); refresh() }
    fun closeTab(index: Int) {
        tabs = tabs.filterIndexed { i, _ -> i != index }
        active = (if (index < active) active - 1 else active).coerceIn(0, (tabs.size - 1).coerceAtLeast(0)); saveTabs()
        if (tabs.isEmpty()) view = View.Home
        resetListing(); refresh()
    }
    fun goHome() { view = View.Home; resetListing(); refresh() }
    fun openCategory(category: Category) { view = View.Category(category); resetListing(); refresh() }
    fun openTrash() { view = View.Trash; resetListing(); refresh() }
    fun up() { place?.parent()?.let { navigate(it) } }
    /** Wstecz: zaznaczenie → wyszukiwanie → historia karty → folder nadrzędny → ekran startowy. */
    fun back(): Boolean {
        if (selecting) { selected = emptySet(); return true }
        if (searchResults != null || searching) { resetListing(); return true }
        if (view != View.Browse) { if (view == View.Home) return false; goHome(); return true }
        val tab = tabs.getOrNull(active) ?: return false
        val previous = tab.history.lastOrNull()
        if (previous != null) {
            tabs = tabs.mapIndexed { i, t -> if (i == active) t.copy(place = previous, history = t.history.dropLast(1)) else t }
            saveTabs(); resetListing(); refresh(); return true
        }
        tab.place.parent()?.let { parent -> tabs = tabs.mapIndexed { i, t -> if (i == active) t.copy(place = parent) else t }; saveTabs(); resetListing(); refresh(); return true }
        goHome(); return true
    }
    fun refresh() {
        loadJob?.cancel(); detailJob?.cancel(); entries = emptyList(); loading = false
        if (details?.item?.let { d -> view == View.Browse && d.place.parent() != place } == true) details = null
        loadJob = viewModelScope.launch {
            loading = true
            try {
                when (val v = view) {
                    View.Home -> loadHome()
                    View.Browse -> { val current = place ?: return@launch; entries = withContext(Dispatchers.IO) { files.list(current) } }
                    is View.Category -> entries = withContext(Dispatchers.IO) { media.files(v.category, roots) }
                    View.Trash -> trashEntries = withContext(Dispatchers.IO) { trash.entries().sortedByDescending { it.deleted } }
                }
            }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = "Nie można otworzyć folderu: ${e.message}" }
            finally { loading = false }
        }
    }
    private suspend fun loadHome() {
        spaces = withContext(Dispatchers.IO) { StorageAccess.volumes(context).mapNotNull { v -> StorageAccess.space(v.path)?.let { v to it } } }
        if (!access) { stats = emptyList(); recent = emptyList(); return }
        coroutineScope {
            launch { recent = withContext(Dispatchers.IO) { media.recent(roots) } }
            launch { stats = withContext(Dispatchers.IO) { media.stats() } }
        }
    }
    fun toggleFavorite(p: Place) { favorites = if (p in favorites) favorites - p else favorites + p; prefs.savePlaces("favorites", favorites) }
    fun toggleTree(p: Place) {
        expanded[p] = expanded[p] != true
        if (expanded[p] == true) viewModelScope.launch {
            try { tree[p] = withContext(Dispatchers.IO) { files.list(p).filter { it.directory && (showHidden || !it.hidden) }.sortedWith { a, b -> naturalCompare(a.name, b.name) } } }
            catch (e: Exception) { if (e !is CancellationException) error = e.message }
        }
    }

    // ——— Wyszukiwanie rekurencyjne ———
    fun search(text: String) {
        val start = place ?: return
        if (text.isBlank()) return
        searchJob?.cancel(); selected = emptySet()
        val found = mutableListOf<Item>()
        searchResults = emptyList(); searching = true
        searchJob = viewModelScope.launch {
            var last = 0L
            try {
                withContext(Dispatchers.IO) {
                    files.search(start, text, { !isActive }) { item ->
                        synchronized(found) { found += item }
                        val now = SystemClock.elapsedRealtime()
                        if (now - last > 250) { last = now; val snapshot = synchronized(found) { found.toList() }; launch(Dispatchers.Main) { if (searching) searchResults = snapshot } }
                    }
                }
                searchResults = synchronized(found) { found.toList() }
                notice = "Znaleziono ${found.size} elementów"
            } finally { searching = false }
        }
    }
    fun stopSearch() { searchJob?.cancel(); searching = false }

    // ——— Zaznaczanie i schowek ———
    fun select(item: Item) { selected = if (item.place in selected) selected - item.place else selected + item.place }
    fun selectAll() { selected = if (selected.size == visible.size) emptySet() else visible.map { it.place }.toSet() }
    fun invertSelection() { selected = visible.map { it.place }.toSet() - selected }
    fun clearSelection() { selected = emptySet() }
    val selectedItems get() = (searchResults ?: entries).filter { it.place in selected }
    fun clip(move: Boolean) {
        val items = selectedItems
        if (items.isEmpty()) return
        clipboard = Clip(items, move); selected = emptySet()
        notice = if (move) "Wycięto ${items.size} – przejdź do celu i wklej" else "Skopiowano ${items.size} – przejdź do celu i wklej"
    }
    fun clearClip() { clipboard = null }
    /** Wklejanie: najpierw sprawdza konflikty nazw i w razie potrzeby pyta, co zrobić. */
    fun paste(destination: Place? = place, dropped: Item? = null) {
        val target = destination ?: return
        val clip = dropped?.let { Clip(listOf(it), false) } ?: clipboard ?: return
        if (busy) return
        operation("Sprawdzanie celu") {
            val conflicts = withContext(Dispatchers.IO) { files.conflicts(clip, target) }
            if (conflicts.isEmpty()) launchTransfer(clip, target, Conflict.FAIL, dropped == null)
            else pendingPaste = PendingPaste(clip, target, conflicts)
        }
    }
    fun resolvePaste(policy: Conflict) {
        val p = pendingPaste ?: return
        pendingPaste = null
        launchTransfer(p.clip, p.destination, policy, p.clip == clipboard)
    }
    private fun launchTransfer(clip: Clip, target: Place, policy: Conflict, fromClipboard: Boolean) {
        val verb = if (clip.move) "Przenoszenie" else "Kopiowanie"
        viewModelScope.launch {
            // Uruchamiane po zakończeniu sprawdzania konfliktów (poprzednia operacja zwalnia „busy”).
            while (busy) delay(20)
            longOperation("$verb ${clip.items.size} elem. do ${title(target)}") { report ->
                val total = withContext(Dispatchers.IO) { files.measure(clip.items) { cancelRequested } }.bytes
                var previous = 0L
                withContext(Dispatchers.IO) { files.transfer(clip, target, { cancelRequested }, policy) { name, copied -> report(name, copied - previous, total); previous = copied } }
                if (clip.move && fromClipboard) clipboard = null
                tree.clear(); refresh(); notice = if (clip.move) "Przeniesiono ${clip.items.size} elem." else "Skopiowano ${clip.items.size} elem."
            }
        }
    }
    fun cancel() { cancelRequested = true; searchJob?.cancel() }

    // ——— Operacje na plikach ———
    fun mkdir(name: String) { val p = place ?: return; operation("Tworzenie folderu") { withContext(Dispatchers.IO) { files.mkdir(p, name) }; tree.clear(); refresh() } }
    fun createFile(name: String) {
        val p = place ?: return
        operation("Tworzenie pliku") {
            withContext(Dispatchers.IO) { files.createFile(p, name) }
            refresh()
            val created = withContext(Dispatchers.IO) { files.stat(p.child(name)) }
            if (created != null && FileRules.isText(created)) editor = EditorState(created, "", "")
        }
    }
    fun rename(item: Item, name: String) = operation("Zmiana nazwy") { withContext(Dispatchers.IO) { files.rename(item, name) }; tree.clear(); refresh(); notice = "Zmieniono nazwę na $name" }
    /** Czy zaznaczone elementy trafią do kosza (pamięć wewnętrzna) zamiast trwałego usunięcia. */
    fun trashable(items: List<Item> = selectedItems): Boolean = useTrash && items.isNotEmpty() && items.all { rootOf(it.place)?.direct == true && trash.supports(files.file(it.place)) }
    fun deleteSelected(items: List<Item> = selectedItems) {
        if (items.isEmpty()) return
        val toTrash = trashable(items)
        longOperation(if (toTrash) "Przenoszenie do kosza" else "Usuwanie ${items.size} elem.") { report ->
            withContext(Dispatchers.IO) {
                items.forEach { item ->
                    if (cancelRequested) throw CancellationException("Anulowano")
                    report(item.name, 1, items.size.toLong())
                    if (toTrash) trash.put(files.file(item.place)) else files.delete(item)
                }
            }
            selected = emptySet(); tree.clear(); refresh()
            notice = if (toTrash) "Przeniesiono do kosza: ${items.size}" else "Usunięto trwale: ${items.size}"
        }
    }
    fun compress(name: String, items: List<Item> = selectedItems) {
        val p = place ?: items.firstOrNull()?.place?.parent() ?: return
        longOperation("Pakowanie do $name") { report ->
            val total = withContext(Dispatchers.IO) { files.measure(items) { cancelRequested } }.bytes
            val zip = withContext(Dispatchers.IO) { files.compress(items, p, name, { cancelRequested }) { n, b -> report(n, b, total) } }
            selected = emptySet(); refresh(); notice = "Utworzono ${zip.parts.last()}"
        }
    }
    fun extract(item: Item) {
        val p = item.place.parent() ?: return
        archive = null
        longOperation("Rozpakowywanie ${item.name}") { report ->
            val folder = withContext(Dispatchers.IO) { files.extract(item, p, { cancelRequested }) { n, b -> report(n, b, 0) } }
            tree.clear()
            if (place == p) refresh()
            notice = "Rozpakowano do ${folder.parts.last()}"
        }
    }
    fun copyPath(item: Item) {
        val root = rootOf(item.place)
        val text = if (root?.direct == true) files.file(item.place).path else pathOf(item.place)
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Ścieżka", text))
        notice = "Skopiowano ścieżkę"
    }

    // ——— Kosz ———
    fun restore(entry: TrashEntry) = operation("Przywracanie") { val f = withContext(Dispatchers.IO) { trash.restore(entry) }; refresh(); notice = "Przywrócono ${f.name}" }
    fun forget(entry: TrashEntry) = operation("Usuwanie") { withContext(Dispatchers.IO) { trash.remove(entry) }; refresh() }
    fun emptyTrash() = operation("Opróżnianie kosza") { withContext(Dispatchers.IO) { trash.empty() }; refresh(); notice = "Kosz opróżniony" }

    // ——— Szczegóły ———
    fun inspect(item: Item) {
        detailJob?.cancel()
        details = Details(item, emptyList(), loading = true)
        detailJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                val info = mutableListOf("Nazwa" to item.name, "Rodzaj" to if (item.directory) "Folder" else item.mime, "Rozmiar" to formatSize(item.size), "Zmodyfikowano" to formatDate(item.modified), "Lokalizacja" to pathOf(item.place.parent() ?: item.place))
                if (rootOf(item.place)?.direct == true) info += "Ścieżka" to files.file(item.place).path
                if (item.directory) runCatching { files.measure(listOf(item)) { !isActive } }.onSuccess { m ->
                    info[2] = "Rozmiar" to "${formatSize(m.bytes)} (${formatBytes(m.bytes)})"
                    info += "Zawartość" to "${m.files} plików, ${m.folders - 1} folderów"
                }
                if (FileRules.isImage(item)) runCatching {
                    files.input(item).use { stream ->
                        val exif = ExifInterface(stream)
                        listOf("Aparat" to ExifInterface.TAG_MODEL, "Producent" to ExifInterface.TAG_MAKE, "Obiektyw" to ExifInterface.TAG_LENS_MODEL, "Wykonano" to ExifInterface.TAG_DATETIME_ORIGINAL, "Szerokość" to ExifInterface.TAG_IMAGE_WIDTH, "Wysokość" to ExifInterface.TAG_IMAGE_LENGTH, "ISO" to ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, "Przysłona" to ExifInterface.TAG_F_NUMBER, "Ekspozycja" to ExifInterface.TAG_EXPOSURE_TIME, "Ogniskowa" to ExifInterface.TAG_FOCAL_LENGTH).forEach { (label, tag) -> exif.getAttribute(tag)?.let { info += label to it } }
                        exif.latLong?.let { info += "GPS" to "%.6f, %.6f".format(java.util.Locale.ROOT, it[0], it[1]) }
                    }
                }.onFailure { info += "EXIF" to "Brak metadanych lub nieobsługiwany format" }
                if (FileRules.isZip(item)) runCatching { files.zipEntries(item, 10_000) }.onSuccess { list -> info += "Archiwum" to "${list.count { !it.first.endsWith('/') }} plików, rozpakowane ${formatSize(list.sumOf { it.second.coerceAtLeast(0) })}" }
                Details(item, info, if (FileRules.isImage(item) || FileRules.isVideo(item)) runCatching { item.localUri ?: files.uri(item) }.getOrNull() else null)
            }
            details = result
        }
    }
    fun hash(item: Item, algorithm: String) {
        longOperation("Liczenie $algorithm") { _ ->
            val value = withContext(Dispatchers.IO) { files.hash(item, algorithm) { cancelRequested } }
            details?.takeIf { it.item == item }?.let { d -> details = d.copy(values = d.values.filterNot { it.first == algorithm } + (algorithm to value)) }
        }
    }

    // ——— Otwieranie ———
    /** Foldery – nawigacja; zdjęcia – przeglądarka; tekst – edytor; ZIP – podgląd archiwum; reszta – inna aplikacja. */
    fun open(item: Item) {
        when {
            item.directory -> navigate(item.place)
            FileRules.isImage(item) && (item.localUri != null || rootOf(item.place)?.network == false) -> {
                val images = visible.filter { FileRules.isImage(it) && it.localUri != null }.ifEmpty { listOf(item) }
                viewer = images to images.indexOfFirst { it.place == item.place }.coerceAtLeast(0)
            }
            FileRules.isText(item) && item.size <= 2 * 1024 * 1024 -> openEditor(item)
            FileRules.isZip(item) -> operation("Czytanie archiwum") { archive = ArchiveState(item, withContext(Dispatchers.IO) { files.zipEntries(item) }) }
            else -> openExternal(item)
        }
    }
    fun openEditor(item: Item) = operation("Otwieranie ${item.name}") {
        val text = withContext(Dispatchers.IO) { files.readText(item) }
        editor = EditorState(item, text, text)
    }
    fun saveEditor() {
        val e = editor ?: return
        operation("Zapisywanie") {
            withContext(Dispatchers.IO) { files.overwrite(e.item, e.text.toByteArray()) }
            editor?.takeIf { it.item == e.item }?.let { editor = it.copy(original = e.text) }
            notice = "Zapisano ${e.item.name}"; refresh()
        }
    }
    private suspend fun shareable(item: Item): Uri = withContext(Dispatchers.IO) {
        files.uri(item) ?: run {
            val folder = File(context.cacheDir, "exports/${UUID.randomUUID()}").apply { mkdirs() }
            val target = File(folder, item.name)
            files.input(item).use { source -> target.outputStream().use { source.copyTo(it) } }
            FileProvider.getUriForFile(context, "${context.packageName}.files", target)
        }
    }
    fun openExternal(item: Item, chooser: Boolean = false) {
        if (item.directory) { navigate(item.place); return }
        operation(if (rootOf(item.place)?.network == true) "Pobieranie z NAS" else "Otwieranie") {
            val uri = shareable(item)
            val mime = if (FileRules.isApk(item)) "application/vnd.android.package-archive" else item.mime
            val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            try { context.startActivity(if (chooser) Intent.createChooser(intent, "Otwórz za pomocą").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) else intent) }
            catch (e: android.content.ActivityNotFoundException) {
                context.startActivity(Intent.createChooser(intent.setDataAndType(uri, "*/*"), "Otwórz za pomocą").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
    fun share(items: List<Item> = selectedItems) {
        val list = items.filterNot { it.directory }
        if (list.isEmpty()) { notice = "Foldery nie mogą być udostępniane – spakuj je do ZIP"; return }
        operation("Przygotowanie do udostępnienia") {
            val uris = list.map { shareable(it) }
            val intent = if (uris.size == 1) Intent(Intent.ACTION_SEND).setType(list[0].mime).putExtra(Intent.EXTRA_STREAM, uris[0])
            else Intent(Intent.ACTION_SEND_MULTIPLE).setType(if (list.map { it.mime.substringBefore('/') }.distinct().size == 1) list[0].mime.substringBefore('/') + "/*" else "*/*").putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            context.startActivity(Intent.createChooser(intent, "Udostępnij").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    // ——— Ustawienia ———
    fun updateGrid(v: Boolean) { grid = v; prefs.setFlag("grid", v) }
    fun updateHidden(v: Boolean) { showHidden = v; prefs.setFlag("hidden", v); tree.clear(); expanded.clear() }
    fun updateFoldersFirst(v: Boolean) { foldersFirst = v; prefs.setFlag("foldersFirst", v) }
    fun updateDescending(v: Boolean) { descending = v; prefs.setFlag("descending", v) }
    fun updateSort(v: Sort) { sort = v; prefs.setText("sort", v.name) }
    fun updateTrash(v: Boolean) { useTrash = v; prefs.setFlag("useTrash", v) }
    fun updateThumbnails(v: Boolean) { thumbnails = v; prefs.setFlag("thumbnails", v) }
    fun updateAutoUpdate(v: Boolean) { autoUpdate = v; prefs.autoUpdate = v }

    // ——— Wykonywanie operacji ———
    private fun operation(title: String, block: suspend () -> Unit) {
        if (busy) return
        viewModelScope.launch {
            progress = Progress(title)
            try { block() }
            catch (e: CancellationException) { notice = "Anulowano" }
            catch (e: Exception) { error = e.message ?: "Operacja nie powiodła się"; refresh() }
            finally { progress = null }
        }
    }
    /** Długa operacja: postęp w aplikacji i w powiadomieniu, usługa pierwszoplanowa chroni proces w tle. */
    private fun longOperation(title: String, block: suspend (report: (String, Long, Long) -> Unit) -> Unit) {
        if (busy) return
        cancelRequested = false
        viewModelScope.launch {
            progress = Progress(title, cancellable = true)
            TransferService.start(context, title)
            val started = SystemClock.elapsedRealtime()
            var last = 0L
            var done = 0L
            val report: (String, Long, Long) -> Unit = { name, value, total ->
                done += value
                val now = SystemClock.elapsedRealtime()
                if (now - last >= 150) {
                    last = now
                    val fraction = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
                    val seconds = (now - started) / 1000.0
                    val speed = if (seconds > 0.5 && total > 0) " · ${formatSize((done / seconds).toLong())}/s" else ""
                    val text = if (total > 0 && total > 1000) "$name · ${formatSize(done)} z ${formatSize(total)}$speed" else name
                    viewModelScope.launch { progress = Progress(title, text, fraction, true) }
                    TransferService.update(context, title, text, fraction?.let { (it * 100).toInt() } ?: -1)
                }
            }
            try { block(report) }
            catch (e: CancellationException) { notice = "Anulowano – ukończone elementy pozostały w celu"; tree.clear(); refresh() }
            catch (e: Exception) { error = e.message ?: "Operacja nie powiodła się"; tree.clear(); refresh() }
            finally { progress = null; TransferService.stop(context) }
        }
    }

    // ——— Aktualizacje ———
    fun checkUpdates(manual: Boolean = true) {
        if (updateBusy || !BuildConfig.UPDATES_ENABLED) { if (manual && !BuildConfig.UPDATES_ENABLED) notice = "Wersja testowa nie aktualizuje się automatycznie"; return }
        viewModelScope.launch {
            updateBusy = true
            try {
                release = withContext(Dispatchers.IO) { updater.check() }
                updateBannerHidden = false
                if (manual && release == null) notice = "Masz najnowszą wersję ${BuildConfig.VERSION_NAME}"
            }
            catch (e: Exception) { if (manual) error = "Aktualizacje: ${e.message}" }
            finally { updateBusy = false }
        }
    }
    fun installUpdate() {
        val r = release ?: return
        if (updateBusy) return
        viewModelScope.launch {
            updateBusy = true
            try {
                val apk = downloaded ?: withContext(Dispatchers.IO) { updater.download(r) { p -> viewModelScope.launch { updateProgress = p } } }.also { downloaded = it }
                updateProgress = null
                withContext(Dispatchers.IO) { updater.install(apk) }
            }
            catch (e: Exception) { error = e.message }
            finally { updateBusy = false; updateProgress = null }
        }
    }
    val certificate: String? by lazy { Updates.installedCertificate(context) }
}

fun formatSize(size: Long): String = when {
    size < 1024 -> "$size B"
    size < 1024 * 1024 -> "%.1f KB".format(size / 1024.0)
    size < 1024L * 1024 * 1024 -> "%.1f MB".format(size / 1048576.0)
    else -> "%.2f GB".format(size / 1073741824.0)
}
fun formatBytes(size: Long): String = "%,d B".format(size).replace(',', ' ')
fun formatDate(time: Long): String = if (time <= 0) "—" else java.text.SimpleDateFormat("d MMM yyyy, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(time))
fun formatShortDate(time: Long): String {
    if (time <= 0) return "—"
    val today = java.util.Calendar.getInstance()
    val then = java.util.Calendar.getInstance().apply { timeInMillis = time }
    val pattern = when {
        today.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) && today.get(java.util.Calendar.DAY_OF_YEAR) == then.get(java.util.Calendar.DAY_OF_YEAR) -> "HH:mm"
        today.get(java.util.Calendar.YEAR) == then.get(java.util.Calendar.YEAR) -> "d MMM"
        else -> "d MMM yyyy"
    }
    return java.text.SimpleDateFormat(pattern, java.util.Locale.getDefault()).format(java.util.Date(time))
}
/** Sortowanie naturalne: „plik 2” przed „plik 10”, bez rozróżniania wielkości liter. */
fun naturalCompare(a: String, b: String): Int {
    var i = 0; var j = 0
    while (i < a.length && j < b.length) {
        val ca = a[i]; val cb = b[j]
        if (ca.isDigit() && cb.isDigit()) {
            var ei = i; while (ei < a.length && a[ei].isDigit()) ei++
            var ej = j; while (ej < b.length && b[ej].isDigit()) ej++
            val na = a.substring(i, ei).trimStart('0'); val nb = b.substring(j, ej).trimStart('0')
            if (na.length != nb.length) return na.length - nb.length
            val c = na.compareTo(nb); if (c != 0) return c
            i = ei; j = ej
        } else {
            val c = ca.lowercaseChar().compareTo(cb.lowercaseChar())
            if (c != 0) return c
            i++; j++
        }
    }
    return (a.length - i) - (b.length - j)
}
