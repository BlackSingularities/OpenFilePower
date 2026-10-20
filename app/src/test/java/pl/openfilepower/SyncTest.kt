package pl.openfilepower

import org.junit.Assert.*
import org.junit.Test
import java.io.InputStream

/** Strona w pamięci: ścieżka → (treść, data); foldery jako zbiór ścieżek. */
private class MemorySide : SyncSide {
    val files = sortedMapOf<String, Pair<ByteArray, Long>>()
    val dirs = sortedSetOf<String>()
    var clock = 1_000_000L
    fun put(path: String, text: String, time: Long = clock.also { clock += 10_000 }) {
        path.split('/').dropLast(1).runningReduce { a, b -> "$a/$b" }.forEach { dirs += it }
        files[path] = text.toByteArray() to time
    }
    fun text(path: String) = files[path]?.first?.let { String(it) }
    override fun scan(filter: SyncFilter, cancelled: () -> Boolean): Map<String, SyncEntry> =
        (dirs.filter { d -> d.split('/').all(filter::accept) }.associateWith { SyncEntry(it, true, 0, 0) } +
            files.filter { (p, _) -> p.split('/').all(filter::accept) }.mapValues { (p, v) -> SyncEntry(p, false, v.first.size.toLong(), v.second) })
    override fun stat(path: String) = files[path]?.let { SyncEntry(path, false, it.first.size.toLong(), it.second) } ?: if (path in dirs) SyncEntry(path, true, 0, 0) else null
    override fun open(path: String): InputStream = files[path]!!.first.inputStream()
    override fun write(path: String, source: InputStream, size: Long, modified: Long, cancelled: () -> Boolean, progress: (Long) -> Unit): SyncEntry {
        // Celowo inna data niż źródła – jak na nośnikach, które nie pozwalają jej ustawić.
        put(path, String(source.readBytes()), clock.also { clock += 10_000 })
        return stat(path)!!
    }
    override fun mkdirs(path: String): SyncEntry { path.split('/').runningReduce { a, b -> "$a/$b" }.forEach { dirs += it }; return stat(path)!! }
    override fun delete(path: String, directory: Boolean): Boolean {
        if (directory) { if (files.keys.any { it.startsWith("$path/") } || dirs.any { it.startsWith("$path/") }) return false; dirs -= path } else files -= path
        return true
    }
    override fun rename(path: String, newName: String): SyncEntry {
        val target = (path.substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "$it/" }) + newName
        files[target] = files.remove(path)!!
        return stat(target)!!
    }
}

class SyncTest {
    private val phone = MemorySide()
    private val disk = MemorySide().apply { clock = 5_000_000L }
    private var base = emptyMap<String, BaseEntry>()
    private var conflicts = emptyList<SyncConflict>()

    private fun sync(mode: SyncMode = SyncMode.TWO_WAY, policy: ConflictPolicy = ConflictPolicy.ASK, deletions: Boolean = true, force: Boolean = false, resolutions: Map<String, Resolution> = emptyMap()): SyncRun {
        val out = SyncEngine(phone, disk, SyncSettings(mode, policy, deletions, SyncFilter(false, SyncFilter.DEFAULT))).run(base, "test", resolutions, force)
        base = out.base; conflicts = out.conflicts
        return out.run
    }

    @Test fun firstSyncCopiesBothWaysAndCreatesFolders() {
        phone.put("a/zdjęcie.jpg", "A"); disk.put("b/c/notatka.txt", "B")
        val run = sync()
        assertEquals(1, run.uploaded); assertEquals(1, run.downloaded)
        assertEquals("A", disk.text("a/zdjęcie.jpg")); assertEquals("B", phone.text("b/c/notatka.txt"))
        assertEquals(0, sync().changes) // drugi przebieg: bez zmian, mimo różnych dat po kopiowaniu
    }

    @Test fun identicalFilesOnFirstSyncAreNotConflicts() {
        phone.put("x.txt", "same"); disk.put("x.txt", "same")
        val run = sync()
        assertEquals(0, run.conflicts); assertEquals(0, run.changes)
    }

    @Test fun editsPropagateFromTheSideThatChanged() {
        phone.put("doc.txt", "v1"); sync()
        disk.put("doc.txt", "v2 z dysku")
        sync()
        assertEquals("v2 z dysku", phone.text("doc.txt"))
        phone.put("doc.txt", "v3 z telefonu")
        sync()
        assertEquals("v3 z telefonu", disk.text("doc.txt"))
    }

