package app.plait.scan

/** One device in the Bluetooth stack's own table: paired, or only seen. Android masks all but the last two address bytes. */
data class BtRemote(
    val tail: String,
    val address: String,
    val name: String?,
    val type: String,
    val deviceClass: Int,
    val connectedClassic: Boolean,
    val connectedLe: Boolean,
    val uuids: List<Int>,
    val packages: List<String>,
    val paired: Boolean,
    val linkKeyClassic: String? = null,
    val linkKeyLe: String? = null,
)

/** An app that has the Bluetooth scanner registered, and how much it has used it. */
data class BtScanApp(val pkg: String, val tags: List<String>, val started: Int, val activeMs: Long, val results: Long, val ongoing: Int, val importance: String?)

data class BtEvent(val time: String, val connected: Boolean, val tail: String, val reason: String?)

data class BtDump(
    val enabled: Boolean?,
    val state: String?,
    val address: String?,
    val name: String?,
    val uptime: String?,
    val scanMode: String?,
    val discovering: Boolean?,
    val profiles: List<String>,
    val paired: List<BtRemote>,
    val others: List<BtRemote>,
    val scanners: List<BtScanApp>,
    val events: List<BtEvent>,
) {
    /** What the phone calls each device, by the last two bytes of its address, for matching against scan results. */
    fun nameHints(): Map<String, String> {
        val byTail = HashMap<String, MutableSet<String>>()
        for (r in paired + others) {
            val n = r.name?.takeIf { it.isNotBlank() && it != "null" } ?: continue
            byTail.getOrPut(r.tail) { linkedSetOf() } += n
        }
        // two different names behind one tail would be a guess, so those are left out
        return byTail.filterValues { it.size == 1 }.mapValues { it.value.first() }
    }
}

/** Reads `dumpsys bluetooth_manager`: only the parts that are stable and useful, each tolerant of the format changing. */
object BtDumpParser {
    private val REMOTE = Regex("""^\s+(\S+?)\((\w+)\s*\) => (\S+?)\((\w+)\s*\) \[\s*([A-Za-z/ ]+?)\s*\] \[0x([0-9a-fA-F]+)\] \[ACL BR/EDR:([YN]) LE:([YN])\] \[ Encryption status.*\] (.*)$""")
    private val SCANNER_TAG = Regex("""app_if: \d+, appName: ([^\s,]+)(?:, tag: (\S+))?""")
    private val EVENT = Regex("""^\s+(\d\d-\d\d \d\d:\d\d:\d\d)\.\d+ (CONNECTED|DISCONNECTED)\s+(\S+)(?: reason=(\d+))?""")

    fun parse(text: String): BtDump {
        // split into top-level sections: a heading is any non-empty line without indentation
        val sections = LinkedHashMap<String, MutableList<String>>()
        var current: MutableList<String>? = null
        for (line in text.lines()) {
            if (line.isNotEmpty() && !line[0].isWhitespace()) {
                current = sections.getOrPut(line.trim()) { mutableListOf() }
            } else current?.add(line)
        }
        fun section(name: String) = sections[name] ?: emptyList()
        fun value(lines: List<String>, key: String) = lines.firstOrNull { it.trim().startsWith("$key:") }?.substringAfter(':')?.trim()

        val status = section("Bluetooth Status")
        val props = section("BluetoothAdapterProperties")
        val linkKeys = parseLinkKeys(section("Link Key Types:"))
        val remote = section("BluetoothRemoteDevices")
        val paired = ArrayList<BtRemote>()
        val others = ArrayList<BtRemote>()
        var pairedMode = true
        var lastDevice: Int = -1
        for (line in remote) {
            val t = line.trim()
            when {
                t.startsWith("Bonded devices") -> pairedMode = true
                t.startsWith("Other devices") -> pairedMode = false
                else -> {
                    val m = REMOTE.matchEntire(line)
                    if (m != null) {
                        val addr = m.groupValues[1]
                        val tail = addr.takeLast(5).lowercase()
                        val name = m.groupValues[9].trim().takeIf { it.isNotEmpty() && it != "null" }
                        val d = BtRemote(tail, addr, name, m.groupValues[5].trim(), m.groupValues[6].toLong(16).toInt(), m.groupValues[7] == "Y", m.groupValues[8] == "Y",
                            emptyList(), emptyList(), pairedMode, linkKeys[tail]?.first, linkKeys[tail]?.second)
                        (if (pairedMode) paired else others) += d
                        lastDevice = (if (pairedMode) paired else others).size - 1
                    } else if (lastDevice >= 0) {
                        // the indented lines under a device: its classic and LE service ids, and the apps that registered it
                        val list = if (pairedMode) paired else others
                        val d = list[lastDevice]
                        when {
                            t.startsWith("[BR/EDR UUIDs]") || t.startsWith("[LE UUIDs") ->
                                list[lastDevice] = d.copy(uuids = d.uuids + uuid16s(t.substringAfter(':')))
                            t.startsWith("[Packages") ->
                                list[lastDevice] = d.copy(packages = t.substringAfter(':').trim().removePrefix("[").removeSuffix("]").split(',').map { it.trim() }.filter { it.isNotEmpty() })
                        }
                    }
                }
            }
        }
        return BtDump(
            enabled = value(status, "enabled")?.toBooleanStrictOrNull(),
            state = value(status, "state"),
            address = value(status, "address"),
            name = value(status, "name"),
            uptime = value(status, "time since enabled"),
            scanMode = sections.keys.firstOrNull { it.startsWith("ScanMode:") }?.substringAfter(':')?.trim(),
            discovering = value(props, "Discovering")?.toBooleanStrictOrNull(),
            profiles = section("Enabled Profile Services:").map { it.trim() }.filter { it.isNotEmpty() },
            paired = paired, others = others,
            scanners = parseScanners(section("LE Scanner:"), section("LE Scanner Map:")),
            events = parseEvents(section("Connection Events:")),
        )
    }

