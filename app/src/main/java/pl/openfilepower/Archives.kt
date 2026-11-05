package pl.openfilepower

import android.content.Context
import org.apache.commons.compress.archivers.sevenz.SevenZArchiveEntry
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import org.apache.commons.compress.archivers.sevenz.SevenZOutputFile
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.CancellationException

/** Archiwum wymaga hasła (albo podane hasło jest błędne). */
class PasswordNeeded(message: String = "Archiwum jest zabezpieczone hasłem") : Exception(message)

enum class ArchiveFormat(val label: String, val extension: String) { ZIP("ZIP", "zip"), SEVEN_Z("7z", "7z"), TAR_GZ("TAR.GZ", "tar.gz") }

/** Podgląd, rozpakowywanie i tworzenie archiwów: ZIP (także z hasłem AES), 7z, RAR (do wersji 4), TAR, GZ, BZ2, XZ. */
class Archives(private val context: Context, private val files: Files) {
    private enum class Kind { ZIP, SEVEN_Z, RAR, TAR, TAR_GZ, TAR_BZ2, TAR_XZ, GZ, BZ2, XZ }
    private fun kind(item: Item): Kind {
        val n = item.name.lowercase()
        return when {
            n.endsWith(".tar.gz") || n.endsWith(".tgz") -> Kind.TAR_GZ
            n.endsWith(".tar.bz2") || n.endsWith(".tbz2") || n.endsWith(".tbz") -> Kind.TAR_BZ2
            n.endsWith(".tar.xz") || n.endsWith(".txz") -> Kind.TAR_XZ
            n.endsWith(".tar") -> Kind.TAR
            n.endsWith(".zip") -> Kind.ZIP
            n.endsWith(".7z") -> Kind.SEVEN_Z
            n.endsWith(".rar") -> Kind.RAR
            n.endsWith(".gz") -> Kind.GZ
            n.endsWith(".bz2") -> Kind.BZ2
            n.endsWith(".xz") -> Kind.XZ
            else -> error("Nieobsługiwany format archiwum")
        }
    }
    /** Nazwa folderu (lub pliku dla .gz/.bz2/.xz) po rozpakowaniu. */
    private fun baseName(item: Item): String {
        val n = item.name
        listOf(".tar.gz", ".tar.bz2", ".tar.xz").firstOrNull { n.endsWith(it, true) }?.let { return n.dropLast(it.length).ifBlank { "Archiwum" } }
        return n.substringBeforeLast('.').ifBlank { "Archiwum" }
    }

    /** Plik lokalny archiwum: z pamięci bezpośrednio, z SAF/sieci – kopia tymczasowa (formaty wymagają swobodnego dostępu). */
    private fun <T> local(item: Item, cancelled: () -> Boolean, block: (File) -> T): T {
        if (files.root(item.place).direct) return block(files.file(item.place))
        val dir = File(context.cacheDir, "archives/${UUID.randomUUID()}").apply { mkdirs() }
        try {
            val temp = File(dir, FileRules.sanitize(item.name))
            files.download(item, temp, cancelled) { _, _ -> }
            return block(temp)
        } finally { dir.deleteRecursively() }
    }

    private fun tarStream(kind: Kind, input: InputStream): TarArchiveInputStream = TarArchiveInputStream(when (kind) {
        Kind.TAR_GZ -> GzipCompressorInputStream(input.buffered(), true)
        Kind.TAR_BZ2 -> BZip2CompressorInputStream(input.buffered(), true)
        Kind.TAR_XZ -> XZCompressorInputStream(input.buffered(), true)
        else -> input.buffered()
    }, "UTF-8")

    private fun zipEncrypted(file: File) = runCatching { net.lingala.zip4j.ZipFile(file).isEncrypted }.getOrDefault(false)

    private fun sevenZ(file: File, password: String?): SevenZFile = SevenZFile.builder().setFile(file).apply { if (!password.isNullOrEmpty()) setPassword(password.toCharArray()) }.get()

    private fun rar(file: File, password: String?): com.github.junrar.Archive = try {
        com.github.junrar.Archive(file, password)
    } catch (e: com.github.junrar.exception.UnsupportedRarV5Exception) { error("RAR 5 nie jest obsługiwany – rozpakuj go na komputerze lub zapisz jako RAR 4 / 7z") }

    private fun passwordError(e: Throwable): Boolean {
        var t: Throwable? = e
        while (t != null) {
            if (t is org.apache.commons.compress.PasswordRequiredException) return true
            if (t is com.github.junrar.exception.CrcErrorException) return true
            if (t is net.lingala.zip4j.exception.ZipException && t.type == net.lingala.zip4j.exception.ZipException.Type.WRONG_PASSWORD) return true
            if (t.message?.contains("password", true) == true) return true
            t = t.cause
        }
        return false
    }
    private fun <T> guarded(password: String?, block: () -> T): T = try { block() } catch (e: CancellationException) { throw e } catch (e: PasswordNeeded) { throw e } catch (e: Exception) {
        if (passwordError(e)) throw PasswordNeeded(if (password.isNullOrEmpty()) "Archiwum jest zabezpieczone hasłem" else "Nieprawidłowe hasło") else throw e
    }

