package pl.openfilepower

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.UUID

/** Para folderów: [local] na telefonie, [remote] na NAS, karcie SD, USB lub w innym folderze. */
data class SyncPair(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val local: Place,
    val remote: Place,
    val mode: SyncMode = SyncMode.TWO_WAY,
    val policy: ConflictPolicy = ConflictPolicy.ASK,
    val deletions: Boolean = true,
    val auto: Boolean = true,
    val hidden: Boolean = false,
    val excludes: String = SyncFilter.DEFAULT,
    val trash: Boolean = true,
) {
    val settings get() = SyncSettings(mode, policy, deletions, SyncFilter(hidden, excludes))
}

/** Zapamiętany stan pary: baza ostatniej synchronizacji, konflikty, historia i ostatni status. */
data class SyncState(
    val base: Map<String, BaseEntry> = emptyMap(),
    val conflicts: List<SyncConflict> = emptyList(),
    val history: List<SyncRun> = emptyList(),
    val lastSuccess: Long = 0,
    val lastAttempt: Long = 0,
    /** null – nie sprawdzano; false – ostatnio dysk był niedostępny. */
    val available: Boolean? = null,
    /** Zmiany na telefonie od ostatniej synchronizacji – liczone, gdy dysk jest niedostępny. */
    val pendingLocal: SideChanges? = null,
    /** Zabezpieczenie zatrzymało synchronizację – wymaga potwierdzenia. */
    val guard: String? = null,
    val message: String? = null,
)

/** Strona synchronizacji oparta o [Files]: działa tak samo dla pamięci telefonu, SAF (USB, SD) i SMB. */
class FilesSide(private val files: Files, private val base: Place, private val discard: ((Item) -> Unit)? = null) : SyncSide {
    private fun place(path: String) = if (path.isEmpty()) base else base.copy(parts = base.parts + path.split('/'))
    private fun entry(path: String, item: Item) = SyncEntry(path, item.directory, if (item.directory) 0 else item.size, item.modified)

    override fun scan(filter: SyncFilter, cancelled: () -> Boolean): Map<String, SyncEntry> {
        val result = HashMap<String, SyncEntry>()
        val root = files.root(base)
        if (root.direct) {
            // Pamięć z pełnym dostępem: bezpośredni spacer po drzewie jest wielokrotnie szybszy niż listowanie przez Item.
            val start = files.file(base)
            check(start.isDirectory) { "Folder nie istnieje: ${start.path}" }
            fun walk(dir: File, prefix: String) {
                if (cancelled()) throw java.util.concurrent.CancellationException("Anulowano")
                val children = dir.listFiles() ?: error("Brak uprawnień do odczytu: ${dir.path}")
                for (f in children) {
                    if (!filter.accept(f.name)) continue
                    val path = prefix + f.name
                    result[path] = SyncEntry(path, f.isDirectory, if (f.isDirectory) 0 else f.length(), f.lastModified())
                    if (f.isDirectory) walk(f, "$path/")
                }
            }
            walk(start, "")
            return result
        }
        val queue = ArrayDeque(listOf(""))
        while (queue.isNotEmpty()) {
            if (cancelled()) throw java.util.concurrent.CancellationException("Anulowano")
            val folder = queue.removeFirst()
            for (item in files.list(place(folder))) {
                if (!filter.accept(item.name)) continue
                val path = if (folder.isEmpty()) item.name else "$folder/${item.name}"
                result[path] = entry(path, item)
                if (item.directory) queue += path
            }
        }
        return result
    }
    override fun stat(path: String): SyncEntry? = files.probe(place(path))?.let { entry(path, it) }
    override fun open(path: String): InputStream {
        val item = files.probe(place(path)) ?: error("Plik zniknął")
        return files.input(item)
    }
    override fun write(path: String, source: InputStream, size: Long, modified: Long, cancelled: () -> Boolean, progress: (Long) -> Unit): SyncEntry {
        val parent = path.substringBeforeLast('/', "")
        if (parent.isNotEmpty()) mkdirs(parent)
        val item = files.replace(place(parent), path.substringAfterLast('/'), source, size, modified, cancelled, { _, n -> progress(n) }, discard ?: files::delete)
        return entry(path, item)
    }
    override fun mkdirs(path: String): SyncEntry {
        var current = ""
        for (part in path.split('/')) {
            val next = if (current.isEmpty()) part else "$current/$part"
            val existing = files.probe(place(next))
            if (existing == null) files.mkdir(place(current), part) else check(existing.directory) { "W miejscu folderu jest plik: $next" }
            current = next
        }
        return stat(path) ?: error("Nie udało się utworzyć folderu $path")
    }
    override fun delete(path: String, directory: Boolean): Boolean {
        val item = files.probe(place(path)) ?: return true
        if (item.directory) {
            if (files.list(item.place).isNotEmpty()) return false
            files.delete(item)
        } else (discard ?: files::delete)(item)
        return true
    }
    override fun rename(path: String, newName: String): SyncEntry {
        val item = files.probe(place(path)) ?: error("Nie znaleziono $path")
        files.rename(item, newName)
        val renamed = (path.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }) + newName
        return stat(renamed) ?: error("Nie udało się zmienić nazwy $path")
    }
}

