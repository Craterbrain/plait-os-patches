package app.plait.shield

/**
 * DNS providers that work as Android's Private DNS (DNS-over-TLS hostname) and blocklists Shield can merge.
 *
 * The provider tags were checked on 2026-11-06 against each provider's own encrypted resolver: "safe search" means
 * www.google.com and www.bing.com came back as their forced-SafeSearch addresses (forcesafesearch.google.com,
 * strict.bing.com); "YouTube restricted" means www.youtube.com came back as restrict.youtube.com; adult and ad blocking
 * were checked with a well-known adult domain and doubleclick.net.
 */
enum class Group(val title: String) {
    FAMILY("Family-friendly: blocks adult sites"),
    ADS("Blocks ads and trackers"),
    SECURITY("Blocks malware"),
    PLAIN("Unfiltered"),
}

data class Provider(
    val name: String,
    val host: String,
    val group: Group,
    val safeSearch: Boolean = false,
    val youtubeRestricted: Boolean = false,
    val blocksAdult: Boolean = false,
    val blocksAds: Boolean = false,
    val blocksMalware: Boolean = false,
    val note: String? = null,
)

val PROVIDERS = listOf(
    Provider("AdGuard Family", "family.adguard-dns.com", Group.FAMILY, safeSearch = true, youtubeRestricted = true,
        blocksAdult = true, blocksAds = true, blocksMalware = true),
    Provider("Control D Family", "family.freedns.controld.com", Group.FAMILY, safeSearch = true, youtubeRestricted = true,
        blocksAdult = true, blocksAds = true, blocksMalware = true),
    Provider("CleanBrowsing Family", "family-filter-dns.cleanbrowsing.org", Group.FAMILY, safeSearch = true,
        youtubeRestricted = true, blocksAdult = true, blocksMalware = true,
        note = "Also blocks VPNs, proxies and mixed-content sites such as Reddit"),
    Provider("CleanBrowsing Adult", "adult-filter-dns.cleanbrowsing.org", Group.FAMILY, safeSearch = true,
        blocksAdult = true, blocksMalware = true),
    Provider("Cloudflare Family", "family.cloudflare-dns.com", Group.FAMILY, safeSearch = true, blocksAdult = true,
        blocksMalware = true),
    Provider("Mullvad Family", "family.dns.mullvad.net", Group.FAMILY, blocksAdult = true, blocksAds = true,
        blocksMalware = true, note = "Also blocks gambling"),
    Provider("Mullvad All", "all.dns.mullvad.net", Group.FAMILY, blocksAdult = true, blocksAds = true,
        blocksMalware = true, note = "Also blocks gambling and social media"),

    Provider("AdGuard", "dns.adguard-dns.com", Group.ADS, blocksAds = true, blocksMalware = true),
    Provider("Mullvad Ad block", "adblock.dns.mullvad.net", Group.ADS, blocksAds = true),
    Provider("Mullvad Base", "base.dns.mullvad.net", Group.ADS, blocksAds = true, blocksMalware = true),
    Provider("Mullvad Extended", "extended.dns.mullvad.net", Group.ADS, blocksAds = true, blocksMalware = true,
        note = "Also blocks social media trackers"),
    Provider("Control D Ads", "p2.freedns.controld.com", Group.ADS, blocksAds = true, blocksMalware = true),

    Provider("Quad9", "dns.quad9.net", Group.SECURITY, blocksMalware = true),
    Provider("Cloudflare Security", "security.cloudflare-dns.com", Group.SECURITY, blocksMalware = true),
    Provider("CleanBrowsing Security", "security-filter-dns.cleanbrowsing.org", Group.SECURITY, blocksMalware = true),

    Provider("Cloudflare", "one.one.one.one", Group.PLAIN),
    Provider("Google", "dns.google", Group.PLAIN),
    Provider("AdGuard Unfiltered", "unfiltered.adguard-dns.com", Group.PLAIN),
    Provider("Mullvad", "dns.mullvad.net", Group.PLAIN),
    Provider("Control D Unfiltered", "p0.freedns.controld.com", Group.PLAIN),
)

/** A blocklist: a hosts file or a plain list of domains, one per line. */
data class Blocklist(
    val id: String,
    val name: String,
    val description: String,
    val url: String,
    val tag: String,
    val approxEntries: Int,
    val onByDefault: Boolean = false,
)

private const val HAGEZI = "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard/"
private const val STEVENBLACK = "https://raw.githubusercontent.com/StevenBlack/hosts/master/"

val BLOCKLISTS = listOf(
    Blocklist("stevenblack", "StevenBlack Unified", "Ads and malware, a widely used base list", STEVENBLACK + "hosts",
        "Ads", 78_000, onByDefault = true),
    Blocklist("adaway", "AdAway", "Mobile ads, small and conservative", "https://adaway.org/hosts.txt", "Ads", 6_500),
    Blocklist("hagezi-light", "HaGeZi Light", "Ads, tracking, metrics; breaks almost nothing", HAGEZI + "light-onlydomains.txt",
        "Ads", 92_000),
    Blocklist("hagezi-multi", "HaGeZi Multi Normal", "Ads, tracking, malware, scams; the balanced choice",
        HAGEZI + "multi-onlydomains.txt", "Ads", 171_000),
    Blocklist("hagezi-pro", "HaGeZi Pro", "Stricter; may occasionally block something you want", HAGEZI + "pro-onlydomains.txt",
        "Ads", 219_000),
    Blocklist("hagezi-nsfw", "HaGeZi NSFW", "Adult sites", HAGEZI + "nsfw-onlydomains.txt", "Adult", 78_000),
    Blocklist("stevenblack-porn", "StevenBlack Adult", "Unified plus adult sites", STEVENBLACK + "alternates/porn/hosts",
        "Adult", 154_000),
    Blocklist("hagezi-gambling", "HaGeZi Gambling", "Gambling sites (large list)", HAGEZI + "gambling-onlydomains.txt",
        "Gambling", 631_000),
    Blocklist("hagezi-bypass", "HaGeZi DoH, VPN and proxy bypass",
        "Blocks services apps use to get around your DNS provider; pair with a family DNS",
        HAGEZI + "doh-vpn-proxy-bypass-onlydomains.txt", "Bypass", 16_000),
    Blocklist("hagezi-nosafesearch", "HaGeZi No SafeSearch",
        "Blocks search engines that cannot enforce safe search; pair with a safe search DNS",
        HAGEZI + "nosafesearch-onlydomains.txt", "Safe search", 200),
)
