package app.plait.scan

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONObject

/**
 * The open ports (and what they answered) found on each device the last time it was scanned, kept by MAC address so they
 * survive the phone changing the device's IP. The device page shows them at once and re-checks them first.
 */
object PortCache {
    class Entry(val time: Long, val ports: List<Int>, val services: Map<Int, ServiceProbe.Info>)

    private var prefs: SharedPreferences? = null
    private const val MAX_DEVICES = 500

    fun init(ctx: Context) {
        prefs = ctx.applicationContext.getSharedPreferences("port-cache", Context.MODE_PRIVATE)
    }

    fun get(mac: String?): Entry? {
        val raw = mac?.let { prefs?.getString(it, null) } ?: return null
        return runCatching {
            val o = JSONObject(raw)
            val ports = o.getJSONArray("ports").let { a -> (0 until a.length()).map { a.getInt(it) } }
            val svc = o.optJSONObject("svc")
            val services = svc?.keys()?.asSequence()?.associate { k ->
                val s = svc.getJSONObject(k)
                k.toInt() to ServiceProbe.Info(s.getString("s"), s.optString("d").ifEmpty { null }, s.optString("a").ifEmpty { null }, s.optString("o").ifEmpty { null })
            } ?: emptyMap()
            Entry(o.getLong("t"), ports, services)
        }.getOrNull()
    }

    fun put(mac: String?, ports: List<Int>, services: Map<Int, ServiceProbe.Info>) {
        val p = prefs ?: return
        if (mac == null) return
        val svc = JSONObject()
        for ((port, i) in services) {
            if (port !in ports) continue
            svc.put(port.toString(), JSONObject().put("s", i.service).put("d", i.detail ?: "").put("a", i.app ?: "").put("o", i.source ?: ""))
        }
        val now = System.currentTimeMillis()
        val e = JSONObject().put("t", now).put("ports", org.json.JSONArray(ports)).put("svc", svc)
        val editor = p.edit().putString(mac, e.toString())
        // keep it bounded: forget the devices not seen for longest
        val all = p.all
        if (all.size >= MAX_DEVICES) {
            all.entries.mapNotNull { (k, v) -> runCatching { k to JSONObject(v as String).getLong("t") }.getOrNull() }
                .sortedBy { it.second }.take(all.size - MAX_DEVICES + 1).forEach { editor.remove(it.first) }
        }
        editor.apply()
    }
}
