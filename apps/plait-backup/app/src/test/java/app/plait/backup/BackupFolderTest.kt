package app.plait.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class BackupFolderTest {
    @Test
    fun ordinaryFoldersAreAccepted() {
        assertNull(BackupFolder.validate("/storage/emulated/0/PlaitBackup"))
        assertNull(BackupFolder.validate("/storage/emulated/0/Backups/Phone_1.2"))
        assertNull(BackupFolder.validate("/storage/emulated/10/Plait-Backup"))
        assertNull(BackupFolder.validate("/storage/1A2B-3C4D/Plait"))
    }

    @Test
    fun anythingElseIsRefused() {
        val bad = listOf(
            "/sdcard/PlaitBackup", "relative/dir", "/data/media/0/PlaitBackup", "/storage/emulated/0", "/storage/emulated/0/",
            "/storage/1A2B-3C4D", "/storage/emulated/0/My Backups", "/storage/emulated/0/a/../b", "/storage/emulated/0/./a",
            "/storage/emulated/0/a;rm -rf x", "/storage/emulated/0/\$HOME", "/storage/emulated/0/a'b", "/storage/emulated/0/a\nb",
            "/storage/emulated/0/Android/data/x", "/storage/emulated/0/android", "/storage/emulated/x/Plait", "/storage/a b/Plait",
        )
        for (p in bad) assertNotNull(p, BackupFolder.validate(p))
    }

    @Test
    fun pickerResultsBecomeFolders() {
        assertEquals("/storage/emulated/0/Backups/Phone", BackupFolder.fromTreeDocumentId("primary:Backups/Phone"))
        assertEquals("/storage/emulated/0/PlaitBackup", BackupFolder.fromTreeDocumentId("primary:"))
        assertEquals("/storage/1234-ABCD/Backups", BackupFolder.fromTreeDocumentId("1234-ABCD:Backups"))
        assertEquals("/storage/1234-ABCD/PlaitBackup", BackupFolder.fromTreeDocumentId("1234-ABCD:"))
        assertNull(BackupFolder.fromTreeDocumentId(""))
    }

    @Test
    fun rootSeesTheSameFolder() {
        assertEquals("/data/media/0/PlaitBackup", BackupFolder.toRootView("/storage/emulated/0/PlaitBackup"))
        assertEquals("/data/media/10/X/Y", BackupFolder.toRootView("/storage/emulated/10/X/Y"))
        assertEquals("/mnt/media_rw/1A2B-3C4D/Plait", BackupFolder.toRootView("/storage/1A2B-3C4D/Plait"))
        assertNull(BackupFolder.toRootView("/storage/emulated/0/a b"))
        assertNull(BackupFolder.toRootView("/data/media/0/x"))
    }
}
