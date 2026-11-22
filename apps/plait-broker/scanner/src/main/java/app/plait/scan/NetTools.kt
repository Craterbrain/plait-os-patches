package app.plait.scan

import app.plait.design.PlaitTonalButton
import app.plait.design.PlaitFilledButton
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
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
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

private val NOTHING = PaddingValues(0.dp)

// ====================================================================== ping

@Composable
fun PingTool(pad: PaddingValues) {
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf(ToolArgs.take() ?: LastScan.lan?.gateway ?: "1.1.1.1") }
    var count by remember { mutableIntStateOf(10) }
    var interval by remember { mutableStateOf("1") }
    var running by remember { mutableStateOf(false) }
    var stats by remember { mutableStateOf(PingStats()) }
    var tick by remember { mutableIntStateOf(0) }
    val lines = remember { mutableStateListOf<String>() }
    var job by remember { mutableStateOf<Job?>(null) }
    DisposableEffect(Unit) { onDispose { job?.cancel() } }

    fun start() {
        val st = PingStats()
        stats = st
        lines.clear()
        running = true
        val target = host.trim()
        val v6 = ':' in target
        val args = buildList {
            add(Proc.pingBinary(v6)); add("-n"); add("-O"); add("-W"); add("2"); add("-i"); add(interval)
            if (count > 0) { add("-c"); add("$count") }
            add(target)
        }
        job = scope.launch {
            try {
                Proc.run(*args.toTypedArray()) { l ->
                    when (val e = PingParse.line(l)) {
                        is PingEvent.Reply -> { st.reply(e.ms); lines += "#${e.seq}  ${ms(e.ms)}  ttl ${e.ttl ?: "?"}  ${e.bytes} bytes" }
                        is PingEvent.Missed -> { st.missed(); lines += "#${e.seq}  no reply" }
                        is PingEvent.Problem -> lines += e.text
                        null -> {}
                    }
                    if (lines.size > 300) lines.removeAt(0)
                    tick++
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                lines += "Could not run ping: ${e.message}"
            } finally { running = false }
        }
    }

    LazyColumn(Modifier.padding(pad).fillMaxWidth(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            InfoCard {
                ToolField("Host or IP", host, { host = it }, keyboard = androidx.compose.ui.text.input.KeyboardType.Uri)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LastScan.lan?.gateway?.let { g -> TextButton(onClick = { host = g }) { Text("Gateway") } }
                    TextButton(onClick = { host = "1.1.1.1" }) { Text("1.1.1.1") }
                    TextButton(onClick = { host = "8.8.8.8" }) { Text("8.8.8.8") }
                    TextButton(onClick = { host = "google.com" }) { Text("google.com") }
                }
                Text("Count", style = MaterialTheme.typography.labelMedium)
                ChipRow(listOf(4, 10, 50, 0), count, { if (it == 0) "∞" else "$it" }) { count = it }
                Text("Interval (seconds)", style = MaterialTheme.typography.labelMedium)
                ChipRow(listOf("0.2", "1", "2"), interval, { it }) { interval = it }
                if (running) PlaitTonalButton(onClick = { job?.cancel() }) { Text("Stop") } else PlaitFilledButton(onClick = { start() }, enabled = host.isNotBlank()) { Text("Ping") }
            }
        }
        item {
            tick // read so the numbers refresh with every reply
            InfoCard("Result") {
                val s = stats
                Text("${s.sent} sent · ${s.received} received · ${"%.0f".format(s.lossPercent)}% loss")
                Text("min ${ms(s.min)} · avg ${ms(s.avg)} · max ${ms(s.max)} · jitter ${ms(s.jitter)}")
                Sparkline(s.rtts.takeLast(60))
            }
        }
        items(lines.reversed().take(120)) { Mono(it) }
    }
}

// ====================================================================== traceroute

data class Hop(val ttl: Int, val ip: String?, val name: String? = null, val avg: Double? = null, val loss: Int? = null, val reached: Boolean = false)

