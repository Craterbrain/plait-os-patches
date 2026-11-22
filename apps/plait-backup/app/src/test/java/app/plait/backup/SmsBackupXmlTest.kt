package app.plait.backup

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.util.Base64

class SmsBackupXmlTest {
    private fun parser(xml: String): XmlPullParser = KXmlParser().also { it.setInput(ByteArrayInputStream(xml.toByteArray()), "UTF-8") }

    private val calls = """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<!--File Created By SMS Backup & Restore-->
<calls count="2">
  <call number="+15550001" duration="43" date="1761754838041" type="1" presentation="1" subscription_id="2" post_dial_digits="" contact_name="Ann" />
  <call number="+15550002" duration="0" date="1761754838999" type="3" presentation="1" contact_name="(Unknown)" />
</calls>"""

    private val messages = """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<smses count="2">
  <sms protocol="0" address="+15550001" date="1740682834420" type="1" subject="null" body="Hi &amp; bye&#13;&#10;there" service_center="+1555" read="0" status="-1" locked="0" date_sent="1740682833000" />
  <mms date="1740634131000" sub="null" ct_t="application/vnd.wap.multipart.related" msg_box="1" m_type="132" seen="1" read="1" date_sent="1740634131" m_id="null">
    <parts>
      <part seq="0" ct="text/plain" name="body" chset="null" cl="null" text="look" />
      <part seq="1" ct="image/png" name="null" cl="a.png" text="null" data="${Base64.getEncoder().encodeToString(byteArrayOf(1, 2, 3, 4))}" />
    </parts>
    <addrs>
      <addr address="+15550001" type="137" charset="106" />
      <addr address="+15550009" type="151" charset="106" />
    </addrs>
  </mms>
</smses>"""

    @Test
    fun `calls map to call log records`() {
        val p = parser(calls)
        assertEquals(SmsBackupXml.Kind.CALLS, SmsBackupXml.sniff(p))
        val a = JSONArray(String(SmsBackupXml.calls(p)))
        assertEquals(2, a.length())
        val first = JSONObject(a.getJSONObject(0).getString("call"))
        assertEquals("+15550001", first.getString("number"))
        assertEquals(1761754838041L, first.getLong("date"))
        assertEquals(1, first.getInt("type"))
        assertEquals("Ann", first.getString("name"))
        assertTrue(JSONObject(a.getJSONObject(1).getString("call")).isNull("name"))
    }

    @Test
    fun `sms records keep text and units`() {
        val p = parser(messages)
        assertEquals(SmsBackupXml.Kind.MESSAGES, SmsBackupXml.sniff(p))
        val a = JSONArray(String(SmsBackupXml.messages(p, mms = false) { _, _ -> error("no attachments in sms") }))
        assertEquals(1, a.length())
        val sms = JSONObject(a.getJSONObject(0).getString("config"))
        assertEquals("Hi & bye\r\nthere", sms.getString("body"))
        assertEquals(1740682834420L, sms.getLong("date"))
        assertTrue(sms.isNull("subject") || !sms.has("subject"))
    }

    @Test
    fun `mms records have seconds, parts, addresses and attachments`() {
        val p = parser(messages)
        SmsBackupXml.sniff(p)
        val files = mutableMapOf<String, ByteArray>()
        val a = JSONArray(String(SmsBackupXml.messages(p, mms = true) { name, open -> files[name] = open().readBytes() }))
        assertEquals(1, a.length())
        val r = a.getJSONObject(0)
        val pdu = JSONObject(r.getString("pdu"))
        assertEquals(1740634131L, pdu.getLong("date"))
        assertEquals(132, pdu.getInt("m_type"))
        assertNull(pdu.opt("m_id"))
        val parts = JSONArray(r.getString("part"))
        assertEquals(2, parts.length())
        assertEquals("look", parts.getJSONObject(0).getString("text"))
        val image = parts.getJSONObject(1)
        assertEquals(listOf(1, 2, 3, 4), files[PersonalFormat.attachmentName(image.getLong("_id"))]!!.map { it.toInt() })
        assertEquals(2, JSONArray(r.getString("addr")).length())
    }

    @Test
    fun `other files are not recognised`() {
        assertNull(SmsBackupXml.sniff(parser("<html/>")))
    }

    @Test
    fun `converted records restore through the normal import`() {
        // The provider in memory is PersonalDataTest's; here only the result of the conversion matters, so use the
        // import's own checks: it must accept every record the converter made
        val store = object : PersonalStore {
            val inserted = mutableListOf<Pair<String, Map<String, Any?>>>()
            val written = mutableMapOf<String, ByteArray>()
            override fun query(uri: String, projection: Array<String>?, selection: String?, args: Array<String>?, sort: String?): Rows =
                when {
                    uri.startsWith("content://mms-sms/threadID") -> Rows(listOf("_id"), listOf(listOf(7L)))
                    selection == "0" -> Rows(listOf("_id", "date", "type", "address", "body", "number", "duration", "msg_box", "m_type", "sub", "name",
                        "date_sent", "read", "seen", "locked", "sub_cs", "v", "m_size", "thread_id", "text_only", "ct_t", "post_dial_digits", "presentation",
                        "service_center", "status", "protocol", "subject"), emptyList())
                    else -> Rows(listOf("_id"), emptyList())
                }
            override fun insert(uri: String, values: Map<String, Any?>): String { inserted += uri to values; return "$uri/${inserted.size}" }
            override fun delete(uri: String) = 1
            override fun read(uri: String): java.io.InputStream = error("unused")
            override fun write(uri: String): java.io.OutputStream = object : java.io.ByteArrayOutputStream() {
                override fun close() { written[uri] = toByteArray() }
            }
        }
        val log = mutableListOf<String>()
        val importer = PersonalImport(store) { log += it }

        val c = parser(calls).also { SmsBackupXml.sniff(it) }
        assertEquals(PersonalResult(2, 0, 0), importer.import(Personal.CALL_LOG, SmsBackupXml.calls(c)))

        val sms = parser(messages).also { SmsBackupXml.sniff(it) }
        assertEquals(PersonalResult(1, 0, 0), importer.import(Personal.SMS, SmsBackupXml.messages(sms, false) { _, _ -> }))

        val files = mutableMapOf<String, ByteArray>()
        val m = parser(messages).also { SmsBackupXml.sniff(it) }
        val json = SmsBackupXml.messages(m, true) { n, open -> files[n] = open().readBytes() }
        val r = importer.import(Personal.MMS, json) { files[it]?.inputStream() }
        assertEquals(log.toString(), PersonalResult(1, 0, 0), r)
        assertEquals(listOf(1, 2, 3, 4), store.written.values.single().map { it.toInt() })
    }
}
