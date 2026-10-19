package pl.openfilepower

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import jcifs.CIFSContext
import jcifs.SmbConstants
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import java.io.InputStream
import java.io.OutputStream
import java.util.Properties

data class Place(val root: String, val parts: List<String> = emptyList()) {
    fun child(name: String) = copy(parts = parts + name)
    fun parent() = if (parts.isEmpty()) null else copy(parts = parts.dropLast(1))
}
data class Root(val id: String, val name: String, val address: String, val user: String = "", val domain: String = "", val password: String = "") {
    val network get() = address.startsWith("smb://")
}
data class Item(val place: Place, val name: String, val directory: Boolean, val size: Long, val modified: Long, val mime: String, val localUri: Uri? = null)
data class Clip(val items: List<Item>, val move: Boolean)

object FileRules {
    fun validateName(name: String) {
        require(name.isNotBlank() && name == name.trim() && name !in listOf(".", "..") && name.none { it in "/\\\u0000:*?\"<>|" }) { "Niedozwolona nazwa pliku" }
    }
    fun isWithin(parent: Place, candidate: Place) = parent.root == candidate.root && candidate.parts.take(parent.parts.size) == parent.parts
    fun mime(name: String): String = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
}

/** All methods are blocking: invoke from Dispatchers.IO. Credentials never enter SMB URLs. */
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
        // jCIFS consumes raw SMB path characters, not percent-encoded HTTP paths.
        val path = root.address.trimEnd('/') + "/" + place.parts.joinToString("/")
        return SmbFile(if (directory) path.trimEnd('/') + "/" else path, smbContext(root))
    }
    fun document(place: Place): DocumentFile {
        var doc = DocumentFile.fromTreeUri(context, Uri.parse(root(place).address)) ?: error("Nieprawidłowy dostęp do folderu")
        check(doc.exists()) { "Brak dostępu do źródła. Podłącz urządzenie lub wybierz folder ponownie." }
        place.parts.forEach { doc = doc.findFile(it) ?: error("Nie znaleziono: $it") }
        return doc
    }
    fun uri(item: Item): Uri? = if (root(item.place).network) null else item.localUri ?: document(item.place).uri
    fun list(place: Place): List<Item> = if (root(place).network) {
        smb(place, true).use { folder -> folder.listFiles().map { f -> f.use {
            val name = it.name.trimEnd('/')
            Item(place.child(name), name, it.isDirectory, if (it.isDirectory) 0 else it.length(), it.lastModified(), FileRules.mime(name))
        } } }
    } else document(place).listFiles().map { Item(place.child(it.name ?: "?"), it.name ?: "?", it.isDirectory, it.length(), it.lastModified(), it.type ?: FileRules.mime(it.name ?: ""), it.uri) }
    fun input(item: Item): InputStream = if (root(item.place).network) smb(item.place).inputStream else context.contentResolver.openInputStream(document(item.place).uri) ?: error("Nie można odczytać pliku")
    private fun exists(place: Place): Boolean = if (root(place).network) smb(place).use { it.exists() } else if (place.parts.isEmpty()) document(place).exists() else document(place.parent()!!).findFile(place.parts.last()) != null
    fun mkdir(parent: Place, name: String): Place {
        FileRules.validateName(name)
        val dest = parent.child(name)
        require(!exists(dest)) { "Nazwa już istnieje: $name" }
        if (root(parent).network) smb(dest, true).use { it.mkdir() } else check(document(parent).createDirectory(name) != null) { "Nie można utworzyć folderu" }
        return dest
    }
    fun rename(item: Item, name: String) {
        FileRules.validateName(name)
        require(item.place.parts.isNotEmpty()) { "Nie można zmienić nazwy źródła" }
        val dest = item.place.parent()!!.child(name)
        require(!exists(dest)) { "Nazwa już istnieje" }
        if (root(item.place).network) smb(item.place, item.directory).use { from -> smb(dest, item.directory).use { from.renameTo(it) } }
        else check(document(item.place).renameTo(name)) { "Zmiana nazwy nie powiodła się" }
    }
    fun delete(item: Item) {
        require(item.place.parts.isNotEmpty()) { "Nie można usunąć źródła" }
        if (root(item.place).network) smb(item.place, item.directory).use { it.delete() }
        else check(document(item.place).delete()) { "Nie udało się usunąć ${item.name}" }
    }
    private fun output(parent: Place, name: String, mime: String): OutputStream = if (root(parent).network) {
        smb(parent.child(name)).openOutputStream(false, SmbConstants.O_WRONLY or SmbConstants.O_CREAT or SmbConstants.O_EXCL, SmbConstants.FILE_WRITE_DATA, SmbConstants.FILE_NO_SHARE)
    } else {
        val created = document(parent).createFile(mime, name) ?: error("Nie można utworzyć $name")
        try {
            check(created.name == name) { "Dostawca plików zmienił nazwę docelową; operacja została przerwana" }
            context.contentResolver.openOutputStream(created.uri, "w") ?: error("Nie można zapisać $name")
        } catch (e: Exception) { runCatching { created.delete() }; throw e }
    }
    fun transfer(clip: Clip, destination: Place, cancelled: () -> Boolean, progress: (String, Long) -> Unit) {
        clip.items.forEach { item ->
            require(!sameOrDescendant(item, destination)) { "Nie można kopiować folderu do niego samego ani jego podfolderu" }
            require(!exists(destination.child(item.name))) { "W folderze docelowym istnieje już ${item.name}. Zmień nazwę lub wybierz inny folder." }
        }
        clip.items.forEach { item ->
            copy(item, destination, cancelled, progress)
            if (clip.move) delete(item)
        }
    }
    private fun sameOrDescendant(item: Item, destination: Place): Boolean {
        if (FileRules.isWithin(item.place, destination)) return true
        if (!item.directory) return false
        val fromRoot = root(item.place)
        val toRoot = root(destination)
        if (fromRoot.network != toRoot.network) return false
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
    private fun copy(item: Item, parent: Place, cancelled: () -> Boolean, progress: (String, Long) -> Unit) {
        if (cancelled()) throw java.util.concurrent.CancellationException("Anulowano transfer")
        val dest = parent.child(item.name)
        require(!exists(dest)) { "Plik już istnieje: ${item.name}" }
        var created = false
        try {
            if (item.directory) {
                mkdir(parent, item.name); created = true
                list(item.place).forEach { copy(it, dest, cancelled, progress) }
            } else {
                input(item).use { source ->
                    output(parent, item.name, item.mime).use { target ->
                        created = true
                        val buffer = ByteArray(128 * 1024)
                        var total = 0L
                        while (true) {
                            if (cancelled()) throw java.util.concurrent.CancellationException("Anulowano transfer")
                            val count = source.read(buffer)
                            if (count < 0) break
                            target.write(buffer, 0, count); total += count
                            progress(item.name, total)
                        }
                        check(item.size <= 0 || total == item.size) { "Rozmiar źródła zmienił się podczas kopiowania: ${item.name}" }
                    }
                }
            }
        } catch (e: Exception) {
            if (created) runCatching { delete(item.copy(place = dest)) }
            throw e
        }
    }
}
