package app.plait.backup

import java.text.Normalizer

/**
 * What a keyboard can do to a passphrase typed into a field it takes for ordinary text: capitalise the first letter, add or
 * keep a trailing space (after "completing" a word), normalise accents. If the passphrase typed now fails on a file, these are
 * tried before giving up: the backup may have been made from the altered text. Pure, so it is unit-tested on the PC.
 */
object PassphraseVariants {
    private fun cap(s: String) = s.replaceFirstChar { it.uppercaseChar() }
    private fun decap(s: String) = s.replaceFirstChar { it.lowercaseChar() }

    /** The alterations of [typed] worth trying, most likely first, each with a description; never [typed] itself. */
    fun of(typed: String): List<Pair<String, String>> {
        val t = typed.trim()
        val all = listOf(
            "without the leading or trailing space" to t,
            "with a trailing space" to "$t ",
            "first letter capitalised" to cap(t),
            "first letter lower case" to decap(t),
            "first letter capitalised, with a trailing space" to cap(t) + " ",
            "first letter lower case, with a trailing space" to decap(t) + " ",
            "accents composed (NFC)" to Normalizer.normalize(typed, Normalizer.Form.NFC),
            "accents decomposed (NFD)" to Normalizer.normalize(typed, Normalizer.Form.NFD),
        )
        return all.filter { it.second != typed && it.second.isNotEmpty() }.distinctBy { it.second }
    }

    /** The first variant for which [works] says yes (it derives the key and checks a file), or null. */
    fun find(typed: String, works: (String) -> Boolean): Pair<String, String>? = of(typed).firstOrNull { works(it.second) }
}
