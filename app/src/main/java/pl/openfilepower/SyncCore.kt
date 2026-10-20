package pl.openfilepower

import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.CancellationException
import kotlin.math.abs

/*
 * Synchronizacja trójstronna (jak Unison): stan telefonu i dysku porównywany jest ze stanem obu stron
 * zapamiętanym po ostatniej udanej synchronizacji. Dzięki temu wiadomo, która strona coś zmieniła,
 * usunięcie odróżnia się od „nowego pliku po drugiej stronie”, a zmiany po obu stronach to konflikt.
 * Ten plik nie zależy od Androida – planer i silnik są testowane jednostkowo.
 */

enum class SyncMode(val label: String, val hint: String) {
    TWO_WAY("Dwukierunkowa", "Zmiany z telefonu i z dysku przenoszone są na drugą stronę"),
    UPLOAD("Telefon → dysk", "Kopia zapasowa: zmiany z telefonu trafiają na dysk, dysk nie zmienia telefonu"),
    DOWNLOAD("Dysk → telefon", "Kopia lokalna: zmiany z dysku trafiają na telefon, telefon nie zmienia dysku");
    val up get() = this != DOWNLOAD
    val down get() = this != UPLOAD
}

enum class ConflictPolicy(val label: String, val hint: String) {
    ASK("Pytaj", "Konflikt czeka na decyzję, pozostałe pliki synchronizują się normalnie"),
    KEEP_BOTH("Zachowaj obie", "Starsza wersja zostaje obok jako „nazwa (konflikt – …)”"),
    NEWER("Nowsza wygrywa", "Wygrywa wersja z późniejszą datą modyfikacji"),
    LOCAL("Telefon wygrywa", "Wersja z telefonu zastępuje wersję z dysku"),
    REMOTE("Dysk wygrywa", "Wersja z dysku zastępuje wersję z telefonu"),
}

enum class SyncSideId(val label: String) { LOCAL("telefon"), REMOTE("dysk"); val other get() = if (this == LOCAL) REMOTE else LOCAL }
enum class ConflictKind(val label: String) {
    BOTH_MODIFIED("Zmieniony na telefonie i na dysku"),
    BOTH_CREATED("Dodany po obu stronach z różną treścią"),
    TYPE("Po jednej stronie plik, po drugiej folder"),
}
enum class Resolution(val label: String) { LOCAL("Zachowaj z telefonu"), REMOTE("Zachowaj z dysku"), BOTH("Zachowaj obie") }

/** Element drzewa: [path] względem folderu pary, z „/” jako separatorem. */
data class SyncEntry(val path: String, val directory: Boolean, val size: Long, val modified: Long) {
    val name get() = path.substringAfterLast('/')
    val state get() = SideState(directory, size, modified)
}
data class SideState(val directory: Boolean, val size: Long, val modified: Long)
/** Stan obu stron po ostatniej synchronizacji; null – element po tej stronie nie istniał (lub świadomie go pominięto). */
data class BaseEntry(val local: SideState?, val remote: SideState?)
data class SyncConflict(val path: String, val kind: ConflictKind, val local: SyncEntry?, val remote: SyncEntry?)

sealed interface SyncAction {
    val path: String
    /** Kopiuje plik ze strony [from] na drugą; [replacing] – oczekiwany stan celu (zabezpieczenie przed nadpisaniem świeżej zmiany). */
    data class Copy(override val path: String, val from: SyncSideId, val entry: SyncEntry, val replacing: SyncEntry?, val restore: Boolean = false) : SyncAction
    data class Mkdir(override val path: String, val on: SyncSideId) : SyncAction
    data class Delete(override val path: String, val on: SyncSideId, val entry: SyncEntry) : SyncAction
    /** Tylko zapis stanu bazowego (strony już są zgodne albo zmianę świadomie pominięto); null – zapomnij element. */
    data class Record(override val path: String, val base: BaseEntry?) : SyncAction
    data class Conflict(override val path: String, val conflict: SyncConflict) : SyncAction
    /** „Zachowaj obie”: wersja [loser] dostaje nową nazwę i obie wersje trafiają na obie strony. */
    data class KeepBoth(override val path: String, val loser: SyncSideId, val local: SyncEntry, val remote: SyncEntry) : SyncAction
}

