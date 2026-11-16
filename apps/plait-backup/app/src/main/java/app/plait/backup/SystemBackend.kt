package app.plait.backup

import android.content.Context
import android.os.Environment
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import kotlin.concurrent.thread

/**
 * The PlaitOS backend: the `plait_backup` system service (system_server, with installd streaming the data). No root:
 * the service checks the broker-signed grant on every call. Archives are the same as [DevRootBackend]'s (DataBackup's
 * layout, every file encrypted, a signed manifest of the plaintext SHA-256s), so they move between the two.
 *
 * The app writes the archive itself (to shared storage, with "All files access"); during a restore the decrypted
 * parts only ever sit in this app's private storage before they are streamed to the service.
 */
class SystemBackend private constructor(private val context: Context, private val connected: Service) : Backend {
    override val name = "PlaitOS backup service"
    override val archiveDir: String get() = ROOT.path

    private val service: Service? = connected

    override fun unavailableReason(): String? = when {
        service == null -> "The PlaitOS backup service is not running"
        !Environment.isExternalStorageManager() ->
            "Allow \"All files access\" for Plait Backup (Settings > Apps > Special app access)"
        else -> null
    }

    private fun appDir(pkg: String, userId: Int) = File(ROOT, "apps/$pkg/user_$userId")

    // ---- backup ----

    override fun backupApp(grant: String, pkg: String, userId: Int, parts: Set<Part>, log: (String) -> Unit): Boolean {
        if (!Layout.isSafePackage(pkg)) return false.also { log("Refused: bad package name $pkg") }
        if (pkg in ArchiveCheck.BLOCKED) return false.also { log("Refused: $pkg is never backed up (it guards backups)") }
        val svc = service ?: return false.also { log("The PlaitOS backup service is not running") }
        val key = KeyRing.currentKey(context)
            ?: return false.also { log("Refused: set an encryption passphrase in Settings first") }
        log("Backing up $pkg (${parts.joinToString { it.label }})")
        val dest = appDir(pkg, userId)
        val fresh = File(dest.path + ".new")
        try {
            fresh.deleteRecursively()
            if (!fresh.mkdirs()) return false.also { log("Failed ($pkg): no archive dir") }
            val sums = sortedMapOf<String, String>()
            // Each part streams from the service straight into an encrypted file; the hash is of the plaintext
            fun save(name: String, produce: (OutputStream) -> Unit): Boolean {
                val out = File(fresh, name + ArchiveCrypto.SUFFIX)
                val (hash, bytes) = encryptTo(out, key, produce)
                if (bytes == 0L) {
                    out.delete()
                    return false
                }
                sums[name] = hash
                return true
            }
            // Small files the root backend always writes (even empty): encrypted straight from memory
            fun saveBytes(name: String, bytes: ByteArray) {
                File(fresh, name + ArchiveCrypto.SUFFIX).outputStream().use { ArchiveCrypto.encrypt(key, bytes.inputStream(), it) }
                sums[name] = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            }
            for (part in parts) {
                if (part == Part.APK) {
                    save(part.fileName) { o -> svc.stream(o) { pfd -> svc.backupApks(grant, pkg, userId, pfd) } }
                } else {
                    save(part.fileName) { o -> svc.stream(o) { pfd -> svc.backupPart(grant, pkg, userId, part.code, pfd) } }
                }
            }
            // Runtime permissions and app-ops, in the files the root backend writes
            val state = svc.getState(grant, pkg, userId)
            saveBytes("permissions.txt", state.substringBefore("---\n").toByteArray())
            saveBytes("appops.txt", state.substringAfter("---\n", "").toByteArray())
            saveBytes("package.txt", "$pkg\n".toByteArray())

            val manifest = ArchiveCheck.manifest(pkg, userId, System.currentTimeMillis(), sums).toByteArray()
            for ((name, bytes) in listOf(
                ArchiveCheck.MANIFEST to manifest,
                ArchiveCheck.SIGNATURE to (ArchiveSigner.sign(manifest) + "\n").toByteArray(),
                ArchiveCheck.KEY to (ArchiveSigner.publicKeyB64() + "\n").toByteArray(),
            )) {
                File(fresh, name + ArchiveCrypto.SUFFIX).outputStream().use { ArchiveCrypto.encrypt(key, bytes.inputStream(), it) }
            }
            // Swap in the new archive only once every file is written: the old one is moved aside first and put back
            // if the new one can't take its place, so a failed backup keeps the previous one
            val old = File(dest.path + ".old")
            old.deleteRecursively()
            if (dest.exists() && !dest.renameTo(old)) return false.also { log("Failed ($pkg): could not move the old archive aside") }
            if (!fresh.renameTo(dest)) {
                if (old.exists()) old.renameTo(dest)
                return false.also { log("Failed ($pkg): could not move the archive in place") }
            }
            old.deleteRecursively()
            return true
        } catch (e: Exception) {
            return false.also { log("Failed ($pkg): ${e.message}") }
        } finally {
            fresh.deleteRecursively()
        }
    }

