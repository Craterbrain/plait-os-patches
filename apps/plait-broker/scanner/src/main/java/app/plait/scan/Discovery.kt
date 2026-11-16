package app.plait.scan

import android.content.Context
import android.net.wifi.WifiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

class Upnp(val friendlyName: String?, val manufacturer: String?, val modelName: String?, val modelNumber: String?, val deviceType: String?)

class Http(val port: Int, val server: String?, val title: String?, val realm: String?)

/** Everything a device said about itself, from whatever protocols it answers. */
class Facts {
    val services = linkedSetOf<String>()                  // "_airplay._tcp"
    val instances = linkedSetOf<Pair<String, String>>()   // (service type, instance name)
    val txt = linkedMapOf<String, Map<String, String>>()  // service type -> TXT records
    @Volatile var mdnsHost: String? = null
    @Volatile var ssdpServer: String? = null
    val ssdpTypes = linkedSetOf<String>()
    @Volatile var upnp: Upnp? = null
    @Volatile var http: Http? = null
    fun txt(key: String): String? = txt.values.firstNotNullOfOrNull { it[key]?.takeIf { v -> v.isNotBlank() } }
}

/**
 * Finds out what each device is by listening to what it says, all at once: multicast DNS (what Apple, Google, printers
 * and most smart-home gear announce), SSDP/UPnP (TVs, routers, Sonos), and the web page a device shows on port 80.
 */
object Discovery {
    private val SERVICES = listOf(
        "_airplay._tcp", "_raop._tcp", "_googlecast._tcp", "_hap._tcp", "_homekit._tcp", "_companion-link._tcp",
        "_device-info._tcp", "_ipp._tcp", "_ipps._tcp", "_printer._tcp", "_pdl-datastream._tcp", "_spotify-connect._tcp",
        "_smb._tcp", "_afpovertcp._tcp", "_ssh._tcp", "_http._tcp", "_workstation._tcp", "_androidtvremote2._tcp",
        "_amzn-wplay._tcp", "_sonos._tcp", "_matter._tcp", "_meshcop._udp", "_nvstream._tcp", "_hue._tcp",
        "_esphomelib._tcp", "_mediaremotetv._tcp", "_apple-mobdev2._tcp", "_adb-tls-connect._tcp", "_sleep-proxy._udp",
        "_rfb._tcp", "_daap._tcp", "_eppc._tcp", "_dns-sd._udp", "_nfs._tcp", "_webdav._tcp", "_ftp._tcp",
    )
    private const val ENUM = "_services._dns-sd._udp.local"

    /** Runs all the listeners together; they share one multicast lock and each stops on its own timer. */
    suspend fun listen(ctx: Context, lan: Lan): Map<String, Facts> = coroutineScope {
        val facts = ConcurrentHashMap<String, Facts>()
        fun of(ip: String) = facts.getOrPut(ip) { Facts() }
        val lock = ctx.applicationContext.getSystemService(WifiManager::class.java).createMulticastLock("plait-scan").apply { setReferenceCounted(false); acquire() }
        try {
            listOf(async { mdns(lan, ::of) }, async { ssdp(::of) }).awaitAll()
        } finally {
            lock.release()
        }
        facts
    }

    // ---------------------------------------------------------------- multicast DNS