    @Test fun deletionsPropagateOrAreKeptWhenDisabled() {
        phone.put("a.txt", "1"); phone.put("b.txt", "2"); sync()
        phone.files -= "a.txt"
        assertEquals(1, sync().deletedRemote); assertNull(disk.text("a.txt"))
        disk.files -= "b.txt"
        sync(deletions = false)
        assertEquals("2", phone.text("b.txt"))
        assertEquals(0, sync(deletions = false).changes) // pominięte usunięcie nie wraca jako „nowy plik”
    }

    @Test fun bothModifiedIsAConflictThatWaitsAndOthersStillSync() {
        phone.put("c.txt", "base"); sync()
        phone.put("c.txt", "telefon"); disk.put("c.txt", "dysk!"); phone.put("inny.txt", "x")
        val run = sync()
        assertEquals(1, run.conflicts); assertEquals(1, run.uploaded)
        assertEquals("telefon", phone.text("c.txt")); assertEquals("dysk!", disk.text("c.txt"))
        assertEquals(ConflictKind.BOTH_MODIFIED, conflicts.single().kind)
        sync(resolutions = mapOf("c.txt" to Resolution.REMOTE))
        assertEquals("dysk!", phone.text("c.txt")); assertTrue(conflicts.isEmpty())
    }

    @Test fun keepBothSavesLoserUnderConflictNameOnBothSides() {
        phone.put("c.txt", "base"); sync()
        phone.put("c.txt", "telefon"); disk.put("c.txt", "dysk nowszy")
        sync(policy = ConflictPolicy.KEEP_BOTH)
        assertEquals("dysk nowszy", phone.text("c.txt")); assertEquals("dysk nowszy", disk.text("c.txt"))
        val copy = phone.files.keys.single { it.contains("konflikt") }
        assertEquals("telefon", phone.text(copy)); assertEquals("telefon", disk.text(copy))
    }

    @Test fun deletedOnOneSideButModifiedOnOtherIsRestored() {
        phone.put("r.txt", "v1"); sync()
        phone.files -= "r.txt"; disk.put("r.txt", "v2")
        sync()
        assertEquals("v2", phone.text("r.txt"))
    }

    @Test fun oneWayBackupDoesNotTouchPhone() {
        phone.put("p.jpg", "P"); disk.put("tylko-na-dysku.txt", "D")
        sync(mode = SyncMode.UPLOAD)
        assertEquals("P", disk.text("p.jpg")); assertNull(phone.text("tylko-na-dysku.txt"))
        disk.files -= "p.jpg"
        sync(mode = SyncMode.UPLOAD)
        assertEquals("P", phone.text("p.jpg"))
    }

    @Test fun emptySideAndMassDeletionRequireConfirmation() {
        repeat(30) { phone.put("f$it.txt", "$it") }
        sync()
        val saved = disk.files.toMap(); disk.files.clear(); disk.dirs.clear()
        assertThrows(SyncGuardException::class.java) { sync() }
        assertEquals(30, phone.files.size) // nic nie usunięto
        disk.files.putAll(saved)
        repeat(25) { phone.files -= "f$it.txt" }
        assertThrows(SyncGuardException::class.java) { sync() }
        assertEquals(25, sync(force = true).deletedRemote)
    }

    @Test fun fileVersusFolderKeepsBoth() {
        phone.put("x", "plik"); disk.put("x/w-srodku.txt", "folder")
        sync(policy = ConflictPolicy.KEEP_BOTH)
        assertEquals("folder", phone.text("x/w-srodku.txt"))
        assertTrue(disk.files.keys.any { it.startsWith("x (konflikt") })
    }

    @Test fun filterSkipsTemporaryAndHiddenFiles() {
        phone.put("Thumbs.db", "t"); phone.put(".ukryty", "h"); phone.put("a.tmp", "x"); phone.put(".plik.ofp-part", "p"); phone.put("ok.jpg", "j")
        sync()
        assertEquals(setOf("ok.jpg"), disk.files.keys)
    }

    @Test fun pendingChangesAreCountedForOfflineStatus() {
        phone.put("a.txt", "1"); phone.put("b.txt", "2"); sync()
        phone.put("a.txt", "zmiana"); phone.files -= "b.txt"; phone.put("c.txt", "nowy")
        val changes = SideChanges.of(phone.scan(SyncFilter(false, "")) { false }, base, SyncSideId.LOCAL)
        assertEquals(SideChanges(1, 1, 1), changes)
    }

    @Test fun conflictNameKeepsExtension() {
        val name = SyncPlanner.conflictName("zdjęcie.jpg", SyncSideId.REMOTE, 0)
        assertTrue(name, name.startsWith("zdjęcie (konflikt – dysk ") && name.endsWith(").jpg"))
    }
}
