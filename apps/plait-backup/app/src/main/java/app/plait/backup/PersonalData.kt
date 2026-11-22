package app.plait.backup

import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64

/**
 * Phone data that is not an app. Each kind is a grant target ("@sms", ...: a grant for every app covers them) and its
 * own archive, holding DataBackup's JSON file for it (same records, so archives move between the two apps).
 */
enum class Personal(val target: String, val label: String, val fileName: String, val dirName: String) {
    CALL_LOG("@call_log", "Call log", "call_logs.json", "call_logs"),
    SMS("@sms", "Text messages", "messages_sms.json", "messages_sms"),
    MMS("@mms", "Picture messages", "messages_mms.json", "messages_mms"),
    CONTACTS("@contacts", "Contacts", "contacts.json", "contacts");

    companion object {
        fun of(target: String) = entries.firstOrNull { it.target == target }
    }
}

/** A query's result: column names, and per row the values (Long, Double, String, ByteArray or null). */
class Rows(val columns: List<String>, val rows: List<List<Any?>>) {
    fun maps(): List<Map<String, Any?>> = rows.map { r -> columns.zip(r).toMap() }
}

/** The call log, messages and contacts providers, as the backend reaches them (on PlaitOS: the plait_backup service). */
interface PersonalStore {
    fun query(uri: String, projection: Array<String>? = null, selection: String? = null, args: Array<String>? = null,
              sort: String? = null): Rows

    /** Inserts a row (values: Long, Double, String, ByteArray or null); the new row's URI, or null. */
    fun insert(uri: String, values: Map<String, Any?>): String?
    fun delete(uri: String): Int
    fun read(uri: String): InputStream
    fun write(uri: String): OutputStream
}

/** What a restore did with the archive's records. */
data class PersonalResult(val added: Int, val skipped: Int, val failed: Int)

/** DataBackup's records for each kind: provider rows as JSON, each row's fields as a JSON string inside the record. */
object PersonalFormat {
    const val CALLS = "content://call_log/calls"
    const val SMS = "content://sms"
    const val MMS = "content://mms"
    const val MMS_PART = "content://mms/part"
    const val RAW_CONTACTS = "content://com.android.contacts/raw_contacts"
    const val CONTACT_DATA = "content://com.android.contacts/data"

    /** The attachment of the MMS part with [partId], as a file in the MMS archive. */
    fun attachmentName(partId: Long) = "mms_part_$partId.bin"

    fun fieldsToJson(fields: Map<String, Any?>): JSONObject = JSONObject().apply {
        for ((k, v) in fields) {
            when (v) {
                null -> put(k, JSONObject.NULL)
                // Blobs (contact photos): a Plait addition, DataBackup's files never have them
                is ByteArray -> put(k, JSONObject().put("blob", Base64.getEncoder().encodeToString(v)))
                else -> put(k, v)
            }
        }
    }

    fun jsonToFields(json: JSONObject): Map<String, Any?> = json.keys().asSequence().associateWith { k ->
        when (val v = json.get(k)) {
            JSONObject.NULL -> null
            is JSONObject -> v.optString("blob").takeIf { v.has("blob") }?.let { Base64.getDecoder().decode(it) }
            else -> v
        }
    }

    fun rowsToJson(rows: List<Map<String, Any?>>): String = JSONArray().apply { rows.forEach { put(fieldsToJson(it)) } }.toString()

    fun jsonToRows(text: String?): List<Map<String, Any?>> {
        if (text.isNullOrEmpty()) return emptyList()
        val a = JSONArray(text)
        return (0 until a.length()).map { jsonToFields(a.getJSONObject(it)) }
    }

    /** Keeps [columns] only, with integers as Long (JSON readers hand them back as Int or Double). */
    fun sanitize(fields: Map<String, Any?>, strings: Set<String>, numbers: Set<String>, blobs: Set<String> = emptySet()): Map<String, Any?> {
        val out = linkedMapOf<String, Any?>()
        for ((k, v) in fields) {
            when {
                v == null -> if (k in strings || k in numbers || k in blobs) out[k] = null
                v is ByteArray -> if (k in blobs) out[k] = v
                k in strings -> out[k] = v.toString()
                k in numbers -> {
                    val d = (v as? Number)?.toDouble() ?: v.toString().toDoubleOrNull() ?: continue
                    if (d.isFinite() && d == Math.floor(d)) out[k] = d.toLong()
                }
            }
        }
        return out
    }

