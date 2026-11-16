package app.plait.scan

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom

/** A small DNS client: any record type, any server, UDP with a TCP retry when the answer is cut off. */
object Dns {
    val TYPES = linkedMapOf(
        "A" to 1, "AAAA" to 28, "CNAME" to 5, "MX" to 15, "NS" to 2, "TXT" to 16, "SOA" to 6, "PTR" to 12, "SRV" to 33, "CAA" to 257,
        "HTTPS" to 65, "DS" to 43, "DNSKEY" to 48, "ANY" to 255,
    )
    private val RCODES = listOf("NOERROR", "FORMERR", "SERVFAIL", "NXDOMAIN", "NOTIMP", "REFUSED")

    data class Record(val name: String, val type: Int, val ttl: Long, val data: String) {
        val typeName get() = TYPES.entries.firstOrNull { it.value == type }?.key ?: "TYPE$type"
    }

    data class Answer(
        val server: String, val ms: Long, val rcode: Int, val flags: List<String>, val answers: List<Record>,
        val authority: List<Record>, val additional: List<Record>, val viaTcp: Boolean, val size: Int,
    ) {
        val rcodeName get() = RCODES.getOrNull(rcode) ?: "RCODE$rcode"
    }

    class DnsException(msg: String) : Exception(msg)

    /** "1.2.3.4" → "4.3.2.1.in-addr.arpa", an IPv6 address → its nibbles under ip6.arpa. */
    fun reverseName(ip: String): String? {
        Cidr.toInt(ip)?.let { return ip.split('.').reversed().joinToString(".") + ".in-addr.arpa" }
        val v = Cidr.toBig(ip) ?: return null
        val hex = String.format("%32s", v.toString(16)).replace(' ', '0')
        return hex.reversed().toCharArray().joinToString(".") + ".ip6.arpa"
    }

    fun query(name: String, type: Int, server: String, port: Int = 53, timeoutMs: Int = 3000, recurse: Boolean = true, dnssec: Boolean = false): Answer {
        val id = SecureRandom().nextInt(0x10000)
        val q = buildQuery(id, name.trim().trimEnd('.'), type, recurse, dnssec)
        val addr = InetAddress.getByName(server)
        val t0 = System.nanoTime()
        var tcp = false
        var reply = udp(q, addr, port, timeoutMs, id)
        if (reply.size >= 4 && (reply[2].toInt() and 0x02) != 0) { // truncated: ask again over TCP
            reply = tcp(q, addr, port, timeoutMs)
            tcp = true
        }
        val ms = (System.nanoTime() - t0) / 1_000_000
        return parse(reply, server, ms, tcp)
    }