/** Które pliki pomijać: ukryte (opcjonalnie), wzorce użytkownika i własne pliki robocze aplikacji. */
class SyncFilter(private val hidden: Boolean, patterns: String) {
    private val globs = patterns.split(',', ';', '\n').map { it.trim() }.filter { it.isNotEmpty() }.map { glob ->
        Regex(glob.split('*').joinToString(".*") { part -> part.split('?').joinToString(".") { Regex.escape(it) } }, RegexOption.IGNORE_CASE)
    }
    fun accept(name: String): Boolean {
        if (name.endsWith(".ofp-part") || name.endsWith(".ofp-tmp") || name == Trash.FOLDER) return false
        if (!hidden && name.startsWith('.')) return false
        return globs.none { it.matches(name) }
    }
    companion object { const val DEFAULT = "*.tmp, *.part, *.crdownload, Thumbs.db, desktop.ini, .DS_Store, ~\$*" }
}

object SyncPlanner {
    /** FAT, exFAT i część serwerów SMB zapisują czas z dokładnością do 2 s. */
    const val TOLERANCE = 2000L
    fun changed(now: SyncEntry?, before: SideState?): Boolean = when {
        now == null && before == null -> false
        now == null || before == null -> true
        now.directory != before.directory -> true
        now.directory -> false
        else -> now.size != before.size || abs(now.modified - before.modified) > TOLERANCE
    }
    fun sameStat(a: SyncEntry?, b: SyncEntry?): Boolean = when {
        a == null || b == null -> a == b
        a.directory || b.directory -> a.directory == b.directory
        else -> a.size == b.size && abs(a.modified - b.modified) <= TOLERANCE
    }

    /**
     * Plan dla wszystkich ścieżek z obu stron i bazy. [contentEqual] wywoływane jest tylko dla plików
     * tej samej wielkości z różną datą, zmienionych po obu stronach (np. pierwsza synchronizacja).
     */
    fun plan(local: Map<String, SyncEntry>, remote: Map<String, SyncEntry>, base: Map<String, BaseEntry>, mode: SyncMode, deletions: Boolean, contentEqual: (String) -> Boolean): List<SyncAction> {
        val actions = mutableListOf<SyncAction>()
        for (p in (local.keys + remote.keys + base.keys).toSortedSet()) {
            val l = local[p]; val r = remote[p]; val b = base[p]
            val lc = changed(l, b?.local); val rc = changed(r, b?.remote)
            when {
                !lc && !rc -> if (b != null && l == null && r == null) actions += SyncAction.Record(p, null)
                lc && !rc -> propagate(actions, p, l, r, SyncSideId.LOCAL, mode.up, deletions)
                !lc && rc -> propagate(actions, p, r, l, SyncSideId.REMOTE, mode.down, deletions)
                else -> both(actions, p, l, r, b, mode, contentEqual)
            }
        }
        return actions
    }
    private fun pair(side: SyncSideId, mine: SyncEntry?, theirs: SyncEntry?) =
        if (side == SyncSideId.LOCAL) BaseEntry(mine?.state, theirs?.state) else BaseEntry(theirs?.state, mine?.state)

