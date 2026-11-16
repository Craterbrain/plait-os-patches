package app.plait.scan

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.BitSet

/**
 * A TCP connect scan of one device: the ports people actually use first (so the answer shows within a second or two),
 * then every remaining port from 1 to 65535 in order. It can be stopped and resumed; ports already tried are not
 * tried again. A refused connection is an answer (closed); only silence costs the timeout.
 */
class PortScan(val ip: String, val mac: String? = null) {
    enum class Phase { IDLE, CACHED, COMMON, REST, DONE }

    var open by mutableStateOf(listOf<Int>())
        private set
    var tried by mutableIntStateOf(0)
        private set
    var phase by mutableStateOf(Phase.IDLE)
        private set
    var running by mutableStateOf(false)
        private set
    var current by mutableIntStateOf(0)
        private set
    /** Closed ports answer at once; if most of a batch is silent the device (or a firewall) is dropping packets. */
    var silent by mutableStateOf(false)
        private set
    /** What each open port answered when asked: filled in by [probe], automatically for ports without a well-known name. */
    val services = mutableStateMapOf<Int, ServiceProbe.Info>()
    val probing = mutableStateMapOf<Int, Boolean>()
    /** Ports remembered from the last scan of this device (by MAC) that have not been re-checked yet. */
    var unverified by mutableStateOf(setOf<Int>())
        private set
    /** How many remembered ports turned out to be closed now. */
    var gone by mutableIntStateOf(0)
        private set
    var cachedAt by mutableStateOf<Long?>(null)
        private set
    /** Every remembered port stayed silent: the device itself is probably asleep or off, so the memory is kept. */
    var offline by mutableStateOf(false)
        private set

    init {
        PortCache.get(mac)?.let { c ->
            if (c.ports.isNotEmpty()) {
                open = c.ports.sorted()
                unverified = c.ports.toSet()
                services.putAll(c.services)
            }
            cachedAt = c.time
        }
    }

    private val done = BitSet(65536)
    private var job: Job? = null

    val total get() = 65535
    val commonDone get() = COMMON.all { done[it] }

    /** Starts (or resumes) the scan; [includeRest] false stops after the common ports. */
    fun start(scope: CoroutineScope, includeRest: Boolean) {
        if (running) return
        running = true
        job = scope.launch(Dispatchers.IO) {
            try {
                // 1. the ports people use most: the quickest answer there is
                if (!commonDone) {
                    phase = Phase.COMMON
                    reconcile(sweep(COMMON.filter { !done[it] }))
                }
                // 2. then the unusual ports found here last time, to see whether they are still open
                val remembered = unverified.filter { !done[it] }
                if (remembered.isNotEmpty() && isActive) {
                    phase = Phase.CACHED
                    reconcile(sweep(remembered))
                }
                if (includeRest && isActive) {
                    phase = Phase.REST
                    val rest = (1..65535).filter { !done[it] }
                    for (batch in rest.chunked(BATCH)) {
                        if (!isActive) break
                        sweep(batch)
                    }
                }
                if (isActive) phase = if (done.cardinality() >= 65535) Phase.DONE else if (commonDone) Phase.COMMON else phase
            } finally {
                running = false
                remember()
            }
        }
    }

    /** Saves what is known so the next visit (or the next scan) starts from it. */
    private fun remember() {
        if (mac == null || offline) return
        // ports still unverified (the scan was stopped early) are kept as they were
        PortCache.put(mac, open, services.toMap())
    }

    /**
     * Remembered ports that just got an answer: still open stays (the sweep already un-dims it); closed ones are dropped.
     * If the device stayed completely silent (nothing open anywhere in the sweep) it is probably asleep, so nothing is dropped.
     */
    private fun reconcile(answers: Map<Int, Answer?>) {
        val mine = answers.filter { (port, a) -> a != null && port in unverified }
        if (mine.isEmpty()) return
        val stillOpen = mine.filterValues { it == Answer.OPEN }.keys
        if (stillOpen.isEmpty() && answers.values.none { it == Answer.OPEN } && mine.values.all { it == Answer.SILENT }) {
            offline = true
            return
        }
        offline = false
        val closed = mine.keys - stillOpen
        synchronized(this) {
            open = open.filter { it !in closed }
            unverified = unverified - mine.keys
            gone += closed.size
        }
        closed.forEach { services.remove(it) }
    }

