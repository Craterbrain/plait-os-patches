package app.plait.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InstallerSettingTest {
    @Test
    fun theRecordedInstallerIsReadFromTheArchive() {
        assertEquals("com.android.vending", InstallerSetting.parseRecorded("com.android.vending"))
        assertEquals("org.fdroid.fdroid", InstallerSetting.parseRecorded("org.fdroid.fdroid\n"))
        assertEquals("com.aurora.store", InstallerSetting.parseRecorded("  com.aurora.store  \nignored"))
        assertNull(InstallerSetting.parseRecorded(null))
        assertNull(InstallerSetting.parseRecorded(""))
        assertNull(InstallerSetting.parseRecorded("null"))
        // not a package name: never reaches a shell command or a service call
        assertNull(InstallerSetting.parseRecorded("com.x; rm -rf /"))
        assertNull(InstallerSetting.parseRecorded("../../data"))
        assertNull(InstallerSetting.parseRecorded("a b"))
    }

    @Test
    fun onlyKnownStoresAreRecorded() {
        // adb, the system, our own apps, and any app a tampered archive might name
        for (p in listOf("com.android.shell", "android", "app.plait.backup", "app.plait.broker", "com.evil.updater", "com.google.android.packageinstaller")) {
            assertNull(p, InstallerSetting.parseRecorded(p))
        }
        assertTrue(InstallerSetting.PLAY_STORE in InstallerSetting.STORES)
    }

    @Test
    fun theSettingDecidesWhatIsRecorded() {
        assertEquals("org.fdroid.fdroid", InstallerSetting.installerFor(InstallerChoice.ORIGINAL, "org.fdroid.fdroid"))
        assertNull(InstallerSetting.installerFor(InstallerChoice.ORIGINAL, null))
        assertEquals("com.android.vending", InstallerSetting.installerFor(InstallerChoice.PLAY, "org.fdroid.fdroid"))
        assertEquals("com.android.vending", InstallerSetting.installerFor(InstallerChoice.PLAY, null))
        assertNull(InstallerSetting.installerFor(InstallerChoice.NONE, "com.android.vending"))
    }

    @Test
    fun aRecordedInstallerIsInstalledAsTheSystemUser() {
        val pkg = "app.plait.testclient"
        val apk = setOf(Part.APK)
        val withPlay = DevRootBackend.restoreScript(pkg, 0, apk, installer = InstallerSetting.PLAY_STORE)
        // all three install steps run as uid 1000, so Android records the store as the one that started the install
        assertTrue(withPlay.contains("su 1000 -c \"pm install-create -r --user 0 -i com.android.vending --install-reason 4 --package-source 2 --update-ownership\""))
        assertTrue(withPlay.contains("su 1000 -c \"pm install-write -S "))
        assertTrue(withPlay.contains("su 1000 -c \"pm install-commit "))
        // the uid must come before -c: "su -c cmd 1000" runs as root and the install is recorded as started by the shell
        assertFalse(withPlay.contains("\" 1000"))
        assertTrue(withPlay.contains("cat \$F | su 1000 -c"))
        // no installer: a plain root install, no su and no installer options
        val plain = DevRootBackend.restoreScript(pkg, 0, apk)
        assertFalse(plain.contains("su -c"))
        assertFalse(plain.contains(" -i "))
        assertTrue(plain.contains("cat \$F | pm install-write"))
    }

    @Test
    fun theInstallerReachesTheShellOnlyAsAPackageName() {
        val pkg = "app.plait.testclient"
        val apk = setOf(Part.APK)
        val bad = DevRootBackend.restoreScript(pkg, 0, apk, installer = "x; reboot")
        assertFalse(bad.contains("reboot"))
        assertFalse(bad.contains("su -c"))
        // the backup records the installer in the archive, under the app's own package name
        val backup = DevRootBackend.backupScript(pkg, 0, apk)
        assertTrue(backup.contains("pm list packages -i --user 0"))
        assertTrue(backup.contains("installer="))
        assertTrue(backup.contains(InstallerSetting.FILE))
    }

    @Test
    fun theGeneratedScriptsAreValidShell() {
        val dir = java.io.File("build/scripts").apply { mkdirs() }
        val scripts = mapOf(
            "restore_apk_plain.sh" to DevRootBackend.restoreScript("app.plait.testclient", 0, Part.entries.toSet()),
            "restore_apk_store.sh" to DevRootBackend.restoreScript("app.plait.testclient", 0, Part.entries.toSet(), installer = InstallerSetting.PLAY_STORE),
            "backup_installer.sh" to DevRootBackend.backupScript("app.plait.testclient", 0, Part.entries.toSet()),
        )
        for ((name, text) in scripts) {
            val f = java.io.File(dir, name).apply { writeText(text) }
            val p = ProcessBuilder("sh", "-n", f.path).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            assertEquals("$name: $out", 0, p.waitFor())
        }
    }
}