object Trace {
    private val EXCEEDED = Regex("""From (\S+?):? icmp_seq=\d+ Time (?:to live )?exceeded""")
    private val REPLY = Regex("""bytes from (\S+?)(?: \([^)]*\))?: icmp_seq""")

    /** The address a TTL-limited ping got an answer from: the router that dropped it, or the target itself. */
    fun probeResult(ttl: Int, output: String): Hop =
        REPLY.find(output)?.let { Hop(ttl, it.groupValues[1], reached = true) }
            ?: EXCEEDED.find(output)?.let { Hop(ttl, it.groupValues[1]) }
            ?: Hop(ttl, null)

    /**
     * A traceroute that needs no raw sockets: one ping per TTL, all sent at once; each router that drops the packet names
     * itself in its "time exceeded" answer. Then each hop is pinged for its round-trip time, and named, all at once.
     */
    suspend fun run(destination: String, v6: Boolean, max: Int, publish: (List<Hop>, String) -> Unit) = coroutineScope {
        val bin = Proc.pingBinary(v6)
        val found = ConcurrentHashMap<Int, Hop>()
        fun view(): List<Hop> {
            val done = found.values.sortedBy { it.ttl }
            val dest = done.filter { it.reached }.minOfOrNull { it.ttl }
            return if (dest != null) done.filter { it.ttl <= dest } else done
        }
        suspend fun probe(ttl: Int): Hop = probeResult(ttl, Proc.text(bin, "-n", "-c", "1", "-W", "2", "-t", "$ttl", destination))
        publish(emptyList(), "Probing $max hops at once…")
        (1..max).map { ttl -> async { found[ttl] = probe(ttl); publish(view(), "Probing…") } }.awaitAll()
        // a silent hop is often just one lost packet: ask the silent ones once more
        val reached = view().firstOrNull { it.reached }?.ttl
        val silent = (1..(reached ?: max)).filter { found[it]?.ip == null }
        if (silent.isNotEmpty()) {
            publish(view(), "Asking ${silent.size} silent hops again…")
            silent.map { ttl -> async { probe(ttl).let { if (it.ip != null) found[ttl] = it } } }.awaitAll()
        }
        publish(view(), "Timing each hop…")
        view().filter { it.ip != null }.map { h ->
            async {
                val out = Proc.text(bin, "-n", "-c", "3", "-i", "0.2", "-W", "1", h.ip!!)
                val rtts = out.lines().mapNotNull { (PingParse.line(it) as? PingEvent.Reply)?.ms }
                found[h.ttl] = found[h.ttl]!!.copy(avg = rtts.takeIf { it.isNotEmpty() }?.average(), loss = (3 - rtts.size) * 100 / 3)
                publish(view(), "Timing each hop…")
            }
        }.awaitAll()
        publish(view(), "Looking up names…")
        view().filter { it.ip != null }.map { h ->
            async {
                val n = withTimeoutOrNull(3000) { withContext(Dispatchers.IO) { runCatching { InetAddress.getByName(h.ip).canonicalHostName }.getOrNull() } }
                if (n != null && n != h.ip) found[h.ttl] = found[h.ttl]!!.copy(name = n)
                publish(view(), "Looking up names…")
            }
        }.awaitAll()
        val last = view()
        publish(last, if (last.any { it.reached }) "Reached the host in ${last.size} hops" else "Did not reach the host (it may not answer ping, or a router drops it)")
    }
}

