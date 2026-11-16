package app.plait.scan

import java.math.BigInteger

/** One socket from the kernel's tables, with the uid of the app that owns it. */
data class Conn(
    val proto: String,          // TCP or UDP
    val v6: Boolean,
    val localIp: String, val localPort: Int,
    val remoteIp: String, val remotePort: Int,
    val state: String,
    val uid: Int,
) {
    val key get() = "$proto $localIp:$localPort $remoteIp:$remotePort $uid"
    val listening get() = state == "LISTEN" || (proto == "UDP" && remotePort == 0)
    val loopback get() = remoteIp.startsWith("127.") || remoteIp == "::1" || localIp.startsWith("127.") && remotePort == 0
    /** Sockets the kernel keeps after a connection ends belong to nobody (uid 0) and say nothing about what an app is doing. */
    val finished get() = state == "TIME_WAIT" || state == "CLOSE"
}

object ConnParse {
    private val TCP_STATES = mapOf(
        "01" to "ESTABLISHED", "02" to "SYN_SENT", "03" to "SYN_RECV", "04" to "FIN_WAIT1", "05" to "FIN_WAIT2", "06" to "TIME_WAIT",
        "07" to "CLOSE", "08" to "CLOSE_WAIT", "09" to "LAST_ACK", "0A" to "LISTEN", "0B" to "CLOSING",
    )

    /** "AF01A8C0" (the host-order word the kernel prints on a little-endian CPU) → 192.168.1.175. */
    fun ip4(hex: String): String {
        val v = hex.toLong(16)
        return "${v and 0xff}.${v shr 8 and 0xff}.${v shr 16 and 0xff}.${v shr 24 and 0xff}"
    }

    /** Four of those words in a row make an IPv6 address; ::ffff:a.b.c.d is shown as the IPv4 address it carries. */
    fun ip6(hex: String): String {
        val big = BigInteger((0 until 4).joinToString("") { i -> hex.substring(i * 8, i * 8 + 8).chunked(2).reversed().joinToString("") }, 16)
        return if (big.shiftRight(32) == BigInteger.valueOf(0xFFFF)) ip4(String.format("%08x", big.toLong() and 0xFFFFFFFFL).chunked(2).reversed().joinToString("")) else Cidr.compress(big)
    }

    private fun addr(s: String, v6: Boolean): Pair<String, Int> {
        val (ip, port) = s.split(':')
        return (if (v6) ip6(ip) else ip4(ip)) to port.toInt(16)
    }

    /** One file's lines (`/proc/net/tcp`, `tcp6`, `udp`, `udp6`) → connections. [proto] is "TCP" or "UDP". */
    fun table(text: String, proto: String, v6: Boolean): List<Conn> = text.lineSequence().mapNotNull { line ->
        val t = line.trim().split(Regex("\\s+"))
        if (t.size < 10 || !t[0].endsWith(":")) return@mapNotNull null
        try {
            val (lip, lport) = addr(t[1], v6)
            val (rip, rport) = addr(t[2], v6)
            val st = t[3].uppercase()
            val state = if (proto == "TCP") TCP_STATES[st] ?: st else if (st == "01") "CONNECTED" else "UNCONNECTED"
            Conn(proto, v6, lip, lport, rip, rport, state, t[7].toInt())
        } catch (e: Exception) { null }
    }.toList()

    /** `pm list packages -U` → uid to package names (apps that share a uid share a row). */
    fun packagesByUid(text: String): Map<Int, List<String>> =
        Regex("""package:(\S+) uid:(\d+)""").findAll(text).groupBy({ it.groupValues[2].toInt() }, { it.groupValues[1] })

    /** Names for the uids that belong to the system, not to an installed app. */
    val SYSTEM_UIDS = mapOf(
        0 to "root", 1000 to "Android system", 1001 to "Phone and radio", 1002 to "Bluetooth", 1010 to "Wi-Fi", 1013 to "Media", 1020 to "Multicast DNS",
        1021 to "GPS", 1027 to "NFC", 1036 to "Logging", 1051 to "Network (DNS)", 1066 to "Statistics", 2000 to "Shell (adb, Plait Broker)", 9999 to "Nobody",
    )

    /** Well-known remote ports, so "443" reads as "HTTPS". */
    val PORT_NAMES = mapOf(
        20 to "FTP data", 21 to "FTP", 22 to "SSH", 25 to "SMTP", 53 to "DNS", 80 to "HTTP", 110 to "POP3", 123 to "NTP", 143 to "IMAP", 443 to "HTTPS / QUIC",
        465 to "SMTPS", 587 to "SMTP", 853 to "DNS over TLS", 993 to "IMAPS", 995 to "POP3S", 1883 to "MQTT", 3478 to "STUN", 5222 to "XMPP", 5228 to "Google push",
        5229 to "Google push", 5230 to "Google push", 5353 to "mDNS", 8080 to "HTTP", 8443 to "HTTPS", 8883 to "MQTT over TLS", 51820 to "WireGuard",
    )
}