    /** The row id at the end of a provider URI ("content://sms/12" -> 12). */
    fun idOf(uri: String?): Long? = uri?.substringBefore('?')?.trimEnd('/')?.substringAfterLast('/')?.toLongOrNull()

    fun long(fields: Map<String, Any?>, key: String): Long? = (fields[key] as? Number)?.toLong() ?: fields[key]?.toString()?.toLongOrNull()

    // ---- columns a restore writes (DataBackup's choices, and Android's own backup agents') ----

    val CALL_STRINGS = setOf("number", "post_dial_digits", "via_number", "subject", "name", "geocoded_location", "countryiso",
        "call_screening_app_name", "call_screening_component_name", "asserted_display_name")
    val CALL_NUMBERS = setOf("date", "duration", "type", "new", "presentation", "features", "data_usage", "is_read", "priority",
        "missed_reason", "block_reason", "is_business_call")
    /** Completed calls only: incoming, outgoing, missed, rejected, blocked, answered elsewhere. */
    val CALL_TYPES = setOf(1L, 2L, 3L, 5L, 6L, 7L)

    val SMS_STRINGS = setOf("address", "body", "subject", "service_center")
    val SMS_NUMBERS = setOf("date", "date_sent", "type", "read", "seen", "status", "locked", "protocol", "reply_path_present")
    /** Received and sent only; never the outbox or queue (the plait_backup service refuses those anyway). */
    val SMS_TYPES = setOf(1L, 2L)

    val MMS_STRINGS = setOf("sub", "ct_t", "ct_l", "m_cls", "m_id", "tr_id", "resp_txt", "retr_txt")
    val MMS_NUMBERS = setOf("date", "date_sent", "msg_box", "read", "seen", "locked", "sub_cs", "m_type", "v", "m_size", "exp",
        "pri", "rr", "rpt_a", "resp_st", "st", "retr_st", "retr_txt_cs", "read_status", "ct_cls", "d_rpt", "d_tm")
    val MMS_BOXES = setOf(1L, 2L)
    /** Sent requests and retrieved messages; notifications and reports are not messages. */
    val MMS_TYPES = setOf(128L, 132L)
    val ADDR_STRINGS = setOf("address")
    val ADDR_NUMBERS = setOf("type", "charset")
    val ADDR_TYPES = setOf(129L, 130L, 137L, 151L) // bcc, cc, from, to
    val PART_STRINGS = setOf("ct", "name", "fn", "cd", "cid", "cl", "text")
    val PART_NUMBERS = setOf("seq", "chset")
    val INLINE_TYPES = setOf("text/plain", "text/html", "application/smil")

    val RAW_NUMBERS = setOf("starred", "send_to_voicemail")
    val RAW_STRINGS = setOf("custom_ringtone")
    val DATA_STRINGS = (1..14).map { "data$it" }.toSet() + "mimetype"
    val DATA_NUMBERS = setOf("is_primary", "is_super_primary")
    const val PHOTO = "vnd.android.cursor.item/photo"
    const val GROUP = "vnd.android.cursor.item/group_membership"
    const val NAME = "vnd.android.cursor.item/name"
}