/** Pary i ich stan w plikach JSON w pamięci aplikacji (zapis atomowy). */
class SyncStore(context: Context) {
    private val dir = File(context.filesDir, "sync").apply { mkdirs() }
    private fun write(file: File, text: String) {
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(text)
        if (!temp.renameTo(file)) { file.delete(); temp.renameTo(file) }
    }
    private fun place(o: JSONObject) = Place(o.getString("root"), o.getJSONArray("parts").let { a -> (0 until a.length()).map { a.getString(it) } })
    private fun place(p: Place) = JSONObject().put("root", p.root).put("parts", JSONArray(p.parts))

    @Synchronized fun pairs(): List<SyncPair> = runCatching {
        val a = JSONArray(File(dir, "pairs.json").readText())
        (0 until a.length()).map { i ->
            a.getJSONObject(i).run {
                SyncPair(
                    getString("id"), getString("name"), place(getJSONObject("local")), place(getJSONObject("remote")),
                    runCatching { SyncMode.valueOf(getString("mode")) }.getOrDefault(SyncMode.TWO_WAY),
                    runCatching { ConflictPolicy.valueOf(getString("policy")) }.getOrDefault(ConflictPolicy.ASK),
                    optBoolean("deletions", true), optBoolean("auto", true), optBoolean("hidden", false), optString("excludes", SyncFilter.DEFAULT), optBoolean("trash", true),
                )
            }
        }
    }.getOrDefault(emptyList())
    @Synchronized fun savePairs(pairs: List<SyncPair>) {
        val a = JSONArray()
        pairs.forEach { p ->
            a.put(JSONObject().put("id", p.id).put("name", p.name).put("local", place(p.local)).put("remote", place(p.remote)).put("mode", p.mode.name).put("policy", p.policy.name)
                .put("deletions", p.deletions).put("auto", p.auto).put("hidden", p.hidden).put("excludes", p.excludes).put("trash", p.trash))
        }
        write(File(dir, "pairs.json"), a.toString())
    }

    private fun side(s: SideState?) = s?.let { JSONArray().put(if (it.directory) 1 else 0).put(it.size).put(it.modified) }
    private fun side(a: JSONArray?) = a?.let { SideState(it.getInt(0) == 1, it.getLong(1), it.getLong(2)) }
    private fun entry(e: SyncEntry?) = e?.let { JSONObject().put("path", it.path).put("dir", it.directory).put("size", it.size).put("modified", it.modified) }
    private fun entry(o: JSONObject?) = o?.let { SyncEntry(it.getString("path"), it.getBoolean("dir"), it.getLong("size"), it.getLong("modified")) }

    @Synchronized fun state(id: String): SyncState = runCatching {
        val o = JSONObject(File(dir, "state-$id.json").readText())
        val base = HashMap<String, BaseEntry>()
        o.getJSONObject("base").let { b -> b.keys().forEach { k -> val v = b.getJSONObject(k); base[k] = BaseEntry(side(v.optJSONArray("l")), side(v.optJSONArray("r"))) } }
        val conflicts = o.optJSONArray("conflicts")?.let { a -> (0 until a.length()).map { i -> a.getJSONObject(i).run { SyncConflict(getString("path"), ConflictKind.valueOf(getString("kind")), entry(optJSONObject("l")), entry(optJSONObject("r"))) } } } ?: emptyList()
        val history = o.optJSONArray("history")?.let { a ->
            (0 until a.length()).map { i ->
                a.getJSONObject(i).run {
                    SyncRun(getLong("time"), optLong("duration"), optString("trigger"), optInt("up"), optInt("down"), optInt("delLocal"), optInt("delRemote"), optInt("folders"), optInt("conflicts"), optInt("resolved"), optLong("bytes"),
                        optJSONArray("errors")?.let { e -> (0 until e.length()).map { e.getString(it) } } ?: emptyList(), optString("failure").ifEmpty { null })
                }
            }
        } ?: emptyList()
        val pending = o.optJSONArray("pending")?.let { SideChanges(it.getInt(0), it.getInt(1), it.getInt(2)) }
        SyncState(base, conflicts, history, o.optLong("lastSuccess"), o.optLong("lastAttempt"), if (o.has("available")) o.getBoolean("available") else null, pending, o.optString("guard").ifEmpty { null }, o.optString("message").ifEmpty { null })
    }.getOrDefault(SyncState())

