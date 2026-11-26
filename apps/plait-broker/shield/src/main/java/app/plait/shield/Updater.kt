package app.plait.shield

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.io.IOException

/**
 * Writes blocklists into the hosts file through Broker, for the Apply button and for the daily update (never both at
 * once). What was last applied is remembered, so the daily update re-applies exactly that, and only writes when a
 * list's content or the never-block names changed since the last write.
 */
object Updater {
    const val PREFS = "shield"
    private const val CACHE_PREFS = "list-cache"

    // In PREFS: what is applied, for the daily update and the status line
    const val ACTIVE = "active"
    const val APPLIED_LISTS = "applied_lists"
    const val APPLIED_ALLOW = "applied_allow"
    private const val WRITTEN = "written_signature"
    const val CHECKED_AT = "checked_at"
    const val RESULT = "update_result"

    private val lock = Any()

    fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun cache(context: Context): ListCache {
        val p = context.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)
        val store = object : Store {
            override fun get(key: String) = p.getString(key, null)
            override fun put(key: String, value: String?) {
                p.edit().apply { if (value == null) remove(key) else putString(key, value) }.apply()
            }
        }
        return ListCache(File(context.filesDir, "lists"), store)
    }

    fun parseAllow(text: String): Set<String> =
        text.split(',', '\n', ' ').map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()

    fun hostsLines(): Int? =
        runCatching { Broker.run("wc", "-l", "/system/etc/hosts").out.trim().substringBefore(' ').toInt() }.getOrNull()

    /**
     * Brings [lists] up to date (cached copies when unchanged or unreachable) and writes them with [allowText] left out.
     * With [force] off (the daily update) nothing is written when the result would be what is already there.
     * Returns the message to show; throws when a list has neither a fresh nor a saved copy, or Broker refuses the write.
     */
    fun apply(context: Context, lists: List<Blocklist>, allowText: String, force: Boolean, progress: (String) -> Unit = {}): String =
        synchronized(lock) {
            val prefs = prefs(context)
            val cache = cache(context)
            val fetched = lists.map { cache.fetch(it, progress) }
            val allow = parseAllow(allowText)
            val signature = ListCache.signature(fetched, allow)
            val notes = fetched.mapNotNull { it.note }.joinToString("") { "\n$it" }
            val now = System.currentTimeMillis()

            if (!force && signature == prefs.getString(WRITTEN, null) && (hostsLines() ?: 0) > 3) {
                val message = "No changes in ${lists.size} list${if (lists.size == 1) "" else "s"}$notes"
                prefs.edit().putLong(CHECKED_AT, now).putString(RESULT, message).apply()
                return@synchronized message
            }

            progress("Merging…")
            val names = Merge.merge(fetched, allow)
            progress("Writing ${"%,d".format(names.size)} names…")
            val r = Broker.run("dd", "of=/system/etc/hosts") { out -> Merge.write(names, lists, out) }
            if (!r.ok) throw IOException("Not written: ${r.err.removePrefix("plait-broker: ")}")

            cache.keepOnly(lists.map { it.id }.toSet())
            val message = "Blocking ${"%,d".format(names.size)} names$notes"
            prefs.edit()
                .putBoolean(ACTIVE, true)
                .putStringSet(APPLIED_LISTS, lists.map { it.id }.toSet())
                .putString(APPLIED_ALLOW, allowText)
                .putString(WRITTEN, signature)
                .putLong(CHECKED_AT, now)
                .putString(RESULT, message)
                .apply()
            message
        }

    /** Empties the hosts file's blocklist and stops the daily update's writes. The saved lists stay for a quick re-apply. */
    fun turnOff(context: Context): String = synchronized(lock) {
        val r = Broker.run("dd", "of=/system/etc/hosts") { out -> Merge.write(emptySet(), emptyList(), out) }
        if (!r.ok) throw IOException("Not written: ${r.err.removePrefix("plait-broker: ")}")
        prefs(context).edit().putBoolean(ACTIVE, false).remove(WRITTEN).remove(RESULT).apply()
        "Blocklist turned off"
    }
}
