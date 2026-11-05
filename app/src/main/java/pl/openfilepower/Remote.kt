package pl.openfilepower

import android.content.Context
import android.util.Xml
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import org.xmlpull.v1.XmlPullParser
import java.io.Closeable
import java.io.FilterInputStream
import java.io.FilterOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/** Element na serwerze SFTP, FTP lub WebDAV. */
data class RemoteEntry(val name: String, val directory: Boolean, val size: Long, val modified: Long)

/** Wspólny interfejs protokołów sieciowych innych niż SMB. Ścieżki są względne wobec folderu bazowego źródła. */
interface RemoteFs : Closeable {
    fun list(path: List<String>): List<RemoteEntry>
    fun stat(path: List<String>): RemoteEntry?
    fun input(path: List<String>): InputStream
    /** Nowy plik do zapisu (nadpisuje, jeśli istnieje – sprawdzanie konfliktów robi wywołujący). */
    fun output(path: List<String>): OutputStream
    fun mkdir(path: List<String>)
    fun rename(from: List<String>, to: List<String>)
    fun delete(path: List<String>, directory: Boolean)
    fun setModified(path: List<String>, time: Long) {}
}

enum class RemoteProtocol(val scheme: String, val label: String, val port: Int) {
    SFTP("sftp", "SFTP", 22), FTP("ftp", "FTP", 21), FTPS("ftps", "FTPS (TLS)", 21), WEBDAV("webdav", "WebDAV (HTTP)", 80), WEBDAVS("webdavs", "WebDAV (HTTPS)", 443);
    companion object { fun of(address: String) = entries.firstOrNull { address.startsWith(it.scheme + "://") } }
}

/** Adres źródła: protokół://host:port/folder/bazowy. */
data class RemoteAddress(val protocol: RemoteProtocol, val host: String, val port: Int, val base: List<String>) {
    override fun toString() = "${protocol.scheme}://$host:$port/" + base.joinToString("/") + if (base.isEmpty()) "" else "/"
    companion object {
        fun parse(address: String): RemoteAddress {
            val protocol = RemoteProtocol.of(address) ?: error("Nieobsługiwany adres: $address")
            val rest = address.removePrefix(protocol.scheme + "://")
            val hostPort = rest.substringBefore('/')
            val path = rest.substringAfter('/', "").split('/').filter { it.isNotEmpty() }
            val host = if (hostPort.startsWith("[")) hostPort.substringBefore(']') + "]" else hostPort.substringBeforeLast(':', hostPort)
            val port = hostPort.removePrefix(host).removePrefix(":").toIntOrNull() ?: protocol.port
            return RemoteAddress(protocol, host, port, path)
        }
    }
}

object RemoteConnections {
    fun open(context: Context, root: Root): RemoteFs {
        val a = RemoteAddress.parse(root.address)
        return when (a.protocol) {
            RemoteProtocol.SFTP -> SftpFs(context, a, root.user, root.password)
            RemoteProtocol.FTP, RemoteProtocol.FTPS -> FtpFs(a, root.user, root.password)
            RemoteProtocol.WEBDAV, RemoteProtocol.WEBDAVS -> WebDavFs(a, root.user, root.password)
        }
    }
}

private fun join(base: List<String>, path: List<String>) = "/" + (base + path).joinToString("/")

// ——— SFTP (JSch) ———

/**
 * SFTP z zaufaniem przy pierwszym połączeniu: odcisk klucza serwera zapisywany jest przy pierwszym
 * połączeniu, a jego zmiana przerywa połączenie (ochrona przed podszyciem się pod serwer).
 */
class SftpFs(private val context: Context, private val a: RemoteAddress, private val user: String, private val password: String) : RemoteFs {
    private val jsch = com.jcraft.jsch.JSch()
    private var session: com.jcraft.jsch.Session? = null
    private var channel: com.jcraft.jsch.ChannelSftp? = null

