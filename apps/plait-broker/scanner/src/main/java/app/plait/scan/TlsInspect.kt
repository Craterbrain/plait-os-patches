package app.plait.scan

import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PublicKey
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.util.Date
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Looks at the certificate a server presents. The handshake accepts any certificate (so a broken one can still be read),
 * and the platform's own trust store is then asked separately whether the chain would really be trusted. Nothing is
 * sent to the server after the handshake.
 */
object Tls {
    data class Cert(
        val subject: String, val issuer: String, val notBefore: Date, val notAfter: Date, val serial: String, val sigAlg: String,
        val key: String, val keyBits: Int, val sans: List<String>, val sha256: String, val sha1: String, val isCa: Boolean,
        val selfSigned: Boolean, val keyUsage: List<String>, val extKeyUsage: List<String>, val version: Int,
    )

    data class Result(
        val host: String, val port: Int, val ip: String, val protocol: String, val cipher: String, val alpn: String?, val chain: List<Cert>,
        val trusted: Boolean, val trustError: String?, val nameMatches: Boolean, val connectMs: Long, val handshakeMs: Long,
        val protocols: Map<String, Boolean?>, val findings: List<String>,
    )

    /** "example.com", "example.com:8443" or "https://example.com/path" → host and port. */
    fun target(input: String, defaultPort: Int = 443): Pair<String, Int>? {
        var s = input.trim().removePrefix("https://").removePrefix("http://").substringBefore('/').substringBefore('?')
        if (s.isEmpty()) return null
        if (s.startsWith("[")) { // [::1]:443
            val host = s.substringAfter('[').substringBefore(']')
            val port = s.substringAfter("]:", "").toIntOrNull() ?: defaultPort
            return host to port
        }
        if (s.count { it == ':' } == 1) { val port = s.substringAfter(':').toIntOrNull() ?: return null; s = s.substringBefore(':'); return s to port }
        return s to defaultPort
    }

    private val TRUST_ALL = arrayOf<TrustManager>(object : X509TrustManager {
        override fun checkClientTrusted(c: Array<X509Certificate>, a: String) {}
        override fun checkServerTrusted(c: Array<X509Certificate>, a: String) {}
        override fun getAcceptedIssuers() = emptyArray<X509Certificate>()
    })

    private fun handshake(host: String, ip: String, port: Int, timeoutMs: Int, protocols: Array<String>? = null): Triple<SSLSocket, Long, Long> {
        val t0 = System.nanoTime()
        val raw = Socket()
        raw.connect(InetSocketAddress(ip, port), timeoutMs)
        val t1 = System.nanoTime()
        raw.soTimeout = timeoutMs
        val ctx = SSLContext.getInstance("TLS").apply { init(null, TRUST_ALL, null) }
        val ssl = ctx.socketFactory.createSocket(raw, host, port, true) as SSLSocket
        val params = ssl.sslParameters
        if (host.any { it.isLetter() }) params.serverNames = listOf(SNIHostName(host))
        params.applicationProtocols = arrayOf("h2", "http/1.1")
        if (protocols != null) params.protocols = protocols
        ssl.sslParameters = params
        ssl.startHandshake()
        return Triple(ssl, (t1 - t0) / 1_000_000, (System.nanoTime() - t1) / 1_000_000)
    }

