package app.plait.backup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ScriptsTest {
    @Test
    fun scriptsAreWrittenForOnDeviceTesting() {
        val dir = File("build/scripts").apply { mkdirs() }
        val pkg = System.getProperty("pkg") ?: "app.plait.testclient"
        File(dir, "backup.sh").writeText(DevRootBackend.backupScript(pkg, 0, Part.entries.toSet()))
        File(dir, "restore.sh").writeText(DevRootBackend.restoreScript(pkg, 0, Part.entries.toSet() - Part.APK))
        File(dir, "restore_apk.sh").writeText(DevRootBackend.restoreScript(pkg, 0, setOf(Part.APK)))
        assertTrue(File(dir, "backup.sh").readText().contains("apk.tar.zst"))
    }

    @Test
    fun onlyRealPackageNamesReachTheShell() {
        assertTrue(Layout.isSafePackage("com.example.app_2"))
        assertFalse(Layout.isSafePackage("com.example;rm -rf /"))
        assertFalse(Layout.isSafePackage("../../data"))
        assertFalse(Layout.isSafePackage("noDots"))
    }
}