    @Synchronized private fun session(): com.jcraft.jsch.Session {
        session?.takeIf { it.isConnected }?.let { return it }
        val s = jsch.getSession(user.ifBlank { "anonymous" }, a.host, a.port)
        s.setPassword(password)
        s.setConfig("StrictHostKeyChecking", "no")
        s.setConfig("PreferredAuthentications", "password,keyboard-interactive")
        s.timeout = 15000
        s.connect(15000)
        val prefs = context.getSharedPreferences("hostkeys", Context.MODE_PRIVATE)
        val key = "${a.host}:${a.port}"
        val fingerprint = s.hostKey.getFingerPrint(jsch)
        val known = prefs.getString(key, null)
        if (known != null && known != fingerprint) { s.disconnect(); error("Klucz serwera ${a.host} zmienił się ($fingerprint). Połączenie przerwane – usuń i dodaj serwer ponownie, jeśli zmiana jest zamierzona.") }
        if (known == null) prefs.edit().putString(key, fingerprint).apply()
        session = s
        return s
    }
    private fun newChannel() = (session().openChannel("sftp") as com.jcraft.jsch.ChannelSftp).apply { connect(15000) }
    @Synchronized private fun <T> sftp(block: (com.jcraft.jsch.ChannelSftp) -> T): T {
        val c = channel?.takeIf { it.isConnected } ?: newChannel().also { channel = it }
        return block(c)
    }
    private fun full(p: List<String>) = join(a.base, p)
    private fun missing(e: Exception) = e is com.jcraft.jsch.SftpException && e.id == com.jcraft.jsch.ChannelSftp.SSH_FX_NO_SUCH_FILE

    override fun list(path: List<String>): List<RemoteEntry> = sftp { c ->
        @Suppress("UNCHECKED_CAST")
        (c.ls(full(path)) as java.util.Vector<com.jcraft.jsch.ChannelSftp.LsEntry>)
            .filter { it.filename != "." && it.filename != ".." }
            .map { RemoteEntry(it.filename, it.attrs.isDir, it.attrs.size, it.attrs.mTime * 1000L) }
    }
    override fun stat(path: List<String>): RemoteEntry? = try {
        sftp { c -> c.stat(full(path)).let { RemoteEntry(path.lastOrNull() ?: "", it.isDir, it.size, it.mTime * 1000L) } }
    } catch (e: Exception) { if (missing(e)) null else throw e }
    // Strumienie dostają własny kanał – kopiowanie między dwoma plikiem na tym samym serwerze działa równolegle.
    override fun input(path: List<String>): InputStream {
        val c = newChannel()
        return object : FilterInputStream(c.get(full(path))) { override fun close() { try { super.close() } finally { c.disconnect() } } }
    }
    override fun output(path: List<String>): OutputStream {
        val c = newChannel()
        return object : FilterOutputStream(c.put(full(path), com.jcraft.jsch.ChannelSftp.OVERWRITE)) {
            override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len) }
            override fun close() { try { super.close() } finally { c.disconnect() } }
        }
    }
    override fun mkdir(path: List<String>) = sftp { it.mkdir(full(path)) }
    override fun rename(from: List<String>, to: List<String>) = sftp { it.rename(full(from), full(to)) }
    override fun delete(path: List<String>, directory: Boolean) {
        if (directory) {
            list(path).forEach { delete(path + it.name, it.directory) }
            sftp { it.rmdir(full(path)) }
        } else sftp { it.rm(full(path)) }
    }
    override fun setModified(path: List<String>, time: Long) { runCatching { sftp { it.setMtime(full(path), (time / 1000).toInt()) } } }
    @Synchronized override fun close() { runCatching { channel?.disconnect() }; runCatching { session?.disconnect() }; channel = null; session = null }
}

// ——— FTP / FTPS (Apache Commons Net) ———

class FtpFs(private val a: RemoteAddress, private val user: String, private val password: String) : RemoteFs {
    private var shared: org.apache.commons.net.ftp.FTPClient? = null
    private fun connect(): org.apache.commons.net.ftp.FTPClient {
        val client = if (a.protocol == RemoteProtocol.FTPS) org.apache.commons.net.ftp.FTPSClient(false) else org.apache.commons.net.ftp.FTPClient()
        client.controlEncoding = "UTF-8"
        client.connectTimeout = 15000
        client.setDataTimeout(java.time.Duration.ofSeconds(30))
        client.connect(a.host, a.port)
        if (!org.apache.commons.net.ftp.FTPReply.isPositiveCompletion(client.replyCode)) { client.disconnect(); error("Serwer FTP odrzucił połączenie: ${client.replyString}") }
        if (client is org.apache.commons.net.ftp.FTPSClient) { client.execPBSZ(0); client.execPROT("P") }
        if (!client.login(user.ifBlank { "anonymous" }, if (user.isBlank()) "anonymous@" else password)) { client.disconnect(); error("Nieprawidłowy login lub hasło FTP") }
        client.enterLocalPassiveMode()
        client.setFileType(org.apache.commons.net.ftp.FTP.BINARY_FILE_TYPE)
        return client
    }
    @Synchronized private fun <T> ftp(block: (org.apache.commons.net.ftp.FTPClient) -> T): T {
        val c = shared?.takeIf { it.isConnected && runCatching { it.sendNoOp() }.getOrDefault(false) } ?: connect().also { shared = it }
        return block(c)
    }
    private fun full(p: List<String>) = join(a.base, p)
    private fun check(ok: Boolean, c: org.apache.commons.net.ftp.FTPClient, what: String) { if (!ok) throw IOException("$what: ${c.replyString?.trim()}") }
    private fun entry(f: org.apache.commons.net.ftp.FTPFile) = RemoteEntry(f.name.substringAfterLast('/'), f.isDirectory, if (f.isDirectory) 0 else f.size, f.timestamp?.timeInMillis ?: 0)

