package pl.openfilepower

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Serwer wykryty w sieci lokalnej: [source] – „mDNS” (ogłasza się) lub „Skan” (otwarty port SMB 445). */
data class NasHost(val address: String, val name: String, val source: String)

/**
 * Wykrywanie NAS w sieci lokalnej: ogłoszenia mDNS/Bonjour (_smb._tcp) oraz szybki skan
 * podsieci /24 bieżącej sieci Wi-Fi/Ethernet w poszukiwaniu otwartego portu SMB 445.
 */
class NasDiscovery(private val context: Context) {
    /** Adres IPv4 i długość prefiksu aktywnej sieci lokalnej (Wi-Fi, Ethernet, VPN nie). */
    fun localNetwork(): Pair<Inet4Address, Int>? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val network = cm.activeNetwork ?: return null
        val caps = cm.getNetworkCapabilities(network)
        if (caps != null && !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) return null
        val link = cm.getLinkProperties(network) ?: return null
        return link.linkAddresses.firstNotNullOfOrNull { la -> (la.address as? Inet4Address)?.takeIf { !it.isLoopbackAddress }?.let { it to la.prefixLength } }
    }

    /** Blokujące: wywołuj z Dispatchers.IO. [progress] dostaje ułamek przeskanowanych adresów. */
    fun scan(cancelled: () -> Boolean, progress: (Float) -> Unit, found: (NasHost) -> Unit) {
        val seen = ConcurrentHashMap<String, Boolean>()
        val report: (NasHost) -> Unit = { h -> if (seen.putIfAbsent(h.address, true) == null) found(h) }
        val nsd = context.getSystemService(NsdManager::class.java)
        val resolveQueue = LinkedBlockingQueue<NsdServiceInfo>()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
            override fun onServiceFound(info: NsdServiceInfo) { resolveQueue.offer(info) }
            override fun onServiceLost(info: NsdServiceInfo) {}
        }
        val mdns = runCatching { nsd?.discoverServices("_smb._tcp.", NsdManager.PROTOCOL_DNS_SD, listener); nsd != null }.getOrDefault(false)
        // Rozwiązywanie usług mDNS po kolei (starsze Androidy nie pozwalają na kilka naraz).
        val resolver = Thread {
            while (!cancelled()) {
                val info = resolveQueue.poll(300, TimeUnit.MILLISECONDS) ?: continue
                val done = java.util.concurrent.CountDownLatch(1)
                @Suppress("DEPRECATION")
                runCatching {
                    nsd?.resolveService(info, object : NsdManager.ResolveListener {
                        override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) { done.countDown() }
                        override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                            @Suppress("DEPRECATION") val host = serviceInfo.host
                            (host as? Inet4Address)?.hostAddress?.let { report(NasHost(it, serviceInfo.serviceName, "mDNS")) }
                            done.countDown()
                        }
                    })
                }.onFailure { done.countDown() }
                done.await(3, TimeUnit.SECONDS)
            }
        }.apply { isDaemon = true; start() }

        try {
            val (ip, prefix) = localNetwork() ?: run { progress(1f); Thread.sleep(3000); return }
            val bytes = ip.address
            // Skanujemy najwyżej /24, nawet w większych sieciach – szybko i bez zalewania sieci.
            val base = if (prefix >= 24) bytes.copyOf() else bytes.copyOf().also { it[3] = 0 }
            val mask = if (prefix >= 24) prefix else 24
            val hostBits = 32 - mask
            val count = (1 shl hostBits) - 2
            val network = ((base[0].toInt() and 0xff) shl 24) or ((base[1].toInt() and 0xff) shl 16) or ((base[2].toInt() and 0xff) shl 8) or (base[3].toInt() and 0xff)
            val start = network and (-1 shl hostBits)
            val done = AtomicInteger()
            val pool = Executors.newFixedThreadPool(48)
            for (i in 1..count) pool.execute {
                if (cancelled()) return@execute
                val n = start + i
                val address = "${(n ushr 24) and 0xff}.${(n ushr 16) and 0xff}.${(n ushr 8) and 0xff}.${n and 0xff}"
                if (address != ip.hostAddress && open(address, 445, 350)) {
                    val name = runCatching { java.net.InetAddress.getByName(address).canonicalHostName }.getOrNull()?.takeIf { it != address } ?: address
                    report(NasHost(address, name.substringBefore('.'), "Skan"))
                }
                progress(done.incrementAndGet().toFloat() / count)
            }
            pool.shutdown()
            while (!pool.awaitTermination(200, TimeUnit.MILLISECONDS)) if (cancelled()) { pool.shutdownNow(); break }
            // Chwila na spóźnione odpowiedzi mDNS.
            if (mdns && !cancelled()) Thread.sleep(1500)
        } finally {
            runCatching { nsd?.stopServiceDiscovery(listener) }
            resolver.interrupt()
        }
    }
    private fun open(host: String, port: Int, timeout: Int) = runCatching { Socket().use { it.connect(InetSocketAddress(host, port), timeout); true } }.getOrDefault(false)

    /** Lista udziałów serwera (bez ukrytych administracyjnych, np. C$, IPC$). */
    fun shares(host: String, user: String, password: String, domain: String): List<String> {
        val config = Properties().apply {
            setProperty("jcifs.smb.client.minVersion", "SMB202"); setProperty("jcifs.smb.client.maxVersion", "SMB311")
            setProperty("jcifs.smb.client.connTimeout", "8000"); setProperty("jcifs.smb.client.responseTimeout", "10000")
        }
        val base = BaseContext(PropertyConfiguration(config))
        val ctx = if (user.isBlank()) base.withGuestCrendentials() else base.withCredentials(NtlmPasswordAuthenticator(domain, user, password))
        return SmbFile("smb://$host/", ctx).use { root -> root.listFiles().map { it.name.trimEnd('/') }.filterNot { it.endsWith("$") }.sortedWith { a, b -> naturalCompare(a, b) } }
    }
}