    /**
     * Starts over: common ports, then the ports remembered from before, and (if [includeRest]) every other port. What was
     * open becomes "remembered", so a rescan shows what is still there and what went away.
     */
    fun rescan(scope: CoroutineScope, includeRest: Boolean) {
        if (running) return
        synchronized(this) { done.clear() }
        tried = 0
        gone = 0
        offline = false
        silent = false
        unverified = open.toSet()
        start(scope, includeRest)
    }

    /** Asks [port] what it is; safe to call again (the answer is replaced). */
    fun probe(port: Int) {
        if (probing[port] == true) return
        probing[port] = true
        PortScans.scope.launch {
            try {
                PortScans.probeGate.withPermit { services[port] = ServiceProbe.identify(ip, port) }
                remember()
            } catch (e: Exception) {
                services[port] = ServiceProbe.Info("Probe failed", e.message)
            } finally {
                probing.remove(port)
            }
        }
    }

    fun stop() {
        job?.cancel()
    }

    private suspend fun sweep(ports: List<Int>): Map<Int, Answer?> = coroutineScope {
        val gate = Semaphore(CONCURRENCY)
        var silentCount = 0
        val results = ports.map { port ->
            async {
                gate.withPermit {
                    if (!isActive) return@withPermit null
                    val r = try {
                        Socket().use { it.connect(InetSocketAddress(ip, port), TIMEOUT_MS) }
                        Answer.OPEN
                    } catch (e: ConnectException) {
                        if (e.message?.contains("refused", true) == true) Answer.CLOSED else Answer.SILENT
                    } catch (e: Exception) {
                        Answer.SILENT
                    }
                    synchronized(this@PortScan) { done.set(port) }
                    tried = synchronized(this@PortScan) { done.cardinality() }
                    current = port
                    if (r == Answer.OPEN) {
                        synchronized(this@PortScan) { open = (open + port).distinct().sorted(); unverified = unverified - port }
                        // ports nobody would recognise by number are asked what they are right away (not too many at once)
                        if (port !in NAMES && port !in services && services.size + probing.size < AUTO_PROBE_LIMIT) probe(port)
                    }
                    if (r == Answer.SILENT) synchronized(this@PortScan) { silentCount++ }
                    r
                }
            }
        }.awaitAll()
        if (results.size >= 50) silent = silentCount > results.size / 2
        ports.zip(results).toMap()
    }

    private enum class Answer { OPEN, CLOSED, SILENT }

