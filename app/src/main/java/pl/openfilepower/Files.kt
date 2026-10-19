package pl.openfilepower

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import jcifs.CIFSContext
import jcifs.SmbConstants
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.Properties
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class Place(val root: String, val parts: List<String> = emptyList()) {
    fun child(name: String) = copy(parts = parts + name)
    fun parent() = if (parts.isEmpty()) null else copy(parts = parts.dropLast(1))
}

/** Źródło plików: folder SAF (content://), pamięć z pełnym dostępem (file://) albo udział SMB (smb://). */
data class Root(val id: String, val name: String, val address: String, val user: String = "", val domain: String = "", val password: String = "") {
    val network get() = address.startsWith("smb://")
    val direct get() = address.startsWith("file://")
    val path: String? get() = if (direct) Uri.parse(address).path else null
}

data class Item(val place: Place, val name: String, val directory: Boolean, val size: Long, val modified: Long, val mime: String, val localUri: Uri? = null, val children: Int = -1) {
    val hidden get() = name.startsWith('.')
    val extension get() = if (directory) "" else name.substringAfterLast('.', "").lowercase()
}
data class Clip(val items: List<Item>, val move: Boolean)

/** Co zrobić, gdy w celu istnieje element o tej samej nazwie. */
enum class Conflict(val label: String) { FAIL("Przerwij"), SKIP("Pomiń"), KEEP_BOTH("Zachowaj oba"), REPLACE("Zastąp") }
data class Measure(val files: Int, val folders: Int, val bytes: Long)

object FileRules {
    private const val FORBIDDEN = "/\\\u0000:*?\"<>|"
    fun validateName(name: String) {
        require(name.isNotBlank() && name == name.trim() && name !in listOf(".", "..") && name.none { it in FORBIDDEN }) { "Niedozwolona nazwa pliku" }
    }
    fun isWithin(parent: Place, candidate: Place) = parent.root == candidate.root && candidate.parts.take(parent.parts.size) == parent.parts
    fun mime(name: String): String = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
    /** Zamienia znaki niedozwolone w systemach plików FAT/SAF; używane przy rozpakowywaniu archiwów. */
    fun sanitize(name: String): String = name.map { if (it in FORBIDDEN || it.code < 32) '_' else it }.joinToString("").trim().ifBlank { "_" }.let { if (it == "." || it == "..") "_" else it }
    /** „zdjęcie.jpg” → „zdjęcie (2).jpg”; foldery bez rozszerzenia. */
    fun numbered(name: String, n: Int, directory: Boolean): String {
        val dot = name.lastIndexOf('.')
        return if (directory || dot <= 0) "$name ($n)" else "${name.substring(0, dot)} ($n)${name.substring(dot)}"
    }
    val textExtensions = setOf("txt", "md", "markdown", "json", "xml", "csv", "tsv", "log", "ini", "conf", "cfg", "yaml", "yml", "toml", "properties", "gradle", "kts", "kt", "java", "js", "ts", "py", "sh", "bat", "ps1", "c", "h", "cpp", "cs", "go", "rs", "html", "htm", "css", "sql", "srt", "nfo", "gpx", "kml")
    val archiveExtensions = setOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz")
    val documentExtensions = setOf("pdf", "doc", "docx", "odt", "rtf", "xls", "xlsx", "ods", "ppt", "pptx", "odp", "txt", "md", "csv", "epub")
    fun isText(item: Item) = !item.directory && (item.mime.startsWith("text/") || item.extension in textExtensions)
    fun isZip(item: Item) = !item.directory && item.extension == "zip"
    fun isRaw(item: Item) = !item.directory && RawFormats.isRaw(item.name)
    fun isImage(item: Item) = !item.directory && (item.mime.startsWith("image/") || RawFormats.isRaw(item.name))
    fun isMedia(item: Item) = isImage(item) || isVideo(item)
    fun isVideo(item: Item) = !item.directory && item.mime.startsWith("video/")
    fun isAudio(item: Item) = !item.directory && item.mime.startsWith("audio/")
    fun isApk(item: Item) = !item.directory && item.extension == "apk"
}

