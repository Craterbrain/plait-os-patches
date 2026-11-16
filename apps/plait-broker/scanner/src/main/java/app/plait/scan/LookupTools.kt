package app.plait.scan

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Chips(options: List<String>, selected: String, onSelect: (String) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { o -> FilterChip(selected = o == selected, onClick = { onSelect(o) }, label = { Text(o) }) }
    }
}

private fun copy(ctx: Context, label: String, text: String) {
    ctx.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
    Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
}

private fun systemDns(ctx: Context): List<String> = runCatching {
    val cm = ctx.getSystemService(ConnectivityManager::class.java)
    cm.getLinkProperties(cm.activeNetwork)?.dnsServers?.mapNotNull { it.hostAddress }.orEmpty()
}.getOrDefault(emptyList())

// ====================================================================== DNS lookup

private val RESOLVERS = linkedMapOf("Cloudflare" to "1.1.1.1", "Google" to "8.8.8.8", "Quad9" to "9.9.9.9", "OpenDNS" to "208.67.222.222")

@Composable
fun DnsTool(pad: PaddingValues) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val system = remember { systemDns(ctx) }
    var name by remember { mutableStateOf(ToolArgs.take() ?: "example.com") }
    var type by remember { mutableStateOf("A") }
    var server by remember { mutableStateOf("System") }
    var custom by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Dns.Answer?>(null) }
    var compare by remember { mutableStateOf<List<Pair<String, Result<Dns.Answer>>>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    DisposableEffect(Unit) { onDispose { job?.cancel() } }

    fun serverAddress(): String? = when (server) {
        "System" -> system.firstOrNull()
        "Custom" -> custom.trim().ifEmpty { null }
        else -> RESOLVERS[server]
    }
    /** The name as it is queried: an IP address becomes its reverse name (and the type PTR). */
    fun target(): Pair<String, Int>? {
        val n = name.trim()
        if (n.isEmpty()) return null
        val rev = Dns.reverseName(n)
        return if (rev != null && (Cidr.toInt(n) != null || ':' in n)) rev to 12 else n to (Dns.TYPES[type] ?: 1)
    }
    fun run(all: Boolean) {
        val (q, t) = target() ?: return
        error = null; running = true; result = null; compare = emptyList()
        job = scope.launch {
            try {
                if (all) {
                    val servers = buildList { system.firstOrNull()?.let { add("System ($it)" to it) }; RESOLVERS.forEach { (n, a) -> add("$n ($a)" to a) } }
                    compare = coroutineScope { servers.map { (label, addr) -> async(Dispatchers.IO) { label to runCatching { Dns.query(q, t, addr) } } }.awaitAll() }
                } else {
                    val addr = serverAddress() ?: run { error = "No server: pick one, or type an address under Custom"; return@launch }
                    result = withContext(Dispatchers.IO) { runCatching { Dns.query(q, t, addr) } }.getOrElse { error = it.message ?: it.javaClass.simpleName; null }
                }
            } finally { running = false }
        }
    }

    LazyColumn(Modifier.padding(pad).fillMaxWidth(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            InfoCard {
                ToolField("Name or IP address", name, { name = it })
                Text("Record type", style = MaterialTheme.typography.labelMedium)
                Chips(Dns.TYPES.keys.toList(), type) { type = it }
                Text("Server", style = MaterialTheme.typography.labelMedium)
                Chips(listOf("System") + RESOLVERS.keys + "Custom", server) { server = it }
                if (server == "System") Text(if (system.isEmpty()) "No system DNS server found" else system.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (server == "Custom") ToolField("Server address", custom, { custom = it })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (running) OutlinedButton(onClick = { job?.cancel() }) { Text("Stop") } else {
                        Button(onClick = { run(false) }, enabled = name.isNotBlank()) { Text("Look up") }
                        OutlinedButton(onClick = { run(true) }, enabled = name.isNotBlank()) { Text("Compare servers") }
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Text("Plain DNS on port 53, so a Private DNS setting is not used here. An IP address is looked up in reverse.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        result?.let { a -> item { AnswerCard(a) } }
        if (compare.isNotEmpty()) {
            item { Text("Same question, every server", style = MaterialTheme.typography.titleSmall) }
            items(compare, key = { it.first }) { (label, r) ->
                InfoCard(label) {
                    r.onSuccess { a ->
                        Text("${a.rcodeName} · ${a.ms} ms", style = MaterialTheme.typography.bodySmall)
                        if (a.answers.isEmpty()) Text("no records", style = MaterialTheme.typography.bodySmall)
                        a.answers.forEach { Mono("${it.typeName}  ${it.data}") }
                    }.onFailure { Text(it.message ?: "failed", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }
}

@Composable
private fun AnswerCard(a: Dns.Answer) {
    InfoCard("${a.rcodeName} · ${a.ms} ms") {
        Text("from ${a.server} over ${if (a.viaTcp) "TCP (the UDP answer was cut off)" else "UDP"} · ${a.size} bytes", style = MaterialTheme.typography.bodySmall)
        if (a.flags.isNotEmpty()) Text(a.flags.joinToString(", "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (a.answers.isEmpty() && a.rcode == 0) Text("The name exists but has no record of this type.", style = MaterialTheme.typography.bodySmall)
        if (a.rcode == 3) Text("That name does not exist (NXDOMAIN).", style = MaterialTheme.typography.bodySmall)
        if (a.rcode == 5) Text("The server refused: it may not answer outside its own network.", style = MaterialTheme.typography.bodySmall)
        Records("Answer", a.answers); Records("Authority", a.authority); Records("Additional", a.additional)
    }
}

@Composable
private fun Records(title: String, list: List<Dns.Record>) {
    if (list.isEmpty()) return
    Text(title, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 6.dp))
    list.forEach { r ->
        Column(Modifier.padding(vertical = 2.dp)) {
            Text("${r.name}  ${r.typeName}  ttl ${r.ttl}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Mono(r.data)
        }
    }
}

// ====================================================================== TLS / certificate inspector

private val DATE = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }

@Composable
fun TlsTool(pad: PaddingValues) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf(ToolArgs.take() ?: "example.com") }
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Tls.Result?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Job?>(null) }
    DisposableEffect(Unit) { onDispose { job?.cancel() } }
    fun inspect() {
        val t = Tls.target(input) ?: run { error = "Enter a host name, host:port or URL"; return }
        running = true; error = null; result = null
        job = scope.launch {
            try {
                result = withContext(Dispatchers.IO) { runCatching { Tls.inspect(t.first, t.second) } }.getOrElse { error = "${it.javaClass.simpleName}: ${it.message}"; null }
            } finally { running = false }
        }
    }
    LazyColumn(Modifier.padding(pad).fillMaxWidth(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            InfoCard {
                ToolField("Host, host:port or URL", input, { input = it })
                Row { listOf("example.com", "github.com", "mail.google.com:993").forEach { s -> TextButton(onClick = { input = s }) { Text(s) } } }
                if (running) OutlinedButton(onClick = { job?.cancel() }) { Text("Stop") } else Button(onClick = { inspect() }, enabled = input.isNotBlank()) { Text("Inspect") }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Text("Works with any TLS service (web, mail, VPN gateways). A broken certificate is still shown; the platform's trust store decides whether it would be trusted.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        result?.let { r ->
            item {
                InfoCard(if (r.trusted && r.nameMatches) "Trusted" else "Problems found") {
                    Text("${r.host}:${r.port} · ${r.ip}", style = MaterialTheme.typography.bodySmall)
                    Text("${r.protocol} · ${r.cipher}", style = MaterialTheme.typography.bodyMedium)
                    Text(listOfNotNull(r.alpn?.let { "ALPN $it" }, "connect ${r.connectMs} ms", "handshake ${r.handshakeMs} ms").joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                    r.findings.forEach { Text("⚠ $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    if (r.findings.isEmpty()) Text("No problems found.", style = MaterialTheme.typography.bodySmall)
                }
            }
            item {
                InfoCard("Protocol versions") {
                    r.protocols.forEach { (p, ok) ->
                        Text("$p: " + when (ok) { true -> "accepted"; false -> "refused"; null -> "this phone cannot offer it, so it was not tested" }, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            items(r.chain.size) { i -> CertCard(r.chain[i], i, r.chain.size) { copy(ctx, "fingerprint", it) } }
        }
    }
}

@Composable
private fun CertCard(c: Tls.Cert, index: Int, total: Int, onCopy: (String) -> Unit) {
    val days = (c.notAfter.time - System.currentTimeMillis()) / 86_400_000
    InfoCard(when { index == 0 -> "Certificate (the server's)"; index == total - 1 -> "Certificate ${index + 1} (top of the chain)"; else -> "Certificate ${index + 1} (intermediate)" }) {
        CertLine("Subject", c.subject); CertLine("Issuer", if (c.selfSigned) "${c.issuer} (self-signed)" else c.issuer)
        CertLine("Valid", "${DATE.format(c.notBefore)} → ${DATE.format(c.notAfter)} UTC\n" + if (days < 0) "expired ${-days} days ago" else "$days days left")
        if (c.sans.isNotEmpty()) CertLine("Names", c.sans.take(14).joinToString("\n") + if (c.sans.size > 14) "\n… and ${c.sans.size - 14} more" else "")
        CertLine("Key", if (c.keyBits > 0) "${c.key} · ${c.keyBits} bits" else c.key); CertLine("Signature", c.sigAlg)
        CertLine("Serial", c.serial); if (c.isCa) CertLine("Role", "certificate authority")
        if (c.keyUsage.isNotEmpty()) CertLine("Key usage", c.keyUsage.joinToString(", ")); if (c.extKeyUsage.isNotEmpty()) CertLine("Extended use", c.extKeyUsage.joinToString(", "))
        Text("SHA-256 (tap to copy)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Mono(c.sha256, Modifier.clickable { onCopy(c.sha256) })
        Text("SHA-1 (tap to copy)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Mono(c.sha1, Modifier.clickable { onCopy(c.sha1) })
    }
}

@Composable
private fun CertLine(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(84.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
    }
}

// ====================================================================== WHOIS and IP location

private class WhoisOutcome(
    val query: String, val addresses: List<String>, val geo: Result<Geo>?, val registration: Result<List<Pair<String, String>>>?, val raw: String?, val step: String = "",
)

@Composable
fun WhoisTool(pad: PaddingValues) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var input by remember { mutableStateOf(ToolArgs.take() ?: "example.com") }
    var running by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var out by remember { mutableStateOf<WhoisOutcome?>(null) }
    var showRaw by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    DisposableEffect(Unit) { onDispose { job?.cancel() } }

    fun lookup(mine: Boolean) {
        val q = input.trim().removePrefix("https://").removePrefix("http://").substringBefore('/')
        if (!mine && q.isEmpty()) return
        running = true; out = null; status = "Working…"
        job = scope.launch {
            try {
                if (mine) {
                    status = "Asking ipwho.is for this connection's public address…"
                    val g = withContext(Dispatchers.IO) { runCatching { GeoIp.lookup(null) } }
                    out = WhoisOutcome("your public address", listOfNotNull(g.getOrNull()?.ip), g, null, null)
                    return@launch
                }
                val isIp = Cidr.toInt(q) != null || ':' in q
                val addrs = if (isIp) listOf(q) else withContext(Dispatchers.IO) { runCatching { InetAddress.getAllByName(q).mapNotNull { it.hostAddress } }.getOrDefault(emptyList()) }
                coroutineScope {
                    // location, registration and the raw WHOIS text are fetched together
                    val geo = addrs.firstOrNull()?.let { ip -> async(Dispatchers.IO) { runCatching { GeoIp.lookup(ip) } } }
                    val reg = async(Dispatchers.IO) {
                        runCatching {
                            val j = Rdap.fetch("https://rdap.org/" + (if (isIp) "ip/" else "domain/") + q)
                            if (isIp) Rdap.ip(j) else Rdap.domain(j)
                        }
                    }
                    val raw = async(Dispatchers.IO) { runCatching { Whois.lookup(q) { status = it } }.getOrNull() }
                    out = WhoisOutcome(q, addrs, geo?.await(), reg.await(), raw.await())
                }
            } finally { running = false; status = "" }
        }
    }

    LazyColumn(Modifier.padding(pad).fillMaxWidth(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            InfoCard {
                ToolField("Domain or IP address", input, { input = it })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (running) OutlinedButton(onClick = { job?.cancel() }) { Text("Stop") } else {
                        Button(onClick = { lookup(false) }, enabled = input.isNotBlank()) { Text("Look up") }
                        OutlinedButton(onClick = { lookup(true) }) { Text("My public IP") }
                    }
                }
                if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)
                Text("Location comes from ipwho.is, which sees the address you look up. Registration comes from the registry's RDAP service and WHOIS servers.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        out?.let { o ->
            if (o.addresses.isNotEmpty() && o.query != "your public address" && o.addresses.first() != o.query) {
                item { InfoCard("Resolves to") { o.addresses.take(8).forEach { Mono(it) } } }
            }
            o.geo?.let { r ->
                item {
                    InfoCard("Location") {
                        r.onSuccess { g ->
                            if (g.error != null) Text(g.error, color = MaterialTheme.colorScheme.error) else {
                                Text(listOfNotNull(g.flag, g.country).joinToString(" "), style = MaterialTheme.typography.titleMedium)
                                Text(listOfNotNull(g.city, g.region, g.postal).joinToString(", "), style = MaterialTheme.typography.bodyMedium)
                                g.isp?.let { Text("Provider: $it", style = MaterialTheme.typography.bodySmall) }
                                if (g.org != null || g.asn != null) Text("Network: ${listOfNotNull(g.asn?.let { "AS$it" }, g.org).joinToString(" · ")}", style = MaterialTheme.typography.bodySmall)
                                g.timezone?.let { Text("Time zone: $it", style = MaterialTheme.typography.bodySmall) }
                                if (g.lat != null && g.lon != null) {
                                    Text("%.4f, %.4f (city level: this is where the provider is, not the device)".format(g.lat, g.lon), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    TextButton(onClick = {
                                        try { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("geo:${g.lat},${g.lon}?q=${g.lat},${g.lon}"))) }
                                        catch (e: Exception) { Toast.makeText(ctx, "No map app", Toast.LENGTH_SHORT).show() }
                                    }) { Text("Open in a map app") }
                                }
                            }
                        }.onFailure { Text("Location lookup failed: ${it.message}", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
            o.registration?.let { r ->
                item {
                    InfoCard("Registration (RDAP)") {
                        r.onSuccess { list ->
                            if (list.isEmpty()) Text("Nothing returned") else list.forEach { (k, v) ->
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text(k, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(96.dp))
                                    Text(v, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
                                }
                            }
                        }.onFailure { Text("RDAP did not answer (${it.message}). The raw WHOIS text below may still have it.", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
            o.raw?.let { raw ->
                item {
                    InfoCard("WHOIS (raw)") {
                        TextButton(onClick = { showRaw = !showRaw }) { Text(if (showRaw) "Hide" else "Show ${raw.lines().size} lines") }
                        if (showRaw) Mono(raw)
                    }
                }
            }
        }
    }
}