    @Synchronized fun saveState(id: String, s: SyncState) {
        val base = JSONObject()
        s.base.forEach { (k, v) -> base.put(k, JSONObject().apply { side(v.local)?.let { put("l", it) }; side(v.remote)?.let { put("r", it) } }) }
        val o = JSONObject().put("base", base).put("lastSuccess", s.lastSuccess).put("lastAttempt", s.lastAttempt)
            .put("conflicts", JSONArray().also { a -> s.conflicts.forEach { c -> a.put(JSONObject().put("path", c.path).put("kind", c.kind.name).apply { entry(c.local)?.let { put("l", it) }; entry(c.remote)?.let { put("r", it) } }) } })
            .put("history", JSONArray().also { a ->
                s.history.forEach { r ->
                    a.put(JSONObject().put("time", r.time).put("duration", r.duration).put("trigger", r.trigger).put("up", r.uploaded).put("down", r.downloaded).put("delLocal", r.deletedLocal).put("delRemote", r.deletedRemote)
                        .put("folders", r.folders).put("conflicts", r.conflicts).put("resolved", r.resolved).put("bytes", r.bytes).put("errors", JSONArray(r.errors.take(50))).put("failure", r.failure ?: ""))
                }
            })
            .put("guard", s.guard ?: "").put("message", s.message ?: "")
        s.available?.let { o.put("available", it) }
        s.pendingLocal?.let { o.put("pending", JSONArray().put(it.added).put(it.modified).put(it.deleted)) }
        write(File(dir, "state-$id.json"), o.toString())
    }
    @Synchronized fun remove(id: String) { File(dir, "state-$id.json").delete() }
}

data class SyncProgress(val text: String, val fraction: Float?)

/**
 * Jedno miejsce wykonywania synchronizacji – wspólne dla aplikacji i zadania w tle,
 * z blokadą, by ta sama para nigdy nie synchronizowała się dwa razy naraz.
 */
object SyncRunner {
    private val lock = Mutex()
    val running = MutableStateFlow<Map<String, SyncProgress>>(emptyMap())
    /** Zmienia się po każdym zapisie stanu – UI odświeża wtedy karty par. */
    val changed = MutableStateFlow(0L)
    private val cancelled = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    fun cancel(id: String) { cancelled += id }

    private fun files(context: Context) = Preferences(context).roots().let { roots -> Files(context) { roots } }
    private fun discard(files: Files, pair: SyncPair): ((Item) -> Unit)? {
        if (!pair.trash) return null
        val trash = Trash()
        return { item ->
            val root = files.root(item.place)
            if (root.direct && trash.supports(files.file(item.place))) trash.put(files.file(item.place)) else files.delete(item)
        }
    }
    /** Czy oba foldery pary są teraz osiągalne (NAS w sieci, USB podłączone). */
    fun reachable(files: Files, place: Place): Boolean = runCatching { files.root(place); if (place.parts.isEmpty()) { files.list(place); true } else files.probe(place)?.directory == true }.getOrDefault(false)

    private fun progress(id: String, p: SyncProgress?) { running.value = if (p == null) running.value - id else running.value + (id to p) }

    suspend fun preview(context: Context, pair: SyncPair): SyncPreview = withContext(Dispatchers.IO) {
        val store = SyncStore(context); val files = files(context)
        check(reachable(files, pair.local)) { "Folder na telefonie jest niedostępny" }
        check(reachable(files, pair.remote)) { "Dysk jest niedostępny – sprawdź połączenie z siecią lub podłącz nośnik" }
        SyncEngine(FilesSide(files, pair.local), FilesSide(files, pair.remote), pair.settings).preview(store.state(pair.id).base)
    }