/** Wszystkie metody blokują: wywołuj z Dispatchers.IO. Dane logowania nigdy nie trafiają do adresów SMB. */
class Files(private val context: Context, private val roots: () -> List<Root>) {
    private val smbContexts = mutableMapOf<Root, CIFSContext>()
    fun root(place: Place) = roots().firstOrNull { it.id == place.root } ?: error("Folder został odłączony")

    @Synchronized private fun smbContext(root: Root): CIFSContext = smbContexts.getOrPut(root) {
        val config = Properties().apply {
            setProperty("jcifs.smb.client.minVersion", "SMB202")
            setProperty("jcifs.smb.client.maxVersion", "SMB311")
            setProperty("jcifs.smb.client.connTimeout", "10000")
            setProperty("jcifs.smb.client.responseTimeout", "15000")
        }
        BaseContext(PropertyConfiguration(config)).withCredentials(NtlmPasswordAuthenticator(root.domain, root.user, root.password))
    }
    private fun smb(place: Place, directory: Boolean = false): SmbFile {
        val root = root(place)
        place.parts.forEach(FileRules::validateName)
        // jCIFS przyjmuje surowe znaki ścieżki SMB, nie kodowanie procentowe jak w HTTP.
        val path = root.address.trimEnd('/') + "/" + place.parts.joinToString("/")
        return SmbFile(if (directory) path.trimEnd('/') + "/" else path, smbContext(root))
    }
    /** Plik w pamięci z pełnym dostępem. Nazwy są walidowane, więc ścieżka nie wyjdzie poza źródło. */
    fun file(place: Place): File {
        val root = root(place)
        place.parts.forEach(FileRules::validateName)
        return place.parts.fold(File(root.path ?: error("To nie jest folder lokalny"))) { f, part -> File(f, part) }
    }
    fun document(place: Place): DocumentFile {
        var doc = DocumentFile.fromTreeUri(context, Uri.parse(root(place).address)) ?: error("Nieprawidłowy dostęp do folderu")
        check(doc.exists()) { "Brak dostępu do źródła. Podłącz urządzenie lub wybierz folder ponownie." }
        place.parts.forEach { doc = doc.findFile(it) ?: error("Nie znaleziono: $it") }
        return doc
    }
    /** Adres content:// do otwarcia w innej aplikacji; dla SMB null (plik trzeba najpierw skopiować). */
    fun uri(item: Item): Uri? {
        val root = root(item.place)
        return when {
            root.network -> null
            root.direct -> FileProvider.getUriForFile(context, "${context.packageName}.files", file(item.place))
            else -> item.localUri ?: document(item.place).uri
        }
    }
    private fun item(place: Place, f: File) = Item(place, f.name, f.isDirectory, if (f.isDirectory) 0 else f.length(), f.lastModified(), if (f.isDirectory) "inode/directory" else FileRules.mime(f.name), Uri.fromFile(f), if (f.isDirectory) f.list()?.size ?: -1 else -1)

