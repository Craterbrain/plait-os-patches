package app.plait.broker

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import org.xmlpull.v1.XmlPullParser

/**
 * What an app declares it will use through the broker, from its manifest:
 * `<meta-data android:name="app.plait.broker.capabilities" android:resource="@xml/..."/>` pointing at
 * ```
 * <plait-broker>
 *     <command rule="settings get global private_dns_mode" reason="Show which DNS provider is set"/>
 *     <ask key="private dns" reason="Set the DNS provider you pick"/>
 * </plait-broker>
 * ```
 * Shown when the app asks for access; allowing switches on exactly these. Declarations only pre-approve: the
 * broker's tiers still apply (a declared command that is Never, Gated or acts on another app is still refused or asked).
 */
data class Declared(val type: String, val key: String, val reason: String)

/**
 * The declared items the broker has never heard of for this app: neither switched on nor off. An app that updates and
 * declares more shows up here, and only these are asked about again, so what the user already switched off stays off.
 */
fun newDeclarations(declared: List<Declared>, known: List<Cap>): List<Declared> =
    declared.filter { d -> known.none { it.type == d.type && it.key == d.key } }

const val CAPABILITIES_META = "app.plait.broker.capabilities"
/** Asked-once functions an app may declare; anything else in an `ask` element is ignored. */
private val DECLARABLE_ASKS = setOf("hosts", "private dns", "logcat", "processes", "connections", "dmesg")

fun declaredBy(pm: PackageManager, pkg: String): List<Declared> = runCatching {
    val ai = pm.getApplicationInfo(pkg, PackageManager.GET_META_DATA)
    val res = ai.metaData?.getInt(CAPABILITIES_META, 0) ?: 0
    if (res == 0) return emptyList()
    parse(pm, ai, res)
}.getOrDefault(emptyList())

private fun parse(pm: PackageManager, ai: ApplicationInfo, res: Int): List<Declared> {
    val out = mutableListOf<Declared>()
    pm.getResourcesForApplication(ai).getXml(res).use { x ->
        while (x.next() != XmlPullParser.END_DOCUMENT && out.size < 64) {
            if (x.eventType != XmlPullParser.START_TAG) continue
            val reason = x.getAttributeValue(null, "reason").orEmpty().take(200)
            when (x.name) {
                "command" -> x.getAttributeValue(null, "rule")?.take(300)?.let { out += Declared("exec", it, reason) }
                "ask" -> x.getAttributeValue(null, "key")?.takeIf { it in DECLARABLE_ASKS }?.let { out += Declared("ask", it, reason) }
            }
        }
    }
    return out
}
