package app.plait.scan

import android.content.Context

/** One Bluetooth device as far as its advertisements (and discovery) have told us. */
data class BtDevice(
    val address: String,
    val name: String? = null,
    val rssi: Int = -100,
    val firstSeen: Long = 0,
    val lastSeen: Long = 0,
    val sightings: Int = 0,
    val raw: ByteArray = ByteArray(0),
    val manufacturer: Map<Int, ByteArray> = emptyMap(),
    val services16: Set<Int> = emptySet(),
    val services128: Set<String> = emptySet(),
    val serviceData: Map<Int, ByteArray> = emptyMap(),
    val txPower: Int? = null,
    val appearance: Int? = null,
    val flags: Int? = null,
    val connectable: Boolean = false,
    val le: Boolean = false,
    val classic: Boolean = false,
    val deviceClass: Int? = null,
    val paired: Boolean = false,
    val nearby: Boolean = true,
    /** A name for it from the phone's own Bluetooth table (matched by the last two address bytes), when it advertises none. */
    val hintName: String? = null,
    val identity: BtIdentity? = null,
)

/** One "length, type, data" structure of an advertisement. */
class Ad(val type: Int, val data: ByteArray)

object AdParser {
    fun parse(b: ByteArray): List<Ad> {
        val out = ArrayList<Ad>()
        var i = 0
        while (i < b.size) {
            val len = b[i].toInt() and 0xff
            if (len == 0 || i + 1 + len > b.size) break
            out += Ad(b[i + 1].toInt() and 0xff, b.copyOfRange(i + 2, i + 1 + len))
            i += 1 + len
        }
        return out
    }
}

fun ByteArray.hex(sep: String = ""): String = joinToString(sep) { "%02x".format(it) }
fun ByteArray.u16le(at: Int) = (this[at].toInt() and 0xff) or ((this[at + 1].toInt() and 0xff) shl 8)

/** Names for the numbers Bluetooth uses (company ids, services, appearance), from Nordic's open database. */
class BtNumbers private constructor(
    private val companies: Map<Int, String>, private val services: Map<Int, String>, private val appearance: Map<Int, String>,
) {
    /** 0xFFFF is the placeholder for "no vendor record"; its registry entry is a sentence, not a company. */
    fun company(id: Int): String? = if (id == 0xFFFF) null else companies[id]?.takeUnless { it.startsWith("Bluetooth SIG", ignoreCase = true) && it.length > 40 }
    fun service(uuid16: Int): String? = services[uuid16] ?: MEMBER[uuid16]
    /** [value] is the 16-bit appearance code: 10 bits of category, 6 bits of sub-category. */
    fun appearance(value: Int): String? {
        val cat = value shr 6
        val sub = value and 0x3f
        val c = appearance[cat * 100000 - 1] ?: return null
        val s = appearance[cat * 100000 + sub]
        return if (s != null) "$c: $s" else c
    }

    companion object {
        /** Services that companies registered as "member" UUIDs: the ones that name what a device is. */
        val MEMBER = mapOf(
            0xFEAA to "Eddystone (Google)", 0xFEED to "Tile", 0xFEEC to "Tile", 0xFE2C to "Google Fast Pair", 0xFD6F to "Exposure Notification",
            0xFD5A to "Samsung SmartThings Find", 0xFE9F to "Google", 0xFEBE to "Bose", 0xFE95 to "Xiaomi", 0xFEE0 to "Anhui Huami (Amazfit / Mi Band)",
            0xFEE7 to "Tencent", 0xFE59 to "Nordic Semiconductor (DFU)", 0xFE8F to "CSR", 0xFD44 to "Apple",
        )

        fun load(ctx: Context): BtNumbers {
            fun lines(name: String) = ctx.assets.open(name).bufferedReader().readLines()
            val companies = lines("bt_companies.tsv").mapNotNull { l -> l.split('\t').takeIf { it.size == 2 }?.let { it[0].toIntOrNull()?.to(it[1]) } }.toMap()
            val services = lines("bt_services.tsv").mapNotNull { l -> l.split('\t').takeIf { it.size == 2 }?.let { it[0].toIntOrNull(16)?.to(it[1]) } }.toMap()
            val appearance = HashMap<Int, String>()
            for (l in lines("bt_appearance.tsv")) {
                val p = l.split('\t')
                if (p.size != 3) continue
                val cat = p[0].toIntOrNull() ?: continue
                val sub = p[1].toIntOrNull() ?: continue
                appearance[if (sub < 0) cat * 100000 - 1 else cat * 100000 + sub] = p[2]
            }
            return BtNumbers(companies, services, appearance)
        }
    }
}