    fun list(place: Place): List<Item> {
        val root = root(place)
        return when {
            root.network -> smb(place, true).use { folder ->
                folder.listFiles().map { f -> f.use {
                    val name = it.name.trimEnd('/')
                    Item(place.child(name), name, it.isDirectory, if (it.isDirectory) 0 else it.length(), it.lastModified(), FileRules.mime(name))
                } }
            }
            root.direct -> {
                val folder = file(place)
                check(folder.isDirectory) { "Folder nie istnieje albo brak uprawnień: ${folder.path}" }
                val children = folder.listFiles() ?: if (place.parts.size >= 2 && place.parts[place.parts.size - 2] == "Android" && place.parts.last() in setOf("data", "obb"))
                    error("Od Androida 11 system blokuje wszystkim menedżerom plików (bez roota) bezpośredni dostęp do Android/${place.parts.last()}. Na Androidzie 11–12 użyj Ustawienia → „Dostęp do Android/data”.")
                else error("Brak uprawnień do odczytu: ${folder.path}")
                children.map { item(place.child(it.name), it) }
            }
            else -> document(place).listFiles().map { Item(place.child(it.name ?: "?"), it.name ?: "?", it.isDirectory, it.length(), it.lastModified(), it.type ?: FileRules.mime(it.name ?: ""), it.uri) }
        }
    }
    fun stat(place: Place): Item? {
        val parent = place.parent() ?: return null
        return runCatching { list(parent).firstOrNull { it.name == place.parts.last() } }.getOrNull()
    }
    fun input(item: Item): InputStream {
        val root = root(item.place)
        return when {
            root.network -> smb(item.place).inputStream
            root.direct -> file(item.place).inputStream()
            else -> context.contentResolver.openInputStream(item.localUri ?: document(item.place).uri) ?: error("Nie można odczytać pliku")
        }
    }
    /** EXIF z pliku: ścieżka lub deskryptor pozwalają czytać też formaty RAW wymagające swobodnego dostępu. */
    fun exif(item: Item): androidx.exifinterface.media.ExifInterface {
        val root = root(item.place)
        return when {
            root.direct -> androidx.exifinterface.media.ExifInterface(file(item.place).path)
            root.network -> input(item).use { androidx.exifinterface.media.ExifInterface(it) }
            else -> (context.contentResolver.openFileDescriptor(item.localUri ?: document(item.place).uri, "r") ?: error("Nie można odczytać pliku")).use { androidx.exifinterface.media.ExifInterface(it.fileDescriptor) }
        }
    }
    fun exists(place: Place): Boolean {
        val root = root(place)
        return when {
            root.network -> smb(place).use { it.exists() }
            root.direct -> file(place).exists()
            place.parts.isEmpty() -> document(place).exists()
            else -> document(place.parent()!!).findFile(place.parts.last()) != null
        }
    }
    fun mkdir(parent: Place, name: String): Place {
        FileRules.validateName(name)
        val dest = parent.child(name)
        require(!exists(dest)) { "Nazwa już istnieje: $name" }
        val root = root(parent)
        when {
            root.network -> smb(dest, true).use { it.mkdir() }
            root.direct -> check(file(dest).mkdir()) { "Nie można utworzyć folderu $name" }
            else -> check(document(parent).createDirectory(name)?.name == name) { "Nie można utworzyć folderu $name" }
        }
        return dest
    }
    fun createFile(parent: Place, name: String): Place {
        output(parent, name, FileRules.mime(name)).close()
        return parent.child(name)
    }
    fun rename(item: Item, name: String) {
        FileRules.validateName(name)
        require(item.place.parts.isNotEmpty()) { "Nie można zmienić nazwy źródła" }
        val dest = item.place.parent()!!.child(name)
        // Sama zmiana wielkości liter: na systemach bez rozróżniania exists() zwróciłby prawdę.
        require(name.equals(item.name, true) || !exists(dest)) { "Nazwa już istnieje" }
        val root = root(item.place)
        when {
            root.network -> smb(item.place, item.directory).use { from -> smb(dest, item.directory).use { from.renameTo(it) } }
            root.direct -> check(file(item.place).renameTo(file(dest))) { "Zmiana nazwy nie powiodła się" }
            else -> check(document(item.place).renameTo(name)) { "Zmiana nazwy nie powiodła się" }
        }
    }
    fun delete(item: Item) {
        require(item.place.parts.isNotEmpty()) { "Nie można usunąć źródła" }
        val root = root(item.place)
        when {
            root.network -> smb(item.place, item.directory).use { it.delete() }
            root.direct -> check(file(item.place).deleteRecursively()) { "Nie udało się usunąć ${item.name}" }
            else -> check(document(item.place).delete()) { "Nie udało się usunąć ${item.name}" }
        }
    }
    /** Otwiera nowy plik do zapisu; nigdy nie nadpisuje istniejącego. */
    private fun output(parent: Place, name: String, mime: String): OutputStream {
        FileRules.validateName(name)
        val root = root(parent)
        return when {
            root.network -> smb(parent.child(name)).openOutputStream(false, SmbConstants.O_WRONLY or SmbConstants.O_CREAT or SmbConstants.O_EXCL, SmbConstants.FILE_WRITE_DATA, SmbConstants.FILE_NO_SHARE)
            root.direct -> {
                val target = file(parent.child(name))
                check(target.createNewFile()) { "Plik już istnieje lub brak uprawnień: $name" }
                FileOutputStream(target)
            }
            else -> {
                val created = document(parent).createFile(mime, name) ?: error("Nie można utworzyć $name")
                try {
                    check(created.name == name) { "Dostawca plików zmienił nazwę docelową; operacja została przerwana" }
                    context.contentResolver.openOutputStream(created.uri, "w") ?: error("Nie można zapisać $name")
                } catch (e: Exception) { runCatching { created.delete() }; throw e }
            }
        }
    }
    /** Nadpisuje treść istniejącego pliku (edytor tekstu). */
    fun overwrite(item: Item, bytes: ByteArray) {
        val root = root(item.place)
        when {
            root.network -> smb(item.place).openOutputStream(false).use { it.write(bytes) }
            root.direct -> {
                // Zapis przez plik tymczasowy: awaria w trakcie nie zostawi połowy treści.
                val target = file(item.place)
                val temp = File(target.parentFile, ".${target.name}.ofp-tmp")
                temp.outputStream().use { it.write(bytes); it.fd.sync() }
                if (!temp.renameTo(target)) { temp.delete(); target.writeBytes(bytes) }
            }
            else -> (context.contentResolver.openOutputStream(item.localUri ?: document(item.place).uri, "wt") ?: error("Nie można zapisać pliku")).use { it.write(bytes) }
        }
    }
    fun readText(item: Item, limit: Int = 2 * 1024 * 1024): String {
        require(item.size <= limit) { "Plik jest za duży do edycji (limit ${formatSize(limit.toLong())})" }
        val bytes = input(item).use { it.readBytes() }
        require(bytes.size <= limit) { "Plik jest za duży do edycji" }
        require(bytes.take(8000).none { it == 0.toByte() }) { "To nie jest plik tekstowy" }
        return String(bytes, Charsets.UTF_8)
    }
    fun hash(item: Item, algorithm: String, cancelled: () -> Boolean = { false }): String {
        val digest = MessageDigest.getInstance(algorithm)
        input(item).use { stream ->
            val buffer = ByteArray(256 * 1024)
            while (true) {
                if (cancelled()) throw CancellationException("Anulowano")
                val n = stream.read(buffer); if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    fun measure(items: List<Item>, cancelled: () -> Boolean = { false }): Measure {
        var files = 0; var folders = 0; var bytes = 0L
        fun walk(item: Item) {
            if (cancelled()) throw CancellationException("Anulowano")
            if (item.directory) {
                folders++
                if (root(item.place).direct) {
                    file(item.place).walkTopDown().forEach { f -> if (f.isDirectory) folders++ else { files++; bytes += f.length() } }
                    folders--
                } else list(item.place).forEach(::walk)
            } else { files++; bytes += item.size.coerceAtLeast(0) }
        }
        items.forEach(::walk)
        return Measure(files, folders, bytes)
    }
    /** Rekurencyjne wyszukiwanie po nazwie (bez rozróżniania wielkości liter, * jako dowolny ciąg). */
    fun search(place: Place, query: String, cancelled: () -> Boolean, limit: Int = 1000, found: (Item) -> Unit) {
        val pattern = Regex(query.trim().split('*').joinToString(".*") { Regex.escape(it) }, RegexOption.IGNORE_CASE)
        var count = 0
        val queue = ArrayDeque(listOf(place))
        while (queue.isNotEmpty() && count < limit) {
            if (cancelled()) return
            val folder = queue.removeFirst()
            val children = runCatching { list(folder) }.getOrNull() ?: continue
            for (child in children) {
                if (pattern.containsMatchIn(child.name)) { found(child); if (++count >= limit) return }
                if (child.directory && !child.hidden) queue.add(child.place)
            }
        }
    }
    fun uniqueName(parent: Place, name: String, directory: Boolean): String {
        if (!exists(parent.child(name))) return name
        var n = 2
        while (exists(parent.child(FileRules.numbered(name, n, directory)))) n++
        return FileRules.numbered(name, n, directory)
    }
    fun conflicts(clip: Clip, destination: Place): List<Item> = clip.items.filter { it.place.parent() != destination && exists(destination.child(it.name)) }

    /**
     * Kopiuje lub przenosi [clip] do [destination]. [progress] dostaje nazwę bieżącego pliku
     * i łączną liczbę skopiowanych bajtów. Domyślnie konflikt nazw przerywa operację przed zapisem.
     */
    fun transfer(clip: Clip, destination: Place, cancelled: () -> Boolean, conflict: Conflict = Conflict.FAIL, progress: (String, Long) -> Unit) {
        clip.items.forEach { item ->
            require(!sameOrDescendant(item, destination)) { "Nie można kopiować folderu do niego samego ani jego podfolderu" }
            if (conflict == Conflict.FAIL) require(!exists(destination.child(item.name))) { "W folderze docelowym istnieje już ${item.name}. Zmień nazwę lub wybierz inny folder." }
        }
        var total = 0L
        val counted: (String, Long) -> Unit = { name, delta -> total += delta; progress(name, total) }
        clip.items.forEach { item ->
            if (cancelled()) throw CancellationException("Anulowano transfer")
            val sameFolder = item.place.parent() == destination
            if (sameFolder && clip.move) return@forEach
            var name = item.name
            val existing = exists(destination.child(name))
            if (existing) when {
                sameFolder -> name = uniqueName(destination, item.name, item.directory)
                conflict == Conflict.SKIP -> return@forEach
                conflict == Conflict.KEEP_BOTH -> name = uniqueName(destination, item.name, item.directory)
                conflict == Conflict.REPLACE -> {
                    val target = stat(destination.child(name))
                    if (target != null && target.directory && item.directory) {
                        merge(item, destination.child(name), cancelled, counted)
                        if (clip.move) delete(item)
                        return@forEach
                    }
                    if (target != null) delete(target)
                }
                else -> error("W folderze docelowym istnieje już ${item.name}")
            }
            if (clip.move && name == item.name && fastMove(item, destination)) { counted(item.name, item.size.coerceAtLeast(0)); return@forEach }
            copy(item, destination, name, cancelled, counted)
            if (clip.move) delete(item)
        }
    }
    private fun merge(item: Item, target: Place, cancelled: () -> Boolean, progress: (String, Long) -> Unit) {
        list(item.place).forEach { child ->
            val dest = target.child(child.name)
            val existing = if (exists(dest)) stat(dest) else null
            when {
                existing == null -> copy(child, target, child.name, cancelled, progress)
                existing.directory && child.directory -> merge(child, dest, cancelled, progress)
                else -> { delete(existing); copy(child, target, child.name, cancelled, progress) }
            }
        }
    }
    /** Przeniesienie bez kopiowania danych, gdy źródło i cel są na tym samym nośniku. */
    private fun fastMove(item: Item, destination: Place): Boolean = runCatching {
        val from = root(item.place); val to = root(destination)
        when {
            from.direct && to.direct -> file(item.place).renameTo(file(destination.child(item.name)))
            from.network && to.network && from.id == to.id -> {
                smb(item.place, item.directory).use { src -> smb(destination.child(item.name), item.directory).use { src.renameTo(it) } }; true
            }
            !from.network && !from.direct && !to.network && !to.direct -> {
                val src = document(item.place).uri
                val parent = document(item.place.parent()!!).uri
                val target = document(destination).uri
                src.authority == target.authority && DocumentsContract.moveDocument(context.contentResolver, src, parent, target) != null
            }
            else -> false
        }
    }.getOrDefault(false)

    private fun sameOrDescendant(item: Item, destination: Place): Boolean {
        if (FileRules.isWithin(item.place, destination)) return true
        if (!item.directory) return false
        val fromRoot = root(item.place)
        val toRoot = root(destination)
        if (fromRoot.direct && toRoot.direct) {
            val a = file(item.place).canonicalPath; val b = file(destination).canonicalPath
            return b == a || b.startsWith(a + File.separator)
        }
        if (fromRoot.network != toRoot.network || fromRoot.direct != toRoot.direct) return false
        if (!fromRoot.network) {
            val from = document(item.place).uri
            val to = document(destination).uri
            if (from.authority != to.authority) return false
            val fromId = DocumentsContract.getDocumentId(from)
            val toId = DocumentsContract.getDocumentId(to)
            if (fromId == toId) return true
            if (from.authority == "com.android.externalstorage.documents" && toId.startsWith(fromId.trimEnd('/') + "/")) return true
            return if (android.os.Build.VERSION.SDK_INT >= 29) runCatching { DocumentsContract.isChildDocument(context.contentResolver, from, to) }.getOrDefault(false) else false
        }
        val a = java.net.URL(null, fromRoot.address, jcifs.smb.Handler())
        val b = java.net.URL(null, toRoot.address, jcifs.smb.Handler())
        if (a.port != b.port) return false
        val sameHost = a.host.equals(b.host, true) || runCatching {
            java.net.InetAddress.getAllByName(a.host).toSet().intersect(java.net.InetAddress.getAllByName(b.host).toSet()).isNotEmpty()
        }.getOrDefault(false)
        if (!sameHost) return false
        val fromParts = a.path.trim('/').split('/') + item.place.parts
        val toParts = b.path.trim('/').split('/') + destination.parts
        return fromParts.size <= toParts.size && fromParts.zip(toParts).all { it.first.equals(it.second, true) }
    }
    private fun copy(item: Item, parent: Place, name: String, cancelled: () -> Boolean, progress: (String, Long) -> Unit) {
        if (cancelled()) throw CancellationException("Anulowano transfer")
        val dest = parent.child(name)
        require(!exists(dest)) { "Plik już istnieje: $name" }
        var created = false
        try {
            if (item.directory) {
                mkdir(parent, name); created = true
                list(item.place).forEach { copy(it, dest, it.name, cancelled, progress) }
            } else {
                input(item).use { source ->
                    output(parent, name, item.mime).use { target ->
                        created = true
                        pump(source, target, item.name, cancelled, progress).also { total ->
                            check(item.size <= 0 || total == item.size) { "Rozmiar źródła zmienił się podczas kopiowania: ${item.name}" }
                        }
                    }
                }
                if (root(parent).direct && item.modified > 0) file(dest).setLastModified(item.modified)
            }
        } catch (e: Exception) {
            if (created) runCatching { delete(item.copy(place = dest, name = name)) }
            throw e
        }
    }
    private fun pump(source: InputStream, target: OutputStream, name: String, cancelled: () -> Boolean, progress: (String, Long) -> Unit): Long {
        val buffer = ByteArray(256 * 1024)
        var total = 0L
        while (true) {
            if (cancelled()) throw CancellationException("Anulowano transfer")
            val count = source.read(buffer)
            if (count < 0) break
            target.write(buffer, 0, count); total += count
            progress(name, count.toLong())
        }
        return total
    }

    /** Pakuje elementy do nowego archiwum ZIP w [parent]. */
    fun compress(items: List<Item>, parent: Place, name: String, cancelled: () -> Boolean, progress: (String, Long) -> Unit): Place {
        val zipName = uniqueName(parent, if (name.endsWith(".zip", true)) name else "$name.zip", false)
        var ok = false
        try {
            ZipOutputStream(output(parent, zipName, "application/zip").buffered()).use { zip ->
                fun add(item: Item, prefix: String) {
                    if (cancelled()) throw CancellationException("Anulowano")
                    val path = prefix + item.name
                    if (item.directory) {
                        zip.putNextEntry(ZipEntry("$path/").apply { if (item.modified > 0) time = item.modified }); zip.closeEntry()
                        list(item.place).forEach { add(it, "$path/") }
                    } else {
                        zip.putNextEntry(ZipEntry(path).apply { if (item.modified > 0) time = item.modified })
                        input(item).use { pump(it, zip, item.name, cancelled, progress) }
                        zip.closeEntry()
                    }
                }
                items.forEach { add(it, "") }
            }
            ok = true
        } finally {
            if (!ok) runCatching { stat(parent.child(zipName))?.let(::delete) }
        }
        return parent.child(zipName)
    }
    fun zipEntries(item: Item, limit: Int = 500): List<Pair<String, Long>> = readZip(item) { zip ->
        val result = mutableListOf<Pair<String, Long>>()
        while (result.size < limit) { val e = zip.nextEntry ?: break; result += e.name to e.size }
        result
    }
    private fun <T> readZip(item: Item, block: (ZipInputStream) -> T): T = try {
        input(item).use { block(ZipInputStream(it.buffered(), Charsets.UTF_8)) }
    } catch (e: IllegalArgumentException) {
        // Archiwa z Windows często zapisują nazwy w CP437 zamiast UTF-8.
        input(item).use { block(ZipInputStream(it.buffered(), charset("IBM437"))) }
    }
    /** Rozpakowuje ZIP do nowego folderu obok archiwum. Chroni przed ścieżkami wychodzącymi poza cel. */
    fun extract(item: Item, parent: Place, cancelled: () -> Boolean, progress: (String, Long) -> Unit): Place {
        val folder = mkdir(parent, uniqueName(parent, item.name.substringBeforeLast('.').ifBlank { "Archiwum" }, true))
        try {
            readZip(item) { zip ->
                val made = mutableSetOf(folder)
                fun ensure(place: Place) {
                    if (place in made) return
                    place.parent()?.let(::ensure)
                    if (!exists(place)) mkdir(place.parent()!!, place.parts.last())
                    made += place
                }
                while (true) {
                    if (cancelled()) throw CancellationException("Anulowano")
                    val entry = zip.nextEntry ?: break
                    val segments = entry.name.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
                    require(segments.none { it == ".." }) { "Archiwum zawiera niebezpieczną ścieżkę: ${entry.name}" }
                    if (segments.isEmpty()) continue
                    val clean = segments.map(FileRules::sanitize)
                    val target = folder.copy(parts = folder.parts + clean)
                    if (entry.isDirectory) { ensure(target); continue }
                    target.parent()?.let(::ensure)
                    val name = if (exists(target)) uniqueName(target.parent()!!, clean.last(), false) else clean.last()
                    output(target.parent()!!, name, FileRules.mime(name)).use { pump(zip, it, name, cancelled, progress) }
                    if (root(folder).direct && entry.time > 0) file(target.parent()!!.child(name)).setLastModified(entry.time)
                }
            }
        } catch (e: Exception) {
            runCatching { stat(folder)?.let(::delete) }
            throw e
        }
        return folder
    }
}