    /** Writes what [produce] emits to [file], encrypted; returns the plaintext SHA-256 (hex) and size. */
    private fun encryptTo(file: File, key: ArchiveCrypto.Key, produce: (OutputStream) -> Unit): Pair<String, Long> {
        val pipe = ParcelFileDescriptor.createPipe()
        var error: Throwable? = null
        val producer = thread(name = "PlaitBackupProduce") {
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { produce(it) }
            } catch (t: Throwable) {
                error = t
            }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        ParcelFileDescriptor.AutoCloseInputStream(pipe[0]).use { raw ->
            val counted = object : InputStream() {
                val inner = DigestInputStream(raw, digest)
                override fun read(): Int = inner.read().also { if (it >= 0) count++ }
                override fun read(b: ByteArray, off: Int, len: Int): Int =
                    inner.read(b, off, len).also { if (it > 0) count += it }
            }
            file.outputStream().use { out -> ArchiveCrypto.encrypt(key, counted, out) }
        }
        producer.join()
        error?.let { throw it }
        return digest.digest().joinToString("") { "%02x".format(it) } to count
    }

    // ---- restore ----

    override fun restoreApp(
        grant: String, pkg: String, userId: Int, parts: Set<Part>, trustForeign: Boolean, log: (String) -> Unit,
    ): Boolean {
        if (!Layout.isSafePackage(pkg)) return false.also { log("Refused: bad package name $pkg") }
        if (pkg in ArchiveCheck.BLOCKED) return false.also { log("Refused: $pkg is never restored (it guards backups)") }
        val svc = service ?: return false.also { log("The PlaitOS backup service is not running") }
        val src = appDir(pkg, userId)
        // App-private staging: nothing in shared storage can change between the checks and the restore
        val stage = File(context.noBackupFilesDir, "stage/$pkg")
        stage.deleteRecursively()
        if (!stage.mkdirs()) return false.also { log("Failed ($pkg): could not stage the archive") }
        try {
            val files = src.listFiles()?.filter { it.isFile && it.name.matches(SAFE_NAME) }
                ?: return false.also { log("Failed ($pkg): no archive") }
            for (f in files) {
                val name = f.name.removeSuffix(ArchiveCrypto.SUFFIX)
                try {
                    f.inputStream().use { input ->
                        File(stage, name).outputStream().use { out ->
                            if (f.name.endsWith(ArchiveCrypto.SUFFIX)) {
                                ArchiveCrypto.decrypt(input, out) { salt, it -> KeyRing.keyFor(context, salt, it) }
                            } else {
                                input.copyTo(out)
                            }
                        }
                    }
                } catch (e: Exception) {
                    return false.also { log("Refused ($pkg): ${f.name} could not be decrypted (wrong passphrase, or the file was changed)") }
                }
            }
            verifyArchive(pkg, stage, trustForeign)?.let { return false.also { _ -> log("Refused ($pkg): $it") } }
            log("Restoring $pkg (${parts.joinToString { it.label }})")

            fun installed() = runCatching { context.packageManager.getPackageInfo(pkg, 0) }.getOrNull() != null
            File(stage, Part.APK.fileName).takeIf { Part.APK in parts && it.isFile }?.let { apk ->
                try {
                    svc.fromFile(apk) { pfd -> svc.installApks(grant, pkg, userId, pfd) }
                } catch (e: Exception) {
                    // Only a refused downgrade (a newer version is installed) keeps the installed app; any other
                    // failure stops the restore, so newer data never lands in an older app
                    if (!installed() || e.message?.contains("INSTALL_FAILED_VERSION_DOWNGRADE") != true) throw e
                    log("Kept the newer installed $pkg")
                }
            }
            if (!installed()) return false.also { log("Failed ($pkg): $pkg is not installed") }
            for (part in parts - Part.APK) {
                val f = File(stage, part.fileName).takeIf { it.isFile } ?: continue
                svc.fromFile(f) { pfd -> svc.restorePart(grant, pkg, userId, part.code, pfd) }
            }
            val perms = File(stage, "permissions.txt").takeIf { it.isFile }?.readLines()
                ?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
            // "Uid mode: OP: mode" lines are uid modes (All files access, ...), sent as "uid:OP"; others are
            // package modes. Root-backend archives hold raw `appops get` output, where only the first uid line is
            // marked: that one is applied as a uid mode, the rest of its block as package modes
            val ops = File(stage, "appops.txt").takeIf { it.isFile }?.readLines().orEmpty().mapNotNull { line ->
                val t = line.trim()
                val uid = t.startsWith("Uid mode: ")
                APPOPS_LINE.find(t.removePrefix("Uid mode: "))?.let {
                    (if (uid) "uid:" else "") + it.groupValues[1] to it.groupValues[2]
                }
            }
            svc.restoreState(grant, pkg, userId, perms.toTypedArray(), ops.map { it.first }.toTypedArray(),
                ops.map { it.second }.toTypedArray())
            return true
        } catch (e: Exception) {
            return false.also { log("Failed ($pkg): ${e.message}") }
        } finally {
            stage.deleteRecursively()
        }
    }