    /** 128-bit ids written in the Bluetooth base form (0000xxxx-0000-1000-8000-00805f9b34fb) reduce to their 16-bit number. */
    private fun uuid16s(s: String): List<Int> =
        Regex("""([0-9a-f]{8})-0000-1000-8000-00805f9b34fb""", RegexOption.IGNORE_CASE).findAll(s)
            .mapNotNull { it.groupValues[1].toLongOrNull(16)?.takeIf { v -> v <= 0xFFFF }?.toInt() }.toList()

    private fun parseLinkKeys(lines: List<String>): Map<String, Pair<String?, String?>> {
        val out = HashMap<String, Pair<String?, String?>>()
        var tail: String? = null
        var br: String? = null
        var le: String? = null
        fun flush() { tail?.let { out[it] = (br?.takeIf { s -> s.isNotEmpty() }) to (le?.takeIf { s -> s.isNotEmpty() }) } }
        for (l in lines) {
            val t = l.trim()
            when {
                t.isEmpty() -> {}
                t.startsWith("BR:") -> br = t.substringAfter(':').trim()
                t.startsWith("LE:") -> le = t.substringAfter(':').trim()
                else -> { flush(); tail = t.takeLast(5).lowercase(); br = null; le = null }
            }
        }
        flush()
        return out
    }

    private fun parseScanners(tags: List<String>, map: List<String>): List<BtScanApp> {
        val tagsByApp = HashMap<String, MutableList<String>>()
        for (l in tags) SCANNER_TAG.find(l)?.let { m -> m.groupValues[2].takeIf { it.isNotEmpty() }?.let { tagsByApp.getOrPut(m.groupValues[1]) { mutableListOf() } += it } }
        val out = ArrayList<BtScanApp>()
        var pkg: String? = null
        var started = 0
        var active = 0L
        var results = 0L
        var ongoing = 0
        var importance: String? = null
        fun flush() { pkg?.let { out += BtScanApp(it, tagsByApp[it].orEmpty().distinct(), started, active, results, ongoing, importance) } }
        for (l in map) {
            val head = Regex("""^  (\S+?)(?: \(\w+\))?:$""").matchEntire(l)
            if (head != null) {
                flush(); pkg = head.groupValues[1]; started = 0; active = 0; results = 0; ongoing = 0; importance = null
                continue
            }
            val t = l.trim()
            when {
                t.startsWith("LE scans") -> started = Regex("""(\d+) / (\d+)""").find(t)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                t.startsWith("Scan time(ms)") -> active = Regex("""(\d+) / (\d+) / (\d+)""").find(t)?.groupValues?.get(3)?.toLongOrNull() ?: 0
                t.startsWith("Total number of results") -> results = t.substringAfter(':').trim().toLongOrNull() ?: 0
                t.startsWith("Ongoing") -> ongoing = Regex("""Ongoing (\d+)""").find(t)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                t.contains("App Importance:") && importance == null -> importance = t.substringAfter("App Importance:").trim()
            }
        }
        flush()
        return out.sortedByDescending { it.ongoing * 1_000_000_000L + it.activeMs }
    }

    private fun parseEvents(lines: List<String>): List<BtEvent> {
        val out = ArrayList<BtEvent>()
        for (l in lines) {
            val m = EVENT.find(l) ?: continue
            val e = BtEvent(m.groupValues[1], m.groupValues[2] == "CONNECTED", m.groupValues[3].takeLast(5).lowercase(), m.groupValues[4].ifEmpty { null })
            // the log repeats the same event once per profile; keep one per second
            if (out.lastOrNull()?.let { it.time == e.time && it.connected == e.connected && it.tail == e.tail } != true) out += e
        }
        return out.take(20)
    }
}
