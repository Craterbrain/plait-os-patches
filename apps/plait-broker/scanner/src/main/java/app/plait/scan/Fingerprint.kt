package app.plait.scan

enum class Kind(val label: String) {
    ROUTER("Router"), PHONE("Phone"), TABLET("Tablet"), LAPTOP("Laptop"), COMPUTER("Computer"), TV("TV"),
    STREAMER("Streaming device"), SPEAKER("Smart speaker"), PRINTER("Printer"), CAMERA("Camera"), NAS("Storage / server"),
    CONSOLE("Game console"), HOME("Smart home"), WATCH("Wearable"), SBC("Single-board computer"), IOT("IoT device"),
    PHONE_OR_PC("Phone or computer"), UNKNOWN("Unknown device"),
}

/** What a device probably is, how sure we are (1 low, 3 high), and what we based it on. */
data class Identity(val kind: Kind, val model: String?, val confidence: Int, val evidence: List<String>, val name: String?)

object Fingerprint {
    private val TV_BRANDS = listOf("samsung", "lg electronics", "sony", "vizio", "tcl", "hisense", "panasonic", "philips", "sharp", "toshiba", "skyworth", "xiaomi")
    private val PHONE_BRANDS = listOf("samsung", "xiaomi", "oneplus", "oppo", "huawei", "vivo", "realme", "motorola", "google", "nothing", "honor", "zte")
    private val NET_BRANDS = listOf("ubiquiti", "netgear", "tp-link", "asustek", "mikrotik", "cisco", "linksys", "eero", "arris", "technicolor", "zyxel", "d-link", "tenda", "sagemcom", "huawei technologies", "ruckus", "aruba", "cambium", "calix", "actiontec")
    private val CAMERA_BRANDS = listOf("hikvision", "dahua", "reolink", "axis comm", "amcrest", "foscam", "wyze", "arlo", "eufy", "ring", "lorex", "annke", "vivotek", "uniview", "hanwha", "swann")
    private val NAS_BRANDS = listOf("synology", "qnap", "asustor", "terramaster", "western digital", "buffalo", "drobo")
    private val HOME_BRANDS = listOf("signify", "philips lighting", "lutron", "nest labs", "ecobee", "belkin", "wemo", "ikea", "tp-link tapo", "shelly", "allterco", "sonoff", "itead", "tuya", "espressif", "lifx", "wiz", "yeelight", "meross", "govee", "kasa", "smartthings", "ring ", "chamberlain", "august", "yale", "honeywell", "resideo", "rachio", "ecovacs", "irobot", "roborock", "dyson")
    private val PC_BRANDS = listOf("intel", "realtek", "liteon", "azurewave", "qualcomm atheros", "killer", "hon hai", "foxconn", "dell", "lenovo", "hewlett", "hp inc", "msi", "micro-star", "gigabyte", "asrock", "acer", "wistron", "compal", "quanta", "pegatron", "ampak", "mediatek", "broadcom", "microsoft")

    /** [needle] as whole words in [hay]: "ring" is not in "manufacturing". */
    private fun word(hay: String, needle: String): Boolean =
        Regex("(^|[^a-z0-9])" + Regex.escape(needle.trim()) + "($|[^a-z0-9])").containsMatchIn(hay)

