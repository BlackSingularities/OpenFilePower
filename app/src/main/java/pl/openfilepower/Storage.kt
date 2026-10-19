package pl.openfilepower

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Pełny dostęp do pamięci współdzielonej: Android 11+ „Dostęp do wszystkich plików”, starsze – uprawnienia odczytu/zapisu. */
object StorageAccess {
    const val INTERNAL_ID = "internal"
    fun granted(context: Context): Boolean = if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
    else context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    data class Volume(val id: String, val name: String, val path: String, val removable: Boolean)
    /** Pamięć wewnętrzna i (od Androida 11, z pełnym dostępem) karty SD oraz pamięci USB. */
    fun volumes(context: Context): List<Volume> {
        val internal = Volume(INTERNAL_ID, "Pamięć wewnętrzna", Environment.getExternalStorageDirectory().path, false)
        if (Build.VERSION.SDK_INT < 30) return listOf(internal)
        val manager = context.getSystemService(StorageManager::class.java) ?: return listOf(internal)
        return listOf(internal) + manager.storageVolumes.filter { !it.isPrimary && it.state == Environment.MEDIA_MOUNTED }.mapNotNull { v ->
            val dir = v.directory ?: return@mapNotNull null
            Volume("volume:${v.uuid ?: dir.name}", v.getDescription(context) ?: "Karta SD", dir.path, v.isRemovable)
        }
    }
    data class Space(val total: Long, val free: Long) { val used get() = total - free; val fraction get() = if (total > 0) used.toFloat() / total else 0f }
    fun space(path: String): Space? = runCatching { StatFs(path).let { Space(it.totalBytes, it.availableBytes) } }.getOrNull()
}

enum class Category(val label: String) { IMAGES("Obrazy"), RAW("RAW"), VIDEO("Wideo"), AUDIO("Muzyka"), DOCUMENTS("Dokumenty"), ARCHIVES("Archiwa"), APK("Aplikacje"), DOWNLOADS("Pobrane"), LARGE("Duże pliki") }
data class CategoryStat(val category: Category, val count: Int, val bytes: Long)

/** Przegląd plików przez MediaStore (wymaga pełnego dostępu, by znać ścieżki). */
class MediaIndex(private val context: Context) {
    private val uri: Uri = MediaStore.Files.getContentUri("external")
    @Suppress("DEPRECATION") private val data = MediaStore.Files.FileColumns.DATA

    private fun selection(category: Category): Pair<String, Array<String>> {
        val mime = MediaStore.Files.FileColumns.MIME_TYPE
        fun ext(list: Collection<String>) = list.joinToString(" OR ") { "$data LIKE ?" } to list.map { "%.$it" }.toTypedArray()
        return when (category) {
            Category.IMAGES -> "$mime LIKE ?" to arrayOf("image/%")
            Category.RAW -> ext(RawFormats.extensions)
            Category.VIDEO -> "$mime LIKE ?" to arrayOf("video/%")
            Category.AUDIO -> "$mime LIKE ?" to arrayOf("audio/%")
            Category.DOCUMENTS -> ext(FileRules.documentExtensions)
            Category.ARCHIVES -> ext(FileRules.archiveExtensions)
            Category.APK -> ext(listOf("apk"))
            Category.DOWNLOADS -> "$data LIKE ?" to arrayOf(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).path + "/%")
            Category.LARGE -> "${MediaStore.Files.FileColumns.SIZE} >= ?" to arrayOf((100L * 1024 * 1024).toString())
        }
    }
    private fun query(selection: String, args: Array<String>, order: String, limit: Int, block: (path: String, size: Long, modified: Long) -> Unit) {
        val projection = arrayOf(data, MediaStore.Files.FileColumns.SIZE, MediaStore.Files.FileColumns.DATE_MODIFIED)
        val notDir = "(${MediaStore.Files.FileColumns.MIME_TYPE} IS NOT NULL OR $data LIKE '%.%') AND $data NOT LIKE '%/.%'"
        context.contentResolver.query(uri, projection, "($selection) AND $notDir", args, order)?.use { c ->
            var n = 0
            while (c.moveToNext() && n < limit) {
                val path = c.getString(0) ?: continue
                block(path, c.getLong(1), c.getLong(2) * 1000); n++
            }
        }
    }
    fun stats(): List<CategoryStat> = Category.entries.map { category ->
        var count = 0; var bytes = 0L
        runCatching { val (s, a) = selection(category); query(s, a, "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC", Int.MAX_VALUE) { _, size, _ -> count++; bytes += size } }
        CategoryStat(category, count, bytes)
    }
    fun files(category: Category, roots: List<Root>, limit: Int = 3000): List<Item> {
        val (s, a) = selection(category)
        val order = if (category == Category.LARGE) "${MediaStore.Files.FileColumns.SIZE} DESC" else "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
        return collect(s, a, order, limit, roots)
    }
    /** Wszystkie zdjęcia, RAW-y i filmy, od najnowszych – oś czasu galerii. */
    fun gallery(roots: List<Root>, limit: Int = 20000): List<Item> {
        val mime = MediaStore.Files.FileColumns.MIME_TYPE
        val raw = RawFormats.extensions.toList()
        val selection = "$mime LIKE ? OR $mime LIKE ? OR " + raw.joinToString(" OR ") { "$data LIKE ?" }
        val args = arrayOf("image/%", "video/%") + raw.map { "%.$it" }
        return collect(selection, args, "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC", limit, roots)
    }
    fun recent(roots: List<Root>, limit: Int = 40): List<Item> = collect("${MediaStore.Files.FileColumns.DATE_MODIFIED} > ?", arrayOf((System.currentTimeMillis() / 1000 - 60L * 86400).toString()), "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC", limit, roots)
    private fun collect(selection: String, args: Array<String>, order: String, limit: Int, roots: List<Root>): List<Item> {
        val result = mutableListOf<Item>()
        val seen = HashSet<String>()
        runCatching {
            query(selection, args, order, limit * 2) { path, _, _ ->
                // MediaStore potrafi zwrócić ten sam plik kilka razy – zduplikowany klucz wywraca listy Compose.
                if (result.size >= limit || !seen.add(path)) return@query
                val f = File(path)
                if (!f.isFile || path.contains("/${Trash.FOLDER}/")) return@query
                val place = placeFor(f, roots) ?: return@query
                result += Item(place, f.name, false, f.length(), f.lastModified(), FileRules.mime(f.name), Uri.fromFile(f))
            }
        }
        return result
    }
    companion object {
        fun placeFor(file: File, roots: List<Root>): Place? {
            val root = roots.filter { it.direct && it.path != null && (file.path == it.path || file.path.startsWith(it.path!!.trimEnd('/') + "/")) }.maxByOrNull { it.path!!.length } ?: return null
            val rel = file.path.removePrefix(root.path!!).trim('/')
            return Place(root.id, if (rel.isEmpty()) emptyList() else rel.split('/'))
        }
    }
}