/** Reads one kind from the providers into DataBackup's JSON (and, for MMS, attachment files). */
object PersonalExport {
    /** The JSON file's contents; [attachment] receives each MMS attachment (file name, stream). */
    fun export(kind: Personal, store: PersonalStore, attachment: (String, () -> InputStream) -> Unit = { _, _ -> }): ByteArray {
        val records = JSONArray()
        when (kind) {
            Personal.CALL_LOG -> store.query(PersonalFormat.CALLS, sort = "date DESC").maps().forEach { row ->
                records.put(JSONObject().put("id", PersonalFormat.long(row, "_id") ?: -1L)
                    .put("call", PersonalFormat.fieldsToJson(row).toString()).put("selected", true))
            }
            Personal.SMS -> store.query(PersonalFormat.SMS, sort = "date DESC").maps().forEach { row ->
                records.put(JSONObject().put("id", PersonalFormat.long(row, "_id") ?: -1L)
                    .put("config", PersonalFormat.fieldsToJson(row).toString()).put("selected", true))
            }
            Personal.MMS -> {
                val partsByMessage = store.query(PersonalFormat.MMS_PART).maps().groupBy { PersonalFormat.long(it, "mid") }
                store.query(PersonalFormat.MMS, sort = "date DESC").maps().forEach { pdu ->
                    val id = PersonalFormat.long(pdu, "_id") ?: return@forEach
                    val addr = store.query("${PersonalFormat.MMS}/$id/addr").maps()
                    val parts = partsByMessage[id].orEmpty()
                    for (p in parts) {
                        val partId = PersonalFormat.long(p, "_id") ?: continue
                        val ct = p["ct"]?.toString().orEmpty()
                        // Text parts keep their text in the row; everything else is a file the provider holds
                        if (ct !in PersonalFormat.INLINE_TYPES && !p["_data"]?.toString().isNullOrBlank()) {
                            attachment(PersonalFormat.attachmentName(partId)) { store.read("${PersonalFormat.MMS_PART}/$partId") }
                        }
                    }
                    records.put(JSONObject().put("id", id).put("pdu", PersonalFormat.fieldsToJson(pdu).toString())
                        .put("addr", PersonalFormat.rowsToJson(addr)).put("part", PersonalFormat.rowsToJson(parts))
                        .put("selected", true))
                }
            }
            Personal.CONTACTS -> {
                val dataByContact = store.query(PersonalFormat.CONTACT_DATA).maps().groupBy { PersonalFormat.long(it, "raw_contact_id") }
                store.query(PersonalFormat.RAW_CONTACTS, selection = "deleted = 0").maps().forEach { raw ->
                    val id = PersonalFormat.long(raw, "_id") ?: return@forEach
                    records.put(JSONObject().put("id", id).put("rawContact", PersonalFormat.fieldsToJson(raw).toString())
                        .put("data", PersonalFormat.rowsToJson(dataByContact[id].orEmpty())).put("selected", true))
                }
            }
        }
        return records.toString().toByteArray()
    }
}

/**
 * Adds an archive's records back through the providers. Existing records are never replaced: duplicates (same call,
 * same message, a contact with the same name) are skipped, so restoring twice adds nothing.
 */
class PersonalImport(private val store: PersonalStore, private val log: (String) -> Unit) {

    /** [attachment] opens an MMS attachment file from the staged archive, or null if the archive has none. */
    fun import(kind: Personal, json: ByteArray, attachment: (String) -> InputStream? = { null }): PersonalResult {
        val records = JSONArray(String(json))
        val list = (0 until records.length()).map { records.getJSONObject(it) }.filter { it.optBoolean("selected", true) }
        return when (kind) {
            Personal.CALL_LOG -> calls(list)
            Personal.SMS -> sms(list)
            Personal.MMS -> mms(list, attachment)
            Personal.CONTACTS -> contacts(list)
        }
    }

    private fun fields(record: JSONObject, key: String): Map<String, Any?> =
        PersonalFormat.jsonToFields(JSONObject(record.optString(key).ifEmpty { "{}" }))

    /** The columns the provider has here (an archive from another Android version may have others). */
    private fun columnsOf(uri: String): Set<String> = store.query(uri, selection = "0").columns.toSet()

    private fun key(fields: Map<String, Any?>, keys: List<String>) = keys.joinToString("\u0001") { fields[it]?.toString().orEmpty() }

    private fun existing(uri: String, keys: List<String>, selection: String? = null): MutableSet<String> =
        store.query(uri, keys.toTypedArray(), selection).maps().mapTo(HashSet()) { key(normalize(it), keys) }

    /** Numbers the same way on both sides of a duplicate check (archives hand back Int or Double). */
    private fun normalize(fields: Map<String, Any?>) = fields.mapValues { (_, v) ->
        if (v is Number && v.toDouble() == Math.floor(v.toDouble())) v.toLong() else v
    }

