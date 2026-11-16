package app.plait.broker

import org.json.JSONObject

data class Cap(val type: String, val key: String, val allowed: Boolean, val uses: Int, val blocked: Int, val last: Long)

data class AppEntry(val uid: Int, val pkg: String, val mode: String, val caps: List<Cap>, val note: String? = null)

data class Snapshot(
    val entries: List<AppEntry>,
    val audit: List<String>,
    val gatesEnabled: Boolean = false,
    val hostsActive: Boolean = false,
    val hostsEntries: Int = -1
)

fun parseSnapshot(json: String): Snapshot {
    val root = JSONObject(json)
    val entries = root.getJSONArray("entries").let { arr ->
        List(arr.length()) { i ->
            val e = arr.getJSONObject(i)
            val pkgs = e.getJSONArray("packages")
            val caps = e.getJSONArray("caps").let { c ->
                List(c.length()) { k ->
                    val o = c.getJSONObject(k)
                    Cap(o.getString("type"), o.getString("key"), o.getBoolean("allowed"), o.optInt("uses"), o.optInt("blocked"), o.optLong("last"))
                }
            }
            AppEntry(
                e.getInt("uid"), if (pkgs.length() > 0) pkgs.getString(0) else "uid ${e.getInt("uid")}", e.getString("mode"), caps,
                e.optString("note").ifEmpty { null }
            )
        }
    }
    val audit = root.getJSONArray("audit").let { a -> List(a.length()) { a.getString(it) }.asReversed() }
    return Snapshot(entries, audit, root.optBoolean("gatesEnabled"), root.optBoolean("hostsActive"), root.optInt("hostsEntries", -1))
}

const val MODE_DENIED = "denied"
const val MODE_RESTRICTED = "restricted"
const val MODE_PERMISSIVE = "permissive"
const val MODE_BLOCKED = "blocked"
