package app.plait.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ArchiveCheckTest {
    private val pkg = "com.example.app"

    @Test
    fun manifestRoundTripsAndComparesExactly() {
        val hashes = mapOf("user.tar.zst" to "a".repeat(64), "apk.tar.zst" to "b".repeat(64))
        val m = ArchiveCheck.parse(ArchiveCheck.manifest(pkg, 0, 1L, hashes))!!
        assertEquals(pkg, m.pkg)
        assertNull(ArchiveCheck.compare(m, hashes))
        assertNotNull(ArchiveCheck.compare(m, hashes + ("evil.tar.zst" to "c".repeat(64))))
        assertNotNull(ArchiveCheck.compare(m, hashes - "apk.tar.zst"))
        assertNotNull(ArchiveCheck.compare(m, hashes + ("user.tar.zst" to "d".repeat(64))))
    }

    @Test
    fun sha256sumOutputParsesWithoutManifestFiles() {
        val out = "${"a".repeat(64)}  user.tar.zst\n${"b".repeat(64)}  manifest.txt\n"
        assertEquals(mapOf("user.tar.zst" to "a".repeat(64)), ArchiveCheck.parseSums(out))
    }

    private fun check(vararg entries: Pair<String, String>) =
        ArchiveCheck.unsafeDataEntry(pkg, entries.map { it.first }, entries.map { it.second })

    @Test
    fun dataEntriesMustStayInsideThePackage() {
        assertNull(check("$pkg/" to "drwx------ a/a 0 x y $pkg/", "$pkg/files/x" to "-rw------- a/a 1 x y $pkg/files/x"))
        assertNotNull(check("$pkg/../com.other/x" to "-rw------- a/a 1 x y $pkg/../com.other/x"))
        assertNotNull(check("/data/system/x" to "-rw------- a/a 1 x y /data/system/x"))
        assertNotNull(check("com.other/x" to "-rw------- a/a 1 x y com.other/x"))
        assertNotNull(check("$pkg/dev" to "crw------- a/a 1 x y $pkg/dev"))
        assertNotNull(check("$pkg/hard" to "hrw------- a/a 1 x y $pkg/hard"))
    }

    @Test
    fun symlinksMayOnlyPointInside() {
        assertNull(check("$pkg/l" to "lrwxrwxrwx a/a 0 x y $pkg/l -> files/x"))
        assertNotNull(check("$pkg/l" to "lrwxrwxrwx a/a 0 x y $pkg/l -> /data/system"))
        assertNotNull(check("$pkg/l" to "lrwxrwxrwx a/a 0 x y $pkg/l -> ../../com.other"))
    }

    @Test
    fun apkArchivesHoldOnlyApks() {
        assertNull(ArchiveCheck.unsafeApkEntry(listOf("base.apk", "split_config.arm64_v8a.apk"), listOf("-rw", "-rw")))
        assertNotNull(ArchiveCheck.unsafeApkEntry(listOf("../x.apk"), listOf("-rw")))
        assertNotNull(ArchiveCheck.unsafeApkEntry(listOf("base.apk"), listOf("lrw")))
        assertNotNull(ArchiveCheck.unsafeApkEntry(listOf("run.sh"), listOf("-rw")))
    }
}
