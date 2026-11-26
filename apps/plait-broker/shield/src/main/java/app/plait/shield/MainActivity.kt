package app.plait.shield

import app.plait.design.PlaitCard
import app.plait.design.SettingsGroup
import app.plait.design.PlaitSwitch
import app.plait.design.PlaitTopBar
import app.plait.design.SectionLabel
import app.plait.design.TonalTile
import app.plait.design.StateChip
import app.plait.design.Tone
import app.plait.design.PlaitTonalButton
import app.plait.design.PlaitFilledButton
import app.plait.design.PlaitIcon
import android.content.Context
import android.net.ConnectivityManager
import android.net.DnsResolver
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.CancellationSignal
import android.text.format.DateUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import app.plait.design.PlaitTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

class MainActivity : ComponentActivity() {
    private val refreshTick = mutableStateOf(0)
    private val onBinder = Shizuku.OnBinderReceivedListener { refreshTick.value++ }
    private val onPermission = Shizuku.OnRequestPermissionResultListener { _, _ -> refreshTick.value++ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Shizuku.addBinderReceivedListenerSticky(onBinder)
        Shizuku.addRequestPermissionResultListener(onPermission)
        setContent { ShieldTheme { ShieldApp(refreshTick.value) } }
    }

    override fun onDestroy() {
        Shizuku.removeBinderReceivedListener(onBinder)
        Shizuku.removeRequestPermissionResultListener(onPermission)
        super.onDestroy()
    }
}

@Composable
fun ShieldTheme(content: @Composable () -> Unit) = PlaitTheme(content = content)

private const val PREFS = "shield"

