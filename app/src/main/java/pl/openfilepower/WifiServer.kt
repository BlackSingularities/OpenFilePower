package pl.openfilepower

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.concurrent.Executors

/**
 * Serwer WWW w sieci Wi-Fi: przeglądanie, pobieranie i wysyłanie plików pamięci wewnętrznej z przeglądarki komputera.
 * Dostęp tylko z kluczem losowanym przy każdym uruchomieniu (w adresie), działa do zatrzymania lub zamknięcia aplikacji.
 */
class WifiServer(private val files: Files, private val rootId: String, private val onChange: () -> Unit) {
    val token: String = SecureRandom().let { r -> (1..6).map { "abcdefghjkmnpqrstuvwxyz23456789"[r.nextInt(31)] }.joinToString("") }
    private var socket: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    val port get() = socket?.localPort ?: 0
    val running get() = socket?.isClosed == false

    fun start(port: Int = 8080) {
        val s = runCatching { ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(port)) } }.getOrElse { ServerSocket(0) }
        socket = s
        Thread({
            while (!s.isClosed) {
                val client = runCatching { s.accept() }.getOrNull() ?: break
                pool.execute { runCatching { client.use { handle(it) } } }
            }
        }, "wifi-server").apply { isDaemon = true }.start()
    }
    fun stop() { runCatching { socket?.close() }; socket = null; pool.shutdownNow() }

    private class Request(val method: String, val path: String, val query: Map<String, String>, val headers: Map<String, String>, val body: InputStream)

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            if (sb.length > 8192) error("Za długi nagłówek")
            sb.append(c.toChar())
        }
    }
    private fun decode(s: String) = URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")

    private fun handle(socket: Socket) {
        socket.soTimeout = 60_000
        val input = BufferedInputStream(socket.getInputStream())
        val out = socket.getOutputStream()
        val line = readLine(input) ?: return
        val (method, target) = line.split(' ').let { (it.getOrNull(0) ?: "") to (it.getOrNull(1) ?: "/") }
        val headers = mutableMapOf<String, String>()
        while (true) { val h = readLine(input) ?: break; if (h.isEmpty()) break; headers[h.substringBefore(':').trim().lowercase()] = h.substringAfter(':').trim() }
        val query = target.substringAfter('?', "").split('&').filter { it.contains('=') }.associate { decode(it.substringBefore('=')) to decode(it.substringAfter('=')) }
        val length = headers["content-length"]?.toLongOrNull() ?: 0L
        val body = LimitedInput(input, length)
        val request = Request(method, target.substringBefore('?'), query, headers, body)
        try {
            if (request.query["t"] != token) { respond(out, 403, "text/html; charset=utf-8", LOCKED.toByteArray()); return }
            route(request, out)
        } catch (e: Exception) {
            runCatching { respond(out, 500, "application/json", JSONObject().put("error", e.message ?: "Błąd").toString().toByteArray()) }
        }
    }

    private fun place(path: String?) = Place(rootId, (path ?: "").split('/').filter { it.isNotEmpty() }.onEach(FileRules::validateName))

    private fun route(r: Request, out: OutputStream) {
        when (r.path) {
            "/" -> respond(out, 200, "text/html; charset=utf-8", PAGE.replace("%TOKEN%", token).toByteArray())
            "/api/list" -> {
                val items = files.list(place(r.query["p"])).filterNot { it.hidden }.sortedWith(compareByDescending<Item> { it.directory }.thenComparator { a, b -> naturalCompare(a.name, b.name) })
                val json = JSONArray().also { a -> items.forEach { a.put(JSONObject().put("n", it.name).put("d", it.directory).put("s", it.size).put("m", it.modified)) } }
                respond(out, 200, "application/json; charset=utf-8", json.toString().toByteArray())
            }
            "/api/get" -> {
                val p = place(r.query["p"])
                val item = files.probe(p)?.takeIf { !it.directory } ?: error("Nie znaleziono pliku")
                val name = java.net.URLEncoder.encode(item.name, "UTF-8").replace("+", "%20")
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: ${item.mime}\r\nContent-Length: ${item.size}\r\nContent-Disposition: attachment; filename*=UTF-8''$name\r\nConnection: close\r\n\r\n").toByteArray())
                files.input(item).use { it.copyTo(out, 256 * 1024) }
                out.flush()
            }
            "/api/put" -> {
                require(r.method == "POST") { "Wymagane POST" }
                val folder = place(r.query["p"])
                val name = r.query["n"] ?: error("Brak nazwy")
                files.receive(r.body, folder, name, { false }) { _, _ -> }
                onChange()
                respond(out, 200, "application/json", "{}".toByteArray())
            }
            "/api/mkdir" -> {
                require(r.method == "POST") { "Wymagane POST" }
                files.mkdir(place(r.query["p"]), r.query["n"] ?: error("Brak nazwy"))
                onChange()
                respond(out, 200, "application/json", "{}".toByteArray())
            }
            else -> respond(out, 404, "text/plain", "404".toByteArray())
        }
    }

    private fun respond(out: OutputStream, code: Int, type: String, body: ByteArray) {
        val status = when (code) { 200 -> "OK"; 403 -> "Forbidden"; 404 -> "Not Found"; else -> "Error" }
        out.write("HTTP/1.1 $code $status\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n".toByteArray())
        out.write(body); out.flush()
    }

    /** Ciało zapytania ograniczone do Content-Length. */
    private class LimitedInput(private val source: InputStream, private var left: Long) : InputStream() {
        override fun read(): Int { if (left <= 0) return -1; val b = source.read(); if (b >= 0) left--; return b }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = source.read(b, off, minOf(len.toLong(), left).toInt())
            if (n > 0) left -= n
            return n
        }
    }

    companion object {
        /** Adres IPv4 telefonu w bieżącej sieci (Wi-Fi lub hotspot). */
        fun address(context: Context): String? {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            val props: LinkProperties? = cm?.getLinkProperties(cm.activeNetwork)
            props?.linkAddresses?.map { it.address }?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress?.let { return it }
            return runCatching {
                java.net.NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }
                    .flatMap { it.inetAddresses.toList() }.firstOrNull { it is Inet4Address && it.isSiteLocalAddress }?.hostAddress
            }.getOrNull()
        }

        private const val LOCKED = "<!doctype html><meta charset=utf-8><title>OpenFilePower</title><body style='font-family:sans-serif;background:#000;color:#fff;padding:40px'><h2>Brak dostępu</h2><p>Użyj pełnego adresu z kluczem, widocznego w aplikacji.</p>"

        private val PAGE = """<!doctype html><html lang="pl"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>OpenFilePower</title>
<style>
:root{--bg:#000;--card:#141414;--line:#2a2a2a;--text:#fff;--dim:#9a9a9a;--folder:#f5c242}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--text);font:15px system-ui,sans-serif}
header{position:sticky;top:0;background:var(--bg);padding:16px;display:flex;gap:10px;align-items:center;flex-wrap:wrap;border-bottom:1px solid var(--line)}
h1{font-size:18px;margin:0 12px 0 0}#crumbs{flex:1;min-width:200px;color:var(--dim)}#crumbs a{color:var(--text);text-decoration:none;cursor:pointer}
button,label.btn{background:#fff;color:#000;border:0;border-radius:999px;padding:9px 16px;font-weight:700;cursor:pointer;font-size:14px}
main{padding:12px 16px 80px}.row{display:flex;align-items:center;gap:12px;padding:10px 12px;border-radius:14px;cursor:pointer}.row:hover{background:var(--card)}
.ic{width:22px;text-align:center}.name{flex:1;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.meta{color:var(--dim);font-size:12px;white-space:nowrap}
#drop{position:fixed;inset:0;background:rgba(255,255,255,.12);outline:3px dashed #fff;outline-offset:-16px;display:none;align-items:center;justify-content:center;font-size:24px;font-weight:700}
#status{position:fixed;bottom:16px;left:50%;transform:translateX(-50%);background:#fff;color:#000;border-radius:16px;padding:10px 18px;display:none;font-weight:600}
</style></head><body>
<header><h1>OpenFilePower</h1><div id="crumbs"></div><button onclick="mk()">Nowy folder</button><label class="btn">Wyślij pliki<input type="file" multiple hidden onchange="up(this.files)"></label></header>
<main id="list"></main><div id="drop">Upuść pliki, aby wysłać</div><div id="status"></div>
<script>
const T="%TOKEN%";let path=[];
const q=(u,p)=>u+"?t="+T+"&p="+encodeURIComponent(p.join("/"));
const size=s=>s<1024?s+" B":s<1048576?(s/1024).toFixed(1)+" KB":s<1073741824?(s/1048576).toFixed(1)+" MB":(s/1073741824).toFixed(2)+" GB";
function status(t){const s=document.getElementById("status");s.textContent=t;s.style.display=t?"block":"none"}
async function load(){const r=await fetch(q("/api/list",path));const items=await r.json();if(!r.ok){status(items.error);return}
const c=document.getElementById("crumbs");c.innerHTML="";const parts=["Pamięć"].concat(path);parts.forEach((n,i)=>{const a=document.createElement("a");a.textContent=n;a.onclick=()=>{path=path.slice(0,i);load()};c.append(a);if(i<parts.length-1)c.append(" / ")});
const l=document.getElementById("list");l.innerHTML="";if(path.length){l.append(row("⬆","..","",()=>{path.pop();load()}))}
items.forEach(it=>l.append(row(it.d?"📁":"📄",it.n,it.d?"":size(it.s)+" · "+new Date(it.m).toLocaleString(),()=>{if(it.d){path.push(it.n);load()}else location.href=q("/api/get",path.concat(it.n))})))}
function row(i,n,m,f){const d=document.createElement("div");d.className="row";d.onclick=f;d.innerHTML="<span class=ic></span><span class=name></span><span class=meta></span>";d.children[0].textContent=i;d.children[1].textContent=n;d.children[2].textContent=m;return d}
async function up(fs){let i=0;for(const f of fs){i++;status("Wysyłanie "+i+"/"+fs.length+": "+f.name);const r=await fetch(q("/api/put",path)+"&n="+encodeURIComponent(f.name),{method:"POST",body:f});if(!r.ok){status("Błąd: "+f.name);return}}status("");load()}
async function mk(){const n=prompt("Nazwa folderu");if(!n)return;const r=await fetch(q("/api/mkdir",path)+"&n="+encodeURIComponent(n),{method:"POST"});if(!r.ok)alert((await r.json()).error);load()}
const drop=document.getElementById("drop");addEventListener("dragover",e=>{e.preventDefault();drop.style.display="flex"});
addEventListener("dragleave",e=>{if(e.target===drop)drop.style.display="none"});addEventListener("drop",e=>{e.preventDefault();drop.style.display="none";up(e.dataTransfer.files)});
load();
</script></body></html>"""
    }
}