@Composable
fun TraceTool(pad: PaddingValues) {
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf(ToolArgs.take() ?: "1.1.1.1") }
    var running by remember { mutableStateOf(false) }
    var hops by remember { mutableStateOf(emptyList<Hop>()) }
    var status by remember { mutableStateOf("") }
    var job by remember { mutableStateOf<Job?>(null) }
    DisposableEffect(Unit) { onDispose { job?.cancel() } }
    fun start() {
        running = true; hops = emptyList(); status = "Resolving…"
        val target = host.trim()
        job = scope.launch {
            try {
                val addr = withContext(Dispatchers.IO) { runCatching { InetAddress.getByName(target) }.getOrNull() }
                if (addr == null) { status = "Cannot find $target"; return@launch }
                val ip = addr.hostAddress ?: target
                Trace.run(ip, addr is java.net.Inet6Address, 30) { h, s -> hops = h; status = s }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                status = "Could not run ping to trace the route: ${e.message}"
            } finally { running = false }
        }
    }
    LazyColumn(Modifier.padding(pad).fillMaxWidth(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            InfoCard {
                ToolField("Host or IP", host, { host = it })
                Row { TextButton(onClick = { host = "1.1.1.1" }) { Text("1.1.1.1") }; TextButton(onClick = { host = "google.com" }) { Text("google.com") } }
                if (running) PlaitTonalButton(onClick = { job?.cancel() }) { Text("Stop") } else PlaitFilledButton(onClick = { start() }, enabled = host.isNotBlank()) { Text("Trace") }
                if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)
            }
        }
        items(hops, key = { it.ttl }) { h ->
            InfoCard {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("${h.ttl}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.width(28.dp))
                    androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                        if (h.ip == null) Text("* no answer", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else {
                            Text(h.name ?: h.ip, style = MaterialTheme.typography.bodyMedium)
                            if (h.name != null) Mono(h.ip)
                        }
                    }
                    if (h.ip != null) Text(if (h.avg != null) ms(h.avg) else if (h.loss == 100) "no ping reply" else "…", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (hops.isNotEmpty()) item { Text("Routers that do not answer ping show no time; the hop itself still forwards your traffic.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

// ====================================================================== path MTU

@Composable
fun MtuTool(pad: PaddingValues) {
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf("1.1.1.1") }
    var running by remember { mutableStateOf(false) }
    var step by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<Mtu.Result?>(null) }
    var v6 by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    DisposableEffect(Unit) { onDispose { job?.cancel() } }
    fun start() {
        running = true; result = null; step = "Checking the host answers…"
        val target = host.trim()
        val six = v6 || ':' in target
        val bin = Proc.pingBinary(six)
        job = scope.launch {
            try {
                result = Mtu.discover(six, { sizes ->
                    coroutineScope { sizes.map { size -> async { Mtu.classify(Proc.text(bin, "-n", "-c", "1", "-W", "2", "-M", "do", "-s", "$size", target)) } }.awaitAll() }
                }) { step = it }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                result = Mtu.Result(null, null, null, "Could not run ping: ${e.message}")
            } finally { running = false; step = "" }
        }
    }
    LazyColumn(Modifier.padding(pad).fillMaxWidth(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            InfoCard {
                ToolField("Host or IP", host, { host = it })
                ChipRow(listOf(false, true), v6, { if (it) "IPv6" else "IPv4" }) { v6 = it }
                if (running) PlaitTonalButton(onClick = { job?.cancel() }) { Text("Stop") } else PlaitFilledButton(onClick = { start() }, enabled = host.isNotBlank()) { Text("Find path MTU") }
                if (step.isNotEmpty()) Text(step, style = MaterialTheme.typography.bodySmall)
            }
        }
        result?.let { r ->
            item {
                InfoCard("Result") {
                    if (r.mtu == null) Text(r.note) else {
                        val six = v6 || ':' in host
                        Text("Path MTU: ${r.mtu} bytes", style = MaterialTheme.typography.titleMedium)
                        Text("Largest ping payload that fits: ${r.payload} bytes (plus ${if (six) 48 else 28} bytes of headers)")
                        Text("TCP segments up to ${r.mtu - (if (six) 60 else 40)} bytes")
                        r.limitedBy?.let { Text("Reported by: $it", style = MaterialTheme.typography.bodySmall) }
                        Text(
                            when {
                                r.mtu >= 1500 -> "Standard Ethernet or better: nothing on the path shrinks packets."
                                r.mtu >= 1492 -> "1492 is typical of PPPoE (DSL and some fibre)."
                                r.mtu >= 1400 -> "Slightly below 1500: usually a tunnel or VPN (WireGuard, GRE, IPsec) on the path."
                                else -> "Well below 1500: a restrictive tunnel or link on the path. Large downloads that stall often point here."
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

// ====================================================================== Wake on LAN

private object WolStore {
    fun load(ctx: Context): List<Pair<String, String>> = runCatching {
        val a = JSONArray(ctx.getSharedPreferences("wol", Context.MODE_PRIVATE).getString("saved", "[]"))
        (0 until a.length()).map { a.getJSONObject(it).let { o -> o.getString("name") to o.getString("mac") } }
    }.getOrDefault(emptyList())

    fun save(ctx: Context, list: List<Pair<String, String>>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("name", it.first).put("mac", it.second)) }
        ctx.getSharedPreferences("wol", Context.MODE_PRIVATE).edit().putString("saved", a.toString()).apply()
    }
}

private suspend fun sendMagic(ctx: Context, mac: ByteArray): String = withContext(Dispatchers.IO) {
    val pkt = Wol.magicPacket(mac)
    val net = (LastScan.lan ?: Scanner.lan(ctx))?.hosts?.firstOrNull()?.substringBeforeLast('.')
    val targets = listOfNotNull(net?.let { "$it.255" }, "255.255.255.255").distinct()
    DatagramSocket().use { s ->
        s.broadcast = true
        for (t in targets) for (port in listOf(9, 7)) s.send(DatagramPacket(pkt, pkt.size, InetAddress.getByName(t), port))
    }
    "Magic packet sent to ${targets.joinToString(" and ")} (ports 9 and 7)"
}

@Composable
fun WolTool(pad: PaddingValues) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var saved by remember { mutableStateOf(WolStore.load(ctx)) }
    var name by remember { mutableStateOf("") }
    var mac by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("") }
    fun wake(m: String) {
        val bytes = Wol.parseMac(m)
        if (bytes == null) { status = "That is not a MAC address"; return }
        scope.launch { status = runCatching { sendMagic(ctx, bytes) }.getOrElse { "Could not send: ${it.message}" } }
    }
    LazyColumn(Modifier.padding(pad).fillMaxWidth(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            InfoCard("Wake a device") {
                ToolField("MAC address", mac, { mac = it }, placeholder = "aa:bb:cc:dd:ee:ff")
                ToolField("Name (to save it)", name, { name = it }, keyboard = androidx.compose.ui.text.input.KeyboardType.Text)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PlaitFilledButton(onClick = { wake(mac) }, enabled = mac.isNotBlank()) { Text("Wake") }
                    PlaitTonalButton(onClick = {
                        val b = Wol.parseMac(mac)
                        if (b == null) status = "That is not a MAC address" else {
                            saved = saved.filter { it.second != Wol.format(b) } + ((name.ifBlank { Wol.format(b) }) to Wol.format(b))
                            WolStore.save(ctx, saved); status = "Saved"
                        }
                    }, enabled = mac.isNotBlank()) { Text("Save") }
                }
                if (status.isNotEmpty()) Text(status, style = MaterialTheme.typography.bodySmall)
                Text("The device must be set to wake on LAN (in its BIOS and network settings) and be on the same network. Wi-Fi devices rarely support it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (saved.isNotEmpty()) {
            item { Text("Saved", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp)) }
            items(saved, key = { "s" + it.second }) { (n, m) ->
                InfoCard {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) { Text(n, style = MaterialTheme.typography.titleSmall); Mono(m) }
                        PlaitFilledButton(onClick = { wake(m) }) { Text("Wake") }
                        TextButton(onClick = { saved = saved.filter { it.second != m }; WolStore.save(ctx, saved) }) { Text("Delete") }
                    }
                }
            }
        }
        val scan = LastScan.hosts.filter { !it.self && it.mac != null && it.alias == null }
        if (scan.isNotEmpty()) {
            item { Text("From the last network scan", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 4.dp)) }
            items(scan, key = { "h" + it.ip }) { h ->
                InfoCard {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
                            Text(h.identity?.name?.takeIf { it.isNotBlank() } ?: h.name?.substringBefore('.') ?: h.vendor?.takeUnless { h.randomized } ?: h.ip, style = MaterialTheme.typography.titleSmall)
                            Mono("${h.ip}  ${h.mac}")
                        }
                        PlaitFilledButton(onClick = { wake(h.mac!!) }) { Text("Wake") }
                    }
                }
            }
        }
    }
}

// ====================================================================== subnet calculator

@Composable
fun SubnetTool(pad: PaddingValues) {
    var input by remember { mutableStateOf(LastScan.lan?.let { "${it.self}/${maxOf(it.prefix, 24)}" } ?: "192.168.1.0/24") }
    var splitTo by remember { mutableStateOf("") }
    var probe by remember { mutableStateOf("") }
    val v4 = remember(input) { Cidr.parseV4(input) }
    val v6 = remember(input) { if (':' in input) Cidr.parseV6(input) else null }
    LazyColumn(Modifier.padding(pad).fillMaxWidth(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            InfoCard {
                ToolField("Address / prefix, or address and mask", input, { input = it }, placeholder = "192.168.1.0/24 or 2001:db8::/32")
                if (v4 == null && v6 == null && input.isNotBlank()) Text("Not a valid IPv4 or IPv6 network", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
        if (v4 != null) {
            item {
                InfoCard("${v4.network}/${v4.prefix}") {
                    Fact2("Netmask", v4.mask); Fact2("Wildcard", v4.wildcard); Fact2("Network", v4.network); Fact2("Broadcast", v4.broadcast)
                    Fact2("First host", v4.first); Fact2("Last host", v4.last)
                    Fact2("Usable hosts", "%,d".format(v4.hosts)); Fact2("Addresses", "%,d".format(v4.total))
                    Fact2("Class", v4.kind); Fact2("Scope", v4.scope)
                    Fact2("Address (bin)", v4.binaryAddress); Fact2("Mask (bin)", v4.binaryMask)
                }
            }
            item {
                InfoCard("Is an address inside?") {
                    ToolField("Address", probe, { probe = it })
                    val a = Cidr.toInt(probe)
                    if (probe.isNotBlank()) {
                        val inside = a != null && Cidr.parseV4("${probe.trim()}/32")?.let { p ->
                            val pv = Cidr.toInt(p.address)!!.toLong() and 0xFFFFFFFFL
                            pv >= (Cidr.toInt(v4.network)!!.toLong() and 0xFFFFFFFFL) && pv <= (Cidr.toInt(v4.broadcast)!!.toLong() and 0xFFFFFFFFL)
                        } == true
                        Text(if (a == null) "Not an IPv4 address" else if (inside) "Yes: ${probe.trim()} is in ${v4.network}/${v4.prefix}" else "No: ${probe.trim()} is outside ${v4.network}/${v4.prefix}")
                    }
                }
            }
            item {
                InfoCard("Split into smaller subnets") {
                    ToolField("New prefix length", splitTo, { splitTo = it.filter(Char::isDigit).take(2) }, keyboard = androidx.compose.ui.text.input.KeyboardType.Number, placeholder = "${minOf(32, v4.prefix + 2)}")
                    val p = splitTo.toIntOrNull()
                    if (p != null) {
                        val parts = Cidr.split(v4, p)
                        if (parts.isEmpty()) Text("The new prefix must be longer than /${v4.prefix} and at most /32")
                        else {
                            val total = 1L shl (p - v4.prefix)
                            Text("$total subnets of ${"%,d".format(1L shl (32 - p))} addresses" + if (total > parts.size) " (first ${parts.size} shown)" else "", style = MaterialTheme.typography.bodySmall)
                            parts.forEach { Mono(it) }
                        }
                    }
                }
            }
        }
        if (v6 != null) {
            item {
                InfoCard("${v6.network}/${v6.prefix}") {
                    Fact2("Address", v6.address); Fact2("Network", v6.network); Fact2("First", v6.first); Fact2("Last", v6.last)
                    Fact2("Addresses", v6.total); Fact2("Kind", v6.kind)
                }
            }
        }
    }
}

@Composable
private fun Fact2(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(104.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
    }
}
