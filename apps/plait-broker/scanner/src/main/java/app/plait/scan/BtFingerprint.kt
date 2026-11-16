package app.plait.scan

enum class BtKind(val label: String) {
    PHONE("Phone"), TABLET("Tablet"), WATCH("Watch or band"), EARBUDS("Earbuds / headphones"), SPEAKER("Speaker"), AUDIO("Audio device"),
    TV("TV / display"), COMPUTER("Computer"), LAPTOP("Laptop"), KEYBOARD("Keyboard"), MOUSE("Mouse / pointer"), GAMEPAD("Game controller"),
    INPUT("Input device"), CONSOLE("Game console"), TRACKER("Item tracker"), BEACON("Beacon"), FITNESS("Fitness sensor"), HEALTH("Health device"),
    CAR("Car"), PRINTER("Printer"), CAMERA("Camera"), HOME("Smart-home device"), REMOTE("Remote"), APPLE("Apple device"), UNKNOWN("Unknown device"),
}

data class BtIdentity(val kind: BtKind, val model: String?, val confidence: Int, val evidence: List<String>)

/** Works out what a Bluetooth device is from what it advertises: vendor frames, services, appearance, class and name. */
object BtFingerprint {
    private const val APPLE = 0x004C
    private const val MICROSOFT = 0x0006

    /** The model code in an Apple "proximity pairing" frame (AirPods and Beats). */
    private val APPLE_AUDIO = mapOf(
        0x0220 to "AirPods", 0x0F20 to "AirPods (2nd generation)", 0x1320 to "AirPods (3rd generation)", 0x0E20 to "AirPods Pro",
        0x1420 to "AirPods Pro (2nd generation)", 0x0A20 to "AirPods Max", 0x0520 to "BeatsX", 0x0620 to "Beats Solo3",
        0x0920 to "Beats Studio3", 0x0B20 to "Powerbeats Pro", 0x0C20 to "Beats Solo Pro",
    )
    private val APPLE_FRAMES = mapOf(
        0x02 to "iBeacon", 0x05 to "AirDrop", 0x07 to "proximity pairing", 0x09 to "AirPlay target", 0x0B to "Watch connection",
        0x0C to "Handoff", 0x0D to "tethering target", 0x0E to "tethering source", 0x0F to "Nearby Action", 0x10 to "Nearby Info", 0x12 to "Find My",
    )
    private val MS_DEVICE = mapOf(
        1 to (BtKind.CONSOLE to "Xbox One"), 6 to (BtKind.PHONE to "iPhone"), 7 to (BtKind.TABLET to "iPad"), 8 to (BtKind.PHONE to "Android device"),
        9 to (BtKind.COMPUTER to "Windows desktop"), 11 to (BtKind.PHONE to "Windows phone"), 12 to (BtKind.COMPUTER to "Linux device"),
        13 to (BtKind.HOME to "Windows IoT"), 14 to (BtKind.TV to "Surface Hub"), 15 to (BtKind.LAPTOP to "Windows laptop"), 16 to (BtKind.TABLET to "Windows tablet"),
    )