    /** Synchronizuje parę; gdy dysk jest niedostępny, liczy tylko oczekujące zmiany na telefonie. */
    suspend fun run(context: Context, pair: SyncPair, trigger: String, resolutions: Map<String, Resolution> = emptyMap(), force: Boolean = false): SyncRun = withContext(Dispatchers.IO) {
        lock.withLock {
            val store = SyncStore(context)
            val files = files(context)
            var state = store.state(pair.id)
            cancelled -= pair.id
            progress(pair.id, SyncProgress("Łączenie…", null))
            try {
                val now = System.currentTimeMillis()
                if (!reachable(files, pair.local)) {
                    state = state.copy(lastAttempt = now, message = "Folder na telefonie jest niedostępny")
                    return@withLock SyncRun(now, 0, trigger, failure = state.message)
                }
                val local = FilesSide(files, pair.local, discard(files, pair))
                if (!reachable(files, pair.remote)) {
                    // Pliki na telefonie są dalej dostępne; zapamiętujemy, ile zmian czeka na wysłanie.
                    val pending = runCatching { SideChanges.of(local.scan(pair.settings.filter) { false }, state.base, SyncSideId.LOCAL) }.getOrNull()
                    state = state.copy(lastAttempt = now, available = false, pendingLocal = pending, message = "Dysk niedostępny – pliki na telefonie są dostępne, zmiany wyślą się po połączeniu")
                    return@withLock SyncRun(now, 0, trigger, failure = state.message)
                }
                val engine = SyncEngine(local, FilesSide(files, pair.remote), pair.settings) { pair.id in cancelled }
                val outcome = try {
                    engine.run(state.base, trigger, resolutions, force, { text, f -> progress(pair.id, SyncProgress(text, f)) }) { base ->
                        state = state.copy(base = base); store.saveState(pair.id, state)
                    }
                } catch (e: SyncGuardException) {
                    state = state.copy(lastAttempt = now, available = true, guard = e.message, message = "Wstrzymano – wymaga potwierdzenia")
                    return@withLock SyncRun(now, 0, trigger, failure = "Wstrzymano: ${e.message}")
                }
                val run = outcome.run
                state = state.copy(
                    base = outcome.base, conflicts = outcome.conflicts, history = (listOf(run) + state.history).take(40),
                    lastSuccess = if (run.errors.isEmpty()) System.currentTimeMillis() else state.lastSuccess, lastAttempt = now, available = true,
                    pendingLocal = null, guard = null, message = if (run.errors.isEmpty()) null else "Część plików nie została zsynchronizowana",
                )
                run
            } catch (e: CancellationException) {
                state = state.copy(lastAttempt = System.currentTimeMillis(), message = "Przerwano – zostanie dokończone przy następnej synchronizacji")
                throw e
            } catch (e: Exception) {
                val message = e.message ?: e.javaClass.simpleName
                state = state.copy(lastAttempt = System.currentTimeMillis(), message = message)
                SyncRun(System.currentTimeMillis(), 0, trigger, failure = message)
            } finally {
                store.saveState(pair.id, state)
                progress(pair.id, null)
                cancelled -= pair.id
                changed.value = System.currentTimeMillis()
            }
        }
    }
}

/** Stan synchronizacji dla interfejsu: pary, statusy, podgląd, konflikty, automatyczne uruchamianie. */
class SyncController(private val context: Context, private val scope: CoroutineScope, private val notice: (String) -> Unit, private val error: (String) -> Unit) {
    private val store = SyncStore(context)
    private val prefs = Preferences(context)
    var pairs by mutableStateOf(store.pairs()); private set
    var states by mutableStateOf<Map<String, SyncState>>(emptyMap()); private set
    var running by mutableStateOf<Map<String, SyncProgress>>(emptyMap()); private set
    var preview by mutableStateOf<Pair<SyncPair, SyncPreview>?>(null)
    var previewBusy by mutableStateOf<String?>(null); private set
    /** Podsumowanie synchronizacji od ostatniego otwarcia ekranu synchronizacji. */
    var unseen by mutableStateOf<List<Pair<SyncPair, SyncRun>>>(emptyList()); private set
    private var lastAuto = 0L

