package pl.openfilepower

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import okio.Path.Companion.toOkioPath
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.security.MessageDigest

/** Formaty RAW aparatów: Sony, Nikon, Canon, Fuji, Adobe DNG, Panasonic, Olympus, Pentax, Samsung, Leica, Hasselblad. */
object RawFormats {
    val extensions = setOf("arw", "srf", "sr2", "nef", "nrw", "cr2", "cr3", "crw", "raf", "dng", "rw2", "orf", "pef", "srw", "rwl", "3fr", "iiq", "x3f", "erf", "mrw", "kdc")
    fun isRaw(name: String) = name.substringAfterLast('.', "").lowercase() in extensions
}

/** Plik RAW do podglądu: Coil wczytuje go przez [RawFetcher]. */
data class RawImage(val uri: Uri, val modified: Long, val size: Long)

/**
 * Podgląd RAW bez dekodowania matrycy: aparaty zapisują w pliku pełnowymiarowy lub duży podgląd JPEG.
 * Wybieramy największy, obracamy według EXIF i buforujemy na dysku (cache aplikacji).
 */
object RawPreview {
    private const val MAX_SIDE = 2560

    fun cached(context: Context, raw: RawImage): File {
        val key = MessageDigest.getInstance("SHA-1").digest("${raw.uri}|${raw.modified}|${raw.size}".toByteArray()).joinToString("") { "%02x".format(it) }
        return File(File(context.cacheDir, "raw-previews").apply { mkdirs() }, "$key.jpg")
    }

    /** Zwraca plik JPEG z podglądem (tworzy go przy pierwszym użyciu). Blokujące. */
    fun preview(context: Context, raw: RawImage): File {
        val target = cached(context, raw)
        if (target.exists() && target.length() > 0) return target
        val buffer = map(context, raw.uri)
        val bitmap = decodeLargestJpeg(buffer) ?: error("Plik RAW nie zawiera osadzonego podglądu")
        val rotated = rotate(bitmap, orientation(context, raw.uri))
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.outputStream().use { rotated.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        if (rotated !== bitmap) rotated.recycle()
        bitmap.recycle()
        tmp.renameTo(target)
        trim(target.parentFile!!)
        return target
    }

    private fun map(context: Context, uri: Uri): ByteBuffer = if (uri.scheme == "file") {
        FileInputStream(File(uri.path!!)).use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.channel.size()) }
    } else {
        val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: error("Nie można otworzyć pliku")
        pfd.use { FileInputStream(it.fileDescriptor).use { s -> s.channel.map(FileChannel.MapMode.READ_ONLY, 0, s.channel.size()) } }
    }

    private fun orientation(context: Context, uri: Uri): Int = runCatching {
        (if (uri.scheme == "file") ExifInterface(uri.path!!) else context.contentResolver.openInputStream(uri)!!.use { ExifInterface(it) })
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

    private fun rotate(bitmap: Bitmap, orientation: Int): Bitmap {
        val degrees = when (orientation) { ExifInterface.ORIENTATION_ROTATE_90 -> 90f; ExifInterface.ORIENTATION_ROTATE_180 -> 180f; ExifInterface.ORIENTATION_ROTATE_270 -> 270f; else -> 0f }
        if (degrees == 0f) return bitmap
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees) }, true)
    }

    /** Szuka znaczników SOI (FFD8FF), sprawdza wymiary każdego kandydata i dekoduje największy. */
    fun decodeLargestJpeg(buffer: ByteBuffer): Bitmap? {
        val limit = buffer.limit()
        var best: Triple<Int, Int, Int>? = null // start, end, width
        var i = 0
        var candidates = 0
        while (i < limit - 3 && candidates < 40) {
            if (buffer.get(i) == 0xFF.toByte() && buffer.get(i + 1) == 0xD8.toByte() && buffer.get(i + 2) == 0xFF.toByte()) {
                val end = jpegEnd(buffer, i)
                if (end > i) {
                    candidates++
                    val bytes = ByteArray(minOf(end - i, 512 * 1024)).also { arr -> for (k in arr.indices) arr[k] = buffer.get(i + k) }
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    if (bounds.outWidth > 0 && (best == null || bounds.outWidth > best.third)) best = Triple(i, end, bounds.outWidth)
                    i = end
                    continue
                }
            }
            i++
        }
        val (start, end, width) = best ?: return null
        val data = ByteArray(end - start).also { arr -> buffer.position(start); buffer.get(arr); buffer.position(0) }
        var sample = 1
        while (width / (sample * 2) >= MAX_SIDE) sample *= 2
        return BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    /** Koniec strumienia JPEG: segmenty z długością do SOS, potem dane entropijne do FFD9. */
    internal fun jpegEnd(b: ByteBuffer, start: Int): Int {
        val limit = b.limit()
        var p = start + 2
        while (p + 4 <= limit) {
            if (b.get(p) != 0xFF.toByte()) return -1
            val marker = b.get(p + 1).toInt() and 0xFF
            if (marker == 0xFF) { p++; continue }
            if (marker == 0xD9) return p + 2
            if (marker in 0xD0..0xD7 || marker == 0x01) { p += 2; continue }
            val length = ((b.get(p + 2).toInt() and 0xFF) shl 8) or (b.get(p + 3).toInt() and 0xFF)
            if (length < 2) return -1
            p += 2 + length
            if (marker == 0xDA) {
                // Dane obrazu: FF00 to bajt FF, FFD0–FFD7 to znaczniki restartu.
                while (p + 1 < limit) {
                    if (b.get(p) == 0xFF.toByte()) {
                        val next = b.get(p + 1).toInt() and 0xFF
                        if (next == 0xD9) return p + 2
                        if (next != 0x00 && next !in 0xD0..0xD7 && next != 0xFF) break
                    }
                    p++
                }
            }
        }
        return -1
    }

    /** Bufor podglądów nie rośnie ponad ~400 MB. */
    private fun trim(dir: File) {
        val files = dir.listFiles()?.sortedBy { it.lastModified() } ?: return
        var total = files.sumOf { it.length() }
        for (f in files) { if (total < 400L * 1024 * 1024) break; total -= f.length(); f.delete() }
    }
}

class RawFetcher(private val data: RawImage, private val options: Options) : Fetcher {
    override suspend fun fetch(): FetchResult {
        val file = RawPreview.preview(options.context, data)
        return SourceResult(ImageSource(file.toOkioPath()), "image/jpeg", DataSource.DISK)
    }
    class Factory : Fetcher.Factory<RawImage> {
        override fun create(data: RawImage, options: Options, imageLoader: ImageLoader) = RawFetcher(data, options)
    }
}

/** Dane do wczytania miniatury/zdjęcia: RAW przez podgląd, reszta bezpośrednio. */
fun Item.imageModel(): Any? = localUri?.let { if (RawFormats.isRaw(name)) RawImage(it, modified, size) else it }
