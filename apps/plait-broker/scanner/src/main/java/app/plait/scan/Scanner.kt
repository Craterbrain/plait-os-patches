package app.plait.scan

import android.content.Context
import android.net.ConnectivityManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.net.ConnectException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

data class Lan(val self: String, val gateway: String?, val iface: String?, val prefix: Int, val hosts: List<String>) {
    /** Only the /24 around this phone is scanned, even on a larger network. */
    val label get() = hosts.firstOrNull()?.substringBeforeLast('.')?.let { "$it.0/${maxOf(prefix, 24)}" } ?: self
}

data class Host(
    val ip: String,
    val mac: String? = null,
    val name: String? = null,
    val vendor: String? = null,
    val randomized: Boolean = false,
    val openPorts: List<Int> = emptyList(),
    val rttMs: Long? = null,
    val ipv6: List<String> = emptyList(),
    val gateway: Boolean = false,
    val self: Boolean = false,
    /** Another address that answers with the gateway's MAC: the gateway itself, on a second IP. */
    val alias: String? = null,
    val facts: Facts? = null,
    val identity: Identity? = null,
)

object Scanner {
    val PORTS = mapOf(
        21 to "FTP", 22 to "SSH", 23 to "Telnet", 53 to "DNS", 80 to "HTTP", 139 to "NetBIOS", 443 to "HTTPS", 445 to "SMB",
        554 to "RTSP", 3001 to "webOS", 5000 to "NAS", 5201 to "iperf3", 8001 to "Samsung TV", 8008 to "Cast", 8009 to "Cast", 8060 to "Roku",
        8080 to "HTTP-alt", 9100 to "Printer", 62078 to "iPhone",
    )

    /** The Wi-Fi (or Ethernet) network this phone is on; null when there is no IPv4 network. */
    fun lan(ctx: Context): Lan? {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val props = cm.getLinkProperties(cm.activeNetwork ?: return null) ?: return null
        val addr = props.linkAddresses.firstOrNull { it.address is Inet4Address } ?: return null
        val self = addr.address.hostAddress ?: return null
        val gateway = props.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress
        val base = self.substringBeforeLast('.')
        val hosts = (1..254).map { "$base.$it" }.filter { it != self }
        return Lan(self, gateway, props.interfaceName, addr.prefixLength, hosts)
    }

    private class Probe(val ports: List<Int>, val rtt: Long?)

    /**
     * Android only keeps neighbor entries for hosts this phone has recently talked to, so every address gets a UDP packet
     * (which makes the kernel look the host up) and a handful of TCP connects (which also show what it serves). A refused
     * connection counts as alive: something answered.
     */
    private suspend fun probe(ip: String, gate: Semaphore): Probe = coroutineScope {
        val tries = PORTS.keys.map { port ->
            async(Dispatchers.IO) { gate.withPermit { connect(ip, port) } }
        }.awaitAll()
        Probe(tries.filter { it.second == Outcome.OPEN }.map { it.first }, tries.mapNotNull { it.third }.minOrNull())
    }

    private enum class Outcome { OPEN, REFUSED, NONE }

    private fun connect(ip: String, port: Int): Triple<Int, Outcome, Long?> {
        val t0 = System.nanoTime()
        fun ms() = (System.nanoTime() - t0) / 1_000_000
        return try {
            Socket().use { it.connect(InetSocketAddress(ip, port), 300) }
            Triple(port, Outcome.OPEN, ms())
        } catch (e: ConnectException) {
            if (e.message?.contains("refused", ignoreCase = true) == true) Triple(port, Outcome.REFUSED, ms()) else Triple(port, Outcome.NONE, null)
        } catch (e: Exception) {
            Triple(port, Outcome.NONE, null)
        }
    }

    private data class Neighbor(val ip: String, val mac: String, val state: String)