    private fun propagate(actions: MutableList<SyncAction>, p: String, src: SyncEntry?, dst: SyncEntry?, from: SyncSideId, allowed: Boolean, deletions: Boolean) {
        // Kierunek wyłączony (tryb jednokierunkowy): zmiana zostaje niezapisana w bazie,
        // więc gdy później zmieni się też druga strona, wyjdzie to jako konflikt, a nie ciche nadpisanie.
        if (!allowed) return
        when {
            src == null && dst == null -> actions += SyncAction.Record(p, null)
            src == null -> actions += if (deletions) SyncAction.Delete(p, from.other, dst!!) else SyncAction.Record(p, pair(from, null, dst))
            src.directory -> actions += when {
                dst == null -> SyncAction.Mkdir(p, from.other)
                dst.directory -> SyncAction.Record(p, pair(from, src, dst))
                else -> SyncAction.Conflict(p, conflict(p, ConflictKind.TYPE, from, src, dst))
            }
            dst?.directory == true -> actions += SyncAction.Conflict(p, conflict(p, ConflictKind.TYPE, from, src, dst))
            else -> actions += SyncAction.Copy(p, from, src, dst)
        }
    }
    private fun conflict(p: String, kind: ConflictKind, side: SyncSideId, mine: SyncEntry?, theirs: SyncEntry?) =
        if (side == SyncSideId.LOCAL) SyncConflict(p, kind, mine, theirs) else SyncConflict(p, kind, theirs, mine)

    private fun both(actions: MutableList<SyncAction>, p: String, l: SyncEntry?, r: SyncEntry?, b: BaseEntry?, mode: SyncMode, contentEqual: (String) -> Boolean) {
        when {
            l == null && r == null -> actions += SyncAction.Record(p, null)
            // Usunięty po jednej stronie, zmieniony po drugiej: zmiana jest ważniejsza – przywracamy plik.
            l == null -> actions += if (!mode.down) SyncAction.Record(p, BaseEntry(null, r!!.state)) else if (r!!.directory) SyncAction.Mkdir(p, SyncSideId.LOCAL) else SyncAction.Copy(p, SyncSideId.REMOTE, r, null, restore = true)
            r == null -> actions += if (!mode.up) SyncAction.Record(p, BaseEntry(l.state, null)) else if (l.directory) SyncAction.Mkdir(p, SyncSideId.REMOTE) else SyncAction.Copy(p, SyncSideId.LOCAL, l, null, restore = true)
            l.directory && r.directory -> actions += SyncAction.Record(p, BaseEntry(l.state, r.state))
            l.directory != r.directory -> actions += SyncAction.Conflict(p, SyncConflict(p, ConflictKind.TYPE, l, r))
            sameStat(l, r) || (l.size == r.size && contentEqual(p)) -> actions += SyncAction.Record(p, BaseEntry(l.state, r.state))
            else -> actions += SyncAction.Conflict(p, SyncConflict(p, if (b?.local == null && b?.remote == null) ConflictKind.BOTH_CREATED else ConflictKind.BOTH_MODIFIED, l, r))
        }
    }

    /** Rozstrzygnięcie wg zasady pary; null – zostaje do decyzji użytkownika. */
    fun automatic(c: SyncConflict, policy: ConflictPolicy): Resolution? {
        val l = c.local; val r = c.remote
        if (c.kind == ConflictKind.TYPE) return if (policy == ConflictPolicy.ASK) null else Resolution.BOTH
        return when (policy) {
            ConflictPolicy.ASK -> null
            ConflictPolicy.KEEP_BOTH -> Resolution.BOTH
            ConflictPolicy.LOCAL -> Resolution.LOCAL
            ConflictPolicy.REMOTE -> Resolution.REMOTE
            ConflictPolicy.NEWER -> when {
                l == null || r == null -> Resolution.BOTH
                l.modified > r.modified + TOLERANCE -> Resolution.LOCAL
                r.modified > l.modified + TOLERANCE -> Resolution.REMOTE
                else -> Resolution.BOTH
            }
        }
    }
    fun expand(c: SyncConflict, resolution: Resolution): SyncAction {
        val l = c.local!!; val r = c.remote!!
        if (c.kind == ConflictKind.TYPE) return SyncAction.KeepBoth(c.path, if (l.directory) SyncSideId.REMOTE else SyncSideId.LOCAL, l, r)
        return when (resolution) {
            Resolution.LOCAL -> SyncAction.Copy(c.path, SyncSideId.LOCAL, l, r)
            Resolution.REMOTE -> SyncAction.Copy(c.path, SyncSideId.REMOTE, r, l)
            Resolution.BOTH -> SyncAction.KeepBoth(c.path, if (l.modified >= r.modified) SyncSideId.REMOTE else SyncSideId.LOCAL, l, r)
        }
    }
    /** „zdjęcie.jpg” → „zdjęcie (konflikt – dysk 2026-10-20 15.30).jpg”. */
    fun conflictName(name: String, side: SyncSideId, time: Long): String {
        val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH.mm", java.util.Locale.ROOT).format(java.util.Date(time))
        val dot = name.lastIndexOf('.')
        val tag = " (konflikt – ${side.label} $stamp)"
        return if (dot <= 0) name + tag else name.substring(0, dot) + tag + name.substring(dot)
    }
}

