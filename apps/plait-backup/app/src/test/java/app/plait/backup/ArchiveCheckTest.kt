package app.plait.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ArchiveCheckTest {
    private val pkg = "com.example.app"

    @Test
    fun verifyStoredChecksContentsAndReportsSigner() {
        val hashes = mapOf("user.tar.zst" to "a".repeat(64))
        val text = ArchiveCheck.manifest(pkg, 10, 1L, hashes)
        fun verify(signer: String, actual: Map<String, String>) =
            ArchiveCheck.verifyStored(pkg, 10, text, "sig", signer, { "ours" }, { _, _, _ -> true }) { actual }

        assertEquals("Verified · 1 file matches · Trusted signer", verify("ours", hashes))
        assertEquals("Verified · 1 file matches · Signed by another install", verify("foreign", hashes))
        assertNotNull(runCatching { verify("ours", hashes - "user.tar.zst") }.exceptionOrNull())
        assertNotNull(runCatching { verify("ours", mapOf("user.tar.zst" to "b".repeat(64))) }.exceptionOrNull())
    }

    @Test
    fun verifyStoredRejectsWrongUserAndInvalidSignatureBeforeReadingPayload() {
        val text = ArchiveCheck.manifest(pkg, 10, 1L, emptyMap())
        fun verify(user: Int, valid: Boolean) =
            ArchiveCheck.verifyStored(pkg, user, text, "sig", "foreign", { "ours" }, { _, _, _ -> valid }) {
                throw AssertionError("Untrusted payload was read")
            }

        assertEquals("Manifest does not match the selected archive",
            runCatching { verify(0, true) }.exceptionOrNull()?.message)
        assertEquals("Invalid archive signature", runCatching { verify(10, false) }.exceptionOrNull()?.message)
    }

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