    /** The kernel's neighbor table, read through the broker: `192.168.1.1 dev wlan0 lladdr aa:bb:.. REACHABLE`. */
    private fun neighbors(): List<Neighbor> {
        val r = Broker.run("ip", "neigh", "show")
        if (!r.ok) throw IllegalStateException(r.err.ifEmpty { "ip neigh show failed (exit ${r.exit})" })
        return r.out.lineSequence().mapNotNull { line ->
            val t = line.trim().split(Regex("\\s+"))
            val at = t.indexOf("lladdr")
            if (at < 0 || at + 1 >= t.size) null else Neighbor(t[0], t[at + 1].lowercase(), t.lastOrNull().orEmpty())
        }.toList()
    }

    /** This phone's own MAC (apps only see a made-up one), also through the broker. */
    private fun ownMac(iface: String?): String? {
        if (iface == null) return null
        val r = Broker.run("ip", "link", "show", iface)
        return if (r.ok) Regex("link/ether\\s+([0-9a-f:]{17})").find(r.out)?.groupValues?.get(1) else null
    }

    private suspend fun hostName(ip: String): String? {
        val t0 = System.currentTimeMillis()
        val r = withTimeoutOrNull(6000) {
            withContext(Dispatchers.IO) { runCatching { InetAddress.getByName(ip).canonicalHostName }.onFailure { android.util.Log.i("PlaitScan", "rdns $ip failed: $it") }.getOrNull() }
        }
        android.util.Log.i("PlaitScan", "rdns $ip -> $r in ${System.currentTimeMillis() - t0} ms")
        return r?.takeIf { it != ip }
    }