    fun classify(h: Host, f: Facts?): Identity {
        val ev = mutableListOf<String>()
        val vendor = h.vendor.orEmpty().lowercase()
        val facts = f ?: Facts()
        val friendly = facts.txt("fn") ?: facts.upnp?.friendlyName ?: facts.instances.firstOrNull { it.first == "_googlecast._tcp" }?.second
        val host = listOfNotNull(h.name, facts.mdnsHost).joinToString(" ").lowercase()
        val text = listOfNotNull(host, friendly, facts.upnp?.modelName, facts.upnp?.manufacturer, facts.upnp?.deviceType, facts.http?.title,
            facts.http?.server, facts.http?.realm, facts.ssdpServer).joinToString(" ").lowercase()
        val svc = facts.services
        val ports = h.openPorts
        val name = friendly ?: facts.mdnsHost?.takeIf { it.isNotBlank() } ?: h.name?.substringBefore('.')
        fun vendorIn(list: List<String>) = list.any { word(vendor, it) }
        fun said(vararg w: String) = w.any { word(text, it) }
        fun id(kind: Kind, model: String?, conf: Int, why: String): Identity {
            ev += why
            return Identity(kind, model, conf, ev.toList(), name)
        }
        if (svc.isNotEmpty()) ev += "mDNS services: " + svc.take(6).joinToString(", ") + if (svc.size > 6) ", …" else ""
        facts.upnp?.let { ev += "UPnP: ${listOfNotNull(it.manufacturer, it.modelName).joinToString(" ")}".trim() }
        facts.ssdpServer?.let { ev += "SSDP server: $it" }
        facts.http?.let { ev += "Web page: ${listOfNotNull(it.server, it.title, it.realm?.let { r -> "login \"$r\"" }).joinToString(" · ")}" }
        if (h.vendor != null && !h.randomized) ev += "MAC vendor: ${h.vendor}"
        if (h.randomized) ev += "Private (randomized) MAC address"
        if (ports.isNotEmpty()) ev += "Open ports: " + ports.joinToString(" ")

        // 1. the device says what it is: model identifiers in mDNS
        facts.txt("md")?.let { md ->
            val m = md.lowercase()
            if ("_googlecast._tcp" in svc || "_googlezone._tcp" in svc) {
                val kind = when {
                    m.contains("tv") || m.contains("bravia") -> Kind.TV
                    m.contains("chromecast") || m.contains("shield") -> Kind.STREAMER
                    m.contains("nest hub") || m.contains("display") -> Kind.SPEAKER
                    m.contains("home") || m.contains("nest") || m.contains("speaker") || m.contains("audio") -> Kind.SPEAKER
                    else -> Kind.STREAMER
                }
                return id(kind, md, 3, "Google Cast model: $md")
            }
            if ("_hap._tcp" in svc || "_homekit._tcp" in svc) return id(Kind.HOME, md, 3, "HomeKit accessory model: $md")
        }
        val spoofsMac = !vendor.contains("apple") && !h.randomized && facts.txt("model")?.startsWith("Mac") == true
        if (spoofsMac && ("_smb._tcp" in svc || 445 in ports)) {
            return id(Kind.NAS, facts.mdnsHost ?: h.vendor, 2, "File server that announces itself as a Mac so Finder shows a Mac icon")
        }
        if (!spoofsMac) appleModel(facts)?.let { (kind, label, raw) -> return id(kind, "$label ($raw)", 3, "Apple model identifier: $raw") }
        if ("_cros_p2p._tcp" in svc) return id(Kind.LAPTOP, "Chromebook", 2, "ChromeOS peer-to-peer service")
        if ("_apple-mobdev2._tcp" in svc || 62078 in ports) {
            val tablet = host.contains("ipad")
            return id(if (tablet) Kind.TABLET else Kind.PHONE, if (tablet) "iPad" else "iPhone", 3, "Apple device-sync port (iPhone/iPad)")
        }
        if ("_ipp._tcp" in svc || "_ipps._tcp" in svc || "_printer._tcp" in svc || "_pdl-datastream._tcp" in svc || 9100 in ports) {
            val model = facts.txt("ty") ?: facts.txt("product")?.trim('(', ')') ?: facts.txt("usb_MDL")
            return id(Kind.PRINTER, model, if (model != null) 3 else 2, "Printer protocol (IPP/JetDirect)")
        }
        if ("_sonos._tcp" in svc || vendor.contains("sonos") || text.contains("sonos")) return id(Kind.SPEAKER, "Sonos", 3, "Sonos")
        if (facts.ssdpServer?.contains("roku", true) == true || "roku:ecp" in facts.ssdpTypes || 8060 in ports) return id(Kind.STREAMER, "Roku", 3, "Roku control port")
        if ("_androidtvremote2._tcp" in svc) return id(Kind.TV, "Android TV / Google TV", 3, "Android TV remote service")
        if ("_googlecast._tcp" in svc) return id(Kind.STREAMER, "Google Cast device", 2, "Google Cast service")
        if ("_hap._tcp" in svc || "_homekit._tcp" in svc || "_matter._tcp" in svc || "_hue._tcp" in svc || "_esphomelib._tcp" in svc) {
            val model = if ("_hue._tcp" in svc) "Philips Hue bridge" else if ("_esphomelib._tcp" in svc) "ESPHome device" else null
            return id(Kind.HOME, model, 2, "Smart-home protocol")
        }
        if ("_nvstream._tcp" in svc) return id(Kind.COMPUTER, "PC with GameStream/Sunshine", 2, "Game streaming service")

        // 2. UPnP says what kind of device it is
        facts.upnp?.let { u ->
            val t = (u.deviceType ?: "").lowercase()
            val model = listOfNotNull(u.manufacturer, u.modelName).joinToString(" ").ifBlank { null }
            when {
                t.contains("internetgatewaydevice") || t.contains("wanconnection") -> return id(Kind.ROUTER, model, 3, "UPnP internet gateway")
                t.contains("printer") -> return id(Kind.PRINTER, model, 3, "UPnP printer")
                t.contains("mediarenderer") && vendorIn(TV_BRANDS) -> return id(Kind.TV, model, 3, "UPnP media renderer from a TV maker")
                t.contains("mediaserver") && vendorIn(NAS_BRANDS) -> return id(Kind.NAS, model, 3, "UPnP media server")
                t.contains("mediarenderer") -> return id(Kind.STREAMER, model, 2, "UPnP media renderer")
            }
        }

        // 3. the web page and the vendor
        if (h.gateway) return id(Kind.ROUTER, facts.upnp?.modelName ?: if (host.contains("unifi")) "Ubiquiti UniFi gateway" else null, 3, "Default gateway of this network")
        if (host.contains("unifi") || text.contains("ubnt") || text.contains("unifi")) return id(Kind.ROUTER, "Ubiquiti UniFi", 2, "UniFi in its name")
        if (vendorIn(NAS_BRANDS) || said("synology", "diskstation", "qnap", "nas", "truenas", "freenas", "unraid", "openmediavault")) {
            return id(Kind.NAS, h.vendor, 2, "NAS maker or page")
        }
        if (vendorIn(CAMERA_BRANDS) || 554 in ports || said("ipcam", "camera", "dvr", "nvr", "webcam")) {
            return id(Kind.CAMERA, h.vendor?.takeUnless { h.randomized }, if (vendorIn(CAMERA_BRANDS)) 3 else 2, "Camera maker, RTSP port or page")
        }
        if (vendor.contains("raspberry")) return id(Kind.SBC, "Raspberry Pi", 3, "Raspberry Pi Foundation MAC")
        if (vendor.contains("sony interactive") || text.contains("playstation") || host.startsWith("ps4") || host.startsWith("ps5")) return id(Kind.CONSOLE, "PlayStation", 3, "Sony Interactive")
        if (vendor.contains("nintendo")) return id(Kind.CONSOLE, "Nintendo", 3, "Nintendo MAC")
        if (text.contains("xbox")) return id(Kind.CONSOLE, "Xbox", 3, "Xbox in its name")
        if (vendor.contains("amazon")) {
            return if (8009 in ports || text.contains("fire")) id(Kind.STREAMER, "Amazon Fire TV", 2, "Amazon MAC with cast/streaming ports")
            else id(Kind.SPEAKER, "Amazon Echo / Fire device", 1, "Amazon MAC")
        }
        if (vendorIn(TV_BRANDS) && (8001 in ports || 8002 in ports || 3000 in ports || 3001 in ports || "_airplay._tcp" in svc || said("tv", "webos", "tizen"))) {
            val m = if (vendor.contains("samsung")) "Samsung smart TV" else if (vendor.contains("lg")) "LG webOS TV" else h.vendor
            return id(Kind.TV, m, 2, "TV maker with TV control ports or name")
        }
        if (vendorIn(HOME_BRANDS)) return id(if (vendorIn(listOf("espressif", "tuya", "sonoff", "itead", "shelly", "allterco"))) Kind.IOT else Kind.HOME, h.vendor, 2, "Smart-home / IoT chip or brand")
        if (vendor.contains("ubiquiti")) return id(Kind.ROUTER, "Ubiquiti access point or switch", 2, "Ubiquiti MAC (UniFi gear)")
        if (vendorIn(NET_BRANDS) && (80 in ports || 443 in ports || 53 in ports || said("router", "gateway", "access point"))) {
            return id(Kind.ROUTER, h.vendor, 1, "Network equipment maker with a web page")
        }

        // 4. names that people and operating systems give their devices
        val byName = when {
            host.contains("iphone") -> Triple(Kind.PHONE, "iPhone", 2)
            host.contains("ipad") -> Triple(Kind.TABLET, "iPad", 2)
            host.contains("pixel") || host.contains("galaxy") || host.contains("android") || host.contains("oneplus") || host.contains("redmi") -> Triple(Kind.PHONE, h.name?.substringBefore('.'), 2)
            host.contains("macbook") -> Triple(Kind.LAPTOP, "MacBook", 2)
            host.contains("imac") || host.contains("mac-mini") || host.contains("macmini") || host.contains("mac-studio") -> Triple(Kind.COMPUTER, "Mac", 2)
            host.contains("laptop") -> Triple(Kind.LAPTOP, null, 2)
            host.startsWith("desktop-") || host.startsWith("win") || host.contains("-pc") -> Triple(Kind.COMPUTER, "Windows PC", 2)
            said("tv", "roku") -> Triple(Kind.TV, null, 1)
            host.contains("echo") || host.contains("alexa") -> Triple(Kind.SPEAKER, "Amazon Echo", 2)
            host.contains("printer") -> Triple(Kind.PRINTER, null, 1)
            else -> null
        }
        byName?.let { (k, m, c) -> return id(k, m, c, "Host name: ${host.trim()}") }
        if ("_adb-tls-connect._tcp" in svc) return id(Kind.PHONE, "Android (wireless debugging on)", 2, "Android wireless debugging service")
        if ("_smb._tcp" in svc || "_workstation._tcp" in svc || "_ssh._tcp" in svc || "_afpovertcp._tcp" in svc || 445 in ports || 22 in ports) {
            if (vendor.contains("apple")) return id(Kind.COMPUTER, "Mac", 2, "Apple MAC with file sharing/SSH")
            if (vendorIn(PC_BRANDS)) return id(Kind.COMPUTER, null, 2, "PC network-card maker with file sharing/SSH")
            if (!h.randomized) return id(Kind.NAS, h.vendor, 1, "File sharing or SSH, no phone-like traits")
        }
        if (vendor.contains("apple")) return id(Kind.PHONE_OR_PC, "Apple device", 1, "Apple MAC, nothing else answered")
        if (h.randomized) return id(Kind.PHONE_OR_PC, null, 1, "Private MAC: modern phones, tablets and laptops use one per network")
        if (vendorIn(PC_BRANDS)) return id(Kind.COMPUTER, null, 1, "PC network-card maker")
        if (vendorIn(PHONE_BRANDS) && ports.isEmpty()) return id(Kind.PHONE, h.vendor, 1, "Phone maker, no open ports")
        if (h.vendor != null && ports.isEmpty()) return id(Kind.IOT, h.vendor, 1, "Nothing open, maker is not a phone or PC brand")
        return id(Kind.UNKNOWN, null, 0, "Not enough to tell")
    }

    /** Apple tells its exact model in several TXT records (AirPlay `model`, RAOP `am`, companion link `rpMd`, device-info `model`). */
    private fun appleModel(f: Facts): Triple<Kind, String, String>? {
        val raw = (listOf("model", "am", "rpMd").firstNotNullOfOrNull { f.txt(it) } ?: return null).substringBefore("@")
        return when {
            raw.startsWith("iPhone") -> Triple(Kind.PHONE, "iPhone", raw)
            raw.startsWith("iPad") -> Triple(Kind.TABLET, "iPad", raw)
            raw.startsWith("AppleTV") -> Triple(Kind.STREAMER, "Apple TV", raw)
            raw.startsWith("AudioAccessory") -> Triple(Kind.SPEAKER, "HomePod", raw)
            raw.startsWith("Watch") -> Triple(Kind.WATCH, "Apple Watch", raw)
            raw.startsWith("MacBook") -> Triple(Kind.LAPTOP, "MacBook", raw)
            raw.startsWith("Macmini") -> Triple(Kind.COMPUTER, "Mac mini", raw)
            raw.startsWith("iMac") -> Triple(Kind.COMPUTER, "iMac", raw)
            raw.startsWith("Mac") -> Triple(Kind.COMPUTER, "Mac", raw)
            raw.startsWith("RealityDevice") -> Triple(Kind.COMPUTER, "Apple Vision Pro", raw)
            else -> null
        }
    }
}
