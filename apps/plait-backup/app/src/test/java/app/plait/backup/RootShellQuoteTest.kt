package app.plait.backup

import org.junit.Assert.assertEquals
import org.junit.Test

class RootShellQuoteTest {
    @Test
    fun quotedNamesReachTheShellUnchanged() {
        for (name in listOf("PART_1_New#Recording_1.opus", "PART_2_CHH SAFETY TRAINING 2024.pdf", "PART_3_Anna\u00e2\u0080\u0099.epub", "it's \$(rm -rf x) `y` \"z\";&|*")) {
            val p = ProcessBuilder("sh", "-c", "printf %s ${RootShell.quote(name)}").start()
            assertEquals(name, p.inputStream.bufferedReader().readText())
        }
    }
}
