package app.plait.scan

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

/**
 * Asks an open port what it is, the way a scanner's service detection does, trying the least intrusive thing first:
 * 1. listen: many services speak first (SSH, FTP, SMTP, MySQL, VNC, Telnet);
 * 2. TLS handshake: reads the certificate's name, then asks it for its web page;
 * 3. a plain HTTP request;
 * 4. an RTSP request (cameras).
 * Nothing here logs in, guesses passwords or sends anything but those standard first requests.
 */
object ServiceProbe {
    /**
     * [service] is the protocol (HTTP, SSH…). [app] is the actual program behind it when something in the answer names it
     * (Radarr, SFTPGo 2.7.5, OpenSSH 9.2p1), and [source] says which part of the answer that name came from.
     */
    data class Info(val service: String, val detail: String? = null, val app: String? = null, val source: String? = null)

    fun identify(ip: String, port: Int): Info {
        banner(ip, port)?.let { b -> classifyBanner(b)?.let { return it } }
        tls(ip, port)?.let { return it }
        val http = request(ip, port, "GET / HTTP/1.0\r\nHost: $ip\r\nUser-Agent: PlaitScan\r\n\r\n", 1500)
        if (http != null) classifyReply(http)?.let { return it }
        val rtsp = request(ip, port, "OPTIONS rtsp://$ip:$port/ RTSP/1.0\r\nCSeq: 1\r\n\r\n", 1000)
        if (rtsp != null) classifyReply(rtsp)?.let { return it }
        return Info("Silent", "Accepts connections but answered nothing to a banner wait, TLS, HTTP or RTSP")
    }

    private fun connect(ip: String, port: Int, readMs: Int): Socket? = try {
        Socket().also { it.connect(InetSocketAddress(ip, port), 800); it.soTimeout = readMs }
    } catch (e: Exception) { null }

