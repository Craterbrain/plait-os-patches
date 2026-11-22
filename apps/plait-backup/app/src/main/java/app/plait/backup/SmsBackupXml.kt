package app.plait.backup

import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.Base64

/**
 * Reads the XML files SMS Backup & Restore writes (calls-*.xml, sms-*.xml) into the records Plait Backup archives hold
 * (see [PersonalFormat]), so a restore treats them like any other backup: same filtering, same duplicate checks.
 *
 * Streaming: the messages file holds every attachment inline as base64 and can be hundreds of MB, so one message is
 * in memory at a time and each attachment goes straight to [attachment]. Everything read is untrusted; the import's
 * own sanitising still applies afterwards, this only maps field names and units.
 */
object SmsBackupXml {
    enum class Kind { CALLS, MESSAGES }

    /** What the file holds, from its root element; null for anything else. */
    fun sniff(p: XmlPullParser): Kind? {
        while (true) {
            when (p.next()) {
                XmlPullParser.START_TAG -> return when (p.name) {
                    "calls" -> Kind.CALLS
                    "smses" -> Kind.MESSAGES
                    else -> null
                }
                XmlPullParser.END_DOCUMENT -> return null
            }
        }
    }

    /** The app writes the text "null" for a missing value. */
    private fun v(p: XmlPullParser, name: String): String? = p.getAttributeValue(null, name)?.takeIf { it != "null" }

    private fun put(o: JSONObject, key: String, value: Any?) { if (value != null) o.put(key, value) }

    private fun num(p: XmlPullParser, name: String): Long? = v(p, name)?.toLongOrNull()

    /** Call log records. The file must be positioned at its root element ([sniff] did that). */
    fun calls(p: XmlPullParser): ByteArray {
        val records = JSONArray()
        var n = 0L
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType != XmlPullParser.START_TAG || p.name != "call") continue
            val row = JSONObject()
            put(row, "number", v(p, "number"))
            for (k in listOf("date", "duration", "type", "presentation")) put(row, k, num(p, k))
            put(row, "post_dial_digits", v(p, "post_dial_digits"))
            // The file's name for a number that is not in the contacts
            put(row, "name", v(p, "contact_name")?.takeIf { it != "(Unknown)" })
            records.put(JSONObject().put("id", n++).put("call", row.toString()).put("selected", true))
        }
        return records.toString().toByteArray()
    }

    /**
     * Text messages ([mms] false) or picture messages ([mms] true) from the messages file; the other kind is skipped.
     * Attachments are handed to [attachment] under [PersonalFormat.attachmentName] of a number made up here.
     */
    fun messages(p: XmlPullParser, mms: Boolean, attachment: (String, () -> InputStream) -> Unit): ByteArray {
        val records = JSONArray()
        var id = 0L
        var partId = 0L
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType != XmlPullParser.START_TAG) continue
            when (p.name) {
                "sms" -> if (!mms) {
                    val row = JSONObject()
                    for (k in listOf("address", "body", "service_center")) put(row, k, p.getAttributeValue(null, k)?.takeIf { it != "null" || k == "body" })
                    put(row, "subject", v(p, "subject"))
                    for (k in listOf("date", "date_sent", "type", "read", "status", "locked", "protocol")) put(row, k, num(p, k))
                    records.put(JSONObject().put("id", id++).put("config", row.toString()).put("selected", true))
                }
                "mms" -> if (mms) {
                    val pdu = JSONObject()
                    for (k in listOf("sub", "ct_t", "ct_l", "m_cls", "m_id", "tr_id", "resp_txt", "retr_txt")) put(pdu, k, v(p, k))
                    for (k in listOf("date_sent", "msg_box", "read", "seen", "locked", "sub_cs", "m_type", "v", "m_size", "exp", "pri",
                        "rr", "rpt_a", "resp_st", "st", "retr_st", "retr_txt_cs", "read_status", "ct_cls", "d_rpt", "d_tm")) put(pdu, k, num(p, k))
                    // Android keeps an MMS's date in seconds; this file has milliseconds
                    num(p, "date")?.let { put(pdu, "date", if (it > 100_000_000_000L) it / 1000 else it) }
                    val parts = JSONArray()
                    val addrs = JSONArray()
                    readMmsBody(p, parts, addrs, { partId++ }, attachment)
                    records.put(JSONObject().put("id", id++).put("pdu", pdu.toString()).put("addr", addrs.toString())
                        .put("part", parts.toString()).put("selected", true))
                } else skipMessage(p)
            }
        }
        return records.toString().toByteArray()
    }

    /** From an `<mms>` start tag to its end tag: the `<part>` and `<addr>` children. */
    private fun readMmsBody(p: XmlPullParser, parts: JSONArray, addrs: JSONArray, nextPartId: () -> Long,
                            attachment: (String, () -> InputStream) -> Unit) {
        val depth = p.depth
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType == XmlPullParser.END_TAG && p.depth == depth) return
            if (p.eventType != XmlPullParser.START_TAG) continue
            when (p.name) {
                "part" -> {
                    val row = JSONObject()
                    val partId = nextPartId()
                    row.put("_id", partId)
                    for (k in listOf("ct", "name", "cd", "fn", "cid", "cl")) put(row, k, v(p, k))
                    put(row, "text", v(p, "text"))
                    for (k in listOf("seq", "chset")) put(row, k, num(p, k))
                    val data = v(p, "data")
                    if (data != null) {
                        val bytes = runCatching { Base64.getMimeDecoder().decode(data) }.getOrNull()
                        if (bytes != null) attachment(PersonalFormat.attachmentName(partId)) { ByteArrayInputStream(bytes) }
                    }
                    parts.put(row)
                }
                "addr" -> addrs.put(JSONObject().apply {
                    put("address", v(p, "address") ?: "")
                    put("type", num(p, "type") ?: 0L)
                    put("charset", num(p, "charset") ?: 106L)
                })
            }
        }
    }

    private fun skipMessage(p: XmlPullParser) {
        val depth = p.depth
        while (p.next() != XmlPullParser.END_DOCUMENT) if (p.eventType == XmlPullParser.END_TAG && p.depth == depth) return
    }
}