    /** Lista wpisów (foldery kończą się „/”) i rozmiary po rozpakowaniu. */
    fun entries(item: Item, password: String?, limit: Int = 5000): List<Pair<String, Long>> = guarded(password) {
        when (val k = kind(item)) {
            Kind.ZIP -> try { files.zipEntries(item, limit) } catch (e: Exception) {
                local(item, { false }) { f ->
                    val zip = net.lingala.zip4j.ZipFile(f, password?.toCharArray())
                    if (zip.isEncrypted && password.isNullOrEmpty()) throw PasswordNeeded()
                    zip.fileHeaders.take(limit).map { (if (it.isDirectory && !it.fileName.endsWith("/")) it.fileName + "/" else it.fileName) to it.uncompressedSize }
                }
            }
            Kind.SEVEN_Z -> local(item, { false }) { f -> sevenZ(f, password).use { z -> z.entries.take(limit).map { (if (it.isDirectory) it.name + "/" else it.name) to if (it.isDirectory) 0L else it.size } } }
            Kind.RAR -> local(item, { false }) { f ->
                rar(f, password).use { r ->
                    if ((r.isPasswordProtected || r.isEncrypted) && password.isNullOrEmpty()) throw PasswordNeeded()
                    r.fileHeaders.take(limit).map { h -> h.fileName.replace('\\', '/').let { if (h.isDirectory) "$it/" else it } to h.fullUnpackSize }
                }
            }
            Kind.TAR, Kind.TAR_GZ, Kind.TAR_BZ2, Kind.TAR_XZ -> files.input(item).use { input ->
                val tar = tarStream(k, input)
                val result = mutableListOf<Pair<String, Long>>()
                while (result.size < limit) { val e: TarArchiveEntry = tar.nextEntry ?: break; result += (if (e.isDirectory && !e.name.endsWith("/")) e.name + "/" else e.name) to e.size }
                result
            }
            Kind.GZ, Kind.BZ2, Kind.XZ -> listOf(baseName(item) to -1L)
        }
    }

    /** Rozpakowuje do nowego folderu w [parent]. */
    fun extract(item: Item, parent: Place, password: String?, cancelled: () -> Boolean, progress: (String, Long) -> Unit): Place = guarded(password) {
        when (val k = kind(item)) {
            Kind.ZIP -> {
                // Zwykły ZIP rozpakowujemy strumieniowo; szyfrowanych wpisów java.util.zip nie czyta – wtedy zip4j.
                val plain = if (!password.isNullOrEmpty()) null else try { files.extract(item, parent, cancelled, progress) } catch (e: java.util.zip.ZipException) {
                    if (e.message?.contains("encrypt", true) == true) null else throw e
                }
                if (plain != null) plain
                else {
                    if (password.isNullOrEmpty()) throw PasswordNeeded()
                    local(item, cancelled) { f ->
                        val zip = net.lingala.zip4j.ZipFile(f, password.toCharArray())
                        files.unpack(parent, baseName(item), cancelled, progress) { sink ->
                            zip.fileHeaders.forEach { h ->
                                if (h.isDirectory) sink(h.fileName, true, 0, null)
                                else zip.getInputStream(h).use { sink(h.fileName, false, net.lingala.zip4j.util.Zip4jUtil.dosToExtendedEpochTme(h.lastModifiedTime), it) }
                            }
                        }
                    }
                }
            }
            Kind.SEVEN_Z -> local(item, cancelled) { f ->
                sevenZ(f, password).use { z ->
                    files.unpack(parent, baseName(item), cancelled, progress) { sink ->
                        while (true) {
                            val e: SevenZArchiveEntry = z.nextEntry ?: break
                            val stream = object : InputStream() {
                                override fun read() = z.read()
                                override fun read(b: ByteArray, off: Int, len: Int) = z.read(b, off, len)
                            }
                            sink(e.name, e.isDirectory, if (e.hasLastModifiedDate) e.lastModifiedDate.time else 0, if (e.isDirectory) null else stream)
                        }
                    }
                }
            }
            Kind.RAR -> local(item, cancelled) { f ->
                rar(f, password).use { r ->
                    if ((r.isPasswordProtected || r.isEncrypted) && password.isNullOrEmpty()) throw PasswordNeeded()
                    files.unpack(parent, baseName(item), cancelled, progress) { sink ->
                        r.fileHeaders.forEach { h ->
                            val name = h.fileName.replace('\\', '/')
                            if (h.isDirectory) sink(name, true, 0, null)
                            else r.getInputStream(h).use { sink(name, false, h.mTime?.time ?: 0, it) }
                        }
                    }
                }
            }
            Kind.TAR, Kind.TAR_GZ, Kind.TAR_BZ2, Kind.TAR_XZ -> files.input(item).use { input ->
                val tar = tarStream(k, input)
                files.unpack(parent, baseName(item), cancelled, progress) { sink ->
                    while (true) {
                        val e: TarArchiveEntry = tar.nextEntry ?: break
                        if (e.isSymbolicLink || e.isLink) continue
                        sink(e.name, e.isDirectory, e.modTime?.time ?: 0, if (e.isDirectory) null else tar)
                    }
                }
            }
            Kind.GZ, Kind.BZ2, Kind.XZ -> files.input(item).use { input ->
                val stream = when (k) { Kind.GZ -> GzipCompressorInputStream(input.buffered(), true); Kind.BZ2 -> BZip2CompressorInputStream(input.buffered(), true); else -> XZCompressorInputStream(input.buffered(), true) }
                val name = files.uniqueName(parent, FileRules.sanitize(baseName(item)), false)
                files.create(parent, name) { out -> files.copyStream(stream, out, name, cancelled, progress) }
            }
        }
    }