internal data class ShieldFixture(val dns: PrivateDns, val hostsLines: Int)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ShieldApp(tick: Int, fixture: ShieldFixture? = null) {
    val context = LocalContext.current
    val prefs = remember { if (fixture == null) context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) else null }
    val scope = rememberCoroutineScope()
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    var running by remember { mutableStateOf(fixture != null) }
    var allowed by remember { mutableStateOf(fixture != null) }
    var dns by remember { mutableStateOf(fixture?.dns) }
    var hostsLines by remember { mutableStateOf(fixture?.hostsLines) }
    var busy by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var selected by remember {
        mutableStateOf(prefs?.getStringSet("lists", null) ?: BLOCKLISTS.filter { it.onByDefault }.map { it.id }.toSet())
    }
    var allowText by remember { mutableStateOf(prefs?.getString("allow", "").orEmpty()) }
    var custom by remember { mutableStateOf("") }
    var active by remember { mutableStateOf((fixture != null || prefs?.getBoolean(Updater.ACTIVE, false) == true)) }
    var checkedAt by remember { mutableStateOf(prefs?.getLong(Updater.CHECKED_AT, 0L) ?: 0L) }
    var lastResult by remember { mutableStateOf(prefs?.getString(Updater.RESULT, null)) }

    suspend fun refresh() = withContext(Dispatchers.IO) {
        if (prefs == null) return@withContext
        running = Broker.running()
        allowed = Broker.allowed()
        if (allowed) {
            dns = runCatching { PrivateDns.read() }.getOrNull()
            hostsLines = Updater.hostsLines()
            // Blocklists applied by a Shield from before daily updates: take the current choice as what is applied
            if (!prefs.contains(Updater.ACTIVE) && (hostsLines ?: 0) > 3) {
                prefs.edit().putBoolean(Updater.ACTIVE, true).putStringSet(Updater.APPLIED_LISTS, selected)
                    .putString(Updater.APPLIED_ALLOW, allowText).apply()
            }
        }
        active = prefs.getBoolean(Updater.ACTIVE, false)
        checkedAt = prefs.getLong(Updater.CHECKED_AT, 0L)
        lastResult = prefs.getString(Updater.RESULT, null)
        if (active) UpdateJob.schedule(context) else UpdateJob.cancel(context)
    }

    LaunchedEffect(tick) { refresh() }
    LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { refresh() } }

    fun setDns(mode: String, host: String?) {
        if (fixture != null) {
            dns = PrivateDns(mode, host)
            return
        }
        scope.launch {
            busy = "Switching DNS…"
            message = withContext(Dispatchers.IO) {
                runCatching { switchDns(context, mode, host) }.getOrElse { "DNS not changed: ${it.message}" }
            }
            busy = null
            refresh()
        }
    }

    fun applyLists(off: Boolean) {
        if (fixture != null) return
        scope.launch {
            busy = if (off) "Turning off…" else "Checking lists…"
            message = withContext(Dispatchers.IO) {
                runCatching {
                    if (off) Updater.turnOff(context)
                    // Apply always writes (force); unchanged lists come from the saved copies, so it is quick
                    else Updater.apply(context, BLOCKLISTS.filter { it.id in selected }, allowText, force = true) { busy = it }
                }.getOrElse { "Failed: ${it.message}" }
            }
            busy = null
            refresh()                                       // schedules or cancels the daily update to match
        }
    }

    Scaffold(topBar = { PlaitTopBar("Shield", Modifier.statusBarsPadding(), showAppVersion = true) }) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                StatusCard(running, allowed, dns, hostsLines, busy, message) { if (fixture == null) Broker.requestAccess() }
            }
            if (!allowed) return@LazyColumn

            item { SectionLabel("DNS provider") }
            item {
                Text(
                    "Private DNS for every app. If the phone cannot reach a provider, Shield restores your previous setting.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
            }
            item {
                DnsPicker(dns, custom, { custom = it }) { mode, host -> setDns(mode, host) }
            }

            item { SectionLabel("Blocklists") }
            item {
                Text(
                    "Shield merges selected blocklists into the phone’s hosts file through Broker. " +
                        "They work with any DNS provider.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
            }
            item(key = "blocklists") {
                // One group, as every Plait list: rows with hairlines instead of a card per list
                SettingsGroup(inset = 14.dp, sidePadding = 0.dp) {
                    BLOCKLISTS.forEach { l ->
                        row {
                            ListToggleRow(l, l.id in selected) { on ->
                                selected = if (on) selected + l.id else selected - l.id
                                prefs?.edit()?.putStringSet("lists", selected)?.apply()
                            }
                        }
                    }
                }
            }
            item {
                PlaitCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Never block", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Names (and their subdomains) to leave out, separated by spaces or commas",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            allowText, { allowText = it; prefs?.edit()?.putString("allow", it)?.apply() },
                            modifier = Modifier.fillMaxWidth(), minLines = 2, placeholder = { Text("example.com") }
                        )
                    }
                }
            }
            item {
                Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PlaitFilledButton(onClick = { applyLists(false) }, enabled = busy == null && selected.isNotEmpty()) { Text("Apply blocklists") }
                    PlaitTonalButton(onClick = { applyLists(true) }, enabled = busy == null) { Text("Turn off") }
                }
            }
            item {
                Text(
                    if (!active) "Applied lists are saved on the phone and updated daily on Wi-Fi."
                    else buildString {
                        append("Updated daily on Wi-Fi from saved copies; only changed lists are downloaded.")
                        if (checkedAt > 0) {
                            append("\nLast check ")
                            append(DateUtils.getRelativeTimeSpanString(checkedAt, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS))
                            lastResult?.let { append(": ").append(it) }
                        }
                    },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
            }
        }
    }
}

/**
 * Sets Private DNS and makes sure lookups still work: strict mode with an unreachable provider leaves the phone without
 * DNS. If a fresh lookup does not succeed within about 20 seconds, the previous setting goes back.
 */
private suspend fun switchDns(context: Context, mode: String, host: String?): String {
    val before = PrivateDns.read() ?: return "Could not read the current DNS setting through Broker"
    val r = PrivateDns.set(mode, host)
    if (!r.ok) return "Not changed: ${r.err.removePrefix("plait-broker: ")}"
    if (mode != "hostname") return "DNS: ${PrivateDns(mode, host).description}"
    repeat(4) {
        delay(5_000)
        if (lookupWorks(context)) return "DNS: ${PrivateDns(mode, host).description}"
    }
    PrivateDns.set(before.mode, before.host)
    return "$host could not be reached from this network (it may block encrypted DNS). Your previous setting is back."
}