    fun classify(d: BtDevice, nums: BtNumbers, oui: Oui?): BtIdentity {
        val ev = mutableListOf<String>()
        val name = (d.name ?: d.hintName).orEmpty()
        val lname = name.lowercase()
        fun said(vararg w: String) = w.any { Regex("(^|[^a-z0-9])" + Regex.escape(it) + "($|[^a-z0-9])").containsMatchIn(lname) }
        fun id(kind: BtKind, model: String?, conf: Int, why: String): BtIdentity {
            ev += why
            return BtIdentity(kind, model, conf, ev.toList())
        }
        if (d.name != null) ev += "Advertised name: ${d.name}"
        else if (d.hintName != null) ev += "Name from the phone's Bluetooth table: ${d.hintName} (matched by the last two address bytes)"
        d.manufacturer.keys.forEach { ev += "Manufacturer data from ${nums.company(it) ?: "company 0x%04x".format(it)}" }
        val svcNames = d.services16.mapNotNull { nums.service(it) }
        if (svcNames.isNotEmpty()) ev += "Services: " + svcNames.take(6).joinToString(", ")
        d.appearance?.let { a -> nums.appearance(a)?.let { ev += "Appearance: $it" } }
        d.deviceClass?.let { ev += "Classic device class 0x%06x".format(it) }
        if (d.paired) ev += "Paired with this phone"

        // 1. Apple: the frames inside manufacturer data say a lot (and are not encrypted)
        d.manufacturer[APPLE]?.let { p ->
            val frames = appleFrames(p)
            if (frames.isNotEmpty()) ev += "Apple frames: " + frames.joinToString(", ") { APPLE_FRAMES[it.first] ?: "type 0x%02x".format(it.first) }
            frames.firstOrNull { it.first == 0x12 }?.let { return id(BtKind.TRACKER, "AirTag or Find My accessory", 3, "Find My (offline finding) frame") }
            frames.firstOrNull { it.first == 0x07 && it.second.size >= 3 }?.let { f ->
                val model = ((f.second[1].toInt() and 0xff) shl 8) or (f.second[2].toInt() and 0xff)
                val known = APPLE_AUDIO[model]
                return id(BtKind.EARBUDS, known ?: "AirPods or Beats", if (known != null) 3 else 2, "Apple pairing frame, model code 0x%04x".format(model) + if (known != null) " (matches $known)" else "")
            }
            frames.firstOrNull { it.first == 0x02 && it.second.size >= 21 }?.let { f ->
                val uuid = f.second.copyOfRange(0, 16).hex()
                return id(BtKind.BEACON, "iBeacon", 3, "iBeacon frame: uuid ${uuid.take(8)}…, major ${(f.second[16].toInt() and 0xff) * 256 + (f.second[17].toInt() and 0xff)}")
            }
            val nearby = frames.firstOrNull { it.first == 0x10 && it.second.isNotEmpty() }
            val kind = when {
                said("iphone") -> BtKind.PHONE to "iPhone"
                said("ipad") -> BtKind.TABLET to "iPad"
                said("macbook") -> BtKind.LAPTOP to "MacBook"
                said("watch") -> BtKind.WATCH to "Apple Watch"
                frames.any { it.first == 0x09 } -> BtKind.TV to "AirPlay device"
                else -> null
            }
            if (kind != null) return id(kind.first, kind.second, 2, "Apple frames and name")
            val action = nearby?.second?.get(0)?.toInt()?.and(0xff)?.shr(4)
            return id(BtKind.APPLE, "iPhone, iPad, Mac or Watch", if (nearby != null) 2 else 1,
                if (action != null) "Nearby Info frame (activity code $action): an iOS or macOS device" else "Apple manufacturer data")
        }
        // 2. Microsoft's Connected Devices beacon names the platform
        d.manufacturer[MICROSOFT]?.let { p ->
            if (p.size >= 2 && p[0].toInt() == 1) {
                val type = p[1].toInt() and 0x3f
                MS_DEVICE[type]?.let { (kind, model) -> return id(kind, model, 2, "Microsoft CDP beacon, device type $type") }
            }
        }
        // 3. Beacons and trackers with their own service numbers
        d.serviceData[0xFEAA]?.let { f ->
            return id(BtKind.BEACON, eddystone(f), 3, "Eddystone frame")
        }
        if (0xFEED in d.services16 || 0xFEEC in d.services16 || 0xFEED in d.serviceData || 0xFEEC in d.serviceData) return id(BtKind.TRACKER, "Tile", 3, "Tile service")
        if (0xFD5A in d.services16 || 0xFD5A in d.serviceData) return id(BtKind.TRACKER, "Samsung SmartTag", 2, "Samsung item-finding service")
        d.serviceData[0xFE2C]?.let { f ->
            val model = if (f.size == 3) "model 0x" + f.hex() else null
            val kind = when {
                said("buds", "earbuds", "airpods", "headphones", "headset") -> BtKind.EARBUDS
                said("speaker", "soundbar") -> BtKind.SPEAKER
                else -> BtKind.AUDIO
            }
            return id(kind, "Google Fast Pair accessory" + (model?.let { " ($it)" } ?: ""), 2, "Google Fast Pair service data")
        }
        if (0xFD6F in d.services16 || 0xFD6F in d.serviceData) return id(BtKind.BEACON, "Exposure notification", 2, "COVID exposure-notification service")

        // 4. what the device says it is
        d.appearance?.let { a ->
            fromAppearance(a)?.let { (kind, model) -> return id(kind, model ?: nums.appearance(a), 2, "Appearance code 0x%04x".format(a)) }
        }
        d.deviceClass?.let { c -> fromClass(c)?.let { (kind, model) -> return id(kind, model, 2, "Classic device class") } }
        fromServices(d.services16)?.let { (kind, model, why) -> return id(kind, model, 2, why) }

        // 5. words in its name
        val byName = when {
            said("airpods") -> BtKind.EARBUDS to "AirPods"
            said("buds", "earbuds", "earphones", "headphones", "headset", "wh-1000xm4", "wh-1000xm5") -> BtKind.EARBUDS to null
            said("speaker", "soundbar", "boombox", "echo", "homepod") -> BtKind.SPEAKER to null
            said("watch", "band", "fit", "mi band", "amazfit", "garmin", "forerunner", "fenix", "versa", "charge") -> BtKind.WATCH to null
            said("tv", "bravia", "roku", "fire tv", "chromecast", "shield") -> BtKind.TV to null
            said("keyboard", "k380", "mx keys") -> BtKind.KEYBOARD to null
            said("mouse", "trackpad", "mx master") -> BtKind.MOUSE to null
            said("controller", "gamepad", "dualshock", "dualsense", "joy-con", "xbox") -> BtKind.GAMEPAD to null
            said("tag", "tracker", "airtag", "tile", "smarttag") -> BtKind.TRACKER to null
            said("printer") -> BtKind.PRINTER to null
            said("lock", "bulb", "light", "lamp", "sensor", "thermostat", "plug") -> BtKind.HOME to null
            said("car", "vehicle", "mazda", "toyota", "ford", "honda", "tesla", "kia", "hyundai", "bmw", "audi") -> BtKind.CAR to null
            said("pixel", "galaxy", "iphone", "oneplus", "redmi", "xiaomi") -> BtKind.PHONE to null
            said("macbook", "laptop", "thinkpad") -> BtKind.LAPTOP to null
            else -> null
        }
        byName?.let { (kind, model) -> return id(kind, model, 2, "Name suggests ${kind.label.lowercase()}") }

        // 6. the maker, as a last hint
        val company = d.manufacturer.keys.firstNotNullOfOrNull { nums.company(it) }
        val companyKind = company?.lowercase()?.let { c ->
            when {
                "garmin" in c || "huami" in c || "fitbit" in c || "polar" in c || "suunto" in c -> BtKind.WATCH
                "bose" in c || "jabra" in c || "gn netcom" in c || "sennheiser" in c || "harman" in c || "skullcandy" in c || "plantronics" in c || "beats" in c -> BtKind.AUDIO
                "logitech" in c -> BtKind.INPUT
                "tile" in c || "chipolo" in c -> BtKind.TRACKER
                else -> null
            }
        }
        if (companyKind != null) return id(companyKind, company, 1, "Maker usually makes ${companyKind.label.lowercase()}s")
        if (company != null) return id(BtKind.UNKNOWN, company, 1, "Only the maker is known")
        val vendor = oui?.vendor(d.address)
        if (vendor != null) {
            val v = vendor.lowercase()
            when {
                listOf("epson", "canon", "brother", "hewlett", "hp inc", "lexmark", "xerox", "kyocera", "ricoh", "konica").any { it in v } ->
                    return id(BtKind.PRINTER, vendor, 2, "Address belongs to a printer maker ($vendor)")
                "sonos" in v -> return id(BtKind.SPEAKER, vendor, 2, "Address belongs to Sonos")
            }
            if (d.classic || d.paired) return id(BtKind.UNKNOWN, vendor, 1, "Address belongs to $vendor")
        }
        return id(BtKind.UNKNOWN, null, 0, "Nothing in its advertisement says what it is")
    }

