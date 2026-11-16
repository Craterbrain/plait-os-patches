package app.plait.scan

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigInteger

/** What the network scan last found, so the tools can offer the gateway, the devices and their MAC addresses. */
object LastScan {
    @Volatile var lan: Lan? = null
    @Volatile var hosts: List<Host> = emptyList()
}

object Proc {
    /**
     * Runs a program and hands every output line (stderr included) to [onLine]. Cancelling the coroutine kills the
     * program, which is how a running ping is stopped. Returns the exit code.
     */
    suspend fun run(vararg cmd: String, onLine: (String) -> Unit = {}): Int = coroutineScope {
        val p = withContext(Dispatchers.IO) { ProcessBuilder(*cmd).redirectErrorStream(true).start() }
        val killer = launch { try { awaitCancellation() } finally { p.destroyForcibly() } }
        try {
            withContext(Dispatchers.IO) {
                p.inputStream.bufferedReader().forEachLine(onLine)
                p.waitFor()
            }
        } finally {
            killer.cancel()
            p.destroyForcibly()
        }
    }

    /** Runs a program to the end and returns all of its output. */
    suspend fun text(vararg cmd: String): String {
        val sb = StringBuilder()
        run(*cmd) { sb.appendLine(it) }
        return sb.toString()
    }

    fun pingBinary(v6: Boolean) = if (v6) "/system/bin/ping6" else "/system/bin/ping"
}

// ---------------------------------------------------------------- ping

sealed interface PingEvent {
    data class Reply(val seq: Int, val ttl: Int?, val ms: Double, val bytes: Int, val from: String) : PingEvent
    data class Missed(val seq: Int) : PingEvent
    data class Problem(val text: String) : PingEvent
}

object PingParse {
    private val REPLY = Regex("""^(\d+) bytes from (\S+?)(?: \([^)]*\))?: icmp_seq=(\d+)(?: ttl=(\d+))? time=([\d.]+) ms""")
    private val MISSED = Regex("""no answer yet for icmp_seq=(\d+)""")

    fun line(l: String): PingEvent? {
        REPLY.find(l)?.let { return PingEvent.Reply(it.groupValues[3].toInt(), it.groupValues[4].toIntOrNull(), it.groupValues[5].toDouble(), it.groupValues[1].toInt(), it.groupValues[2]) }
        MISSED.find(l)?.let { return PingEvent.Missed(it.groupValues[1].toInt()) }
        if (l.startsWith("ping:") || l.contains("Unreachable", true) || l.contains("unknown host", true) || l.contains("Name or service not known", true) ||
            l.contains("Time to live exceeded") || l.contains("Frag needed")) return PingEvent.Problem(l.trim())
        return null
    }
}

/** Running numbers for a ping: min, average, max, loss and jitter (the average change between neighbouring replies). */
class PingStats {
    val rtts = ArrayList<Double>()
    var sent = 0
        private set
    var received = 0
        private set

    fun reply(ms: Double) { sent++; received++; rtts += ms }
    fun missed() { sent++ }

    val min get() = rtts.minOrNull()
    val max get() = rtts.maxOrNull()
    val avg get() = if (rtts.isEmpty()) null else rtts.average()
    val lossPercent get() = if (sent == 0) 0.0 else (sent - received) * 100.0 / sent
    val jitter get() = if (rtts.size < 2) null else rtts.zipWithNext { a, b -> kotlin.math.abs(b - a) }.average()
}

// ---------------------------------------------------------------- path MTU

object Mtu {
    sealed interface Probe {
        data object Ok : Probe
        data class TooBig(val mtu: Int?) : Probe
        data object Silent : Probe
    }

    private val LOCAL = Regex("""Message too long, mtu=(\d+)""")
    private val REMOTE = Regex("""Frag needed and DF set \(mtu = (\d+)\)""")

    fun classify(output: String): Probe {
        LOCAL.find(output)?.let { return Probe.TooBig(it.groupValues[1].toInt()) }
        REMOTE.find(output)?.let { return Probe.TooBig(it.groupValues[1].toInt()) }
        return if (Regex("""bytes from""").containsMatchIn(output)) Probe.Ok else Probe.Silent
    }

    data class Result(val mtu: Int?, val payload: Int?, val limitedBy: String?, val note: String)