/** Jedna strona pary. Wszystkie metody blokują. */
interface SyncSide {
    fun scan(filter: SyncFilter, cancelled: () -> Boolean): Map<String, SyncEntry>
    fun stat(path: String): SyncEntry?
    fun open(path: String): InputStream
    /** Zapis atomowy (plik tymczasowy → podmiana), tworzy brakujące foldery nadrzędne; zwraca nowy stan. */
    fun write(path: String, source: InputStream, size: Long, modified: Long, cancelled: () -> Boolean, progress: (Long) -> Unit): SyncEntry
    fun mkdirs(path: String): SyncEntry
    /** Folder usuwany jest tylko, gdy jest pusty; zwraca false, gdy coś w nim zostało. */
    fun delete(path: String, directory: Boolean): Boolean
    fun rename(path: String, newName: String): SyncEntry
}

/** Zatrzymanie przed ryzykowną operacją – wymaga potwierdzenia użytkownika. */
class SyncGuardException(message: String) : Exception(message)

data class SyncRun(
    val time: Long, val duration: Long, val trigger: String,
    val uploaded: Int = 0, val downloaded: Int = 0, val deletedLocal: Int = 0, val deletedRemote: Int = 0,
    val folders: Int = 0, val conflicts: Int = 0, val resolved: Int = 0, val bytes: Long = 0,
    val errors: List<String> = emptyList(), val failure: String? = null,
) {
    val changes get() = uploaded + downloaded + deletedLocal + deletedRemote + folders + resolved
    fun summary(): String {
        failure?.let { return it }
        val parts = buildList {
            if (uploaded > 0) add("wysłano $uploaded")
            if (downloaded > 0) add("pobrano $downloaded")
            if (deletedRemote > 0) add("usunięto z dysku $deletedRemote")
            if (deletedLocal > 0) add("usunięto z telefonu $deletedLocal")
            if (folders > 0) add("foldery $folders")
            if (resolved > 0) add("rozstrzygnięto $resolved")
            if (conflicts > 0) add("konflikty $conflicts")
            if (errors.isNotEmpty()) add("błędy ${errors.size}")
        }
        return if (parts.isEmpty()) "Bez zmian – wszystko aktualne" else parts.joinToString(" · ").replaceFirstChar { it.uppercase() }
    }
}