    /** Apple manufacturer data is a list of (type, length, data) frames. */
    private fun appleFrames(p: ByteArray): List<Pair<Int, ByteArray>> {
        val out = ArrayList<Pair<Int, ByteArray>>()
        var i = 0
        while (i + 1 < p.size) {
            val type = p[i].toInt() and 0xff
            val len = p[i + 1].toInt() and 0xff
            if (i + 2 + len > p.size) break
            out += type to p.copyOfRange(i + 2, i + 2 + len)
            i += 2 + len
        }
        return out
    }

    private fun eddystone(f: ByteArray): String = when (f.firstOrNull()?.toInt()?.and(0xff)) {
        0x00 -> "Eddystone-UID beacon"
        0x10 -> "Eddystone-URL: " + eddystoneUrl(f)
        0x20 -> "Eddystone-TLM (telemetry)"
        0x30 -> "Eddystone-EID beacon"
        else -> "Eddystone beacon"
    }

    private fun eddystoneUrl(f: ByteArray): String {
        if (f.size < 3) return "?"
        val scheme = arrayOf("http://www.", "https://www.", "http://", "https://").getOrElse(f[2].toInt()) { "" }
        val tail = arrayOf(".com/", ".org/", ".edu/", ".net/", ".info/", ".biz/", ".gov/", ".com", ".org", ".edu", ".net", ".info", ".biz", ".gov")
        return scheme + (3 until f.size).joinToString("") { val c = f[it].toInt() and 0xff; if (c < tail.size) tail[c] else c.toChar().toString() }
    }

