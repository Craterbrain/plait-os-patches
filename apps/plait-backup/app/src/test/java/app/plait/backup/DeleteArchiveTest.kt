package app.plait.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Deleting a backup goes only through [Layout.archiveRelative]: it may name one backup's own folder and nothing else. */
class DeleteArchiveTest {
    @Test fun appBackupIsItsUserFolder() =
        assertEquals("apps/org.example.notes/user_0", Layout.archiveRelative("org.example.notes", 0))

    @Test fun otherUsersGetTheirOwnFolder() =
        assertEquals("apps/org.example.notes/user_10", Layout.archiveRelative("org.example.notes", 10))

    @Test fun phoneDataMapsToItsPersonalFolder() {
        assertEquals("personal/call_logs/user_0", Layout.archiveRelative(Personal.CALL_LOG.target, 0))
        assertEquals("personal/contacts/user_0", Layout.archiveRelative(Personal.CONTACTS.target, 0))
    }

    @Test fun nothingOutsideABackupFolderCanBeNamed() {
        for (bad in listOf("", ".", "..", "../..", "apps", "org.example/../../etc", "org.example notes", "org.example;rm",
                "/data", "org.example\$(id)", "@unknown", "a", "org..example", ".org.example", "org.example/")) {
            assertNull("'$bad' must be refused", Layout.archiveRelative(bad, 0))
        }
    }

    @Test fun negativeUserIsRefused() = assertNull(Layout.archiveRelative("org.example.notes", -1))
}