/** Ile zmieniło się po jednej stronie od ostatniej synchronizacji. */
data class SideChanges(val added: Int, val modified: Int, val deleted: Int) {
    val total get() = added + modified + deleted
    fun describe() = buildList { if (added > 0) add("nowe $added"); if (modified > 0) add("zmienione $modified"); if (deleted > 0) add("usunięte $deleted") }.joinToString(", ").ifEmpty { "bez zmian" }
    companion object {
        fun of(now: Map<String, SyncEntry>, base: Map<String, BaseEntry>, side: SyncSideId): SideChanges {
            var a = 0; var m = 0; var d = 0
            fun before(b: BaseEntry?) = if (side == SyncSideId.LOCAL) b?.local else b?.remote
            now.values.filter { !it.directory }.forEach { e -> val b = before(base[e.path]); if (b == null) a++ else if (SyncPlanner.changed(e, b)) m++ }
            base.forEach { (p, b) -> val s = before(b); if (s != null && !s.directory && p !in now) d++ }
            return SideChanges(a, m, d)
        }
    }
}

data class SyncPreview(val actions: List<SyncAction>, val conflicts: List<SyncConflict>, val local: SideChanges, val remote: SideChanges)

data class SyncSettings(val mode: SyncMode, val policy: ConflictPolicy, val deletions: Boolean, val filter: SyncFilter)

/** Wynik przebiegu: nowa baza, nierozstrzygnięte konflikty i podsumowanie. */
data class SyncOutcome(val base: Map<String, BaseEntry>, val conflicts: List<SyncConflict>, val run: SyncRun)

class SyncEngine(private val local: SyncSide, private val remote: SyncSide, private val settings: SyncSettings, private val cancelled: () -> Boolean = { false }) {
    private fun side(id: SyncSideId) = if (id == SyncSideId.LOCAL) local else remote
    private fun check() { if (cancelled()) throw CancellationException("Anulowano synchronizację") }

    private class Scan(val local: Map<String, SyncEntry>, val remote: Map<String, SyncEntry>)
    private fun scan(): Scan = Scan(local.scan(settings.filter, cancelled), remote.scan(settings.filter, cancelled))