    /** Null if the staged archive may be restored, else why not: as [DevRootBackend], signature then file hashes. */
    private fun verifyArchive(pkg: String, dir: File, trustForeign: Boolean): String? {
        fun text(name: String) = File(dir, name).takeIf { it.isFile }?.readText().orEmpty()
        val manifestText = text(ArchiveCheck.MANIFEST)
        val sig = text(ArchiveCheck.SIGNATURE).trim()
        val key = text(ArchiveCheck.KEY).trim()
        ArchiveCheck.verify(pkg, manifestText, sig, key, ArchiveSigner.publicKeyB64(), trustForeign,
            sums = {
                dir.listFiles().orEmpty().filter { it.isFile && it.name !in ArchiveCheck.MANIFEST_FILES }
                    .associate { it.name to sha256(it) }
            },
            verifySignature = ArchiveSigner::verify,
        )?.let { return it }
        // Tar entries are checked by installd itself as it extracts (nothing can leave the app's dirs)
        return null
    }

    private fun sha256(f: File): String {
        val d = MessageDigest.getInstance("SHA-256")
        FileInputStream(f).use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                d.update(buf, 0, n)
            }
        }
        return d.digest().joinToString("") { "%02x".format(it) }
    }

    override fun listArchives(): List<ArchiveApp> {
        val apps = File(ROOT, "apps").listFiles().orEmpty()
        return apps.flatMap { pkgDir ->
            pkgDir.listFiles().orEmpty().filter { it.isDirectory && it.name.startsWith("user_") }.mapNotNull { userDir ->
                val user = userDir.name.removePrefix("user_").toIntOrNull() ?: return@mapNotNull null
                val names = userDir.list().orEmpty().toSet()
                val parts = Part.entries.filter { p -> p.fileName in names || p.fileName + ArchiveCrypto.SUFFIX in names }.toSet()
                val size = userDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                if (parts.isEmpty() || !Layout.isSafePackage(pkgDir.name)) null
                else ArchiveApp(pkgDir.name, user, parts, size)
            }
        }.sortedBy { it.packageName }
    }

    private val Part.code: Int
        get() = when (this) {
            Part.USER -> 1
            Part.USER_DE -> 2
            Part.DATA -> 3
            Part.OBB -> 4
            Part.MEDIA -> 5
            Part.APK -> error("APKs are not a data part")
        }

    /**
     * A hand-written proxy for com.android.internal.plait.IPlaitBackup (the framework's copy is hidden from apps).
     * Transaction codes follow the method order in the .aidl; keep them in step.
     */
    private class Service(private val binder: IBinder) {
        fun backupPart(grant: String, pkg: String, userId: Int, part: Int, out: ParcelFileDescriptor): Boolean =
            call(TX_BACKUP_PART, { it.writeString(grant); it.writeString(pkg); it.writeInt(userId); it.writeInt(part); fd(it, out) }) {
                it.readInt() != 0
            }

        fun backupApks(grant: String, pkg: String, userId: Int, out: ParcelFileDescriptor) =
            call(TX_BACKUP_APKS, { it.writeString(grant); it.writeString(pkg); it.writeInt(userId); fd(it, out) }) {}

        fun getState(grant: String, pkg: String, userId: Int): String =
            call(TX_GET_STATE, { it.writeString(grant); it.writeString(pkg); it.writeInt(userId) }) { it.readString().orEmpty() }

        fun installApks(grant: String, pkg: String, userId: Int, source: ParcelFileDescriptor) =
            call(TX_INSTALL_APKS, { it.writeString(grant); it.writeString(pkg); it.writeInt(userId); fd(it, source) }) {}

        fun restorePart(grant: String, pkg: String, userId: Int, part: Int, source: ParcelFileDescriptor) =
            call(TX_RESTORE_PART, { it.writeString(grant); it.writeString(pkg); it.writeInt(userId); it.writeInt(part); fd(it, source) }) {}

        fun restoreState(grant: String, pkg: String, userId: Int, perms: Array<String>, ops: Array<String>, modes: Array<String>) =
            call(TX_RESTORE_STATE, {
                it.writeString(grant); it.writeString(pkg); it.writeInt(userId)
                it.writeStringArray(perms); it.writeStringArray(ops); it.writeStringArray(modes)
            }) {}

        /** Runs a service call that writes into a pipe, copying what it writes to [out]. */
        fun stream(out: OutputStream, call: (ParcelFileDescriptor) -> Any?) {
            val pipe = ParcelFileDescriptor.createPipe()
            var error: Throwable? = null
            // The call blocks until installd is done, so it runs beside the copy
            val caller = thread(name = "PlaitBackupCall") {
                try {
                    call(pipe[1])
                } catch (t: Throwable) {
                    error = t
                } finally {
                    pipe[1].close()
                }
            }
            ParcelFileDescriptor.AutoCloseInputStream(pipe[0]).use { it.copyTo(out) }
            caller.join()
            error?.let { throw it }
        }

        /** Runs a service call that reads from a pipe, feeding it [file]. */
        fun fromFile(file: File, call: (ParcelFileDescriptor) -> Any?) {
            val pipe = ParcelFileDescriptor.createPipe()
            var error: Throwable? = null
            val feeder = thread(name = "PlaitBackupFeed") {
                try {
                    ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { out ->
                        file.inputStream().use { it.copyTo(out) }
                    }
                } catch (t: Throwable) {
                    // The service stopped reading (it refused the stream); its error says why
                    if (error == null) error = t
                }
            }
            try {
                call(pipe[0])
            } finally {
                pipe[0].close()
                feeder.join()
            }
            // The call succeeded: a feeder failure (other than the service having stopped reading) means the
            // service may have got a short stream, which must not count as a restore
            error?.let { e ->
                val stoppedReading = e is java.io.IOException && e.message?.contains("EPIPE") == true
                if (!stoppedReading) throw e
            }
        }

        private fun fd(p: Parcel, pfd: ParcelFileDescriptor) {
            p.writeInt(1)
            pfd.writeToParcel(p, 0)
        }

        private fun <T> call(code: Int, write: (Parcel) -> Unit, read: (Parcel) -> T): T {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(DESCRIPTOR)
                write(data)
                binder.transact(code, data, reply, 0)
                reply.readException()
                return read(reply)
            } finally {
                reply.recycle()
                data.recycle()
            }
        }

        companion object {
            const val DESCRIPTOR = "com.android.internal.plait.IPlaitBackup"
            const val TX_BACKUP_PART = IBinder.FIRST_CALL_TRANSACTION
            const val TX_BACKUP_APKS = IBinder.FIRST_CALL_TRANSACTION + 1
            const val TX_GET_STATE = IBinder.FIRST_CALL_TRANSACTION + 2
            const val TX_INSTALL_APKS = IBinder.FIRST_CALL_TRANSACTION + 3
            const val TX_RESTORE_PART = IBinder.FIRST_CALL_TRANSACTION + 4
            const val TX_RESTORE_STATE = IBinder.FIRST_CALL_TRANSACTION + 5

            /** ServiceManager is hidden but allowed for apps (unsupported app usage); checkService doesn't wait. */
            fun connect(): Service? = runCatching {
                val binder = Class.forName("android.os.ServiceManager").getMethod("checkService", String::class.java)
                    .invoke(null, "plait_backup") as IBinder?
                binder?.let { Service(it) }
            }.getOrNull()
        }
    }

    companion object {
        /** The app's view of the same folder the root backend writes ([Layout.ROOT] is /data/media/0/...). */
        private val ROOT = File(Environment.getExternalStorageDirectory(), "PlaitBackup")

        /** The backend on PlaitOS (the service exists), else null. */
        fun create(context: Context): SystemBackend? = Service.connect()?.let { SystemBackend(context, it) }

        private val APPOPS_LINE = Regex("^([A-Z_0-9]+): ([a-z]+)")
        private val SAFE_NAME = Regex("[A-Za-z0-9._-]+")
    }
}
