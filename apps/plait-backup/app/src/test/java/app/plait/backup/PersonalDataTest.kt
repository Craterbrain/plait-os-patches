package app.plait.backup

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

class PersonalDataTest {
    /** A provider in memory: tables by URI, rows with ids, part files by part id. */
    private class FakeStore : PersonalStore {
        val tables = mutableMapOf<String, MutableList<MutableMap<String, Any?>>>()
        val files = mutableMapOf<Long, ByteArray>()
        val deleted = mutableListOf<String>()
        var nextId = 100L
        var threadQueries = mutableListOf<String>()

        fun table(uri: String) = tables.getOrPut(uri) { mutableListOf() }

        fun add(uri: String, vararg fields: Pair<String, Any?>): Long {
            val id = nextId++
            table(uri).add(mutableMapOf<String, Any?>("_id" to id).apply { putAll(fields) })
            return id
        }

        override fun query(uri: String, projection: Array<String>?, selection: String?, args: Array<String>?, sort: String?): Rows {
            if (uri.startsWith("content://mms-sms/threadID")) {
                threadQueries += uri
                return Rows(listOf("_id"), listOf(listOf(7L)))
            }
            // The columns this provider has: those of any row, plus a few every table has
            val all = table(uri)
            val columns = (projection?.toList() ?: (all.flatMap { it.keys } + listOf("_id", "date", "type", "address", "body",
                "number", "duration", "msg_box", "m_type", "sub", "display_name")).distinct())
            val rows = if (selection == "0") emptyList() else all.filter { selection != "deleted = 0" || it["deleted"] != 1L }
            return Rows(columns, rows.map { r -> columns.map { r[it] } })
        }

        override fun insert(uri: String, values: Map<String, Any?>): String {
            val id = nextId++
            val target = when {
                uri.matches(Regex("content://mms/\\d+/part")) -> "content://mms/part"
                else -> uri
            }
            table(target).add(values.toMutableMap().apply { put("_id", id) })
            return if (target == "content://mms/part") "content://mms/part/$id" else "$uri/$id"
        }

        override fun delete(uri: String): Int {
            deleted += uri
            return 1
        }

        override fun read(uri: String): InputStream = ByteArrayInputStream(files.getValue(uri.substringAfterLast('/').toLong()))

        override fun write(uri: String): OutputStream {
            val id = uri.substringAfterLast('/').toLong()
            return object : ByteArrayOutputStream() {
                override fun close() {
                    files[id] = toByteArray()
                }
            }
        }
    }

    private fun roundTrip(kind: Personal, source: FakeStore, target: FakeStore): PersonalResult {
        val attachments = mutableMapOf<String, ByteArray>()
        val json = PersonalExport.export(kind, source) { name, open -> attachments[name] = open().readBytes() }
        return PersonalImport(target) {}.import(kind, json) { attachments[it]?.let(::ByteArrayInputStream) }
    }

    @Test
    fun callsAreRestoredOnceAndUnfinishedOnesNever() {
        val old = FakeStore()
        old.add(PersonalFormat.CALLS, "number" to "+15551234", "date" to 1000L, "duration" to 30L, "type" to 1L, "is_read" to 1L)
        old.add(PersonalFormat.CALLS, "number" to "+15559999", "date" to 2000L, "duration" to 0L, "type" to 4L) // voicemail
        val new = FakeStore()
        assertEquals(PersonalResult(added = 1, skipped = 1, failed = 0), roundTrip(Personal.CALL_LOG, old, new))
        val row = new.table(PersonalFormat.CALLS).single()
        assertEquals("+15551234", row["number"])
        assertEquals(30L, row["duration"])
        // Restoring again adds nothing
        assertEquals(PersonalResult(added = 0, skipped = 2, failed = 0), roundTrip(Personal.CALL_LOG, old, new))
    }

    @Test
    fun smsKeepsTheInboxAndSentButNeverTheOutbox() {
        val old = FakeStore()
        old.add(PersonalFormat.SMS, "address" to "555", "body" to "hi", "date" to 1L, "type" to 1L, "thread_id" to 3L)
        old.add(PersonalFormat.SMS, "address" to "555", "body" to "yo", "date" to 2L, "type" to 2L)
        old.add(PersonalFormat.SMS, "address" to "555", "body" to "later", "date" to 3L, "type" to 4L) // outbox
        val new = FakeStore()
        assertEquals(PersonalResult(2, 1, 0), roundTrip(Personal.SMS, old, new))
        // The provider picks the conversation; ids never travel
        assertTrue(new.table(PersonalFormat.SMS).none { it.containsKey("thread_id") })
        assertEquals(PersonalResult(0, 3, 0), roundTrip(Personal.SMS, old, new))
    }

