package app.plait.scan

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

/** WHOIS (port 43, following the registry's referrals) and RDAP (its JSON successor), plus IP geolocation. */
object Whois {
    fun raw(server: String, query: String, timeoutMs: Int = 6000): String = Socket().use { s ->
        s.connect(InetSocketAddress(server, 43), timeoutMs)
        s.soTimeout = timeoutMs
        s.getOutputStream().apply { write("$query\r\n".toByteArray()); flush() }
        s.getInputStream().readNBytes(256 * 1024).toString(Charsets.UTF_8)
    }

    /** The server a WHOIS answer points to: IANA's "refer:", or a registry's "Registrar WHOIS Server:". */
    fun referral(text: String): String? =
        Regex("""(?im)^\s*(?:refer|whois|registrar whois server):\s*([A-Za-z0-9.-]+)\s*$""").find(text)?.groupValues?.get(1)?.lowercase()

    /** IANA first, then whoever it names, then the registrar the registry names; at most three servers. */
    fun lookup(query: String, onStep: (String) -> Unit = {}): String {
        val out = StringBuilder()
        var server = "whois.iana.org"
        val seen = HashSet<String>()
        repeat(3) {
            if (!seen.add(server)) return@repeat
            onStep("Asking $server…")
            val text = runCatching { raw(server, query) }.getOrElse { "(could not reach $server: ${it.message})" }
            out.appendLine("# $server").appendLine(text.trim()).appendLine()
            val next = referral(text)
            if (next == null || next == server) return out.toString().trim()
            server = next
        }
        return out.toString().trim()
    }
}

object Rdap {
    fun fetch(url: String, timeoutMs: Int = 8000): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs; c.instanceFollowRedirects = true
        c.setRequestProperty("Accept", "application/rdap+json, application/json")
        if (c.responseCode !in 200..299) throw java.io.IOException("RDAP answered ${c.responseCode}")
        return JSONObject(c.inputStream.use { it.readNBytes(1024 * 1024) }.toString(Charsets.UTF_8))
    }

    private fun vcardName(entity: JSONObject): String? {
        val props = entity.optJSONArray("vcardArray")?.optJSONArray(1) ?: return null
        for (i in 0 until props.length()) {
            val p = props.optJSONArray(i) ?: continue
            if (p.optString(0) == "fn") return p.optString(3).ifBlank { null }
        }
        return null
    }

    private fun events(j: JSONObject): List<Pair<String, String>> {
        val a = j.optJSONArray("events") ?: return emptyList()
        return (0 until a.length()).map { a.getJSONObject(it) }.map { it.optString("eventAction") to it.optString("eventDate").substringBefore('T') }
    }

    private fun entities(j: JSONObject): List<Pair<String, String>> {
        val a = j.optJSONArray("entities") ?: return emptyList()
        val out = ArrayList<Pair<String, String>>()
        for (i in 0 until a.length()) {
            val e = a.getJSONObject(i)
            val roles = e.optJSONArray("roles")?.let { r -> (0 until r.length()).map { r.getString(it) } }.orEmpty()
            val name = vcardName(e) ?: e.optString("handle").ifBlank { null } ?: continue
            out += (roles.joinToString(", ").ifEmpty { "entity" }) to name
            // registrars often hold the abuse contact one level down
            e.optJSONArray("entities")?.let { sub ->
                for (k in 0 until sub.length()) {
                    val s = sub.getJSONObject(k); val sn = vcardName(s) ?: continue
                    val sr = s.optJSONArray("roles")?.let { r -> (0 until r.length()).map { r.getString(it) } }.orEmpty().joinToString(", ")
                    if (sr.isNotEmpty()) out += sr to sn
                }
            }
        }
        return out
    }

    fun domain(j: JSONObject): List<Pair<String, String>> = buildList {
        add("Name" to j.optString("ldhName").ifBlank { j.optString("unicodeName") })
        j.optJSONArray("status")?.let { a -> add("Status" to (0 until a.length()).joinToString(", ") { a.getString(it) }) }
        events(j).forEach { (a, d) -> add(a.replaceFirstChar { it.uppercase() } to d) }
        addAll(entities(j))
        j.optJSONArray("nameservers")?.let { a -> add("Name servers" to (0 until a.length()).joinToString("\n") { a.getJSONObject(it).optString("ldhName").lowercase() }) }
        j.optJSONObject("secureDNS")?.let { add("DNSSEC" to if (it.optBoolean("delegationSigned")) "signed" else "not signed") }
    }

    fun ip(j: JSONObject): List<Pair<String, String>> = buildList {
        add("Network" to j.optString("name"))
        add("Handle" to j.optString("handle"))
        val cidrs = j.optJSONArray("cidr0_cidrs")?.let { a -> (0 until a.length()).joinToString(", ") { a.getJSONObject(it).let { c -> (c.optString("v4prefix").ifEmpty { c.optString("v6prefix") }) + "/" + c.optInt("length") } } }
        add("Range" to (cidrs?.takeIf { it.isNotEmpty() } ?: "${j.optString("startAddress")} – ${j.optString("endAddress")}"))
        j.optString("country").takeIf { it.isNotBlank() }?.let { add("Country" to it) }
        j.optString("type").takeIf { it.isNotBlank() }?.let { add("Type" to it) }
        events(j).forEach { (a, d) -> add(a.replaceFirstChar { it.uppercase() } to d) }
        addAll(entities(j))
    }.filter { it.second.isNotBlank() }
}

data class Geo(
    val ip: String, val country: String?, val flag: String?, val region: String?, val city: String?, val postal: String?,
    val lat: Double?, val lon: Double?, val timezone: String?, val isp: String?, val org: String?, val asn: Long?, val domain: String?, val error: String?,
)

object GeoIp {
    fun parse(j: JSONObject): Geo {
        if (!j.optBoolean("success", true)) return Geo(j.optString("ip"), null, null, null, null, null, null, null, null, null, null, null, null, j.optString("message", "the lookup failed"))
        val c = j.optJSONObject("connection")
        return Geo(
            j.optString("ip"), j.optString("country").ifBlank { null }, j.optJSONObject("flag")?.optString("emoji")?.ifBlank { null },
            j.optString("region").ifBlank { null }, j.optString("city").ifBlank { null }, j.optString("postal").ifBlank { null },
            if (j.has("latitude")) j.optDouble("latitude") else null, if (j.has("longitude")) j.optDouble("longitude") else null,
            j.optJSONObject("timezone")?.optString("id")?.ifBlank { null }, c?.optString("isp")?.ifBlank { null }, c?.optString("org")?.ifBlank { null },
            c?.optLong("asn")?.takeIf { it != 0L }, c?.optString("domain")?.ifBlank { null }, null,
        )
    }

    /** [ip] null asks about this connection's own public address. */
    fun lookup(ip: String?): Geo = parse(Rdap.fetch("https://ipwho.is/" + (ip ?: "")))
}