    override fun list(path: List<String>): List<RemoteEntry> = ftp { c ->
        c.listFiles(full(path)).filter { it != null && it.name != "." && it.name != ".." }.map(::entry)
    }
    override fun stat(path: List<String>): RemoteEntry? {
        if (path.isEmpty()) return RemoteEntry("", true, 0, 0)
        return list(path.dropLast(1)).firstOrNull { it.name == path.last() }
    }
    // Transfer danych blokuje połączenie sterujące, więc każdy strumień ma własne połączenie.
    override fun input(path: List<String>): InputStream {
        val c = connect()
        val stream = c.retrieveFileStream(full(path)) ?: run { val m = c.replyString; c.disconnect(); throw IOException("Nie można pobrać: $m") }
        return object : FilterInputStream(stream) { override fun close() { try { super.close(); c.completePendingCommand() } finally { runCatching { c.logout() }; runCatching { c.disconnect() } } } }
    }
    override fun output(path: List<String>): OutputStream {
        val c = connect()
        val stream = c.storeFileStream(full(path)) ?: run { val m = c.replyString; c.disconnect(); throw IOException("Nie można zapisać: $m") }
        return object : FilterOutputStream(stream) {
            override fun write(b: ByteArray, off: Int, len: Int) { out.write(b, off, len) }
            override fun close() { try { super.close(); if (!c.completePendingCommand()) throw IOException("Zapis nie powiódł się: ${c.replyString}") } finally { runCatching { c.logout() }; runCatching { c.disconnect() } } }
        }
    }
    override fun mkdir(path: List<String>) = ftp { check(it.makeDirectory(full(path)), it, "Nie można utworzyć folderu") }
    override fun rename(from: List<String>, to: List<String>) = ftp { check(it.rename(full(from), full(to)), it, "Zmiana nazwy nie powiodła się") }
    override fun delete(path: List<String>, directory: Boolean) {
        if (directory) {
            list(path).forEach { delete(path + it.name, it.directory) }
            ftp { check(it.removeDirectory(full(path)), it, "Nie można usunąć folderu") }
        } else ftp { check(it.deleteFile(full(path)), it, "Nie można usunąć pliku") }
    }
    override fun setModified(path: List<String>, time: Long) {
        val stamp = SimpleDateFormat("yyyyMMddHHmmss", Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(java.util.Date(time))
        runCatching { ftp { it.setModificationTime(full(path), stamp) } }
    }
    @Synchronized override fun close() { shared?.let { runCatching { it.logout() }; runCatching { it.disconnect() } }; shared = null }
}

// ——— WebDAV (OkHttp) ———

/** WebDAV, np. Nextcloud (…/remote.php/dav/files/użytkownik), Synology, QNAP, Apache mod_dav. */
class WebDavFs(private val a: RemoteAddress, user: String, password: String) : RemoteFs {
    private val auth = if (user.isNotBlank()) Credentials.basic(user, password, Charsets.UTF_8) else null
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS).followRedirects(false).build()
    private val origin = (if (a.protocol == RemoteProtocol.WEBDAVS) "https" else "http") + "://" + a.host + if (a.port == a.protocol.port) "" else ":${a.port}"
    private fun encode(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")
    private fun url(p: List<String>, directory: Boolean = false) = origin + "/" + (a.base + p).joinToString("/") { encode(it) } + if (directory && (a.base + p).isNotEmpty()) "/" else ""
    private fun request(url: String) = Request.Builder().url(url).apply { auth?.let { header("Authorization", it) } }

    private fun execute(r: Request, allow404: Boolean = false): okhttp3.Response {
        val response = http.newCall(r).execute()
        if (response.isSuccessful || (allow404 && response.code == 404)) return response
        val code = response.code; response.close()
        throw IOException(when (code) { 401, 403 -> "Brak dostępu (HTTP $code) – sprawdź login i hasło"; 404 -> "Nie znaleziono (HTTP 404)"; 405 -> "Element już istnieje lub operacja niedozwolona (HTTP 405)"; else -> "Serwer WebDAV zwrócił błąd HTTP $code" })
    }
    private val propfind = """<?xml version="1.0" encoding="utf-8"?><d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/><d:getcontentlength/><d:getlastmodified/></d:prop></d:propfind>"""
    private fun propfind(p: List<String>, depth: String): List<Pair<String, RemoteEntry>>? {
        val body = propfind.toRequestBody("application/xml; charset=utf-8".toMediaType())
        execute(request(url(p, true)).method("PROPFIND", body).header("Depth", depth).build(), allow404 = true).use { response ->
            if (response.code == 404) return null
            return parse(response.body?.byteStream() ?: return emptyList())
        }
    }
    private fun parse(stream: InputStream): List<Pair<String, RemoteEntry>> {
        val parser = Xml.newPullParser().apply { setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true); setInput(stream, null) }
        val result = mutableListOf<Pair<String, RemoteEntry>>()
        var href = ""; var dir = false; var size = 0L; var modified = 0L; var tag = ""
        val dates = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    tag = parser.name
                    if (tag == "response") { href = ""; dir = false; size = 0; modified = 0 }
                    if (tag == "collection") dir = true
                }
                XmlPullParser.TEXT -> when (tag) {
                    "href" -> href += parser.text.trim()
                    "getcontentlength" -> size = parser.text.trim().toLongOrNull() ?: 0
                    "getlastmodified" -> modified = runCatching { dates.parse(parser.text.trim())?.time ?: 0L }.getOrDefault(0L)
                }
                XmlPullParser.END_TAG -> {
                    if (parser.name == "response") {
                        val raw = if (href.startsWith("http")) runCatching { java.net.URI(href).rawPath }.getOrNull() ?: "" else href
                        val path = runCatching { java.net.URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8") }.getOrDefault(raw)
                        val name = path.trimEnd('/').substringAfterLast('/')
                        result += path.trimEnd('/') to RemoteEntry(name, dir, if (dir) 0 else size, modified)
                    }
                    tag = ""
                }
            }
        }
        return result
    }
    override fun list(path: List<String>): List<RemoteEntry> {
        val all = propfind(path, "1") ?: throw IOException("Folder nie istnieje")
        val self = "/" + (a.base + path).joinToString("/")
        return all.filter { it.first.trimEnd('/') != self.trimEnd('/') }.map { it.second }
    }
    override fun stat(path: List<String>): RemoteEntry? = propfind(path, "0")?.firstOrNull()?.second?.copy(name = path.lastOrNull() ?: "")
    override fun input(path: List<String>): InputStream {
        val response = execute(request(url(path)).get().build())
        return object : FilterInputStream(response.body!!.byteStream()) { override fun close() { try { super.close() } finally { response.close() } } }
    }
    /** PUT strumieniowo: dane płyną przez potok do zapytania wysyłanego w osobnym wątku. */
    override fun output(path: List<String>): OutputStream {
        val source = PipedInputStream(256 * 1024)
        val sink = PipedOutputStream(source)
        var failure: Throwable? = null
        val body = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun writeTo(sink: BufferedSink) { source.use { input -> val buffer = ByteArray(64 * 1024); while (true) { val n = input.read(buffer); if (n < 0) break; sink.write(buffer, 0, n) } } }
        }
        val worker = Thread { try { execute(request(url(path)).put(body).build()).close() } catch (e: Throwable) { failure = e; runCatching { source.close() } } }.apply { start() }
        return object : FilterOutputStream(sink) {
            override fun write(b: ByteArray, off: Int, len: Int) { failure?.let { throw IOException(it.message, it) }; out.write(b, off, len) }
            override fun close() { runCatching { super.close() }; worker.join(); failure?.let { throw IOException(it.message, it) } }
        }
    }
    override fun mkdir(path: List<String>) { execute(request(url(path, true)).method("MKCOL", null).build()).close() }
    override fun rename(from: List<String>, to: List<String>) {
        val directory = stat(from)?.directory == true
        execute(request(url(from, directory)).method("MOVE", null).header("Destination", url(to, directory)).header("Overwrite", "F").build()).close()
    }
    override fun delete(path: List<String>, directory: Boolean) { execute(request(url(path, directory)).delete().build()).close() }
    override fun close() {}
}
