package app.plait.backup

import android.content.ContentValues
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

    private val service: Service? = connected

    override fun unavailableReason(): String? = when {
        service == null -> "The PlaitOS backup service is not running"
        !Environment.isExternalStorageManager() ->
            "Allow \"All files access\" for Backup (Settings > Apps > Special app access)"
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
            // Files that stay readable without the passphrase (the app's name and icon): hashed in the manifest like the rest
            fun savePlain(name: String, bytes: ByteArray) {
                File(fresh, name).writeBytes(bytes)
                sums[name] = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
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
                    val saved = save(part.fileName) { o -> svc.stream(o) { pfd -> svc.backupPart(grant, pkg, userId, part.code, pfd) } }
                    // Every installed app has its private folders: none means something is wrong, not "no data" (as the root backend)
                    if (!saved && (part == Part.USER || part == Part.USER_DE)) return false.also { log("Failed ($pkg): no ${part.label.lowercase()} came back") }
                }
            }
            // Runtime permissions and app-ops, in the files the root backend writes
            val state = svc.getState(grant, pkg, userId)
            saveBytes("permissions.txt", state.substringBefore("---\n").toByteArray())
            saveBytes("appops.txt", state.substringAfter("---\n", "").toByteArray())
            saveBytes("package.txt", "$pkg\n".toByteArray())
            // The name and icon, plain, so the Restore list can show them where the app is not installed
            val (labelBytes, iconBytes) = AppMeta.collect(context, pkg)
            labelBytes?.let { savePlain(AppMeta.LABEL, it) }
            iconBytes?.let { savePlain(AppMeta.ICON, it) }
            // Who installed it (Play Store, F-Droid, ...), so a restore can record the same installer
            val installer = runCatching { context.packageManager.getInstallSourceInfo(pkg).installingPackageName }.getOrNull()
            saveBytes(InstallerSetting.FILE, (installer ?: "").toByteArray())

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
    ): Boolean = restoreApp(grant, pkg, userId, parts, trustForeign, log, retried = false)

    private fun restoreApp(
        grant: String, pkg: String, userId: Int, parts: Set<Part>, trustForeign: Boolean, log: (String) -> Unit, retried: Boolean,
    ): Boolean {
        if (!Layout.isSafePackage(pkg)) return false.also { log("Refused: bad package name $pkg") }
        if (pkg in ArchiveCheck.BLOCKED) return false.also { log("Refused: $pkg is never restored (it guards backups)") }
        val svc = service ?: return false.also { log("The PlaitOS backup service is not running") }
        val src = appDir(pkg, userId)
        // App-private staging for the small files (manifest, APK, permissions): nothing in shared storage can change
        // between the checks and their use. The data parts are the big ones and are not copied aside: each is
        // decrypted and hashed as it streams into the service, which extracts it into a staging dir of its own and
        // only swaps it in (after the whole archive has been checked) once everything has arrived intact.
        val stage = File(context.noBackupFilesDir, "stage/$pkg")
        stage.deleteRecursively()
        if (!stage.mkdirs()) return false.also { log("Failed ($pkg): could not stage the archive") }
        val staged = ArrayList<Int>() // data parts the service holds staged, to be finished either way
        var finished = false
        try {
            val files = src.listFiles()?.filter { it.isFile && it.name.matches(SAFE_NAME) }
                ?: return false.also { log("Failed ($pkg): no archive") }
            fun plain(f: File) = f.name.removeSuffix(ArchiveCrypto.SUFFIX)
            val dataFileNames = Part.entries.filter { it != Part.APK }.map { it.fileName }.toSet()
            val dataParts = (parts - Part.APK).filter { p -> files.any { plain(it) == p.fileName } }
            val streamed = dataParts.map { it.fileName }.toSet()
            val sums = HashMap<String, String>() // plaintext SHA-256 of every file but the manifest's

            // 1. The small files into the staging dir; data files nobody asked to restore are only hashed (the
            //    manifest lists every file, so each has to be checked)
            for (f in files) {
                val name = plain(f)
                if (name in streamed) continue
                val keep = name in ArchiveCheck.MANIFEST_FILES || name !in dataFileNames
                val hash = try {
                    if (keep) File(stage, name).outputStream().buffered(BUF).use { decryptTo(f, it) }
                    else decryptTo(f, object : OutputStream() {
                        override fun write(b: Int) {}
                        override fun write(b: ByteArray, off: Int, len: Int) {}
                    })
                } catch (e: Exception) {
                    // A tag mismatch with a typed older passphrase: the keyboard may have altered it (see PassphraseVariants)
                    val variant = if (!retried && f.name.endsWith(ArchiveCrypto.SUFFIX) && e is javax.crypto.AEADBadTagException) KeyRing.recoverTyped(f) else null
                    if (variant != null) {
                        log("The typed passphrase did not open the archive, but it does $variant: using that")
                        return restoreApp(grant, pkg, userId, parts, trustForeign, log, retried = true)
                    }
                    return false.also { log("Refused ($pkg): ${f.name} could not be decrypted: ${ArchiveCrypto.describe(e)}") }
                }
                if (name !in ArchiveCheck.MANIFEST_FILES) sums[name] = hash
            }

            // 2. Signature, trust and everything already hashed; the data parts count as what the manifest says
            //    until they have streamed (checked again below, before anything is swapped in)
            val declared = ArchiveCheck.parse(File(stage, ArchiveCheck.MANIFEST).takeIf { it.isFile }?.readText().orEmpty())
                ?.files?.filterKeys { it in streamed }.orEmpty()
            verifyStaged(pkg, stage, trustForeign) { sums + declared }?.let { return false.also { _ -> log("Refused ($pkg): $it") } }
            log("Restoring $pkg (${parts.joinToString { it.label }})")

            fun installed() = runCatching { context.packageManager.getPackageInfo(pkg, 0) }.getOrNull() != null
            File(stage, Part.APK.fileName).takeIf { Part.APK in parts && it.isFile }?.let { apk ->
                val recorded = InstallerSetting.parseRecorded(File(stage, InstallerSetting.FILE).takeIf { it.isFile }?.readText())
                val installer = InstallerSetting.installerFor(InstallerSetting.choice, recorded)
                try {
                    if (installer == null) {
                        svc.fromFile(apk) { pfd -> svc.installApks(grant, pkg, userId, pfd) }
                    } else {
                        try {
                            svc.fromFile(apk) { pfd -> svc.installApksAs(grant, pkg, userId, pfd, installer) }
                            log("Recorded $pkg as installed by $installer")
                        } catch (e: ServiceTooOld) {
                            log("This ROM's backup service cannot record an installer: $pkg was installed without one")
                            svc.fromFile(apk) { pfd -> svc.installApks(grant, pkg, userId, pfd) }
                        }
                    }
                } catch (e: Exception) {
                    // Only a refused downgrade (a newer version is installed) keeps the installed app; any other
                    // failure stops the restore, so newer data never lands in an older app
                    if (!installed() || e.message?.contains("INSTALL_FAILED_VERSION_DOWNGRADE") != true) throw e
                    log("Kept the newer installed $pkg")
                }
            }
            if (!installed()) return false.also { log("Failed ($pkg): $pkg is not installed") }

            // 3. The data parts. A ROM with the two-step restore takes them as a stream; an older one gets them
            //    from staged files, as before
            val twoStep = dataParts.isEmpty() || runCatching { svc.restoreFinish(grant, pkg, userId, IntArray(0), false) }
                .map { true }.getOrElse { if (it is ServiceTooOld) false else throw it }
            if (twoStep) {
                for (part in dataParts) {
                    val f = files.first { plain(it) == part.fileName }
                    staged += part.code
                    val digest = MessageDigest.getInstance("SHA-256")
                    svc.fromDecrypted(f, digest, { out, input -> decryptStream(f, input, out) }) { pfd ->
                        svc.restorePartStage(grant, pkg, userId, part.code, pfd)
                    }
                    sums[part.fileName] = digest.digest().joinToString("") { "%02x".format(it) }
                }
                // Everything is hashed now: the same checks with the real numbers, before anything is swapped in
                verifyStaged(pkg, stage, trustForeign) { sums }?.let { return false.also { _ -> log("Refused ($pkg): $it") } }
                if (staged.isNotEmpty()) svc.restoreFinish(grant, pkg, userId, staged.toIntArray(), true)
                finished = true
            } else {
                for (part in dataParts) {
                    val f = files.first { plain(it) == part.fileName }
                    sums[part.fileName] = File(stage, part.fileName).outputStream().buffered(BUF).use { decryptTo(f, it) }
                }
                verifyStaged(pkg, stage, trustForeign) { sums }?.let { return false.also { _ -> log("Refused ($pkg): $it") } }
                for (part in dataParts) {
                    svc.fromFile(File(stage, part.fileName)) { pfd -> svc.restorePart(grant, pkg, userId, part.code, pfd) }
                }
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
            // Whatever the service still holds staged is thrown away (it never touched the app's data); this
            // must not throw, and a service that is gone has nothing left to discard
            if (staged.isNotEmpty() && !finished) runCatching { svc.restoreFinish(grant, pkg, userId, staged.toIntArray(), false) }
            stage.deleteRecursively()
        }
    }

    /** Decrypts [f] (or copies it, for an archive made without encryption) into [sink]; the plaintext SHA-256 (hex). */
    private fun decryptTo(f: File, sink: OutputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        f.inputStream().buffered(BUF).use { input -> decryptStream(f, input, java.security.DigestOutputStream(sink, digest)) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun decryptStream(f: File, input: InputStream, out: OutputStream) {
        if (f.name.endsWith(ArchiveCrypto.SUFFIX)) {
            ArchiveCrypto.decrypt(input, out) { salt, it -> KeyRing.keyFor(context, salt, it) }
        } else {
            input.copyTo(out, BUF)
        }
    }

    /** As [verifyArchive], for files that are staged but whose hashes [sums] gives (some were hashed as they streamed). */
    private fun verifyStaged(pkg: String, dir: File, trustForeign: Boolean, sums: () -> Map<String, String>): String? {
        fun text(name: String) = File(dir, name).takeIf { it.isFile }?.readText().orEmpty()
        return ArchiveCheck.verify(pkg, text(ArchiveCheck.MANIFEST), text(ArchiveCheck.SIGNATURE).trim(),
            text(ArchiveCheck.KEY).trim(), ArchiveSigner.publicKeyB64(), trustForeign,
            sums = sums, verifySignature = ArchiveSigner::verify)
    }

    // ---- phone data: call log, messages, contacts ----

    override val supportsPersonal = true

    private fun personalDir(kind: Personal, userId: Int) = File(ROOT, "personal/${kind.dirName}/user_$userId")

    override fun backupPersonal(grant: String, kind: Personal, userId: Int, log: (String) -> Unit): Boolean {
        val svc = service ?: return false.also { log("The PlaitOS backup service is not running") }
        val key = KeyRing.currentKey(context)
            ?: return false.also { log("Refused: set an encryption passphrase in Settings first") }
        log("Backing up ${kind.label}")
        val dest = personalDir(kind, userId)
        val fresh = File(dest.path + ".new")
        try {
            fresh.deleteRecursively()
            if (!fresh.mkdirs()) return false.also { log("Failed (${kind.label}): no archive dir") }
            val (count, attachments) = PersonalArchive.write(fresh, key, kind, userId, svc.store(grant, userId))
            swapIn(fresh, dest)?.let { return false.also { _ -> log("Failed (${kind.label}): $it") } }
            log("${kind.label}: $count saved" + if (attachments > 0) ", $attachments attachments" else "")
            return true
        } catch (e: Exception) {
            return false.also { log("Failed (${kind.label}): ${e.message}") }
        } finally {
            fresh.deleteRecursively()
        }
    }

    override fun importPersonal(kind: Personal, userId: Int, produce: ((String, () -> java.io.InputStream) -> Unit) -> ByteArray, log: (String) -> Unit): Boolean {
        val key = KeyRing.currentKey(context)
            ?: return false.also { log("Refused: set an encryption passphrase in Settings first") }
        val dest = personalDir(kind, userId)
        val fresh = File(dest.path + ".new")
        try {
            fresh.deleteRecursively()
            if (!fresh.mkdirs()) return false.also { log("Failed (${kind.label}): no archive dir") }
            val (count, attachments) = PersonalArchive.writeFrom(fresh, key, kind, userId, produce)
            if (count == 0) return false.also { log("${kind.label}: the file has none, nothing imported") }
            swapIn(fresh, dest)?.let { return false.also { _ -> log("Failed (${kind.label}): $it") } }
            log("${kind.label}: $count imported" + if (attachments > 0) ", $attachments attachments" else "")
            return true
        } catch (e: Exception) {
            return false.also { log("Failed (${kind.label}): ${e.message}") }
        } finally {
            fresh.deleteRecursively()
        }
    }

    override fun restorePersonal(grant: String, kind: Personal, userId: Int, trustForeign: Boolean, log: (String) -> Unit): Boolean {
        val svc = service ?: return false.also { log("The PlaitOS backup service is not running") }
        val stage = File(context.noBackupFilesDir, "stage/${kind.dirName}")
        try {
            stageArchive(personalDir(kind, userId), stage)?.let { return false.also { _ -> log("Refused (${kind.label}): $it") } }
            verifyArchive(kind.target, stage, trustForeign)?.let { return false.also { _ -> log("Refused (${kind.label}): $it") } }
            val json = File(stage, kind.fileName).takeIf { it.isFile }?.readBytes()
                ?: return false.also { log("Failed (${kind.label}): the archive has no ${kind.fileName}") }
            log("Restoring ${kind.label}")
            val r = PersonalImport(svc.store(grant, userId), log).import(kind, json) { name ->
                File(stage, name).takeIf { it.isFile && name.matches(SAFE_NAME) }?.inputStream()
            }
            log("${kind.label}: ${r.added} added, ${r.skipped} skipped (already there or not restorable)" +
                if (r.failed > 0) ", ${r.failed} failed" else "")
            return r.failed == 0
        } catch (e: Exception) {
            return false.also { log("Failed (${kind.label}): ${e.message}") }
        } finally {
            stage.deleteRecursively()
        }
    }

    /** Puts [fresh] in place of [dest], keeping the old archive if that fails; null on success, else why not. */
    private fun swapIn(fresh: File, dest: File): String? {
        val old = File(dest.path + ".old")
        old.deleteRecursively()
        dest.parentFile?.mkdirs()
        if (dest.exists() && !dest.renameTo(old)) return "could not move the old archive aside"
        if (!fresh.renameTo(dest)) {
            if (old.exists()) old.renameTo(dest)
            return "could not move the archive in place"
        }
        old.deleteRecursively()
        return null
    }

    /** Decrypts the archive in [src] into app-private [stage]; null on success, else why not. */
    private fun stageArchive(src: File, stage: File): String? {
        stage.deleteRecursively()
        if (!stage.mkdirs()) return "could not stage the archive"
        val files = src.listFiles()?.filter { it.isFile && it.name.matches(SAFE_NAME) } ?: return "no archive"
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
                return "${f.name} could not be decrypted: ${ArchiveCrypto.describe(e)}"
            }
        }
        return null
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
                else {
                    val label = AppMeta.parseLabel(
                        File(userDir, AppMeta.LABEL).takeIf { it.isFile && it.length() <= AppMeta.MAX_LABEL_BYTES }?.readText())
                    val icon = File(userDir, AppMeta.ICON).takeIf { it.isFile && it.length() <= AppMeta.MAX_ICON_BYTES }
                        ?.readBytes()?.takeIf(AppMeta::looksLikeIcon)
                    ArchiveApp(pkgDir.name, user, parts, size, label, icon)
                }
            }
        }.sortedBy { it.packageName } + Personal.entries.flatMap { kind ->
            File(ROOT, "personal/${kind.dirName}").listFiles().orEmpty().filter { it.isDirectory && it.name.startsWith("user_") }
                .mapNotNull { userDir ->
                    val user = userDir.name.removePrefix("user_").toIntOrNull() ?: return@mapNotNull null
                    if (userDir.list().orEmpty().none { it.startsWith(kind.fileName) }) return@mapNotNull null
                    ArchiveApp(kind.target, user, emptySet(), userDir.walkTopDown().filter { it.isFile }.sumOf { it.length() })
                }
        }
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
    /** The service does not know the call (an older ROM): an unknown code makes transact return false, which is not success. */
    private class ServiceTooOld : RuntimeException("the backup service does not have this call")

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

        /** As [installApks], recording [installer] (a package name) as the app's installer. Only newer ROMs have it. */
        fun installApksAs(grant: String, pkg: String, userId: Int, source: ParcelFileDescriptor, installer: String) =
            call(TX_INSTALL_APKS_AS, {
                it.writeString(grant); it.writeString(pkg); it.writeInt(userId); fd(it, source); it.writeString(installer)
            }) {}

        fun restorePart(grant: String, pkg: String, userId: Int, part: Int, source: ParcelFileDescriptor) =
            call(TX_RESTORE_PART, { it.writeString(grant); it.writeString(pkg); it.writeInt(userId); it.writeInt(part); fd(it, source) }) {}

        /** The first step of a two-step restore (newer ROMs only): extracts into a staging dir, the app's data untouched. */
        fun restorePartStage(grant: String, pkg: String, userId: Int, part: Int, source: ParcelFileDescriptor) =
            call(TX_RESTORE_PART_STAGE, { it.writeString(grant); it.writeString(pkg); it.writeInt(userId); it.writeInt(part); fd(it, source) }) {}

        /** The second step: swaps the staged [parts] in (commit) or throws them away. */
        fun restoreFinish(grant: String, pkg: String, userId: Int, parts: IntArray, commit: Boolean) =
            call(TX_RESTORE_FINISH, {
                it.writeString(grant); it.writeString(pkg); it.writeInt(userId); it.writeIntArray(parts); it.writeInt(if (commit) 1 else 0)
            }) {}

        /**
         * Runs a service call that reads a pipe, which [feed] fills (decrypting [file] into it, hashing as it goes
         * into [digest]). A failure of the feed (a wrong passphrase, a changed file) is the error, not the service's
         * complaint about the short stream it then got.
         */
        fun fromDecrypted(
            file: File, digest: MessageDigest, feed: (OutputStream, InputStream) -> Unit, call: (ParcelFileDescriptor) -> Any?,
        ) {
            val pipe = ParcelFileDescriptor.createPipe()
            runCatching { android.system.Os.fcntlInt(pipe[0].fileDescriptor, F_SETPIPE_SZ, 1 shl 20) }
            var error: Throwable? = null
            val feeder = thread(name = "PlaitBackupFeed") {
                try {
                    ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).buffered(BUF).use { raw ->
                        val out = java.security.DigestOutputStream(raw, digest)
                        file.inputStream().buffered(BUF).use { feed(out, it) }
                    }
                } catch (t: Throwable) {
                    if (error == null) error = t
                }
            }
            var callError: Throwable? = null
            try {
                call(pipe[0])
            } catch (t: Throwable) {
                callError = t
            } finally {
                pipe[0].close()
                feeder.join()
            }
            val e = error
            val stoppedReading = e is java.io.IOException && e.message?.contains("EPIPE") == true
            if (e != null && !stoppedReading) throw IllegalStateException("${file.name} could not be decrypted: ${ArchiveCrypto.describe(e)}", e)
            callError?.let { throw it }
        }

        fun restoreState(grant: String, pkg: String, userId: Int, perms: Array<String>, ops: Array<String>, modes: Array<String>) =
            call(TX_RESTORE_STATE, {
                it.writeString(grant); it.writeString(pkg); it.writeInt(userId)
                it.writeStringArray(perms); it.writeStringArray(ops); it.writeStringArray(modes)
            }) {}

        fun personalQuery(grant: String, userId: Int, uri: String, projection: Array<String>?, selection: String?,
                          args: Array<String>?, sort: String?, out: ParcelFileDescriptor) =
            call(TX_PERSONAL_QUERY, {
                it.writeString(grant); it.writeInt(userId); it.writeString(uri); it.writeStringArray(projection)
                it.writeString(selection); it.writeStringArray(args); it.writeString(sort); fd(it, out)
            }) {}

        fun personalInsert(grant: String, userId: Int, uri: String, values: ContentValues): String? =
            call(TX_PERSONAL_INSERT, {
                it.writeString(grant); it.writeInt(userId); it.writeString(uri); it.writeInt(1); values.writeToParcel(it, 0)
            }) { it.readString() }

        fun personalDelete(grant: String, userId: Int, uri: String): Int =
            call(TX_PERSONAL_DELETE, { it.writeString(grant); it.writeInt(userId); it.writeString(uri) }) { it.readInt() }

        fun personalOpenFile(grant: String, userId: Int, uri: String, mode: String): ParcelFileDescriptor =
            call(TX_PERSONAL_OPEN_FILE, { it.writeString(grant); it.writeInt(userId); it.writeString(uri); it.writeString(mode) }) {
                if (it.readInt() != 0) ParcelFileDescriptor.CREATOR.createFromParcel(it) else error("no file for $uri")
            }

        /** The providers behind call log, messages and contacts, through the service, for one grant. */
        fun store(grant: String, userId: Int): PersonalStore = object : PersonalStore {
            override fun query(uri: String, projection: Array<String>?, selection: String?, args: Array<String>?, sort: String?): Rows {
                val text = java.io.ByteArrayOutputStream()
                stream(text) { pfd -> personalQuery(grant, userId, uri, projection, selection, args, sort, pfd) }
                val lines = text.toString(Charsets.UTF_8.name()).lineSequence().filter { it.isNotEmpty() }.toList()
                val columns = lines.firstOrNull()?.let { head ->
                    val a = org.json.JSONObject(head).getJSONArray("columns")
                    (0 until a.length()).map { a.getString(it) }
                } ?: return Rows(emptyList(), emptyList())
                val rows = lines.drop(1).map { line ->
                    val a = org.json.JSONArray(line)
                    (0 until a.length()).map { i ->
                        when (val v = a.get(i)) {
                            org.json.JSONObject.NULL -> null
                            is org.json.JSONObject -> java.util.Base64.getDecoder().decode(v.getString("blob"))
                            is Int -> v.toLong()
                            else -> v
                        }
                    }
                }
                return Rows(columns, rows)
            }

            override fun insert(uri: String, values: Map<String, Any?>) = personalInsert(grant, userId, uri, ContentValues().apply {
                for ((k, v) in values) {
                    when (v) {
                        null -> putNull(k)
                        is Long -> put(k, v)
                        is Int -> put(k, v)
                        is Double -> put(k, v)
                        is ByteArray -> put(k, v)
                        else -> put(k, v.toString())
                    }
                }
            })
            override fun delete(uri: String) = personalDelete(grant, userId, uri)
            override fun read(uri: String): InputStream =
                ParcelFileDescriptor.AutoCloseInputStream(personalOpenFile(grant, userId, uri, "r"))
            override fun write(uri: String): OutputStream =
                ParcelFileDescriptor.AutoCloseOutputStream(personalOpenFile(grant, userId, uri, "w"))
        }

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
                if (!binder.transact(code, data, reply, 0)) throw ServiceTooOld()
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
            const val TX_PERSONAL_QUERY = IBinder.FIRST_CALL_TRANSACTION + 6
            const val TX_PERSONAL_INSERT = IBinder.FIRST_CALL_TRANSACTION + 7
            const val TX_PERSONAL_DELETE = IBinder.FIRST_CALL_TRANSACTION + 8
            const val TX_PERSONAL_OPEN_FILE = IBinder.FIRST_CALL_TRANSACTION + 9
            const val TX_INSTALL_APKS_AS = IBinder.FIRST_CALL_TRANSACTION + 10
            const val TX_RESTORE_PART_STAGE = IBinder.FIRST_CALL_TRANSACTION + 11
            const val TX_RESTORE_FINISH = IBinder.FIRST_CALL_TRANSACTION + 12

            /** ServiceManager is hidden but allowed for apps (unsupported app usage); checkService doesn't wait. */
            fun connect(): Service? = runCatching {
                val binder = Class.forName("android.os.ServiceManager").getMethod("checkService", String::class.java)
                    .invoke(null, "plait_backup") as IBinder?
                binder?.let { Service(it) }
            }.getOrNull()
        }
    }

    companion object {
        /** The folder chosen in Settings, as this app sees it ([Layout.ROOT] is the same folder as root sees it). */
        private val ROOT: File get() = File(BackupFolder.userPath)

        /** The backend on PlaitOS (the service exists), else null. */
        fun create(context: Context): SystemBackend? = Service.connect()?.let { SystemBackend(context, it) }

        /** Copy and pipe buffers: the defaults (8 KiB, 64 KiB) make a multi-GB restore mostly system calls. */
        private const val BUF = 1 shl 20
        private const val F_SETPIPE_SZ = 1031
        private val APPOPS_LINE = Regex("^([A-Z_0-9]+): ([a-z]+)")
        private val SAFE_NAME = Regex("[A-Za-z0-9._-]+")
    }
}