    private fun calls(list: List<JSONObject>): PersonalResult {
        val have = columnsOf(PersonalFormat.CALLS)
        val keys = listOf("number", "date", "duration", "type")
        val seen = existing(PersonalFormat.CALLS, keys)
        var added = 0; var skipped = 0; var failed = 0
        for (r in list) {
            val row = PersonalFormat.sanitize(fields(r, "call"), PersonalFormat.CALL_STRINGS, PersonalFormat.CALL_NUMBERS)
                .filterKeys { it in have }
            if (PersonalFormat.long(row, "type") !in PersonalFormat.CALL_TYPES || row["date"] == null) { skipped++; continue }
            if (!seen.add(key(row, keys))) { skipped++; continue }
            if (runCatching { store.insert(PersonalFormat.CALLS, row) }.getOrNull() != null) added++ else failed++
        }
        return PersonalResult(added, skipped, failed)
    }

    private fun sms(list: List<JSONObject>): PersonalResult {
        val have = columnsOf(PersonalFormat.SMS)
        val keys = listOf("date", "type", "address", "body")
        val seen = existing(PersonalFormat.SMS, keys)
        var added = 0; var skipped = 0; var failed = 0
        for (r in list) {
            val row = PersonalFormat.sanitize(fields(r, "config"), PersonalFormat.SMS_STRINGS, PersonalFormat.SMS_NUMBERS)
                .filterKeys { it in have }.toMutableMap()
            if (PersonalFormat.long(row, "type") !in PersonalFormat.SMS_TYPES || row["date"] == null) { skipped++; continue }
            row.putIfAbsent("address", "")
            row.putIfAbsent("body", "")
            if (!seen.add(key(row, keys))) { skipped++; continue }
            // No thread_id: the provider finds (or makes) the conversation for the address
            if (runCatching { store.insert(PersonalFormat.SMS, row) }.getOrNull() != null) added++ else failed++
        }
        return PersonalResult(added, skipped, failed)
    }

    private fun mms(list: List<JSONObject>, attachment: (String) -> InputStream?): PersonalResult {
        val have = columnsOf(PersonalFormat.MMS)
        val keys = listOf("date", "msg_box", "m_type", "sub")
        val seen = existing(PersonalFormat.MMS, keys)
        var added = 0; var skipped = 0; var failed = 0
        for (r in list) {
            val pdu = PersonalFormat.sanitize(fields(r, "pdu"), PersonalFormat.MMS_STRINGS, PersonalFormat.MMS_NUMBERS)
                .filterKeys { it in have }.toMutableMap()
            val addrs = PersonalFormat.jsonToRows(r.optString("addr")).map {
                PersonalFormat.sanitize(it, PersonalFormat.ADDR_STRINGS, PersonalFormat.ADDR_NUMBERS)
            }.filter { it["address"] is String && PersonalFormat.long(it, "type") in PersonalFormat.ADDR_TYPES }
            val rawParts = PersonalFormat.jsonToRows(r.optString("part"))
            val box = PersonalFormat.long(pdu, "msg_box")
            if (box !in PersonalFormat.MMS_BOXES || PersonalFormat.long(pdu, "m_type") !in PersonalFormat.MMS_TYPES ||
                addrs.isEmpty() || rawParts.isEmpty()
            ) { skipped++; continue }
            // Every file part needs its attachment in the archive (DataBackup's own archives keep them elsewhere)
            val parts = rawParts.map { p ->
                val ct = p["ct"]?.toString().orEmpty()
                val file = if (ct in PersonalFormat.INLINE_TYPES) null else PersonalFormat.long(p, "_id")?.let { PersonalFormat.attachmentName(it) }
                Triple(PersonalFormat.sanitize(p, PersonalFormat.PART_STRINGS, PersonalFormat.PART_NUMBERS), ct, file)
            }
            if (parts.any { (_, ct, file) -> ct !in PersonalFormat.INLINE_TYPES && (file == null || attachment(file)?.also { it.close() } == null) }) {
                skipped++; continue
            }
            if (!seen.add(key(normalize(pdu), keys))) { skipped++; continue }
            val thread = threadId(addrs, box ?: 1L)
            if (thread == null) { failed++; continue }
            pdu["thread_id"] = thread
            pdu["text_only"] = if (parts.size <= 2 && parts.all { it.second == "text/plain" || it.second == "application/smil" }) 1L else 0L
            var msgUri: String? = null
            try {
                msgUri = store.insert(PersonalFormat.MMS, pdu) ?: error("no row")
                val id = PersonalFormat.idOf(msgUri) ?: error("no id")
                for (a in addrs) store.insert("${PersonalFormat.MMS}/$id/addr", a) ?: error("address")
                for ((values, _, file) in parts) {
                    val partUri = store.insert("${PersonalFormat.MMS}/$id/part", values) ?: error("part")
                    if (file != null) {
                        val partId = PersonalFormat.idOf(partUri) ?: error("part id")
                        val input = attachment(file) ?: error("attachment")
                        input.use { i -> store.write("${PersonalFormat.MMS_PART}/$partId").use { o -> i.copyTo(o) } }
                    }
                }
                added++
            } catch (e: Exception) {
                failed++
                log("A picture message could not be restored: ${e.message}")
                msgUri?.let { u -> runCatching { store.delete(u) } }
            }
        }
        return PersonalResult(added, skipped, failed)
    }

