package app.plait.backup

import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.AEADBadTagException

class DecryptMessageTest {
    @Test fun `a missing key is told apart from a wrong passphrase`() {
        val missing = ArchiveCrypto.describe(IllegalStateException("no key for this file's passphrase"))
        val wrong = ArchiveCrypto.describe(AEADBadTagException("Tag mismatch"))
        assertTrue(missing, "different passphrase" in missing && "older backups" in missing)
        assertTrue(wrong, "does not match" in wrong)
    }

    @Test fun `other failures keep their real cause`() {
        assertTrue(ArchiveCrypto.describe(java.io.FileNotFoundException("/x: Permission denied")).contains("Permission denied"))
        assertTrue("damaged" in ArchiveCrypto.describe(IllegalStateException("file is cut short")))
        assertTrue("not an encrypted" in ArchiveCrypto.describe(IllegalArgumentException("not a Backup encrypted file")))
    }
}