    /** Pakuje [items] do nowego archiwum w [parent]; ZIP z hasłem używa AES-256. */
    fun compress(items: List<Item>, parent: Place, name: String, format: ArchiveFormat, password: String?, cancelled: () -> Boolean, progress: (String, Long) -> Unit): Place {
        val fileName = if (name.endsWith("." + format.extension, true)) name else "$name.${format.extension}"
        // Pliki w kolejności przejścia drzewa: (ścieżka w archiwum, element).
        val all = mutableListOf<Pair<String, Item>>()
        fun walk(item: Item, prefix: String) {
            if (cancelled()) throw CancellationException("Anulowano")
            val path = prefix + item.name
            all += (if (item.directory) "$path/" else path) to item
            if (item.directory) files.list(item.place).forEach { walk(it, "$path/") }
        }
        items.forEach { walk(it, "") }
        return when (format) {
            ArchiveFormat.ZIP -> if (password.isNullOrEmpty()) files.compress(items, parent, fileName, cancelled, progress)
            else files.create(parent, fileName) { out ->
                net.lingala.zip4j.io.outputstream.ZipOutputStream(out, password.toCharArray()).use { zip ->
                    all.forEach { (path, item) ->
                        val params = net.lingala.zip4j.model.ZipParameters().apply {
                            fileNameInZip = path
                            if (!item.directory) { isEncryptFiles = true; encryptionMethod = net.lingala.zip4j.model.enums.EncryptionMethod.AES; aesKeyStrength = net.lingala.zip4j.model.enums.AesKeyStrength.KEY_STRENGTH_256 }
                            if (item.modified > 0) lastModifiedFileTime = item.modified
                        }
                        zip.putNextEntry(params)
                        if (!item.directory) files.input(item).use { files.copyStream(it, zip, item.name, cancelled, progress) }
                        zip.closeEntry()
                    }
                }
            }
            ArchiveFormat.TAR_GZ -> files.create(parent, fileName) { out ->
                TarArchiveOutputStream(GzipCompressorOutputStream(out.buffered()), "UTF-8").use { tar ->
                    tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                    tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
                    all.forEach { (path, item) ->
                        val entry = TarArchiveEntry(path).apply { if (!item.directory) size = item.size; if (item.modified > 0) setModTime(item.modified) }
                        tar.putArchiveEntry(entry)
                        if (!item.directory) files.input(item).use { files.copyStream(it, tar, item.name, cancelled, progress) }
                        tar.closeArchiveEntry()
                    }
                }
            }
            ArchiveFormat.SEVEN_Z -> {
                val dir = File(context.cacheDir, "archives/${UUID.randomUUID()}").apply { mkdirs() }
                try {
                    val temp = File(dir, "out.7z")
                    SevenZOutputFile(temp).use { z ->
                        all.forEach { (path, item) ->
                            val entry = SevenZArchiveEntry().apply { this.name = path.trimEnd('/'); isDirectory = item.directory; if (item.modified > 0) lastModifiedDate = java.util.Date(item.modified) }
                            z.putArchiveEntry(entry)
                            if (!item.directory) files.input(item).use { input ->
                                val buffer = ByteArray(256 * 1024)
                                while (true) { if (cancelled()) throw CancellationException("Anulowano"); val n = input.read(buffer); if (n < 0) break; z.write(buffer, 0, n); progress(item.name, n.toLong()) }
                            }
                            z.closeArchiveEntry()
                        }
                    }
                    files.create(parent, fileName) { out -> temp.inputStream().use { it.copyTo(out, 256 * 1024) } }
                } finally { dir.deleteRecursively() }
            }
        }
    }
}