    private fun udp(q: ByteArray, addr: InetAddress, port: Int, timeoutMs: Int, id: Int): ByteArray = DatagramSocket().use { s ->
        s.soTimeout = timeoutMs
        s.send(DatagramPacket(q, q.size, addr, port))
        val buf = ByteArray(4096)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() <= deadline) {
            val p = DatagramPacket(buf, buf.size)
            try { s.receive(p) } catch (e: java.net.SocketTimeoutException) { throw DnsException("no answer from ${addr.hostAddress} within ${timeoutMs / 1000.0} s") }
            // ignore stray packets: the answer carries our query id
            if (p.length >= 12 && ((buf[0].toInt() and 0xff) shl 8 or (buf[1].toInt() and 0xff)) == id) return buf.copyOf(p.length)
        }
        throw DnsException("no answer from ${addr.hostAddress}")
    }

    private fun tcp(q: ByteArray, addr: InetAddress, port: Int, timeoutMs: Int): ByteArray = Socket().use { s ->
        s.soTimeout = timeoutMs
        s.connect(InetSocketAddress(addr, port), timeoutMs)
        val out = s.getOutputStream()
        out.write(byteArrayOf((q.size shr 8).toByte(), q.size.toByte())); out.write(q); out.flush()
        val inp = s.getInputStream()
        fun readN(n: Int): ByteArray { val b = ByteArray(n); var o = 0; while (o < n) { val r = inp.read(b, o, n - o); if (r < 0) throw DnsException("connection closed"); o += r }; return b }
        val len = readN(2).let { (it[0].toInt() and 0xff shl 8) or (it[1].toInt() and 0xff) }
        readN(len)
    }

    fun buildQuery(id: Int, name: String, type: Int, recurse: Boolean, dnssec: Boolean): ByteArray {
        val o = ByteArrayOutputStream()
        fun u16(v: Int) { o.write(v shr 8 and 0xff); o.write(v and 0xff) }
        u16(id); u16(if (recurse) 0x0100 else 0); u16(1); u16(0); u16(0); u16(1)
        if (name.isNotEmpty()) for (label in name.split('.')) {
            val b = label.toByteArray(Charsets.UTF_8)
            if (b.size > 63) throw DnsException("a label is longer than 63 bytes")
            o.write(b.size); o.write(b)
        }
        o.write(0); u16(type); u16(1)
        // EDNS0: say we accept bigger answers than 512 bytes (and optionally that we want DNSSEC records)
        o.write(0); u16(41); u16(1232); u16(0); u16(if (dnssec) 0x8000 else 0); u16(0)
        return o.toByteArray()
    }

    fun parse(d: ByteArray, server: String, ms: Long, viaTcp: Boolean): Answer {
        if (d.size < 12) throw DnsException("the answer is too short")
        fun u16(p: Int) = (d[p].toInt() and 0xff shl 8) or (d[p + 1].toInt() and 0xff)
        fun u32(p: Int) = (u16(p).toLong() shl 16) or u16(p + 2).toLong()
        val flags = u16(2)
        val rcode = flags and 0xF
        val qd = u16(4); val an = u16(6); val ns = u16(8); val ar = u16(10)
        val names = HashMap<Int, String>()
        fun name(start: Int): Pair<String, Int> {
            val labels = ArrayList<String>(); var p = start; var end = -1; var hops = 0
            while (true) {
                if (p >= d.size) throw DnsException("malformed name")
                val l = d[p].toInt() and 0xff
                when {
                    l == 0 -> { if (end < 0) end = p + 1; break }
                    l and 0xC0 == 0xC0 -> { if (end < 0) end = p + 2; p = (l and 0x3F shl 8) or (d[p + 1].toInt() and 0xff); if (++hops > 30) throw DnsException("name loop") }
                    else -> { labels += String(d, p + 1, l, Charsets.UTF_8); p += 1 + l }
                }
            }
            return (if (labels.isEmpty()) "." else labels.joinToString(".")) to end
        }
        var p = 12
        repeat(qd) { p = name(p).second + 4 }
        fun record(): Record? {
            val (owner, after) = name(p)
            val type = u16(after); val ttl = u32(after + 4); val len = u16(after + 8); val rd = after + 10
            p = rd + len
            if (type == 41) return null // the EDNS pseudo-record is not an answer
            return Record(owner, type, ttl, rdata(d, rd, len, type, ::name))
        }
        fun section(n: Int) = (0 until n).mapNotNull { record() }
        val answers = section(an); val auth = section(ns); val add = section(ar)
        val names2 = ArrayList<String>()
        if (flags and 0x0400 != 0) names2 += "authoritative"
        if (flags and 0x0200 != 0) names2 += "truncated"
        if (flags and 0x0100 != 0) names2 += "recursion desired"
        if (flags and 0x0080 != 0) names2 += "recursion available"
        if (flags and 0x0020 != 0) names2 += "authenticated (DNSSEC)"
        return Answer(server, ms, rcode, names2, answers, auth, add, viaTcp, d.size)
    }

    private fun rdata(d: ByteArray, at: Int, len: Int, type: Int, name: (Int) -> Pair<String, Int>): String {
        fun u16(p: Int) = (d[p].toInt() and 0xff shl 8) or (d[p + 1].toInt() and 0xff)
        fun u32(p: Int) = (u16(p).toLong() shl 16) or u16(p + 2).toLong()
        return try {
            when (type) {
                1 -> if (len == 4) (0 until 4).joinToString(".") { (d[at + it].toInt() and 0xff).toString() } else hex(d, at, len)
                28 -> if (len == 16) Cidr.compress(BigInteger(1, d.copyOfRange(at, at + 16))) else hex(d, at, len)
                2, 5, 12 -> name(at).first
                15 -> "${u16(at)} ${name(at + 2).first}"
                16 -> {
                    val parts = ArrayList<String>(); var q = at
                    while (q < at + len) { val l = d[q].toInt() and 0xff; parts += "\"" + String(d, q + 1, minOf(l, at + len - q - 1), Charsets.UTF_8) + "\""; q += 1 + l }
                    parts.joinToString(" ")
                }
                6 -> {
                    val (m, p1) = name(at); val (r, p2) = name(p1)
                    "$m $r serial ${u32(p2)} refresh ${u32(p2 + 4)} retry ${u32(p2 + 8)} expire ${u32(p2 + 12)} minimum ${u32(p2 + 16)}"
                }
                33 -> "${u16(at)} ${u16(at + 2)} ${u16(at + 4)} ${name(at + 6).first}"
                257 -> {
                    val tagLen = d[at + 1].toInt() and 0xff
                    "${d[at].toInt() and 0xff} ${String(d, at + 2, tagLen)} \"${String(d, at + 2 + tagLen, len - 2 - tagLen)}\""
                }
                else -> hex(d, at, len)
            }
        } catch (e: Exception) { hex(d, at, len) }
    }

    private fun hex(d: ByteArray, at: Int, len: Int) = d.copyOfRange(at, at + len).joinToString("") { "%02x".format(it) }
}
