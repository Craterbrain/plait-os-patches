package app.plait.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64

class AppMetaTest {
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4, 5, 6, 7, 8)

    @Test
    fun labelsAreCleanedForDisplay() {
        assertEquals("Balatro", AppMeta.parseLabel("Balatro"))
        assertEquals("Broker Test Client", AppMeta.parseLabel("  Broker Test Client \nsecond line"))
        assertEquals("AB", AppMeta.parseLabel("A\u0000\u0007B"))
        assertNull(AppMeta.parseLabel(""))
        assertNull(AppMeta.parseLabel("   \n"))
        assertNull(AppMeta.parseLabel(null))
        assertEquals(AppMeta.MAX_LABEL_CHARS, AppMeta.parseLabel("x".repeat(500))?.length)
    }

    @Test
    fun onlySmallPngsAreIcons() {
        assertTrue(AppMeta.looksLikeIcon(png))
        assertFalse(AppMeta.looksLikeIcon(null))
        assertFalse(AppMeta.looksLikeIcon(ByteArray(0)))
        assertFalse(AppMeta.looksLikeIcon("not a png at all".toByteArray()))
        assertFalse(AppMeta.looksLikeIcon(png + ByteArray(AppMeta.MAX_ICON_BYTES)))
    }

    /** Runs the listing script against a real folder with a real shell, then parses what it printed. */
    @Test
    fun theListingScriptFindsNamesAndIcons() {
        val root = File("build/archives-test").apply { deleteRecursively(); mkdirs() }
        fun archive(pkg: String, label: String?, icon: ByteArray?) {
            val d = File(root, "apps/$pkg/user_0").apply { mkdirs() }
            File(d, "apk.tar.zst.enc").writeBytes(ByteArray(2048))
            File(d, "user.tar.zst.enc").writeBytes(ByteArray(4096))
            label?.let { File(d, AppMeta.LABEL).writeText(it) }
            icon?.let { File(d, AppMeta.ICON).writeBytes(it) }
        }
        archive("com.playstack.balatro.android", "Balatro", png)
        archive("app.plait.testclient", "Broker Test Client", null)
        archive("org.old.noicon", null, null)
        archive("com.big.icon", "Too Big", png + ByteArray(AppMeta.MAX_ICON_BYTES))

        val script = DevRootBackend.listScript(root.absolutePath)
        val p = ProcessBuilder("sh", "-c", script).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        assertEquals(out, 0, p.waitFor())

        val byPkg = DevRootBackend.parseList(out).associateBy { it.packageName }
        assertEquals(4, byPkg.size)
        assertEquals("Balatro", byPkg.getValue("com.playstack.balatro.android").label)
        assertArrayEquals(png, byPkg.getValue("com.playstack.balatro.android").iconPng)
        assertEquals("Broker Test Client", byPkg.getValue("app.plait.testclient").label)
        assertNull(byPkg.getValue("app.plait.testclient").iconPng)
        assertNull(byPkg.getValue("org.old.noicon").label)
        // an icon over the limit is never read at all
        assertEquals("Too Big", byPkg.getValue("com.big.icon").label)
        assertNull(byPkg.getValue("com.big.icon").iconPng)
        assertEquals(setOf(Part.APK, Part.USER), byPkg.getValue("com.playstack.balatro.android").parts)
    }

    @Test
    fun aTamperedListingCannotInjectAnApp() {
        val b64 = Base64.getEncoder().encodeToString(png)
        val out = "evil;rm/user_0 apk.tar.zst 1\nI evil;rm/user_0 $b64\nL x/user_0 name\nok.app.pkg/user_0 apk.tar.zst.enc 4\nI ok.app.pkg/user_0 !!!notbase64!!!\n"
        val list = DevRootBackend.parseList(out)
        assertEquals(listOf("ok.app.pkg"), list.map { it.packageName })
        assertNull(list.single().iconPng)
    }
}