    /**
     * Finds the largest packet that crosses the path without being split. Each round sends several sizes at once and
     * narrows the range, so it takes a few seconds, not a few dozen. [concurrent] sends one don't-fragment ping per
     * payload size, all together, and returns what each one got.
     */
    suspend fun discover(v6: Boolean, concurrent: suspend (List<Int>) -> List<Probe>, onStep: (String) -> Unit = {}): Result {
        val overhead = if (v6) 48 else 28
        if (concurrent(listOf(32)).firstOrNull() != Probe.Ok) return Result(null, null, null, "The host does not answer ping, so the path cannot be measured.")
        var lo = 32                         // the largest payload known to arrive
        var hi = 9000 - overhead + 1        // the smallest payload known (or assumed) not to
        var limit: String? = null
        fun learn(sizes: List<Int>, results: List<Probe>) {
            for ((size, r) in sizes.zip(results)) if (r == Probe.Ok) lo = maxOf(lo, size)
            for ((size, r) in sizes.zip(results)) {
                if (r == Probe.Ok) continue
                if (size > lo) hi = minOf(hi, size)
                // a device that refuses the packet often says how big it may be
                (r as? Probe.TooBig)?.mtu?.let { m ->
                    val fits = m - overhead
                    if (fits + 1 < hi) { hi = maxOf(fits + 1, lo + 1); limit = "a device on the path reports an MTU of $m" }
                }
            }
        }
        // the usual sizes first: 1500 for most paths, 9000 for jumbo frames
        val usual = listOf(1500 - overhead, 9000 - overhead).filter { it in (lo + 1) until hi }
        learn(usual, concurrent(usual))
        var rounds = 0
        while (hi - lo > 1 && rounds < 10) {
            rounds++
            val n = minOf(8, hi - lo - 1)
            val sizes = (1..n).map { lo + (hi - lo) * it / (n + 1) }.distinct().filter { it in (lo + 1) until hi }
            if (sizes.isEmpty()) break
            onStep("Testing packets of ${sizes.joinToString(", ") { (it + overhead).toString() }} bytes…")
            learn(sizes, concurrent(sizes))
        }
        return Result(lo + overhead, lo, limit, "")
    }
}

// ---------------------------------------------------------------- Wake on LAN