    private fun readSome(input: InputStream, max: Int = 2048): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(1024)
        try {
            while (out.size() < max) {
                val n = input.read(buf)
                if (n <= 0) break
                out.write(buf, 0, n)
                if (input.available() == 0) break
            }
        } catch (e: SocketTimeoutException) {
        } catch (e: Exception) {
        }
        return out.toByteArray()
    }

    /** What the service says first, if it speaks first. */
    private fun banner(ip: String, port: Int): ByteArray? = connect(ip, port, 900)?.use { s ->
        readSome(s.getInputStream()).takeIf { it.isNotEmpty() }
    }

    private fun request(ip: String, port: Int, text: String, readMs: Int): ByteArray? = connect(ip, port, readMs)?.use { s ->
        try {
            s.getOutputStream().apply { write(text.toByteArray()); flush() }
            readSome(s.getInputStream(), 8192).takeIf { it.isNotEmpty() }
        } catch (e: Exception) { null }
    }

    /**
     * A TLS handshake only to read the certificate (who it was issued to) and, if it completes, the web page behind it.
     * The certificate is not trusted for anything: self-signed ones are normal on a home network.
     */
    private fun tls(ip: String, port: Int): Info? {
        val raw = connect(ip, port, 1500) ?: return null
        raw.use {
            return try {
                val trustAll = object : X509TrustManager {
                    override fun checkClientTrusted(c: Array<X509Certificate>, a: String) {}
                    override fun checkServerTrusted(c: Array<X509Certificate>, a: String) {}
                    override fun getAcceptedIssuers() = emptyArray<X509Certificate>()
                }
                val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), null) }
                val ssl = ctx.socketFactory.createSocket(raw, ip, port, true) as SSLSocket
                ssl.soTimeout = 1500
                ssl.startHandshake()
                val cert = ssl.session.peerCertificates.firstOrNull() as? X509Certificate
                val subject = cert?.subjectX500Principal?.name?.let { name(it) }
                val issuer = cert?.issuerX500Principal?.name?.let { name(it) }
                val page = try {
                    ssl.outputStream.apply { write("GET / HTTP/1.0\r\nHost: $ip\r\nUser-Agent: PlaitScan\r\n\r\n".toByteArray()); flush() }
                    readSome(ssl.inputStream, 8192)
                } catch (e: Exception) { ByteArray(0) }
                val web = if (page.isNotEmpty()) classifyReply(page) else null
                val cn = listOfNotNull(subject?.let { "certificate for $it" }, issuer?.takeIf { it != subject }?.let { "issued by $it" }).joinToString(", ")
                val certApp = subject?.takeIf { meaningful(it) }
                Info(
                    "HTTPS", listOfNotNull(web?.detail, cn.ifEmpty { null }).joinToString(" · ").ifEmpty { "TLS service" },
                    app = web?.app ?: certApp, source = if (web?.app != null) web.source else if (certApp != null) "certificate name" else null,
                )
            } catch (e: Exception) { null }
        }
    }

    private fun name(dn: String): String? =
        Regex("CN=([^,]+)").find(dn)?.groupValues?.get(1) ?: Regex("O=([^,]+)").find(dn)?.groupValues?.get(1)

    private fun text(b: ByteArray, max: Int = 200): String =
        String(b, 0, minOf(b.size, max), Charsets.ISO_8859_1).filter { it == '\n' || it == '\r' || it in ' '..'~' }.replace(Regex("\\s+"), " ").trim()

    private fun printable(b: ByteArray): Boolean =
        b.isNotEmpty() && b.take(64).count { it in 32..126 || it == 10.toByte() || it == 13.toByte() || it == 9.toByte() } >= b.take(64).size * 0.9

    /** Web servers and frameworks that say nothing about which application runs on them. */
    private val GENERIC_SERVERS = setOf(
        "nginx", "apache", "kestrel", "lighttpd", "microsoft-iis", "microsoft-httpapi", "uvicorn", "gunicorn", "werkzeug", "jetty",
        "caddy", "openresty", "express", "golang", "cowboy", "tornado", "thin", "puma", "webrick", "hypercorn", "waitress", "cherrypy",
        "simplehttp", "basehttp", "python", "node", "envoy", "traefik", "haproxy", "tengine", "litespeed", "mini_httpd", "boa",
        "goahead-webs", "httpd", "server", "http", "web", "webserver", "jetty(", "tomcat", "coyote", "undertow", "netty", "php",
    )
    private val GENERIC_TITLE = Regex(
        "(?i)^(index of|welcome to|\\d{3}\\b|redirect|moved|not found|forbidden|unauthori[sz]ed|log ?in|sign ?in|home|index|untitled|document|" +
            "loading|please wait|error|it works|default|test page|apache|nginx|web interface|web ui|dashboard$)",
    )

    /** A name worth showing: not an IP, not a hash or container id, not a generic word. */
    private fun meaningful(n: String): Boolean {
        val t = n.trim()
        if (t.length < 3 || t.length > 40) return false
        if (Regex("^[0-9a-f]{8,}$", RegexOption.IGNORE_CASE).matches(t)) return false
        if (Regex("^[0-9.:]+$").matches(t)) return false
        if (Regex("(?i)^(localhost|example\\.com|default|server|unknown|none|self-?signed.*|.*\\.local(domain)?)$").matches(t)) return false
        return !GENERIC_TITLE.containsMatchIn(t)
    }

    /** "Radarr - Login" → "Radarr"; "Sign in | Dockge" → "Dockge". */
    private fun titleApp(title: String): String? =
        title.split(Regex("\\s+[-|–—·:]\\s+")).map { it.trim() }.firstOrNull { meaningful(it) }

    private fun metaApp(html: String): String? {
        for (tag in Regex("(?is)<meta\\b[^>]*>").findAll(html)) {
            val t = tag.value
            val key = Regex("(?i)(?:name|property)\\s*=\\s*[\"']([^\"']+)[\"']").find(t)?.groupValues?.get(1)?.lowercase() ?: continue
            if (key !in setOf("application-name", "generator", "og:site_name", "apple-mobile-web-app-title", "twitter:site")) continue
            val content = Regex("(?i)content\\s*=\\s*[\"']([^\"']+)[\"']").find(t)?.groupValues?.get(1)?.trim() ?: continue
            if (meaningful(content)) return content
        }
        return null
    }

    /** "SFTPGo/2.7.5" → "SFTPGo 2.7.5"; null for plain web servers like nginx or Kestrel. */
    private fun serverApp(server: String): String? {
        val product = server.trim().substringBefore(' ')
        val name = product.substringBefore('/')
        if (name.lowercase().trimEnd('(') in GENERIC_SERVERS || name.length < 3) return null
        return product.replace('/', ' ')
    }

    private fun classifyReply(b: ByteArray): Info? {
        val s = String(b, Charsets.ISO_8859_1)
        val first = s.lineSequence().firstOrNull()?.trim().orEmpty()
        fun header(h: String) = Regex("(?im)^$h:\\s*(.+)$").find(s)?.groupValues?.get(1)?.trim()
        return when {
            first.startsWith("HTTP/") -> {
                val title = Regex("(?is)<title[^>]*>(.*?)</title>").find(s)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
                val realm = header("WWW-Authenticate")?.let { Regex("realm=\"([^\"]*)\"", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) }
                val server = header("Server")
                // the first thing in the answer that actually names the program wins, and we remember where it was
                val (app, source) = listOf(
                    server?.let { serverApp(it) } to "Server header",
                    header("X-Application")?.takeIf { meaningful(it) } to "X-Application header",
                    metaApp(s) to "page metadata",
                    title?.let { titleApp(it) } to "page title",
                    realm?.takeIf { meaningful(it) } to "login prompt",
                ).firstOrNull { it.first != null }?.let { it.first to it.second } ?: (null to null)
                Info("HTTP", listOfNotNull(first.substringAfter(' '), server, title?.let { "“$it”" }, realm?.let { "login: $it" }).joinToString(" · "), app, source)
            }
            first.startsWith("RTSP/") -> {
                val server = header("Server")
                Info("RTSP", listOfNotNull(first.substringAfter(' '), server, header("Public")?.let { "methods $it" }).joinToString(" · "),
                    server?.takeIf { meaningful(it) }, if (server != null && meaningful(server)) "Server header" else null)
            }
            else -> null
        }
    }

    private fun classifyBanner(b: ByteArray): Info? {
        val t = text(b)
        val line = t.lineSequence().first()
        return when {
            t.startsWith("SSH-") -> {
                // SSH-2.0-OpenSSH_9.2p1 Debian-2 → OpenSSH 9.2p1
                val soft = line.removePrefix("SSH-").substringAfter('-').substringBefore(' ')
                Info("SSH", line.take(80), soft.replace('_', ' ').takeIf { it.isNotBlank() }, "SSH banner")
            }
            t.startsWith("220") && t.contains("FTP", true) -> ftpOrMail("FTP", t, "FTP banner", listOf("ProFTPD", "vsFTPd", "Pure-FTPd", "FileZilla", "Microsoft FTP", "SFTPGo", "wu-ftpd"))
            t.startsWith("220") && (t.contains("SMTP", true) || t.contains("mail", true)) -> ftpOrMail("SMTP", t, "SMTP banner", listOf("Postfix", "Exim", "Sendmail", "OpenSMTPD", "Microsoft ESMTP"))
            t.startsWith("+OK") -> Info("POP3", t.removePrefix("+OK").trim().take(80), listOf("Dovecot", "Courier", "Cyrus").firstOrNull { t.contains(it, true) }, "POP3 banner")
            t.startsWith("* OK") -> Info("IMAP", t.take(80), listOf("Dovecot", "Courier", "Cyrus").firstOrNull { t.contains(it, true) }, "IMAP banner")
            t.startsWith("RFB ") -> Info("VNC", t.take(12))
            t.startsWith("@RSYNCD") -> Info("rsync", t.take(40), "rsync ${t.removePrefix("@RSYNCD:").trim()}", "rsync banner")
            t.startsWith("HTTP/") || t.startsWith("RTSP/") -> classifyReply(b)
            t.contains("-NOAUTH") || t.startsWith("-DENIED") || t.startsWith("+PONG") -> Info("Redis", null, "Redis", "its reply to a connection")
            t.startsWith("AMQP") -> Info("AMQP", null, "RabbitMQ (AMQP)", "AMQP protocol header")
            b.size > 6 && b[4] == 0x0a.toByte() && b[5] in '0'.code.toByte()..'9'.code.toByte() -> {
                val end = b.drop(5).indexOf(0.toByte()).let { if (it > 0) 5 + it else minOf(b.size, 20) }
                val v = String(b, 5, end - 5, Charsets.ISO_8859_1)
                Info("MySQL / MariaDB", v, (if (v.contains("MariaDB", true)) "MariaDB " else "MySQL ") + v.substringBefore('-'), "MySQL handshake")
            }
            b.size >= 3 && b[0] == 0xFF.toByte() && (b[1] == 0xFD.toByte() || b[1] == 0xFB.toByte() || b[1] == 0xFE.toByte() || b[1] == 0xFC.toByte()) -> Info("Telnet")
            printable(b) -> Info("Text service", t.take(100))
            else -> Info("Binary service", "answered with ${b.size} bytes: " + b.take(12).joinToString(" ") { "%02x".format(it) })
        }
    }

    private fun ftpOrMail(service: String, t: String, source: String, known: List<String>): Info {
        val hit = known.firstOrNull { t.contains(it, true) }
        return Info(service, t.removePrefix("220").trim().take(80), hit, if (hit != null) source else null)
    }
}