    /**
     * Scans [lan]. Everything slow starts at once: multicast discovery listens while the ports are probed, the broker is
     * asked for the neighbor table and this phone's MAC side by side, and after that each host gets its web page, name
     * and fingerprint at the same time as all the others. [onUpdate] gets a status line and the hosts known so far.
     */
    suspend fun scan(ctx: Context, lan: Lan, oui: Oui, onUpdate: (String, List<Host>) -> Unit): List<Host> = coroutineScope {
        onUpdate("Looking for devices…", emptyList())
        val discovery = async { runCatching { Discovery.listen(ctx, lan) }.getOrDefault(emptyMap()) }
        val ownJob = async(Dispatchers.IO) { ownMac(lan.iface) }
        val subnet = lan.hosts.toSet()

        val probes = ConcurrentHashMap<String, Probe>()
        val rdns = ConcurrentHashMap<String, String>()
        val web = ConcurrentHashMap<String, Http>()
        val gate = Semaphore(200)
        val factsRef = java.util.concurrent.atomic.AtomicReference<Map<String, Facts>>(emptyMap())
        val tableRef = java.util.concurrent.atomic.AtomicReference<List<Neighbor>>(emptyList())
        val jobs = mutableListOf<Deferred<*>>()

        fun usable(n: Neighbor) = !n.state.equals("FAILED", true) && !n.state.equals("INCOMPLETE", true)
        fun live(t: List<Neighbor>) = t.filter { it.ip in subnet && usable(it) }.map { it.ip }
        suspend fun table() = withContext(Dispatchers.IO) { neighbors() }.also { tableRef.set(it) }

        // One UDP packet per address makes the kernel ask "who has this IP?" (ARP). Whoever answers shows up in the
        // neighbor table, which the broker reads; only those hosts are worth a port scan.
        suspend fun spray(ips: Collection<String>) = withContext(Dispatchers.IO) {
            DatagramSocket().use { sock ->
                for (ip in ips) runCatching { sock.send(DatagramPacket(ByteArray(1), 1, InetAddress.getByName(ip), 9)) }
            }
        }

        /** The hosts as far as we know them now; every stage shows this, so devices appear the moment ARP answers. */
        fun render(): List<Host> {
            val v4 = tableRef.get().filter { it.ip in subnet && usable(it) }.associateBy { it.ip }
            val v6ByMac = tableRef.get().filter { it.ip.contains(':') && usable(it) }.groupBy({ it.mac }, { it.ip })
            val gatewayMac = lan.gateway?.let { v4[it]?.mac }
            val ownMac = if (ownJob.isCompleted && !ownJob.isCancelled) ownJob.getCompleted() else null
            fun host(ip: String): Host {
                val mac = v4[ip]?.mac
                val p = probes[ip]
                val alias = if (mac != null && mac == gatewayMac && ip != lan.gateway) lan.gateway else null
                val f = (factsRef.get()[ip] ?: if (web.containsKey(ip)) Facts() else null)?.also { x -> web[ip]?.let { w -> x.http = w } }
                val h = Host(
                    ip, mac, rdns[ip], mac?.let { oui.vendor(it) }, mac?.let { Oui.randomized(it) } ?: false, p?.ports ?: emptyList(), p?.rtt,
                    mac?.let { v6ByMac[it] } ?: emptyList(), gateway = ip == lan.gateway, alias = alias, facts = f,
                )
                return h.copy(identity = if (alias != null) Identity(Kind.ROUTER, "Second address of the gateway", 3, listOf("Same MAC as the gateway $alias"), null) else Fingerprint.classify(h, f))
            }
            val ips = lan.hosts.filter { v4.containsKey(it) || probes[it]?.let { p -> p.ports.isNotEmpty() || p.rtt != null } == true }
            val me = Host(lan.self, ownMac, null, ownMac?.let { oui.vendor(it) }, ownMac?.let { Oui.randomized(it) } ?: false, self = true,
                identity = Identity(Kind.PHONE, "This phone", 3, listOf("This phone"), null))
            return (ips.map { host(it) } + me).sortedBy { it.ip.substringAfterLast('.').toInt() }
        }

        val named = ConcurrentHashMap.newKeySet<String>()
        fun startNames(ips: Collection<String>) {
            for (ip in ips) if (ip != lan.self && named.add(ip)) jobs += async { hostName(ip)?.let { rdns[ip] = it } }
        }
        suspend fun portScan(ips: Collection<String>) {
            ips.map { ip -> async { probes[ip] = probe(ip, gate) } }.awaitAll()
            val withPorts = ips.map { it to (probes[it]?.ports ?: emptyList()) }
            jobs += async { Discovery.web(withPorts) { ip, h -> web[ip] = h } }
        }

        // Stage 1: ask the whole subnet, then read the neighbor table until it stops growing.
        spray(lan.hosts)
        var first = emptyList<String>()
        var quiet = 0
        for (i in 0 until 8) {
            delay(if (i == 0) 350 else 250)
            val now = live(table())
            if (now.size == first.size) quiet++ else quiet = 0
            first = now
            if (first.isNotEmpty()) onUpdate("Found ${first.size} devices…", render())
            if (quiet >= 2 && first.isNotEmpty()) break
        }
        startNames(first)

        // Stage 2: ports of the live hosts at once; sleepers that missed the first knock get a second one meanwhile.
        val firstScan = async { portScan(first) }
        spray(lan.hosts - first.toSet())
        delay(600)
        val extra = live(table()) - first.toSet()
        startNames(extra)
        firstScan.await()
        onUpdate("Checking ports and what devices announce…", render())
        if (extra.isNotEmpty()) portScan(extra)

        // Stage 3: what the multicast listeners heard, names and web pages, then done.
        table()
        factsRef.set(discovery.await())
        jobs.forEach { it.await() }
        ownJob.await()
        val result = render()
        result.forEach { h ->
            val f = h.facts
            android.util.Log.i("PlaitScan", "${h.ip} ${h.mac} name=${h.name} vendor=${h.vendor} -> ${h.identity?.kind} ${h.identity?.model} (${h.identity?.confidence}) " +
                "svc=${f?.services} mdnsHost=${f?.mdnsHost} upnp=${f?.upnp?.let { "${it.manufacturer}/${it.modelName}/${it.deviceType}" }} ssdp=${f?.ssdpServer} http=${f?.http?.let { "${it.server}|${it.title}|${it.realm}" }} ports=${h.openPorts}")
        }
        onUpdate("Found ${result.count { it.alias == null }} devices", result)
        result
    }
}