/** A fresh lookup through the active network succeeds. Any error counts as "no", so the caller puts DNS back. */
private suspend fun lookupWorks(context: Context): Boolean = try {
    freshLookup(context)
} catch (e: Exception) {
    false
}

private suspend fun freshLookup(context: Context): Boolean {
    val cm = context.getSystemService(ConnectivityManager::class.java)
    val network = cm.activeNetwork ?: return false
    if (cm.getNetworkCapabilities(network)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) != true) return false
    return withTimeoutOrNull(8_000) {
        suspendCancellableCoroutine { cont ->
            val signal = CancellationSignal()
            cont.invokeOnCancellation { signal.cancel() }
            DnsResolver.getInstance().query(
                network, "www.google.com", DnsResolver.FLAG_NO_CACHE_LOOKUP, Executors.newSingleThreadExecutor(), signal,
                object : DnsResolver.Callback<List<java.net.InetAddress>> {
                    override fun onAnswer(answer: List<java.net.InetAddress>, rcode: Int) {
                        if (cont.isActive) cont.resume(answer.isNotEmpty())
                    }

                    override fun onError(error: DnsResolver.DnsException) {
                        if (cont.isActive) cont.resume(false)
                    }
                }
            )
        }
    } ?: false
}

// ------------------------------------------------------------------ pieces

