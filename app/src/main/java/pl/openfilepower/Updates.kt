package pl.openfilepower

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class Release(val version: String, val notes: String, val apk: String, val checksum: String)
class Updates(private val context: Context) {
    private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build()
    private fun request(url: String) = Request.Builder().url(url).header("User-Agent", "OpenFilePower/${BuildConfig.VERSION_NAME}").build()
    fun check(): Release? {
        client.newCall(request("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")).execute().use { response ->
            if (response.code == 404) return null
            check(response.isSuccessful) { "GitHub: HTTP ${response.code}" }
            val json = JSONObject(response.body!!.string())
            val version = json.getString("tag_name").removePrefix("v")
            if (!newer(version, BuildConfig.VERSION_NAME)) return null
            val assets = json.getJSONArray("assets")
            fun asset(name: String): String = (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.getString("name") == name }?.getString("browser_download_url") ?: error("Wydanie nie zawiera $name")
            return Release(version, json.optString("body"), asset("OpenFilePower.apk"), asset("OpenFilePower.apk.sha256"))
        }
    }
    fun download(release: Release): File {
        fun allowed(url: String) = url.startsWith("https://github.com/${BuildConfig.UPDATE_REPO}/releases/download/")
        require(allowed(release.apk) && allowed(release.checksum)) { "Nieprawidłowe źródło aktualizacji" }
        val expected = client.newCall(request(release.checksum)).execute().use { check(it.isSuccessful); it.body!!.string().trim().substringBefore(' ') }
        require(expected.matches(Regex("[a-fA-F0-9]{64}"))) { "Brak prawidłowej sumy kontrolnej" }
        val folder = File(context.cacheDir, "updates").apply { mkdirs() }
        val part = File(folder, "update.part")
        try {
            client.newCall(request(release.apk)).execute().use { response ->
                check(response.isSuccessful) { "Nie udało się pobrać APK" }
                response.body!!.byteStream().use { source -> part.outputStream().use { source.copyTo(it) } }
            }
            val digest = MessageDigest.getInstance("SHA-256")
            part.inputStream().use { stream -> val buffer = ByteArray(65536); while (true) { val n = stream.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) } }
            check(digest.digest().joinToString("") { "%02x".format(it) }.equals(expected, true)) { "Suma kontrolna APK nie zgadza się" }
            @Suppress("DEPRECATION")
            val archive = context.packageManager.getPackageArchiveInfo(part.path, PackageManager.GET_SIGNATURES) ?: error("Nieprawidłowy APK")
            @Suppress("DEPRECATION")
            val current = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNATURES)
            @Suppress("DEPRECATION")
            check(archive.packageName == context.packageName && !archive.signatures.isNullOrEmpty() && archive.signatures?.toSet() == current.signatures?.toSet() && PackageInfoCompat.getLongVersionCode(archive) > PackageInfoCompat.getLongVersionCode(current)) { "Niezgodny podpis, pakiet lub numer wersji" }
            val apk = File(folder, "OpenFilePower.apk")
            check(!apk.exists() || apk.delete())
            check(part.renameTo(apk))
            return apk
        } finally { part.delete() }
    }
    fun install(apk: File) {
        if (!context.packageManager.canRequestPackageInstalls()) {
            context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            error("Zezwól na instalacje z OpenFilePower, wróć i naciśnij Zainstaluj ponownie")
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    companion object {
        fun newer(candidate: String, current: String): Boolean {
            if (!candidate.matches(Regex("\\d+\\.\\d+\\.\\d+"))) return false
            val a = candidate.split('.').map { it.toLongOrNull() ?: return false }
            val b = current.split('.').map { it.toLongOrNull() ?: return false }
            return a.zip(b).firstOrNull { it.first != it.second }?.let { it.first > it.second } ?: false
        }
    }
}