    private fun contentEqual(p: String): Boolean = runCatching {
        fun digest(s: SyncSide) = s.open(p).use { input ->
            val md = MessageDigest.getInstance("SHA-256"); val buf = ByteArray(256 * 1024)
            while (true) { check(); val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
            md.digest()
        }
        digest(local).contentEquals(digest(remote))
    }.getOrDefault(false)

    private fun plan(s: Scan, base: Map<String, BaseEntry>, resolutions: Map<String, Resolution>): Pair<List<SyncAction>, List<SyncConflict>> {
        val raw = SyncPlanner.plan(s.local, s.remote, base, settings.mode, settings.deletions, ::contentEqual)
        val pending = mutableListOf<SyncConflict>()
        val actions = raw.mapNotNull { a ->
            if (a !is SyncAction.Conflict) return@mapNotNull a
            val resolution = resolutions[a.path] ?: SyncPlanner.automatic(a.conflict, settings.policy)
            if (resolution == null) { pending += a.conflict; null } else SyncPlanner.expand(a.conflict, resolution)
        }
        return actions to pending
    }

    fun preview(base: Map<String, BaseEntry>): SyncPreview {
        val s = scan()
        val (actions, conflicts) = plan(s, base, emptyMap())
        return SyncPreview(actions.filter { it !is SyncAction.Record }, conflicts, SideChanges.of(s.local, base, SyncSideId.LOCAL), SideChanges.of(s.remote, base, SyncSideId.REMOTE))
    }

    /**
     * Wykonuje synchronizację. [checkpoint] dostaje bazę co kilkadziesiąt operacji – przerwany przebieg
     * (brak zasięgu, zamknięcie aplikacji) wznawia się od miejsca przerwania. [force] pomija zabezpieczenia.
     */
    fun run(base: Map<String, BaseEntry>, trigger: String, resolutions: Map<String, Resolution> = emptyMap(), force: Boolean = false, progress: (String, Float?) -> Unit = { _, _ -> }, checkpoint: (Map<String, BaseEntry>) -> Unit = {}): SyncOutcome {
        val started = System.currentTimeMillis()
        progress("Porównywanie folderów…", null)
        var s = scan()
        if (!force) guardEmpty(s, base)
        var (actions, pending) = plan(s, base, resolutions)
        if (!force) guardDeletes(actions, base)

        val state = base.toMutableMap()
        var run = SyncRun(started, 0, trigger)
        val errors = mutableListOf<String>()
        var renamed = false
        var done = 0; var lastSave = System.currentTimeMillis()
        fun tick() {
            done++
            if (done % 25 == 0 || System.currentTimeMillis() - lastSave > 20_000) { checkpoint(state.toMap()); lastSave = System.currentTimeMillis() }
        }
        fun record(p: String, l: SyncEntry?, r: SyncEntry?) { if (l == null && r == null) state.remove(p) else state[p] = BaseEntry(l?.state, r?.state) }

        for (pass in 0..1) {
            val work = actions.filter { it !is SyncAction.Record }
            val total = work.size.coerceAtLeast(1)
            fun step(i: Int, text: String) = progress(text, i.toFloat() / total)
            // Kolejność: foldery od najpłytszych, pliki, usuwanie plików, usuwanie folderów od najgłębszych.
            val ordered = work.filterIsInstance<SyncAction.Mkdir>().sortedBy { depth(it.path) } +
                work.filter { it is SyncAction.Copy || it is SyncAction.KeepBoth } +
                work.filterIsInstance<SyncAction.Delete>().filter { !it.entry.directory } +
                work.filterIsInstance<SyncAction.Delete>().filter { it.entry.directory }.sortedByDescending { depth(it.path) }
            ordered.forEachIndexed { i, a ->
                check()
                try {
                    when (a) {
                        is SyncAction.Mkdir -> {
                            step(i, "Folder ${a.path}")
                            val made = side(a.on).mkdirs(a.path)
                            val other = side(a.on.other).stat(a.path)
                            if (a.on == SyncSideId.LOCAL) record(a.path, made, other) else record(a.path, other, made)
                            run = run.copy(folders = run.folders + 1)
                        }
                        is SyncAction.Copy -> {
                            step(i, (if (a.from == SyncSideId.LOCAL) "↑ " else "↓ ") + a.path)
                            val written = copy(a.path, a.from, a.entry, a.replacing)
                            if (a.from == SyncSideId.LOCAL) record(a.path, a.entry, written) else record(a.path, written, a.entry)
                            run = if (a.from == SyncSideId.LOCAL) run.copy(uploaded = run.uploaded + 1) else run.copy(downloaded = run.downloaded + 1)
                            run = run.copy(bytes = run.bytes + a.entry.size)
                            if (a.path in resolutions) run = run.copy(resolved = run.resolved + 1)
                        }
                        is SyncAction.KeepBoth -> {
                            step(i, "Konflikt: ${a.path}")
                            keepBoth(a, ::record)
                            renamed = true
                            run = run.copy(resolved = run.resolved + 1)
                        }
                        is SyncAction.Delete -> {
                            step(i, "Usuwanie ${a.path}")
                            val target = side(a.on)
                            val now = target.stat(a.path)
                            check(SyncPlanner.sameStat(now, a.entry)) { "zmienił się w trakcie – sprawdzę przy następnej synchronizacji" }
                            val removed = now == null || target.delete(a.path, a.entry.directory)
                            if (removed) {
                                state.remove(a.path)
                                if (!a.entry.directory) run = if (a.on == SyncSideId.LOCAL) run.copy(deletedLocal = run.deletedLocal + 1) else run.copy(deletedRemote = run.deletedRemote + 1)
                            } else {
                                // W folderze są nowe pliki, których druga strona nie znała – folder zostaje.
                                if (a.on == SyncSideId.LOCAL) record(a.path, now, null) else record(a.path, null, now)
                            }
                        }
                        else -> {}
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { errors += "${a.path}: ${e.message ?: e.javaClass.simpleName}" }
                tick()
            }
            actions.filterIsInstance<SyncAction.Record>().forEach { if (it.base == null) state.remove(it.path) else state[it.path] = it.base }
            // Rozwiązanie konfliktu typu (plik ↔ folder) zmienia nazwy – drugi przebieg dokańcza kopiowanie.
            if (!renamed || pass == 1) break
            renamed = false
            s = scan()
            plan(s, state, emptyMap()).let { actions = it.first; pending = it.second }
        }
        val finished = run.copy(duration = System.currentTimeMillis() - started, conflicts = pending.size, errors = errors)
        return SyncOutcome(state.toMap(), pending, finished)
    }

    private fun depth(p: String) = p.count { it == '/' }

    private fun copy(p: String, from: SyncSideId, entry: SyncEntry, replacing: SyncEntry?): SyncEntry {
        val src = side(from); val dst = side(from.other)
        // Obie strony muszą wyglądać tak jak przy skanowaniu – inaczej ktoś właśnie coś zmienił.
        check(SyncPlanner.sameStat(src.stat(p), entry)) { "źródło zmieniło się w trakcie – sprawdzę przy następnej synchronizacji" }
        check(SyncPlanner.sameStat(dst.stat(p), replacing)) { "cel zmienił się w trakcie – sprawdzę przy następnej synchronizacji" }
        return src.open(p).use { input -> dst.write(p, input, entry.size, entry.modified, cancelled) {} }
    }

    private fun keepBoth(a: SyncAction.KeepBoth, record: (String, SyncEntry?, SyncEntry?) -> Unit) {
        val loserSide = side(a.loser)
        val loser = if (a.loser == SyncSideId.LOCAL) a.local else a.remote
        val winner = if (a.loser == SyncSideId.LOCAL) a.remote else a.local
        check(SyncPlanner.sameStat(loserSide.stat(a.path), loser)) { "zmienił się w trakcie – sprawdzę przy następnej synchronizacji" }
        val newName = SyncPlanner.conflictName(loser.name, a.loser, loser.modified.takeIf { it > 0 } ?: System.currentTimeMillis())
        val renamed = loserSide.rename(a.path, newName)
        if (winner.directory) return // konflikt typu: folder dokończy drugi przebieg
        val written = copy(a.path, a.loser.other, winner, null)
        if (a.loser == SyncSideId.LOCAL) record(a.path, written, winner) else record(a.path, winner, written)
        // Wersja przemianowana trafi na drugą stronę jako nowy plik w drugim przebiegu.
        record(renamed.path, null, null)
    }

    private fun guardEmpty(s: Scan, base: Map<String, BaseEntry>) {
        val knownLocal = base.values.count { it.local != null }; val knownRemote = base.values.count { it.remote != null }
        if (s.local.isEmpty() && knownLocal >= 5) throw SyncGuardException("Folder na telefonie jest pusty, a przy ostatniej synchronizacji miał $knownLocal elementów. Jeśli to nośnik wymienny, mógł zostać odłączony. Kontynuacja usunie te pliki z dysku.")
        if (s.remote.isEmpty() && knownRemote >= 5) throw SyncGuardException("Folder na dysku jest pusty, a przy ostatniej synchronizacji miał $knownRemote elementów. Jeśli dysk był wymieniony lub formatowany, kontynuacja usunie te pliki z telefonu.")
    }
    private fun guardDeletes(actions: List<SyncAction>, base: Map<String, BaseEntry>) {
        val deletes = actions.filterIsInstance<SyncAction.Delete>().filter { !it.entry.directory }
        for (side in SyncSideId.entries) {
            val n = deletes.count { it.on == side }
            val known = base.values.count { (if (side == SyncSideId.LOCAL) it.local else it.remote)?.directory == false }
            if (n >= 20 && n * 100 >= known * 30) throw SyncGuardException("Synchronizacja usunie $n z $known plików na stronie „${side.label}”. Sprawdź podgląd zmian i potwierdź, jeśli to zamierzone.")
        }
    }
}