object Wol {
    fun parseMac(s: String): ByteArray? {
        val hex = s.filter { it.isLetterOrDigit() }
        if (hex.length != 12 || !hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
        return ByteArray(6) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    fun format(mac: ByteArray) = mac.joinToString(":") { "%02x".format(it) }

    /** Six 0xFF bytes, then the target's MAC sixteen times: any NIC set to wake on magic packets recognises it. */
    fun magicPacket(mac: ByteArray): ByteArray {
        require(mac.size == 6)
        val p = ByteArray(6 + 16 * 6) { 0xFF.toByte() }
        for (i in 0 until 16) System.arraycopy(mac, 0, p, 6 + i * 6, 6)
        return p
    }
}

// ---------------------------------------------------------------- Wi-Fi scan table from `cmd wifi list-scan-results`

data class WifiAp(val bssid: String, val freq: Int, val rssi: Int, val ssid: String, val flags: String, val ageSec: Double?) {
    val channel: Int get() = channelOf(freq)
    val band: String get() = when { freq < 3000 -> "2.4 GHz"; freq < 5925 -> "5 GHz"; else -> "6 GHz" }
    val security: String
        get() = when {
            "SAE" in flags && "PSK" in flags -> "WPA2/WPA3"
            "SAE" in flags -> "WPA3"
            "OWE" in flags -> "Enhanced Open"
            "RSN-EAP" in flags || "WPA2-EAP" in flags || "EAP" in flags -> "WPA2 Enterprise"
            "RSN" in flags || "WPA2" in flags -> "WPA2"
            "WPA-" in flags || "[WPA" in flags -> "WPA"
            "WEP" in flags -> "WEP"
            else -> "Open"
        }
    val hidden get() = ssid.isEmpty()

    companion object {
        fun channelOf(f: Int): Int = when {
            f == 2484 -> 14
            f in 2412..2472 -> (f - 2407) / 5
            f in 5160..5885 -> (f - 5000) / 5
            f in 5955..7115 -> (f - 5950) / 5
            else -> 0
        }
    }
}

data class WifiLink(val ssid: String?, val bssid: String, val rssi: Int?, val linkMbps: Int?, val freq: Int?, val standard: String?)

object WifiParse {
    /** The network this phone is connected to, from the `WifiInfo:` line of `cmd wifi status`. */
    fun link(text: String): WifiLink? {
        val l = text.lines().firstOrNull { "WifiInfo:" in it && "BSSID:" in it } ?: return null
        fun g(re: String) = Regex(re).find(l)?.groupValues?.get(1)
        val bssid = g("""BSSID: ([0-9a-fA-F:]{17})""")?.lowercase() ?: return null
        return WifiLink(
            g("""SSID: "(.*?)", BSSID""")?.trim()?.ifEmpty { null }, bssid, g("""RSSI: (-?\d+)""")?.toIntOrNull(),
            g("""Link speed: (\d+)Mbps""")?.toIntOrNull(), g("""Frequency: (\d+)MHz""")?.toIntOrNull(), g("""Wi-Fi standard: (\w+)"""),
        )
    }

    private val ROW = Regex("""^\s*([0-9a-fA-F]{2}(?::[0-9a-fA-F]{2}){5})\s+(\d{4})\s+(-?\d+)\s+(\S+)\s?(.*)$""")

    fun scanResults(text: String): List<WifiAp> = text.lines().mapNotNull { l ->
        val m = ROW.find(l) ?: return@mapNotNull null
        val rest = m.groupValues[5]
        // the SSID runs up to the capability flags, which are the bracketed words at the end
        val flagsAt = rest.indexOf('[')
        val ssid = (if (flagsAt >= 0) rest.substring(0, flagsAt) else rest).trim()
        val flags = if (flagsAt >= 0) rest.substring(flagsAt).trim() else ""
        WifiAp(m.groupValues[1].lowercase(), m.groupValues[2].toInt(), m.groupValues[3].toInt(), ssid, flags, m.groupValues[4].removePrefix(">").toDoubleOrNull())
    }
}

// ---------------------------------------------------------------- subnet / CIDR

object Cidr {
    data class V4(
        val address: String, val prefix: Int, val mask: String, val wildcard: String, val network: String, val broadcast: String,
        val first: String, val last: String, val hosts: Long, val total: Long, val kind: String, val scope: String, val binaryAddress: String, val binaryMask: String,
    )

    fun parseV4(s: String): V4? {
        val t = s.trim()
        val addrPart: String
        val prefix: Int
        when {
            "/" in t -> {
                addrPart = t.substringBefore('/').trim()
                val p = t.substringAfter('/').trim()
                prefix = p.toIntOrNull() ?: maskToPrefix(p) ?: return null
            }
            " " in t -> {
                addrPart = t.substringBefore(' ').trim()
                prefix = maskToPrefix(t.substringAfter(' ').trim()) ?: return null
            }
            else -> { addrPart = t; prefix = 32 }
        }
        if (prefix !in 0..32) return null
        val a = toInt(addrPart) ?: return null
        val maskBits = if (prefix == 0) 0L else (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL
        val addr = a.toLong() and 0xFFFFFFFFL
        val net = addr and maskBits
        val bc = net or (maskBits.inv() and 0xFFFFFFFFL)
        val total = 1L shl (32 - prefix)
        val usable = when (prefix) { 32 -> 1L; 31 -> 2L; else -> total - 2 }
        val first = if (prefix >= 31) net else net + 1
        val last = if (prefix >= 31) bc else bc - 1
        return V4(
            fmt(addr), prefix, fmt(maskBits), fmt(maskBits.inv() and 0xFFFFFFFFL), fmt(net), fmt(bc), fmt(first), fmt(last), usable, total,
            classOf(addr), scopeOf(addr), binary(addr), binary(maskBits),
        )
    }

    /** "255.255.255.0" or the wildcard form "0.0.0.255" to a prefix length; null if it is not a contiguous mask. */
    fun maskToPrefix(s: String): Int? {
        val m = toInt(s)?.toLong()?.and(0xFFFFFFFFL) ?: return null
        for (candidate in listOf(m, m.inv() and 0xFFFFFFFFL)) {
            val ones = java.lang.Long.bitCount(candidate)
            val expect = if (ones == 0) 0L else (0xFFFFFFFFL shl (32 - ones)) and 0xFFFFFFFFL
            if (candidate == expect) return ones
        }
        return null
    }

    fun toInt(s: String): Int? {
        val p = s.trim().split('.')
        if (p.size != 4) return null
        var v = 0L
        for (x in p) {
            val n = x.toIntOrNull() ?: return null
            if (n !in 0..255) return null
            v = v * 256 + n
        }
        return v.toInt()
    }

    fun fmt(v: Long) = "${v shr 24 and 255}.${v shr 16 and 255}.${v shr 8 and 255}.${v and 255}"
    private fun binary(v: Long) = (3 downTo 0).joinToString(".") { String.format("%8s", java.lang.Long.toBinaryString(v shr (it * 8) and 255)).replace(' ', '0') }

    private fun classOf(a: Long) = when ((a shr 24).toInt()) {
        in 0..127 -> "A"; in 128..191 -> "B"; in 192..223 -> "C"; in 224..239 -> "D (multicast)"; else -> "E (reserved)"
    }

    private fun scopeOf(a: Long): String {
        val b0 = (a shr 24).toInt(); val b1 = (a shr 16 and 255).toInt()
        return when {
            b0 == 10 -> "Private (RFC 1918)"
            b0 == 172 && b1 in 16..31 -> "Private (RFC 1918)"
            b0 == 192 && b1 == 168 -> "Private (RFC 1918)"
            b0 == 127 -> "Loopback"
            b0 == 169 && b1 == 254 -> "Link-local"
            b0 == 100 && b1 in 64..127 -> "Carrier-grade NAT (RFC 6598)"
            b0 in 224..239 -> "Multicast"
            b0 == 0 -> "This network"
            else -> "Public"
        }
    }

    /** Splits [net]/[prefix] into subnets of [newPrefix] (at most [limit] listed). */
    fun split(net: V4, newPrefix: Int, limit: Int = 64): List<String> {
        if (newPrefix <= net.prefix || newPrefix > 32) return emptyList()
        val start = toInt(net.network)!!.toLong() and 0xFFFFFFFFL
        val count = 1L shl (newPrefix - net.prefix)
        val size = 1L shl (32 - newPrefix)
        return (0 until minOf(count, limit.toLong())).map { fmt(start + it * size) + "/" + newPrefix }
    }

    data class V6(val address: String, val prefix: Int, val network: String, val first: String, val last: String, val total: String, val kind: String)

    fun parseV6(s: String): V6? {
        val t = s.trim()
        val addr = t.substringBefore('/')
        val prefix = if ("/" in t) t.substringAfter('/').trim().toIntOrNull() ?: return null else 128
        if (prefix !in 0..128 || ':' !in addr) return null
        val v = toBig(addr) ?: return null
        val all = BigInteger.ONE.shiftLeft(128).subtract(BigInteger.ONE)
        val mask = if (prefix == 0) BigInteger.ZERO else all.shiftLeft(128 - prefix).and(all)
        val net = v.and(mask)
        val last = net.or(all.xor(mask))
        return V6(compress(v), prefix, compress(net), compress(net), compress(last), BigInteger.ONE.shiftLeft(128 - prefix).toString(), kindV6(v))
    }

    fun toBig(addr: String): BigInteger? {
        var a = addr.trim().lowercase().substringBefore('%')
        if (a.contains('.')) { // embedded IPv4 tail, e.g. ::ffff:192.0.2.1
            val v4 = toInt(a.substringAfterLast(':')) ?: return null
            val hi = (v4 ushr 16) and 0xFFFF; val lo = v4 and 0xFFFF
            a = a.substringBeforeLast(':') + ":" + Integer.toHexString(hi) + ":" + Integer.toHexString(lo)
        }
        val halves = a.split("::")
        if (halves.size > 2) return null
        fun groups(s: String) = if (s.isEmpty()) emptyList() else s.split(':')
        val head = groups(halves[0]); val tail = if (halves.size == 2) groups(halves[1]) else emptyList()
        val missing = 8 - head.size - tail.size
        if (halves.size == 2 && missing < 1 || halves.size == 1 && missing != 0) return null
        val all = head + List(if (halves.size == 2) missing else 0) { "0" } + tail
        var v = BigInteger.ZERO
        for (g in all) {
            val n = g.toIntOrNull(16) ?: return null
            if (n !in 0..0xFFFF) return null
            v = v.shiftLeft(16).or(BigInteger.valueOf(n.toLong()))
        }
        return v
    }

    fun compress(v: BigInteger): String {
        val g = (7 downTo 0).map { v.shiftRight(it * 16).and(BigInteger.valueOf(0xFFFF)).toInt() }
        // the longest run of zero groups (at least two) becomes "::"
        var bestStart = -1; var bestLen = 0; var i = 0
        while (i < 8) {
            if (g[i] == 0) { var j = i; while (j < 8 && g[j] == 0) j++; if (j - i > bestLen) { bestStart = i; bestLen = j - i }; i = j } else i++
        }
        if (bestLen < 2) return g.joinToString(":") { Integer.toHexString(it) }
        val left = g.subList(0, bestStart).joinToString(":") { Integer.toHexString(it) }
        val right = g.subList(bestStart + bestLen, 8).joinToString(":") { Integer.toHexString(it) }
        return "$left::$right"
    }

    private fun kindV6(v: BigInteger): String {
        val top = v.shiftRight(112).toInt()
        return when {
            v == BigInteger.ONE -> "Loopback"
            v == BigInteger.ZERO -> "Unspecified"
            top and 0xFFC0 == 0xFE80 -> "Link-local"
            top and 0xFE00 == 0xFC00 -> "Unique local (private)"
            top and 0xFF00 == 0xFF00 -> "Multicast"
            top and 0xE000 == 0x2000 -> "Global unicast"
            else -> "Other"
        }
    }
}