@Composable
private fun StatusCard(
    running: Boolean, allowed: Boolean, dns: PrivateDns?, hostsLines: Int?, busy: String?, message: String?, onConnect: () -> Unit
) {
    PlaitCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when {
                !running -> {
                    Text("Broker is not running", style = MaterialTheme.typography.titleMedium)
                    Text("Shield changes DNS and the blocklist only through Broker.", style = MaterialTheme.typography.bodySmall)
                }
                !allowed -> {
                    Text("Connect to Broker", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Broker will list what Shield declares it uses: reading and setting Private DNS, and writing the " +
                            "blocklist. Nothing else is switched on.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    PlaitFilledButton(onClick = onConnect) { Text("Connect") }
                }
                else -> {
                    Text("DNS", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(dns?.description ?: "…", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.size(4.dp))
                    Text("Blocklist", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        hostsLines?.let { if (it > 3) "%,d lines in the hosts file".format(it) else "Off" } ?: "…",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
            (busy ?: message)?.let {
                Spacer(Modifier.size(4.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

/** The badges that describe a provider, in the order they are shown. */
private fun badges(p: Provider): List<Pair<String, Boolean?>> = buildList {
    if (p.group == Group.FAMILY) add(if (p.safeSearch) "Safe search" to true else "No safe search" to false)
    if (p.youtubeRestricted) add("YouTube restricted" to true)
    if (p.blocksAdult && p.group != Group.FAMILY) add("Adult" to null)
    if (p.blocksAds) add("Ads" to null)
    if (p.blocksMalware) add("Malware" to null)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Badges(p: Provider, compact: Boolean) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp), verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 6.dp)) {
        badges(p).forEach { (text, good) -> Tag(text, good, compact) }
    }
}

/**
 * The DNS provider as a drop-down: the card shows the current choice with its badges, and tapping it opens the list,
 * grouped, each entry with the same badges in a smaller size.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DnsPicker(dns: PrivateDns?, custom: String, onCustom: (String) -> Unit, onPick: (String, String?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    var customOpen by remember { mutableStateOf(false) }
    val current = dns?.takeIf { it.mode == "hostname" }?.let { d -> PROVIDERS.firstOrNull { it.host == d.host } }
    val menuScroll = rememberScrollState()
    val scrollbar = MaterialTheme.colorScheme.onSurfaceVariant
    Column {
      // The standard exposed drop-down: opens right under the card, as wide as the card, scrolls when it is long
      ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }) {
        PlaitCard(Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable)) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TonalTile(PlaitIcon.Shield)
                Column(Modifier.weight(1f)) {
                    when {
                        dns == null -> Text("…", style = MaterialTheme.typography.titleMedium)
                        current != null -> {
                            Text(current.name, style = MaterialTheme.typography.titleMedium)
                            Text(current.host, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.size(6.dp))
                            Badges(current, compact = false)
                            current.note?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 6.dp))
                            }
                        }
                        dns.mode == "hostname" -> {
                            Text("Other provider", style = MaterialTheme.typography.titleMedium)
                            Text(dns.host ?: "?", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        dns.mode == "off" -> {
                            Text("Off", style = MaterialTheme.typography.titleMedium)
                            Text("Your network's own DNS", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        else -> {
                            Text("Automatic", style = MaterialTheme.typography.titleMedium)
                            Text("Encrypted when the network's DNS supports it", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                PlaitIcon(PlaitIcon.ChevronRight, contentDescription = "Choose a provider", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        ExposedDropdownMenu(
            expanded = open,
            onDismissRequest = { open = false },
            scrollState = menuScroll,
            // A scroll bar along the right edge: the menu itself draws none
            modifier = Modifier.heightIn(max = 420.dp).drawWithContent {
                drawContent()
                val max = menuScroll.maxValue
                if (max > 0) {
                    val h = size.height
                    val thumb = (h * h / (h + max)).coerceAtLeast(32.dp.toPx())
                    val y = (h - thumb) * menuScroll.value / max
                    drawRoundRect(
                        scrollbar.copy(alpha = 0.55f), topLeft = Offset(size.width - 6.dp.toPx(), y),
                        size = Size(4.dp.toPx(), thumb), cornerRadius = CornerRadius(2.dp.toPx())
                    )
                }
            }
        ) {
            MenuRow("Off", "Your network's own DNS", null, dns?.mode == "off") { open = false; onPick("off", null) }
            MenuRow("Automatic", "Encrypted when the network's DNS supports it", null, dns?.mode == "opportunistic") { open = false; onPick("opportunistic", null) }
            Group.entries.forEach { group ->
                Text(group.title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 2.dp))
                PROVIDERS.filter { it.group == group }.forEach { p ->
                    MenuRow(p.name, p.host, p, dns?.mode == "hostname" && dns.host == p.host) { open = false; onPick("hostname", p.host) }
                }
            }
            MenuRow("Other provider…", "Enter a hostname", null, false) { open = false; customOpen = true }
        }
      }
        if (customOpen) {
            PlaitCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    OutlinedTextField(custom, { onCustom(it.trim()) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("dns.example.com") })
                    TextButton(onClick = { customOpen = false; onPick("hostname", custom) }, enabled = custom.contains('.')) { Text("Use") }
                }
            }
        }
    }
}

@Composable
private fun MenuRow(title: String, subtitle: String, p: Provider?, selected: Boolean, onClick: () -> Unit) {
    DropdownMenuItem(
        onClick = onClick,
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f, fill = false))
                    if (selected) {
                        Spacer(Modifier.width(6.dp))
                        PlaitIcon(PlaitIcon.CheckCircle, contentDescription = "In use", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                    }
                }
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = if (p != null) FontFamily.Monospace else null)
                if (p != null) {
                    Spacer(Modifier.size(3.dp))
                    Badges(p, compact = true)
                }
            }
        }
    )
}

@Composable
private fun Tag(text: String, good: Boolean? = null, compact: Boolean = false) {
    StateChip(text, when (good) { true -> Tone.Success; false -> Tone.Danger; null -> Tone.Neutral })
}

@Composable
private fun ListToggleRow(l: Blocklist, on: Boolean, onToggle: (Boolean) -> Unit) {
    // The whole row toggles, as a SwitchRow does
    Box(Modifier.fillMaxWidth().clickable { onToggle(!on) }) {
        Row(Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(l.name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(8.dp))
                    Tag(l.tag)
                }
                Text(l.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("about %,d names".format(l.approxEntries), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.width(12.dp))
            PlaitSwitch(on, onToggle)
        }
    }
}