    @Test
    fun mmsComesBackWithAddressesPartsAndAttachments() {
        val old = FakeStore()
        val id = old.add(PersonalFormat.MMS, "date" to 50L, "msg_box" to 1L, "m_type" to 132L, "sub" to "pic", "thread_id" to 9L)
        old.add("content://mms/$id/addr", "address" to "+1555", "type" to 137L, "charset" to 106L)
        old.add("content://mms/$id/addr", "address" to "insert-address-token", "type" to 151L)
        old.add(PersonalFormat.MMS_PART, "mid" to id, "seq" to 0L, "ct" to "text/plain", "text" to "look")
        val img = old.add(PersonalFormat.MMS_PART, "mid" to id, "seq" to 1L, "ct" to "image/jpeg", "_data" to "/data/x/PART_1")
        old.files[img] = byteArrayOf(1, 2, 3)
        val new = FakeStore()
        assertEquals(PersonalResult(1, 0, 0), roundTrip(Personal.MMS, old, new))
        val pdu = new.table(PersonalFormat.MMS).single()
        assertEquals(7L, pdu["thread_id"])
        assertEquals("content://mms-sms/threadID?recipient=%2B1555", new.threadQueries.single())
        assertEquals(2, new.table(PersonalFormat.MMS_PART).size)
        val newImg = new.table(PersonalFormat.MMS_PART).single { it["ct"] == "image/jpeg" }
        assertNull(newImg["_data"]) // never a path from the old phone
        assertArrayEquals(byteArrayOf(1, 2, 3), new.files[newImg["_id"] as Long])
        assertEquals(PersonalResult(0, 1, 0), roundTrip(Personal.MMS, old, new))
    }

    @Test
    fun mmsWithoutItsAttachmentIsSkipped() {
        val old = FakeStore()
        val id = old.add(PersonalFormat.MMS, "date" to 50L, "msg_box" to 2L, "m_type" to 128L)
        old.add("content://mms/$id/addr", "address" to "+1555", "type" to 151L)
        old.add(PersonalFormat.MMS_PART, "mid" to id, "ct" to "image/png")
        val json = PersonalExport.export(Personal.MMS, old)
        val new = FakeStore()
        assertEquals(PersonalResult(0, 1, 0), PersonalImport(new) {}.import(Personal.MMS, json))
        assertTrue(new.table(PersonalFormat.MMS).isEmpty())
    }

    @Test
    fun contactsBecomePhoneContactsWithTheirPhotoAndWithoutGroups() {
        val old = FakeStore()
        val raw = old.add(PersonalFormat.RAW_CONTACTS, "display_name" to "Ada", "account_name" to "a@example.com",
            "account_type" to "com.google", "starred" to 1L, "deleted" to 0L)
        old.add(PersonalFormat.CONTACT_DATA, "raw_contact_id" to raw, "mimetype" to PersonalFormat.NAME, "data1" to "Ada")
        old.add(PersonalFormat.CONTACT_DATA, "raw_contact_id" to raw, "mimetype" to "vnd.android.cursor.item/phone_v2", "data1" to "555")
        old.add(PersonalFormat.CONTACT_DATA, "raw_contact_id" to raw, "mimetype" to PersonalFormat.GROUP, "data1" to 4L)
        old.add(PersonalFormat.CONTACT_DATA, "raw_contact_id" to raw, "mimetype" to PersonalFormat.PHOTO, "data14" to 77L,
            "data15" to byteArrayOf(9, 9))
        old.add(PersonalFormat.RAW_CONTACTS, "display_name" to "Gone", "deleted" to 1L)
        val new = FakeStore()
        assertEquals(PersonalResult(1, 0, 0), roundTrip(Personal.CONTACTS, old, new))
        val newRaw = new.table(PersonalFormat.RAW_CONTACTS).single()
        assertNull(newRaw["account_type"])
        assertEquals(1L, newRaw["starred"])
        val data = new.table(PersonalFormat.CONTACT_DATA)
        assertEquals(3, data.size)
        assertTrue(data.all { it["raw_contact_id"] == newRaw["_id"] })
        val photo = data.single { it["mimetype"] == PersonalFormat.PHOTO }
        assertNull(photo["data14"])
        assertArrayEquals(byteArrayOf(9, 9), photo["data15"] as ByteArray)
    }

    @Test
    fun dataBackupRecordsAreRead() {
        // DataBackup's own call_logs.json: each row's fields as a JSON string, numbers as doubles
        val json = """[{"id":5,"call":"{\"number\":\"123\",\"date\":1.7E12,\"duration\":12.0,\"type\":2.0}","selected":true}]"""
        val new = FakeStore()
        assertEquals(PersonalResult(1, 0, 0), PersonalImport(new) {}.import(Personal.CALL_LOG, json.toByteArray()))
        assertEquals(1_700_000_000_000L, new.table(PersonalFormat.CALLS).single()["date"])
    }
}
