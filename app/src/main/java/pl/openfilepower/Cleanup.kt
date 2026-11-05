package pl.openfilepower

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CancellationException

/** Zainstalowana aplikacja. */
data class AppInfo(
    val packageName: String, val label: String, val version: String, val versionCode: Long,
    val apkSize: Long, val system: Boolean, val installed: Long, val updated: Long,
    val sourceDir: String, val splits: Int,
)

object InstalledApps {
    @Suppress("DEPRECATION")
    fun list(context: Context): List<AppInfo> {
        val pm = context.packageManager
        val packages = if (Build.VERSION.SDK_INT >= 33) pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(0)) else pm.getInstalledPackages(0)
        return packages.mapNotNull { p ->
            val ai = p.applicationInfo ?: return@mapNotNull null
            val splits = ai.splitSourceDirs?.size ?: 0
            val size = File(ai.sourceDir).length() + (ai.splitSourceDirs?.sumOf { File(it).length() } ?: 0L)
            AppInfo(
                p.packageName, pm.getApplicationLabel(ai).toString(), p.versionName ?: "", androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(p),
                size, ai.flags and ApplicationInfo.FLAG_SYSTEM != 0, p.firstInstallTime, p.lastUpdateTime, ai.sourceDir, splits,
            )
        }.sortedWith { a, b -> naturalCompare(a.label, b.label) }
    }
    /** Pliki APK aplikacji: podstawowy i (dla pakietów dzielonych) części. */
    fun apkFiles(context: Context, packageName: String): List<File> {
        val ai = context.packageManager.getApplicationInfo(packageName, 0)
        return listOf(File(ai.sourceDir)) + (ai.splitSourceDirs?.map(::File) ?: emptyList())
    }
}

/** Grupa identycznych plików (ta sama treść). */
data class DuplicateGroup(val size: Long, val hash: String, val files: List<File>) { val wasted get() = size * (files.size - 1) }

/** Kategoria rzeczy do posprzątania. */
data class CleanupGroup(val key: String, val title: String, val description: String, val files: List<File>) { val size get() = files.sumOf { if (it.isDirectory) 0L else it.length() } }

/** Wyszukiwanie duplikatów i zbędnych plików w pamięci wewnętrznej (z pełnym dostępem). */
class StorageCleaner(private val base: File) {
    /** Foldery pomijane: dane innych aplikacji (system i tak blokuje), kosz i ukryte. */
    private fun skip(f: File) = f.name.startsWith('.') || (f.parentFile == base && f.name == "Android")

    private fun walk(cancelled: () -> Boolean, visit: (File) -> Unit) {
        val queue = ArrayDeque(listOf(base))
        while (queue.isNotEmpty()) {
            if (cancelled()) throw CancellationException("Anulowano")
            val dir = queue.removeFirst()
            dir.listFiles()?.forEach { f -> if (!skip(f)) { if (f.isDirectory) queue.add(f) else visit(f) } }
        }
    }

    private fun digest(file: File, limit: Long, cancelled: () -> Boolean): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(256 * 1024); var read = 0L
            while (read < limit) {
                if (cancelled()) throw CancellationException("Anulowano")
                val n = input.read(buffer, 0, minOf(buffer.size.toLong(), limit - read).toInt()); if (n < 0) break
                md.update(buffer, 0, n); read += n
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Duplikaty: najpierw grupowanie po rozmiarze, potem skrót pierwszych 64 KB, na końcu pełny SHA-256 –
     * dzięki temu czytane są tylko pliki, które naprawdę mogą być kopiami.
     */
    fun duplicates(minSize: Long, cancelled: () -> Boolean, progress: (String) -> Unit): List<DuplicateGroup> {
        val bySize = HashMap<Long, MutableList<File>>()
        var count = 0
        walk(cancelled) { f -> val s = f.length(); if (s >= minSize) bySize.getOrPut(s) { mutableListOf() } += f; if (++count % 500 == 0) progress("Przejrzano $count plików") }
        val candidates = bySize.filterValues { it.size > 1 }
        val result = mutableListOf<DuplicateGroup>()
        var checked = 0
        candidates.forEach { (size, list) ->
            val byHead = list.groupBy { runCatching { digest(it, 64 * 1024, cancelled) }.getOrElse { e -> if (e is CancellationException) throw e; "" } }.filter { it.key.isNotEmpty() && it.value.size > 1 }
            byHead.values.forEach { same ->
                val byFull = if (size <= 64 * 1024) mapOf("h" + size to same) else same.groupBy { runCatching { digest(it, Long.MAX_VALUE, cancelled) }.getOrElse { e -> if (e is CancellationException) throw e; "" } }
                byFull.filter { it.key.isNotEmpty() && it.value.size > 1 }.forEach { (hash, files) -> result += DuplicateGroup(size, hash, files.sortedBy { it.path.length }) }
            }
            checked += list.size
            progress("Porównano $checked z ${candidates.values.sumOf { it.size }} podejrzanych plików")
        }
        return result.sortedByDescending { it.wasted }
    }

    /** Puste foldery, stare pliki APK, pliki tymczasowe, logi i miniatury podglądu. */
    fun junk(cancelled: () -> Boolean): List<CleanupGroup> {
        val apks = mutableListOf<File>(); val temp = mutableListOf<File>(); val logs = mutableListOf<File>()
        walk(cancelled) { f ->
            val n = f.name.lowercase()
            when {
                n.endsWith(".apk") || n.endsWith(".apks") || n.endsWith(".xapk") -> apks += f
                n.endsWith(".tmp") || n.endsWith(".temp") || n.endsWith(".part") || n.endsWith(".crdownload") || n.endsWith(".ofp-part") || n == "thumbs.db" || n == ".ds_store" -> temp += f
                n.endsWith(".log") -> logs += f
            }
        }
        // Miniatury generowane przez galerię – system odtworzy je w razie potrzeby.
        val thumbs = listOf(File(base, "DCIM/.thumbnails"), File(base, "Pictures/.thumbnails")).filter { it.isDirectory }.flatMap { d -> d.walkTopDown().filter { it.isFile }.toList() }
        val empty = mutableListOf<File>()
        fun emptyDirs(dir: File): Boolean {
            if (cancelled()) throw CancellationException("Anulowano")
            val children = dir.listFiles() ?: return false
            var allEmpty = true
            children.forEach { c -> if (c.isDirectory && !skip(c)) { if (!emptyDirs(c)) allEmpty = false } else allEmpty = false }
            if (allEmpty && dir != base) empty += dir
            return allEmpty
        }
        emptyDirs(base)
        // Zgłaszamy tylko najwyższy pusty folder z gałęzi – usunięcie go usuwa też puste podfoldery.
        val topEmpty = empty.filter { e -> empty.none { o -> o != e && e.path.startsWith(o.path + "/") } }
        return listOf(
            CleanupGroup("apk", "Pliki instalacyjne APK", "Zwykle niepotrzebne po zainstalowaniu aplikacji", apks),
            CleanupGroup("temp", "Pliki tymczasowe", "Niedokończone pobrania i pozostałości programów", temp),
            CleanupGroup("logs", "Logi", "Pliki .log zapisane przez aplikacje", logs),
            CleanupGroup("thumbs", "Miniatury galerii", "Podglądy w folderach .thumbnails – zostaną odtworzone", thumbs),
            CleanupGroup("empty", "Puste foldery", "Foldery bez żadnych plików", topEmpty),
        ).filter { it.files.isNotEmpty() }
    }
}