    companion object {
        private const val CONCURRENCY = 256
        private const val BATCH = 2048
        private const val TIMEOUT_MS = 350
        private const val AUTO_PROBE_LIMIT = 30

        /** What is likely to be listening on a home network, roughly most common first. */
        val COMMON: List<Int> = listOf(
            80, 443, 22, 21, 23, 25, 53, 110, 111, 135, 139, 143, 445, 993, 995, 1723, 3306, 3389, 5900, 8080,
            8443, 8000, 8008, 8009, 8888, 554, 5060, 5353, 1900, 548, 631, 9100, 515, 161, 389, 636, 873, 2049,
            3000, 3001, 5000, 5001, 5432, 5555, 5357, 5985, 6379, 7000, 8081, 8082, 8083, 8090, 8123, 8200, 8291,
            8883, 1883, 9000, 9090, 9200, 10000, 27017, 32400, 49152, 49153, 49154, 62078, 8060, 8001, 8002, 1400,
            3478, 4443, 5201, 5683, 6668, 7547, 8728, 8729, 9999, 20000, 37777, 34567, 81, 82, 88, 113, 123, 137, 138, 179,
            427, 465, 500, 587, 646, 902, 1080, 1194, 1433, 1521, 1812, 2000, 2082, 2083, 2181, 2222, 3260, 3283,
            3689, 4000, 4500, 5001, 5004, 5222, 5223, 5228, 5353, 5440, 5601, 5800, 5984, 6000, 6443, 6881, 7070,
            7777, 8086, 8087, 8096, 8112, 8181, 8384, 8444, 8500, 8880, 9001, 9091, 9093, 9100, 9418, 9443, 10001,
            11211, 15672, 25565, 51820, 5037, 5038, 6466, 7676, 8554, 9080, 10554, 49000, 55443, 56789,
        ).distinct().filter { it in 1..65535 }

        /** Names for the ports people will recognize; everything else is just a number. */
        val NAMES: Map<Int, String> = mapOf(
            21 to "FTP", 22 to "SSH", 23 to "Telnet", 25 to "SMTP", 53 to "DNS", 80 to "HTTP", 81 to "HTTP-alt", 88 to "Kerberos",
            110 to "POP3", 111 to "RPC", 123 to "NTP", 135 to "MS RPC", 137 to "NetBIOS", 139 to "NetBIOS", 143 to "IMAP", 161 to "SNMP",
            179 to "BGP", 389 to "LDAP", 427 to "SLP", 443 to "HTTPS", 445 to "SMB", 465 to "SMTPS", 500 to "IPsec", 515 to "LPD",
            548 to "AFP", 554 to "RTSP", 587 to "SMTP", 631 to "IPP", 636 to "LDAPS", 873 to "rsync", 902 to "VMware", 993 to "IMAPS",
            995 to "POP3S", 1080 to "SOCKS", 1194 to "OpenVPN", 1400 to "Sonos", 1433 to "SQL Server", 1521 to "Oracle", 1723 to "PPTP",
            1883 to "MQTT", 1900 to "UPnP", 2049 to "NFS", 2181 to "ZooKeeper", 3000 to "HTTP-alt", 3001 to "webOS", 3260 to "iSCSI",
            3306 to "MySQL", 3389 to "RDP", 3478 to "STUN", 3689 to "iTunes", 4443 to "HTTPS-alt", 5000 to "UPnP / NAS", 5001 to "NAS HTTPS",
            5060 to "SIP", 5201 to "iperf3", 5222 to "XMPP", 5353 to "mDNS", 5357 to "WSDD", 5432 to "PostgreSQL", 5555 to "ADB", 5683 to "CoAP",
            5900 to "VNC", 5985 to "WinRM", 6379 to "Redis", 6668 to "Tuya", 7547 to "TR-069", 8000 to "HTTP-alt", 8001 to "Samsung TV",
            8002 to "Samsung TV", 8008 to "Cast", 8009 to "Cast", 8060 to "Roku", 8080 to "HTTP-alt", 8081 to "HTTP-alt", 8086 to "InfluxDB",
            8096 to "Jellyfin", 8123 to "Home Assistant", 8200 to "Plex DLNA", 8291 to "MikroTik", 8443 to "HTTPS-alt", 8554 to "RTSP-alt",
            8728 to "MikroTik API", 8883 to "MQTTS", 8888 to "HTTP-alt", 9000 to "HTTP-alt", 9090 to "HTTP-alt", 9100 to "Printer",
            9200 to "Elasticsearch", 10000 to "Webmin", 11211 to "memcached", 25565 to "Minecraft", 27017 to "MongoDB", 32400 to "Plex",
            34567 to "DVR", 37777 to "Dahua", 7878 to "Radarr", 8686 to "Lidarr", 8989 to "Sonarr", 9696 to "Prowlarr", 49152 to "UPnP", 51820 to "WireGuard", 62078 to "iPhone sync",
        )
    }
}

/** Port scans outlive the device page: leave and come back and the results are still there (and still running). */
object PortScans {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** Service probes are slow (they wait for answers), so only a few run together and the sweep stays fast. */
    val probeGate = Semaphore(8)
    private val all = HashMap<String, PortScan>()
    /** One scan per device and address; a different MAC at the same IP is a different device. */
    @Synchronized fun of(ip: String, mac: String?): PortScan = all.getOrPut("$mac|$ip") { PortScan(ip, mac) }
}