    private suspend fun mdns(lan: Lan, of: (String) -> Facts) = withContext(Dispatchers.IO) {
        val group = InetAddress.getByName("224.0.0.251")
        DatagramSocket().use { sock ->
            sock.soTimeout = 250
            val known = ConcurrentHashMap.newKeySet<String>().apply { addAll(SERVICES.map { "$it.local" }) }
            val asked = ConcurrentHashMap.newKeySet<String>().apply { addAll(known) }
            val spoke = linkedSetOf<String>()
            fun send(names: List<Pair<String, Int>>, to: InetAddress) {
                names.chunked(14).forEach { chunk ->
                    val q = query(chunk)
                    runCatching { sock.send(DatagramPacket(q, q.size, to, 5353)) }
                }
            }
            // ask everyone for the service list and the common types; the QU bit makes them answer us directly
            val first = listOf(ENUM to 12) + SERVICES.map { "$it.local" to 12 }
            send(first, group)
            val start = System.currentTimeMillis()
            var resent = false
            var followed = false
            val buf = ByteArray(9000)
            var lastRx = start
            while (System.currentTimeMillis() - start < 3200 && !(System.currentTimeMillis() - start > 2000 && System.currentTimeMillis() - lastRx > 700)) {
                val now = System.currentTimeMillis() - start
                if (!resent && now > 900) { resent = true; send(first, group) }
                if (!followed && now > 1500) {
                    followed = true
                    // ask each device that spoke about the rest: what it announces, its instances' details, its .local name
                    for (ip in spoke.toList()) {
                        val to = InetAddress.getByName(ip)
                        val f = of(ip)
                        val reverse = ip.split('.').reversed().joinToString(".") + ".in-addr.arpa"
                        val ask = mutableListOf(reverse to 12, ENUM to 12)
                        synchronized(f) { f.instances.toList() }.forEach { (svc, label) -> ask += "$label.$svc.local" to 255 }
                        send(ask, to)
                    }
                }
                val p = DatagramPacket(buf, buf.size)
                try { sock.receive(p) } catch (e: SocketTimeoutException) { continue } catch (e: Exception) { break }
                val ip = p.address.hostAddress ?: continue
                if (ip !in lan.hosts) continue
                spoke += ip
                lastRx = System.currentTimeMillis()
                runCatching { parse(buf, p.length, of(ip), known, asked) }
                // a type we had not heard of: ask for it (to everyone; cheap)
                val fresh = known.filter { asked.add(it) }
                if (fresh.isNotEmpty()) send(fresh.map { it to 12 }, group)
            }
        }
    }

    private fun query(questions: List<Pair<String, Int>>): ByteArray {
        val o = ByteArrayOutputStream()
        fun u16(v: Int) { o.write(v shr 8 and 0xff); o.write(v and 0xff) }
        u16(0); u16(0); u16(questions.size); u16(0); u16(0); u16(0)
        for ((name, type) in questions) {
            for (label in name.trimEnd('.').split('.')) { val b = label.toByteArray(); o.write(b.size); o.write(b) }
            o.write(0); u16(type); u16(0x8001)
        }
        return o.toByteArray()
    }