    /** The conversation for the message's other people (the sender of a received message, the recipients of a sent one). */
    private fun threadId(addrs: List<Map<String, Any?>>, box: Long): Long? {
        val wanted = if (box == 1L) setOf(137L) else setOf(151L, 130L, 129L)
        val recipients = addrs.filter { PersonalFormat.long(it, "type") in wanted }.map { it["address"].toString() }
            .filter { it.isNotBlank() && it != "insert-address-token" }.distinct()
            .ifEmpty { listOf(addrs.first()["address"].toString()) }
        val query = recipients.joinToString("&") { "recipient=" + java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
        return store.query("content://mms-sms/threadID?$query").maps().firstOrNull()?.let { PersonalFormat.long(it, "_id") }
    }

    private fun contacts(list: List<JSONObject>): PersonalResult {
        val names = store.query(PersonalFormat.RAW_CONTACTS, arrayOf("display_name"), "deleted = 0").maps()
            .mapNotNullTo(HashSet()) { it["display_name"]?.toString()?.takeIf(String::isNotBlank) }
        var added = 0; var skipped = 0; var failed = 0
        for (r in list) {
            val raw = fields(r, "rawContact")
            if (PersonalFormat.long(raw, "deleted") == 1L) { skipped++; continue }
            val data = PersonalFormat.jsonToRows(r.optString("data")).mapNotNull { row ->
                when (row["mimetype"]?.toString()) {
                    null, "", PersonalFormat.GROUP -> null // groups are the old phone's
                    // The thumbnail only: data14 is a photo file id on the old phone
                    PersonalFormat.PHOTO -> PersonalFormat.sanitize(row, setOf("mimetype"), PersonalFormat.DATA_NUMBERS, setOf("data15"))
                        .takeIf { it["data15"] is ByteArray }
                    else -> PersonalFormat.sanitize(row, PersonalFormat.DATA_STRINGS + "data15", PersonalFormat.DATA_NUMBERS, setOf("data15"))
                }
            }
            if (data.isEmpty()) { skipped++; continue }
            val name = raw["display_name"]?.toString()?.takeIf(String::isNotBlank)
                ?: data.firstOrNull { it["mimetype"] == PersonalFormat.NAME }?.get("data1")?.toString()?.takeIf(String::isNotBlank)
            if (name != null && !names.add(name)) { skipped++; continue }
            try {
                // A phone contact, not tied to the old phone's accounts
                val values = PersonalFormat.sanitize(raw, PersonalFormat.RAW_STRINGS, PersonalFormat.RAW_NUMBERS) +
                    mapOf("account_name" to null, "account_type" to null)
                val rawUri = store.insert(PersonalFormat.RAW_CONTACTS, values) ?: error("no row")
                val id = PersonalFormat.idOf(rawUri) ?: error("no id")
                for (row in data) store.insert(PersonalFormat.CONTACT_DATA, row + ("raw_contact_id" to id))
                added++
            } catch (e: Exception) {
                failed++
                log("A contact could not be restored: ${e.message}")
            }
        }
        return PersonalResult(added, skipped, failed)
    }
}