    private fun fromAppearance(a: Int): Pair<BtKind, String?>? {
        val cat = a shr 6
        val sub = a and 0x3f
        return when (cat) {
            1 -> BtKind.PHONE to null
            2 -> when (sub) { 3, 11 -> BtKind.LAPTOP; 7, 12 -> BtKind.TABLET; else -> BtKind.COMPUTER } to null
            3 -> BtKind.WATCH to null
            5 -> BtKind.TV to null
            6 -> BtKind.REMOTE to null
            8, 9 -> BtKind.TRACKER to null
            10 -> BtKind.AUDIO to null
            12, 13, 14, 16, 49, 50, 51 -> BtKind.HEALTH to null
            15 -> when (sub) { 1 -> BtKind.KEYBOARD; 2 -> BtKind.MOUSE; 3, 4 -> BtKind.GAMEPAD; else -> BtKind.INPUT } to null
            17, 18 -> BtKind.FITNESS to null
            else -> null
        }
    }

    /** Bluetooth Classic "class of device": major class and the interesting sub-classes. */
    fun kindOfClass(c: Int): BtKind? = fromClass(c)?.first

    private fun fromClass(c: Int): Pair<BtKind, String?>? = when (c and 0x1F00) {
        0x0100 -> BtKind.COMPUTER to null
        0x0200 -> BtKind.PHONE to null
        0x0400 -> when (c and 0xFC) {
            0x04, 0x08, 0x18 -> BtKind.EARBUDS; 0x14, 0x28 -> BtKind.SPEAKER; 0x20 -> BtKind.CAR
            0x24, 0x38, 0x3C -> BtKind.TV; 0x30, 0x34 -> BtKind.CAMERA; 0x48 -> BtKind.GAMEPAD
            else -> BtKind.AUDIO
        } to null
        0x0500 -> when (c and 0xC0) { 0x40 -> BtKind.KEYBOARD; 0x80 -> BtKind.MOUSE; else -> BtKind.INPUT } to null
        0x0600 -> BtKind.PRINTER to null
        0x0700 -> BtKind.WATCH to null
        0x0800 -> BtKind.GAMEPAD to null
        0x0900 -> BtKind.HEALTH to null
        else -> null
    }

    private fun fromServices(s: Set<Int>): Triple<BtKind, String?, String>? = when {
        0x180D in s || 0x1814 in s || 0x1816 in s || 0x1818 in s || 0x1826 in s -> Triple(BtKind.FITNESS, null, "Fitness service (heart rate, running, cycling)")
        0x1809 in s || 0x1808 in s || 0x1810 in s || 0x181D in s -> Triple(BtKind.HEALTH, null, "Health service (thermometer, glucose, blood pressure, weight)")
        0x1812 in s -> Triple(BtKind.INPUT, "HID device (keyboard, mouse or controller)", "Human Interface Device service")
        0x1827 in s || 0x1828 in s -> Triple(BtKind.HOME, "Bluetooth Mesh node", "Bluetooth Mesh service")
        0x181A in s -> Triple(BtKind.HOME, "Environmental sensor", "Environmental Sensing service")
        else -> null
    }
}