    private fun parse(d: ByteArray, len: Int, f: Facts, known: MutableSet<String>, asked: MutableSet<String>) {
        fun u16(p: Int) = (d[p].toInt() and 0xff shl 8) or (d[p + 1].toInt() and 0xff)
        fun name(start: Int): Pair<String, Int> {
            val sb = StringBuilder(); var p = start; var end = -1; var hops = 0
            while (p < len) {
                val l = d[p].toInt() and 0xff
                when {
                    l == 0 -> { if (end < 0) end = p + 1; break }
                    l and 0xc0 == 0xc0 -> { if (end < 0) end = p + 2; p = (l and 0x3f shl 8) or (d[p + 1].toInt() and 0xff); if (++hops > 20) break }
                    else -> { sb.append(String(d, p + 1, l, Charsets.UTF_8)).append('.'); p += 1 + l }
                }
            }
            return sb.toString().trimEnd('.') to (if (end < 0) p else end)
        }
        val qd = u16(4); val rr = u16(6) + u16(8) + u16(10)
        var p = 12
        repeat(qd) { p = name(p).second + 4 }
        repeat(rr) {
            if (p >= len) return
            val (owner, after) = name(p)
            val type = u16(after); val rdLen = u16(after + 8); val rd = after + 10
            p = rd + rdLen
            if (p > len) return
            synchronized(f) {
                when (type) {
                    12 -> { // PTR
                        val target = name(rd).first
                        when {
                            owner.equals(ENUM, true) -> { known += target; }
                            owner.endsWith(".in-addr.arpa") -> f.mdnsHost = target.removeSuffix(".local")
                            else -> {
                                val svc = owner.removeSuffix(".local")
                                f.services += svc
                                f.instances += svc to target.removeSuffix(".$owner")
                            }
                        }
                    }
                    33 -> { // SRV: the instance lives on a host name
                        val host = name(rd + 6).first.removeSuffix(".local")
                        if (f.mdnsHost == null) f.mdnsHost = host
                    }
                    16 -> { // TXT
                        val svc = Regex("(_[a-z0-9-]+\\._(?:tcp|udp))\\.local$", RegexOption.IGNORE_CASE).find(owner)?.groupValues?.get(1) ?: return@synchronized
                        val kv = linkedMapOf<String, String>()
                        var q = rd
                        while (q < rd + rdLen) {
                            val l = d[q].toInt() and 0xff
                            val s = String(d, q + 1, minOf(l, rd + rdLen - q - 1), Charsets.UTF_8)
                            val eq = s.indexOf('=')
                            if (eq > 0) kv[s.substring(0, eq)] = s.substring(eq + 1)
                            q += 1 + l
                        }
                        f.services += svc
                        f.txt[svc] = (f.txt[svc] ?: emptyMap()) + kv
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- SSDP / UPnP

    private suspend fun ssdp(of: (String) -> Facts) = withContext(Dispatchers.IO) {
        val group = InetAddress.getByName("239.255.255.250")
        val locations = ConcurrentHashMap<String, String>()
        DatagramSocket().use { sock ->
            sock.soTimeout = 250
            fun search(st: String) {
                val m = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $st\r\n\r\n".toByteArray()
                runCatching { sock.send(DatagramPacket(m, m.size, group, 1900)) }
            }
            val targets = listOf("ssdp:all", "upnp:rootdevice", "urn:dial-multiscreen-org:service:dial:1", "roku:ecp")
            targets.forEach { search(it) }
            val start = System.currentTimeMillis()
            var again = false
            val buf = ByteArray(4096)
            var lastRx = start
            while (System.currentTimeMillis() - start < 3000 && !(System.currentTimeMillis() - start > 1800 && System.currentTimeMillis() - lastRx > 600)) {
                if (!again && System.currentTimeMillis() - start > 1200) { again = true; targets.forEach { search(it) } }
                val p = DatagramPacket(buf, buf.size)
                try { sock.receive(p) } catch (e: SocketTimeoutException) { continue } catch (e: Exception) { break }
                val ip = p.address.hostAddress ?: continue
                lastRx = System.currentTimeMillis()
                val h = String(buf, 0, p.length).lineSequence().drop(1).mapNotNull { l -> l.indexOf(':').takeIf { it > 0 }?.let { l.substring(0, it).trim().lowercase() to l.substring(it + 1).trim() } }.toMap()
                val f = of(ip)
                h["server"]?.let { f.ssdpServer = it }
                (h["st"] ?: h["nt"])?.let { synchronized(f) { f.ssdpTypes += it } }
                h["location"]?.let { loc -> if (runCatching { URL(loc).host }.getOrNull() == ip) locations.putIfAbsent(ip, loc) }
            }
        }
        // the description of each device, fetched together; only from the address that announced it
        coroutineScope {
            locations.map { (ip, loc) -> async { of(ip).upnp = fetchUpnp(loc) } }.awaitAll()
        }
    }

    private fun fetchUpnp(location: String): Upnp? = runCatching {
        val c = URL(location).openConnection() as HttpURLConnection
        c.connectTimeout = 1200; c.readTimeout = 1500; c.instanceFollowRedirects = false
        val xml = c.inputStream.use { it.readNBytes(32 * 1024) }.toString(Charsets.UTF_8)
        fun tag(t: String) = Regex("<$t>([^<]*)</$t>").find(xml)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
        Upnp(tag("friendlyName"), tag("manufacturer"), tag("modelName"), tag("modelNumber"), tag("deviceType"))
    }.getOrNull()

    // ---------------------------------------------------------------- web pages

    /** The server banner, page title and login realm of each device's web page, all hosts at once. */
    suspend fun web(hosts: List<Pair<String, List<Int>>>, set: (String, Http) -> Unit) = coroutineScope {
        val gate = Semaphore(48)
        hosts.mapNotNull { (ip, ports) ->
            val port = listOf(80, 8080, 8008).firstOrNull { it in ports } ?: return@mapNotNull null
            async(Dispatchers.IO) { gate.withPermit { grab(ip, port)?.let { set(ip, it) } } }
        }.awaitAll()
    }

    private fun grab(ip: String, port: Int): Http? = runCatching {
        val c = URL("http://$ip:$port/").openConnection() as HttpURLConnection
        c.connectTimeout = 1000; c.readTimeout = 1500; c.instanceFollowRedirects = false
        c.setRequestProperty("User-Agent", "PlaitScan")
        c.responseCode
        val body = (c.errorStream ?: c.inputStream).use { it.readNBytes(8192) }.toString(Charsets.UTF_8)
        Http(
            port, c.getHeaderField("Server"),
            Regex("<title[^>]*>([^<]*)</title>", RegexOption.IGNORE_CASE).find(body)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() },
            c.getHeaderField("WWW-Authenticate")?.let { Regex("realm=\"([^\"]*)\"", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) },
        )
    }.getOrNull()
}
