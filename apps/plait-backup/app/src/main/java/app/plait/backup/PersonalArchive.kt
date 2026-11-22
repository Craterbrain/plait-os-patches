package app.plait.backup

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.DigestInputStream
import java.security.MessageDigest

/** Writes one kind of phone data as an archive: its JSON file, MMS attachments, a signed manifest; every file encrypted. */
object PersonalArchive {
    /** Fills [dir] (empty, app-writable) and returns how many records it holds and how many attachments. */
    fun write(dir: File, key: ArchiveCrypto.Key, kind: Personal, userId: Int, store: PersonalStore): Pair<Int, Int> =
        writeFrom(dir, key, kind, userId) { attachment -> PersonalExport.export(kind, store, attachment) }

    /**
     * As [write], for records that come from somewhere else (an imported file): [produce] returns the JSON and calls its
     * argument once per attachment.
     */
    fun writeFrom(dir: File, key: ArchiveCrypto.Key, kind: Personal, userId: Int,
                  produce: ((String, () -> InputStream) -> Unit) -> ByteArray): Pair<Int, Int> {
        val sums = sortedMapOf<String, String>()
        fun hex(d: ByteArray) = d.joinToString("") { "%02x".format(it) }
        var attachments = 0
        val json = produce { name, open ->
            val digest = MessageDigest.getInstance("SHA-256")
            open().use { input ->
                File(dir, name + ArchiveCrypto.SUFFIX).outputStream().use { ArchiveCrypto.encrypt(key, DigestInputStream(input, digest), it) }
            }
            sums[name] = hex(digest.digest())
            attachments++
        }
        File(dir, kind.fileName + ArchiveCrypto.SUFFIX).outputStream().use { ArchiveCrypto.encrypt(key, json.inputStream(), it) }
        sums[kind.fileName] = hex(MessageDigest.getInstance("SHA-256").digest(json))
        val manifest = ArchiveCheck.manifest(kind.target, userId, System.currentTimeMillis(), sums).toByteArray()
        for ((name, bytes) in listOf(
            ArchiveCheck.MANIFEST to manifest,
            ArchiveCheck.SIGNATURE to (ArchiveSigner.sign(manifest) + "\n").toByteArray(),
            ArchiveCheck.KEY to (ArchiveSigner.publicKeyB64() + "\n").toByteArray(),
        )) {
            File(dir, name + ArchiveCrypto.SUFFIX).outputStream().use { ArchiveCrypto.encrypt(key, bytes.inputStream(), it) }
        }
        return org.json.JSONArray(String(json)).length() to attachments
    }
}

/**
 * Read-only phone data from copies of the providers' own databases (the root backend: mmssms.db, calllog.db,
 * contacts2.db). Their tables are what the providers serve, so the records match a provider query's. MMS
 * attachments are read where the telephony provider keeps them, through [readPart].
 */
class DatabaseStore(
    private val telephony: File?, private val calls: File?, private val contacts: File?,
    private val readPart: (String) -> InputStream,
) : PersonalStore, AutoCloseable {
    private val open = mutableMapOf<File, SQLiteDatabase>()

    private fun db(f: File?): SQLiteDatabase {
        f ?: error("that database was not found on this phone")
        // Read-write on our own copy: SQLite folds the copied WAL in on open
        return open.getOrPut(f) { SQLiteDatabase.openDatabase(f.path, null, SQLiteDatabase.OPEN_READWRITE) }
    }

    override fun query(uri: String, projection: Array<String>?, selection: String?, args: Array<String>?, sort: String?): Rows {
        val cols = projection?.joinToString(", ") ?: "*"
        val where = selection?.let { " WHERE $it" }.orEmpty()
        val order = sort?.let { " ORDER BY $it" }.orEmpty()
        val (database, sql) = when {
            uri == PersonalFormat.CALLS -> db(calls) to "SELECT $cols FROM calls$where$order"
            uri == PersonalFormat.SMS -> db(telephony) to "SELECT $cols FROM sms$where$order"
            uri == PersonalFormat.MMS -> db(telephony) to "SELECT $cols FROM pdu$where$order"
            uri == PersonalFormat.MMS_PART -> db(telephony) to "SELECT $cols FROM part$where$order"
            uri.matches(Regex("content://mms/\\d+/addr")) -> {
                val id = uri.split('/')[3].toLong()
                db(telephony) to "SELECT $cols FROM addr WHERE msg_id = $id" + (selection?.let { " AND ($it)" }.orEmpty()) + order
            }
            uri == PersonalFormat.RAW_CONTACTS -> db(contacts) to "SELECT $cols FROM raw_contacts$where$order"
            // The provider's data rows name their kind; the table stores a mimetype id
            uri == PersonalFormat.CONTACT_DATA -> db(contacts) to
                "SELECT ${projection?.joinToString(", ") ?: "data.*, mimetypes.mimetype AS mimetype"} FROM data JOIN mimetypes " +
                "ON data.mimetype_id = mimetypes._id$where$order"
            else -> error("not available from the databases: $uri")
        }
        database.rawQuery(sql, args).use { c ->
            val rows = ArrayList<List<Any?>>()
            while (c.moveToNext()) {
                rows += (0 until c.columnCount).map { i ->
                    when (c.getType(i)) {
                        Cursor.FIELD_TYPE_NULL -> null
                        Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                        Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                        Cursor.FIELD_TYPE_BLOB -> c.getBlob(i)
                        else -> c.getString(i)
                    }
                }
            }
            return Rows(c.columnNames.toList(), rows)
        }
    }

    override fun read(uri: String): InputStream {
        val id = PersonalFormat.idOf(uri) ?: error("not a part: $uri")
        val path = db(telephony).rawQuery("SELECT _data FROM part WHERE _id = $id", null).use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: error("part $id has no file")
        return readPart(path)
    }

    override fun insert(uri: String, values: Map<String, Any?>): String? = error("read-only")
    override fun delete(uri: String): Int = error("read-only")
    override fun write(uri: String): OutputStream = error("read-only")

    override fun close() {
        open.values.forEach { it.close() }
        open.clear()
    }
}