data class TrashEntry(val id: String, val name: String, val original: String, val deleted: Long, val size: Long, val directory: Boolean)

/**
 * Kosz dla pamięci z pełnym dostępem: elementy przenoszone są (bez kopiowania) do ukrytego folderu
 * na tym samym nośniku i można je przywrócić. Dla SAF i SMB usuwanie jest trwałe.
 */
class Trash(private val base: File = File(Environment.getExternalStorageDirectory(), FOLDER)) {
    private val index get() = File(base, "index.json")
    fun supports(file: File) = file.path.startsWith(base.parentFile!!.path + "/") && !file.path.startsWith(base.path)
    @Synchronized fun entries(): List<TrashEntry> = runCatching {
        val a = JSONArray(index.readText())
        (0 until a.length()).map { a.getJSONObject(it).run { TrashEntry(getString("id"), getString("name"), getString("original"), getLong("deleted"), optLong("size"), optBoolean("directory")) } }
            .filter { File(base, it.id).exists() }
    }.getOrDefault(emptyList())
    @Synchronized private fun save(list: List<TrashEntry>) {
        base.mkdirs()
        File(base, ".nomedia").takeIf { !it.exists() }?.createNewFile()
        index.writeText(JSONArray().also { a -> list.forEach { e -> a.put(JSONObject().put("id", e.id).put("name", e.name).put("original", e.original).put("deleted", e.deleted).put("size", e.size).put("directory", e.directory)) } }.toString())
    }
    @Synchronized fun put(file: File) {
        require(supports(file)) { "Kosz działa tylko w pamięci wewnętrznej" }
        val id = UUID.randomUUID().toString()
        val holder = File(base, id).apply { mkdirs() }
        val size = if (file.isDirectory) file.walkTopDown().filter { it.isFile }.sumOf { it.length() } else file.length()
        val directory = file.isDirectory
        check(file.renameTo(File(holder, file.name))) { holder.delete(); "Nie udało się przenieść do kosza: ${file.name}" }
        save(entries() + TrashEntry(id, file.name, file.path, System.currentTimeMillis(), size, directory))
    }
    @Synchronized fun restore(entry: TrashEntry): File {
        val stored = File(File(base, entry.id), entry.name)
        var target = File(entry.original)
        target.parentFile?.mkdirs()
        var n = 2
        while (target.exists()) target = File(File(entry.original).parentFile, FileRules.numbered(entry.name, n++, entry.directory))
        check(stored.renameTo(target)) { "Nie udało się przywrócić ${entry.name}" }
        File(base, entry.id).deleteRecursively()
        save(entries().filterNot { it.id == entry.id })
        return target
    }
    @Synchronized fun remove(entry: TrashEntry) {
        File(base, entry.id).deleteRecursively()
        save(entries().filterNot { it.id == entry.id })
    }
    @Synchronized fun empty() { entries().forEach { File(base, it.id).deleteRecursively() }; save(emptyList()) }
    companion object { const val FOLDER = ".OpenFilePowerKosz" }
}