    init {
        reload()
        scope.launch { SyncRunner.running.collect { running = it } }
        scope.launch { SyncRunner.changed.collect { reload() } }
        // Powrót sieci (np. do domowego Wi-Fi z NAS) – od razu synchronizujemy pary automatyczne.
        runCatching {
            context.getSystemService(ConnectivityManager::class.java)?.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { scope.launch { delay(4000); syncAuto("po połączeniu z siecią", true) } }
            })
        }
        // Gdy aplikacja jest otwarta – co 15 minut.
        scope.launch { while (isActive) { delay(15 * 60_000L); syncAuto("automatycznie", true) } }
        SyncWorker.schedule(context, pairs.any { it.auto })
    }

    fun reload() {
        pairs = store.pairs()
        states = pairs.associate { it.id to store.state(it.id) }
        val seen = prefs.text("syncSeen", "0").toLongOrNull() ?: 0
        unseen = pairs.flatMap { p -> states[p.id]?.history.orEmpty().filter { it.time > seen && it.failure == null && (it.changes > 0 || it.conflicts > 0) }.map { p to it } }
    }
    fun markSeen() { prefs.setText("syncSeen", System.currentTimeMillis().toString()); unseen = emptyList() }
    val conflictCount get() = states.values.sumOf { it.conflicts.size }

    fun save(pair: SyncPair) {
        require(pair.name.isNotBlank()) { "Podaj nazwę" }
        require(!FileRules.isWithin(pair.local, pair.remote) && !FileRules.isWithin(pair.remote, pair.local)) { "Foldery nie mogą być jeden w drugim" }
        val existing = pairs.firstOrNull { it.id == pair.id }
        // Zmiana folderów unieważnia bazę – inaczej wszystko wyglądałoby na usunięte.
        if (existing != null && (existing.local != pair.local || existing.remote != pair.remote)) store.saveState(pair.id, SyncState())
        pairs = if (existing == null) pairs + pair else pairs.map { if (it.id == pair.id) pair else it }
        store.savePairs(pairs)
        SyncWorker.schedule(context, pairs.any { it.auto })
        reload()
    }
    fun remove(pair: SyncPair) {
        SyncRunner.cancel(pair.id)
        pairs = pairs - pair; store.savePairs(pairs); store.remove(pair.id)
        SyncWorker.schedule(context, pairs.any { it.auto })
        reload(); notice("Usunięto parę „${pair.name}” – pliki pozostały bez zmian")
    }

    fun sync(pair: SyncPair, resolutions: Map<String, Resolution> = emptyMap(), force: Boolean = false, trigger: String = "ręcznie") {
        if (pair.id in running) return
        preview = null
        scope.launch {
            TransferService.start(context, "Synchronizacja ${pair.name}")
            try {
                val run = SyncRunner.run(context, pair, trigger, resolutions, force)
                notice("${pair.name}: ${run.summary()}")
            } catch (e: CancellationException) { notice("Synchronizacja przerwana – dokończy się następnym razem") }
            finally { TransferService.stop(context) }
        }
    }
    fun cancel(pair: SyncPair) = SyncRunner.cancel(pair.id)
    fun syncAll() = pairs.forEach { sync(it) }
    /** Pary automatyczne; [throttle] – nie częściej niż co 2 minuty (wiele zdarzeń sieci naraz). */
    fun syncAuto(trigger: String, throttle: Boolean) {
        val now = System.currentTimeMillis()
        if (throttle && now - lastAuto < 120_000) return
        lastAuto = now
        scope.launch {
            for (pair in pairs.filter { it.auto && states[it.id]?.guard == null }) {
                runCatching { SyncRunner.run(context, pair, trigger) }.onSuccess { run ->
                    if (run.failure == null && (run.changes > 0 || run.conflicts > 0)) notice("${pair.name}: ${run.summary()}")
                }
            }
        }
    }
    fun showPreview(pair: SyncPair) {
        if (previewBusy != null) return
        previewBusy = pair.id
        scope.launch {
            try { preview = pair to SyncRunner.preview(context, pair) }
            catch (e: Exception) { error(e.message ?: "Nie można porównać folderów") }
            finally { previewBusy = null }
        }
    }
    /** Rozstrzyga konflikty pary i od razu synchronizuje. */
    fun resolve(pair: SyncPair, choices: Map<String, Resolution>) { if (choices.isNotEmpty()) sync(pair, choices, trigger = "rozstrzygnięcie konfliktów") }
    fun confirmGuard(pair: SyncPair) = sync(pair, force = true, trigger = "ręcznie, po potwierdzeniu")
    fun dismissGuard(pair: SyncPair) {
        store.saveState(pair.id, store.state(pair.id).copy(guard = null, message = "Wstrzymano przez użytkownika"))
        reload()
    }
}
