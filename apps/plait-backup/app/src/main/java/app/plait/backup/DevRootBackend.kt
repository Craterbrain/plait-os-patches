package app.plait.backup

import android.os.Process
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec

/**
 * Stand-in for the PlaitOS `plait_backup` service on the current rooted phone: the same checks and the same archive
 * layout, done with su + toybox tar + zstd. It verifies every grant against the broker manager's public key (fetched
 * as root from its system-only provider) and asks the manager whether the grant is still active, exactly as the system
 * service will, so the grant flow is exercised end to end. Never part of the ROM.
 */
class DevRootBackend(private val context: android.content.Context) : Backend {
    override val needsRoot get() = true
    override val name = "Root (development backend)"

    private val myUid = Process.myUid()
    private var managerKey: PublicKey? = null

    override fun unavailableReason(): String? = RootShell.problem()

    // ---- grants ----

    private fun publicKey(): PublicKey? {
        managerKey?.let { return it }
        val r = RootShell.run("content call --uri content://$GRANTS --method publicKey", timeoutMinutes = 1)
        // "Result: Bundle[{key=[48, 89, ...]}]"
        val list = Regex("key=\\[([-0-9, ]+)]").find(r.out)?.groupValues?.get(1) ?: return null
        val bytes = list.split(',').map { it.trim().toInt().toByte() }.toByteArray()
        return KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(bytes)).also { managerKey = it }
    }

    private fun active(grantId: String): Boolean =
        RootShell.run("content call --uri content://$GRANTS --method check --arg $grantId", timeoutMinutes = 1)
            .out.contains("active=true")

    private fun logUse(grantId: String, what: String) {
        val safe = what.replace(Regex("[^A-Za-z0-9._ +-]"), "_")
        RootShell.run("content call --uri content://$GRANTS --method used --arg $grantId --extra what:s:'$safe'", timeoutMinutes = 1)
    }

    /** Null if the grant allows [scope] on [pkg] for this app now, else the reason it does not. */
    private fun refuse(token: String, scope: String, pkg: String): String? {
        val key = publicKey() ?: return "could not read the broker's public key"
        val g = GrantToken.verify(token, key) ?: return "grant signature is invalid"
        if (g.uid != myUid || g.packageName != Grants.PACKAGE) return "grant was issued to another app"
        if (!g.allows(scope, pkg, System.currentTimeMillis())) return "grant does not cover $scope of $pkg (or expired)"
        if (!active(g.grantId)) return "grant was revoked"
        return null
    }

    // ---- backup ----

    override fun backupApp(grant: String, pkg: String, userId: Int, parts: Set<Part>, log: (String) -> Unit): Boolean {
        if (!Layout.isSafePackage(pkg)) return false.also { log("Refused: bad package name $pkg") }
        if (pkg in ArchiveCheck.BLOCKED) return false.also { log("Refused: $pkg is never backed up (it guards backups)") }
        refuse(grant, GrantToken.SCOPE_BACKUP, pkg)?.let { return false.also { _ -> log("Refused: $it") } }
        val key = KeyRing.currentKey(context)
            ?: return false.also { log("Refused: set an encryption passphrase in Settings first") }
        // Plaintext only ever exists in the root-only staging dir; shared storage only receives encrypted files
        val out = "$STAGE/out.$pkg"
        log("Backing up $pkg (${parts.joinToString { it.label }})")
        try {
            val r = RootShell.run(backupScript(pkg, userId, parts, out))
            if (!r.ok) return false.also { log("Failed ($pkg): ${r.err.ifBlank { "exit ${r.code}" }.trim()}") }
            if (!signArchive(pkg, userId, out)) return false.also { log("Failed ($pkg): could not sign the archive") }
            val dest = Layout.appDir(pkg, userId)
            val fresh = "$dest.new"
            if (!RootShell.run("rm -rf $fresh && mkdir -p $fresh", timeoutMinutes = 1).ok) return false.also { log("Failed ($pkg): no archive dir") }
            for (name in listNames(out)) {
                RootShell.readFile("$out/$name") { plain ->
                    RootShell.writeFile("$fresh/$name${ArchiveCrypto.SUFFIX}") { enc -> ArchiveCrypto.encrypt(key, plain, enc) }
                }
            }
            // Swap in the new archive only once every file is encrypted, so a failed backup keeps the previous one
            val swap = RootShell.run(
                "rm -rf $dest && mv $fresh $dest && chown -R 1023:1023 $ROOT_DIR && chmod -R u=rwX,g=rwX,o= $dest && restorecon -RF $ROOT_DIR",
                timeoutMinutes = 5,
            )
            if (!swap.ok) return false.also { log("Failed ($pkg): ${swap.err.trim()}") }
        } catch (e: Exception) {
            return false.also { log("Failed ($pkg): ${e.message}") }
        } finally {
            RootShell.run("rm -rf $out ${Layout.appDir(pkg, userId)}.new", timeoutMinutes = 5)
        }
        logUse(grantIdOf(grant), "backup $pkg")
        return true
    }

    /** File names in a dir we control, limited to a safe charset (they are used in shell commands). */
    private fun listNames(dir: String): List<String> =
        RootShell.run("ls $dir", timeoutMinutes = 1).out.lines().map { it.trim() }
            .filter { it.matches(Regex("[A-Za-z0-9._-]+")) && it != "." && it != ".." }

    /** Writes the manifest (SHA-256 of every file) and its signature into [dir] (staging, before encryption). */
    private fun signArchive(pkg: String, userId: Int, dir: String): Boolean {
        val sums = ArchiveCheck.parseSums(RootShell.run("cd $dir && sha256sum *", timeoutMinutes = 10).out)
        if (sums.isEmpty()) return false
        val manifest = ArchiveCheck.manifest(pkg, userId, System.currentTimeMillis(), sums).toByteArray()
        fun b64(b: ByteArray) = java.util.Base64.getEncoder().encodeToString(b)
        val r = RootShell.run(
            """
            echo ${b64(manifest)} | base64 -d > $dir/${ArchiveCheck.MANIFEST} &&
            echo ${ArchiveSigner.sign(manifest)} > $dir/${ArchiveCheck.SIGNATURE} &&
            echo ${ArchiveSigner.publicKeyB64()} > $dir/${ArchiveCheck.KEY}
            """.trimIndent(),
            timeoutMinutes = 1,
        )
        return r.ok
    }

    // ---- restore ----

    override fun restoreApp(
        grant: String, pkg: String, userId: Int, parts: Set<Part>, trustForeign: Boolean, log: (String) -> Unit,
    ): Boolean {
        if (!Layout.isSafePackage(pkg)) return false.also { log("Refused: bad package name $pkg") }
        if (pkg in ArchiveCheck.BLOCKED) return false.also { log("Refused: $pkg is never restored (it guards backups)") }
        refuse(grant, GrantToken.SCOPE_RESTORE, pkg)?.let { return false.also { _ -> log("Refused: $it") } }
        // Work on a root-only copy: nothing in shared storage can change between these checks and the extraction
        val stage = "$STAGE/$pkg"
        val src = Layout.appDir(pkg, userId)
        val prep = RootShell.run("rm -rf $stage && mkdir -p $STAGE && chmod 0700 $STAGE && mkdir -m 0700 $stage", timeoutMinutes = 1)
        if (!prep.ok) return false.also { log("Failed ($pkg): could not stage the archive: ${prep.err.trim()}") }
        try {
            for (name in listNames(src)) {
                if (name.endsWith(ArchiveCrypto.SUFFIX)) {
                    try {
                        RootShell.readFile("$src/$name") { enc ->
                            RootShell.writeFile("$stage/${name.removeSuffix(ArchiveCrypto.SUFFIX)}") { plain ->
                                ArchiveCrypto.decrypt(enc, plain) { salt, it -> KeyRing.keyFor(context, salt, it) }
                            }
                        }
                    } catch (e: Exception) {
                        return false.also { log("Refused ($pkg): $name could not be decrypted (wrong passphrase, or the file was changed)") }
                    }
                } else if (!RootShell.run("cp $src/$name $stage/", timeoutMinutes = 30).ok) {
                    return false.also { log("Failed ($pkg): could not stage $name") }
                }
            }
            verifyArchive(pkg, stage, parts, trustForeign)?.let { return false.also { _ -> log("Refused ($pkg): $it") } }
            log("Restoring $pkg (${parts.joinToString { it.label }})")
            val r = RootShell.run(restoreScript(pkg, userId, parts, src = stage))
            if (!r.ok) log("Failed ($pkg): ${r.err.ifBlank { "exit ${r.code}" }.trim()}")
            else logUse(grantIdOf(grant), "restore $pkg")
            return r.ok
        } finally {
            RootShell.run("rm -rf $stage", timeoutMinutes = 5)
        }
    }

    /** Null if the staged archive may be restored, else why not: signature, file hashes, then every tar entry. */
    private fun verifyArchive(pkg: String, dir: String, parts: Set<Part>, trustForeign: Boolean): String? {
        val manifestText = RootShell.run("cat $dir/${ArchiveCheck.MANIFEST} 2>/dev/null", timeoutMinutes = 1).out
        val sig = RootShell.run("cat $dir/${ArchiveCheck.SIGNATURE} 2>/dev/null", timeoutMinutes = 1).out.trim()
        val key = RootShell.run("cat $dir/${ArchiveCheck.KEY} 2>/dev/null", timeoutMinutes = 1).out.trim()
        ArchiveCheck.verify(pkg, manifestText, sig, key, ArchiveSigner.publicKeyB64(), trustForeign,
            sums = { ArchiveCheck.parseSums(RootShell.run("cd $dir && sha256sum *", timeoutMinutes = 10).out) },
            verifySignature = ArchiveSigner::verify,
        )?.let { return it }
        for (part in parts) {
            val f = "$dir/${part.fileName}"
            if (!RootShell.run("[ -f $f ]", timeoutMinutes = 1).ok) continue
            val names = RootShell.run("zstd -q -dc $f | tar -tf -", timeoutMinutes = 10).out.lines().filter { it.isNotEmpty() }
            val verbose = RootShell.run("zstd -q -dc $f | tar -tvf -", timeoutMinutes = 10).out.lines().filter { it.isNotEmpty() }
            val bad = if (part == Part.APK) ArchiveCheck.unsafeApkEntry(names, verbose) else ArchiveCheck.unsafeDataEntry(pkg, names, verbose)
            if (bad != null) return "${part.fileName} has an unsafe entry: $bad"
        }
        return null
    }

    override fun listArchives(): List<ArchiveApp> {
        val r = RootShell.run(
            "cd $ROOT_DIR/apps 2>/dev/null || exit 0\nfor D in */user_*; do echo \"\$D \$(ls \$D | tr '\\n' ' ') \$(du -sk \$D | cut -f1)\"; done",
            timeoutMinutes = 2,
        )
        return r.out.lines().mapNotNull { line ->
            val f = line.trim().split(' ').filter { it.isNotEmpty() }
            if (f.size < 2) return@mapNotNull null
            val pkg = f[0].substringBefore('/')
            val user = f[0].substringAfter("user_").toIntOrNull() ?: return@mapNotNull null
            val parts = Part.entries.filter { p -> f.any { it == p.fileName || it == p.fileName + ArchiveCrypto.SUFFIX } }.toSet()
            val kb = f.last().toLongOrNull() ?: 0
            if (parts.isEmpty() || !Layout.isSafePackage(pkg)) null else ArchiveApp(pkg, user, parts, kb * 1024)
        }.sortedBy { it.packageName }
    }

    private fun grantIdOf(token: String) =
        runCatching { GrantToken.verify(token, publicKey()!!)?.grantId }.getOrNull() ?: "unknown"

    companion object {
        private const val GRANTS = "app.plait.broker.grants"
        private const val ROOT_DIR = Layout.ROOT
        /** Root-only (Magisk's /data/adb): neither apps nor the shell user (adb, the broker daemon) can touch it. */
        private const val STAGE = "/data/adb/plaitbackup.stage"

        /** The root script for one app; pure, so tests can print it. [pkg] must pass [Layout.isSafePackage]. */
        fun backupScript(pkg: String, userId: Int, parts: Set<Part>, out: String = "$STAGE/out.$pkg"): String {
            val s = StringBuilder("set -o pipefail\nmkdir -p $STAGE && chmod 0700 $STAGE\nOUT=$out\nrm -rf \$OUT && mkdir -m 0700 \$OUT || exit 1\n")
            fun data(part: Part, parent: String) {
                s.append(
                    """
                    if [ -d $parent/$pkg ]; then
                      tar --selinux -cpf - -C $parent --exclude=$pkg/cache --exclude=$pkg/code_cache $pkg | zstd --single-thread -q -f -o ${'$'}OUT/${part.fileName} || exit 1
                    else rm -f ${'$'}OUT/${part.fileName}; fi
                    """.trimIndent() + "\n"
                )
            }
            if (Part.APK in parts) s.append(
                """
                D=${'$'}(dirname "${'$'}(pm path --user $userId $pkg | head -1 | cut -d: -f2)")
                [ -d "${'$'}D" ] || exit 2
                (cd "${'$'}D" && tar -cf - *.apk) | zstd --single-thread -q -f -o ${'$'}OUT/apk.tar.zst || exit 1
                """.trimIndent() + "\n"
            )
            if (Part.USER in parts) data(Part.USER, "/data/user/$userId")
            if (Part.USER_DE in parts) data(Part.USER_DE, "/data/user_de/$userId")
            if (Part.DATA in parts) data(Part.DATA, "/data/media/$userId/Android/data")
            if (Part.OBB in parts) data(Part.OBB, "/data/media/$userId/Android/obb")
            if (Part.MEDIA in parts) data(Part.MEDIA, "/data/media/$userId/Android/media")
            // Runtime permissions and app-ops, so a restore can put them back
            s.append(
                """
                dumpsys package $pkg | sed -n '/runtime permissions:/,/^ *[a-zA-Z ]*:${'$'}/p' | grep 'granted=true' | sed 's/^ *\([^:]*\):.*/\1/' > ${'$'}OUT/permissions.txt
                appops get --user $userId $pkg > ${'$'}OUT/appops.txt 2>/dev/null
                echo "$pkg" > ${'$'}OUT/package.txt
                """.trimIndent() + "\n"
            )
    return s.toString()
        }

        /** The root script for one app; pure, so tests can print it. [pkg] must pass [Layout.isSafePackage]. */
        fun restoreScript(pkg: String, userId: Int, parts: Set<Part>, src: String = "$STAGE/$pkg"): String {
            val s = StringBuilder("set -o pipefail\nSRC=$src\n")
            if (Part.APK in parts) s.append(
                """
                if [ -f ${'$'}SRC/apk.tar.zst ]; then
                  T=${'$'}SRC/apk.extract; rm -rf ${'$'}T; mkdir -m 0700 ${'$'}T
                  zstd -q -dc ${'$'}SRC/apk.tar.zst | tar -xf - -C ${'$'}T || exit 1
                  SID=${'$'}(pm install-create -r --user $userId | sed -n 's/.*\[\([0-9]*\)\].*/\1/p')
                  [ -n "${'$'}SID" ] || exit 3
                  for F in ${'$'}T/*.apk; do pm install-write -S ${'$'}(stat -c %s ${'$'}F) ${'$'}SID ${'$'}(basename ${'$'}F) ${'$'}F > /dev/null || exit 3; done
                  pm install-commit ${'$'}SID || exit 3
                  rm -rf ${'$'}T
                fi
                """.trimIndent() + "\n"
            )
            s.append(
                """
                pm path --user $userId $pkg > /dev/null || { echo "$pkg is not installed" >&2; exit 4; }
                am force-stop --user $userId $pkg
                # The app's uid from the package manager: right after a reinstall its data dirs may not exist yet
                APPID=${'$'}(pm list packages -U --user $userId $pkg | sed -n 's/^package:$pkg uid:\([0-9]*\).*/\1/p' | head -1)
                [ -n "${'$'}APPID" ] || { echo "no uid for $pkg" >&2; exit 5; }
                APPID=${'$'}((APPID % 100000))
                [ "${'$'}APPID" -ge 10000 ] || exit 5
                AID=${'$'}(($userId * 100000 + APPID))
                CGID=${'$'}(($userId * 100000 + APPID + 10000)) # cache gid: 20000 + (appId - 10000)
                # Recreate missing private dirs the way installd lays them out; restorecon adds the app's SELinux categories
                for BASE in /data/user/$userId /data/user_de/$userId; do
                  if [ ! -d ${'$'}BASE/$pkg ]; then
                    mkdir -m 0700 ${'$'}BASE/$pkg && chown ${'$'}AID:${'$'}AID ${'$'}BASE/$pkg
                    for C in cache code_cache; do mkdir -m 02771 ${'$'}BASE/$pkg/${'$'}C && chown ${'$'}AID:${'$'}CGID ${'$'}BASE/$pkg/${'$'}C; done
                    restorecon -RF ${'$'}BASE/$pkg
                  fi
                done
                """.trimIndent() + "\n"
            )
            // Private data goes back owned by the app; cache dirs keep their existing owner (cache gid) and are not touched
            fun data(part: Part, parent: String, group: String) {
                s.append(
                    """
                    if [ -f ${'$'}SRC/${part.fileName} ]; then
                      mkdir -p $parent/$pkg
                      find $parent/$pkg -mindepth 1 -maxdepth 1 ! -name cache ! -name code_cache -exec rm -rf {} +
                      zstd -q -dc ${'$'}SRC/${part.fileName} | tar -xpf - -C $parent --exclude=$pkg/cache --exclude=$pkg/code_cache || exit 1
                      find $parent/$pkg -mindepth 1 -maxdepth 1 ! -name cache ! -name code_cache -exec chown -R ${'$'}AID:$group {} +
                      chown ${'$'}AID:$group $parent/$pkg
                      restorecon -RF $parent/$pkg
                    fi
                    """.trimIndent() + "\n"
                )
            }
            if (Part.USER in parts) data(Part.USER, "/data/user/$userId", "\$AID")
            if (Part.USER_DE in parts) data(Part.USER_DE, "/data/user_de/$userId", "\$AID")
            if (Part.DATA in parts) data(Part.DATA, "/data/media/$userId/Android/data", "1078") // ext_data_rw
            if (Part.OBB in parts) data(Part.OBB, "/data/media/$userId/Android/obb", "1079") // ext_obb_rw
            if (Part.MEDIA in parts) data(Part.MEDIA, "/data/media/$userId/Android/media", "1023") // media_rw
            s.append(
                """
                if [ -f ${'$'}SRC/permissions.txt ]; then
                  while read P; do [ -n "${'$'}P" ] && pm grant --user $userId $pkg "${'$'}P" 2>/dev/null; done < ${'$'}SRC/permissions.txt
                fi
                if [ -f ${'$'}SRC/appops.txt ]; then
                  sed -n 's/^\([A-Z_0-9]*\): \([a-z]*\).*/\1 \2/p' ${'$'}SRC/appops.txt | while read OP MODE; do
                    appops set --user $userId $pkg ${'$'}OP ${'$'}MODE 2>/dev/null
                  done
                fi
                """.trimIndent() + "\n"
            )
    return s.toString()
        }
    }
}
