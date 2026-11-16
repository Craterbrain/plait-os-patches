package app.plait.scan

import android.content.Context

/** Who made a network card, from the IEEE list (assets/oui.tsv, built from hwdata's oui.txt). */
class Oui private constructor(private val vendors: Map<String, String>) {
    fun vendor(mac: String): String? = vendors[mac.filter { it != ':' }.take(6).lowercase()]

    companion object {
        fun load(ctx: Context): Oui {
            val map = HashMap<String, String>(48_000)
            ctx.assets.open("oui.tsv").bufferedReader().useLines { lines ->
                for (line in lines) {
                    val tab = line.indexOf('\t')
                    if (tab == 6) map[line.substring(0, 6)] = line.substring(7)
                }
            }
            return Oui(map)
        }

        /** Phones and laptops pick a made-up address per network; the vendor of those is meaningless. */
        fun randomized(mac: String): Boolean = (mac.substring(0, 2).toIntOrNull(16) ?: 0) and 0x02 != 0
    }
}