    fun inspect(host: String, port: Int, timeoutMs: Int = 5000): Result {
        val ip = java.net.InetAddress.getByName(host).hostAddress ?: host
        val (ssl, connectMs, handshakeMs) = handshake(host, ip, port, timeoutMs)
        ssl.use {
            val session = ssl.session
            val certs = session.peerCertificates.filterIsInstance<X509Certificate>()
            val alpn = runCatching { ssl.applicationProtocol?.takeIf { it.isNotEmpty() } }.getOrNull()
            // would the platform trust this chain?
            var trusted = true; var trustError: String? = null
            try {
                val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(null as KeyStore?) }
                val tm = tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
                val authType = when (certs.firstOrNull()?.publicKey?.algorithm) { "EC" -> "ECDHE_ECDSA"; else -> "RSA" }
                tm.checkServerTrusted(certs.toTypedArray(), authType)
            } catch (e: Exception) { trusted = false; trustError = (e.message ?: e.javaClass.simpleName).lines().first().take(200) }
            val nameOk = certs.firstOrNull()?.let { nameMatches(it, host) } ?: false
            val infos = certs.map { describe(it) }
            // which protocol versions it accepts, tried together; versions this phone's TLS stack lacks cannot be tested
            val protocols = probeProtocols(ssl.supportedProtocols.toSet(), timeoutMs) { p -> handshake(host, ip, port, timeoutMs, arrayOf(p)).first.use { true } }
            return Result(host, port, ip, session.protocol, session.cipherSuite, alpn, infos, trusted, trustError, nameOk, connectMs, handshakeMs, protocols, findings(infos, trusted, trustError, nameOk, session.protocol, protocols, host))
        }
    }

    /**
     * Tries each protocol version at the same time. true = the server accepted it, false = it refused, null = this phone
     * cannot offer that version so it was not tried. (A plain HashMap: ConcurrentHashMap rejects null values.)
     */
    fun probeProtocols(supported: Set<String>, timeoutMs: Int, attempt: (String) -> Boolean): Map<String, Boolean?> {
        val tests = listOf("TLSv1.3", "TLSv1.2", "TLSv1.1", "TLSv1")
        val results = java.util.Collections.synchronizedMap(HashMap<String, Boolean?>())
        val threads = tests.map { p ->
            Thread {
                results[p] = if (p !in supported) null else try { attempt(p) } catch (e: Throwable) { false }
            }.also { it.start() }
        }
        threads.forEach { it.join(timeoutMs + 1000L) }
        return tests.associateWith { results[it] }
    }

    private fun findings(chain: List<Cert>, trusted: Boolean, err: String?, nameOk: Boolean, protocol: String, protocols: Map<String, Boolean?>, host: String): List<String> {
        val out = ArrayList<String>()
        val leaf = chain.firstOrNull() ?: return listOf("The server sent no certificate")
        val now = Date()
        if (leaf.notAfter.before(now)) out += "The certificate expired on ${leaf.notAfter}"
        else {
            val days = (leaf.notAfter.time - now.time) / 86_400_000
            if (days < 14) out += "The certificate expires in $days days"
        }
        if (leaf.notBefore.after(now)) out += "The certificate is not valid yet (from ${leaf.notBefore})"
        if (!trusted) out += "Not trusted by this phone: ${err ?: "unknown reason"}"
        if (!nameOk) out += "The certificate does not list the name $host"
        if (leaf.selfSigned) out += "Self-signed certificate"
        if (leaf.sigAlg.contains("SHA1", true) || leaf.sigAlg.contains("MD5", true)) out += "Weak signature algorithm (${leaf.sigAlg})"
        if (leaf.key == "RSA" && leaf.keyBits in 1..2047) out += "RSA key shorter than 2048 bits (${leaf.keyBits})"
        if (protocols["TLSv1.1"] == true || protocols["TLSv1"] == true) out += "Accepts obsolete TLS 1.0/1.1"
        if (protocols["TLSv1.3"] == false && protocols["TLSv1.2"] == true) out += "No TLS 1.3 support"
        if (protocol == "TLSv1.2" && protocols["TLSv1.3"] == null) out += "Negotiated TLS 1.2 (this phone could not test 1.3)"
        return out
    }

    fun describe(c: X509Certificate): Cert {
        fun fp(alg: String) = MessageDigest.getInstance(alg).digest(c.encoded).joinToString(":") { "%02X".format(it) }
        val sans = runCatching {
            c.subjectAlternativeNames?.mapNotNull { e ->
                val v = e[1]?.toString() ?: return@mapNotNull null
                when (e[0] as Int) { 2 -> v; 7 -> "IP $v"; 1 -> "email $v"; 6 -> "URI $v"; else -> null }
            }
        }.getOrNull().orEmpty()
        val (keyName, bits) = keyOf(c.publicKey)
        val ku = c.keyUsage?.let { arr -> listOf("digitalSignature", "nonRepudiation", "keyEncipherment", "dataEncipherment", "keyAgreement", "keyCertSign", "cRLSign", "encipherOnly", "decipherOnly").filterIndexed { i, _ -> i < arr.size && arr[i] } }.orEmpty()
        val eku = runCatching { c.extendedKeyUsage }.getOrNull().orEmpty().map { mapOf("1.3.6.1.5.5.7.3.1" to "serverAuth", "1.3.6.1.5.5.7.3.2" to "clientAuth", "1.3.6.1.5.5.7.3.3" to "codeSigning", "1.3.6.1.5.5.7.3.4" to "emailProtection")[it] ?: it }
        val self = c.subjectX500Principal == c.issuerX500Principal && runCatching { c.verify(c.publicKey); true }.getOrDefault(false)
        return Cert(
            c.subjectX500Principal.name, c.issuerX500Principal.name, c.notBefore, c.notAfter, c.serialNumber.toString(16).uppercase(), c.sigAlgName,
            keyName, bits, sans, fp("SHA-256"), fp("SHA-1"), c.basicConstraints >= 0, self, ku, eku, c.version,
        )
    }

    private fun keyOf(k: PublicKey): Pair<String, Int> = when (k) {
        is RSAPublicKey -> "RSA" to k.modulus.bitLength()
        is ECPublicKey -> ("EC " + k.params.toString().substringBefore(" [").substringBefore(" (")) to k.params.curve.field.fieldSize
        else -> k.algorithm to 0
    }

    /** Does the certificate name this host? Subject alternative names first; the common name only when there are none. */
    fun nameMatches(c: X509Certificate, host: String): Boolean {
        val h = host.lowercase().trimEnd('.')
        val isIp = Regex("^[0-9.]+$").matches(h) || ':' in h
        val sans = runCatching { c.subjectAlternativeNames }.getOrNull()
        val names = sans?.filter { (it[0] as Int) == (if (isIp) 7 else 2) }?.mapNotNull { it[1]?.toString()?.lowercase() }.orEmpty()
        val candidates = if (names.isNotEmpty() || sans != null && sans.isNotEmpty()) names else
            listOfNotNull(Regex("CN=([^,]+)").find(c.subjectX500Principal.getName("RFC2253"))?.groupValues?.get(1)?.lowercase())
        return candidates.any { pattern ->
            when {
                pattern == h -> true
                !isIp && pattern.startsWith("*.") -> h.substringAfter('.', "") == pattern.removePrefix("*.") && h.count { it == '.' } == pattern.count { it == '.' }
                else -> false
            }
        }
    }
}
