package app.plait.backup

import java.util.concurrent.TimeUnit

/** Runs a script as root through Magisk's su (dev backend only; PlaitOS has no su). */
object RootShell {
    class Result(val code: Int, val out: String, val err: String) {
        val ok get() = code == 0
    }

    fun run(script: String, timeoutMinutes: Long = 30): Result = runFor(script, timeoutMinutes * 60_000)

    private fun runFor(script: String, timeoutMs: Long): Result = try {
        val p = ProcessBuilder("su").start()
        // The script goes in on stdin, so nothing in it is ever re-parsed by su's own argument handling
        p.outputStream.bufferedWriter().use { it.write(script); it.write("\nexit \$?\n") }
        // Both pipes are read on their own threads, so the timeout applies even when su never answers
        var out = ""
        var err = ""
        val readers = listOf(
            Thread { out = p.inputStream.bufferedReader().readText() },
            Thread { err = p.errorStream.bufferedReader().readText() },
        ).onEach { it.start() }
        if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
            p.destroyForcibly()
            Result(-2, out, "timed out")
        } else {
            readers.forEach { it.join() }
            Result(p.exitValue(), out, err)
        }
    } catch (e: Exception) {
        Result(-1, "", e.message ?: e.toString())
    }

    /**
     * Streams a root-only file into [consume]. [path] must be one of ours (fixed dirs, validated names): it is put
     * in a shell command.
     */
    fun <T> readFile(path: String, consume: (java.io.InputStream) -> T): T {
        val p = ProcessBuilder("su", "-c", "cat '$path' 2>/dev/null").start()
        p.outputStream.close()
        try {
            val result = p.inputStream.use(consume)
            check(p.waitFor() == 0) { "could not read $path" }
            return result
        } finally {
            p.destroy()
        }
    }

    /** Streams [produce]'s output into a file as root (created 0600). Same rule for [path] as [readFile]. */
    fun writeFile(path: String, produce: (java.io.OutputStream) -> Unit) {
        val p = ProcessBuilder("su", "-c", "umask 077; cat > '$path' 2>/dev/null").start()
        try {
            p.outputStream.use(produce)
            check(p.waitFor() == 0) { "could not write $path" }
        } finally {
            p.destroy()
        }
    }

    /** Null if root works, else what went wrong (su missing, denied, or not answered). */
    fun problem(): String? {
        val r = runFor("id -u", 15_000)
        return when {
            r.ok && r.out.trim() == "0" -> null
            // Magisk 31.1+ has no prompt: su waits until the app is switched on in the Superuser tab
            r.code == -2 -> "No answer from Magisk. Magisk 31.1 and later never ask: open Magisk > Superuser, " +
                "switch on Plait Backup, then tap Try root again."
            r.code == -1 && r.err.contains("No such file", ignoreCase = true) ->
                "su not found: Magisk's su is not visible to this app"
            r.code == -1 -> "su could not run: ${r.err}"
            else -> "Root denied by Magisk (exit ${r.code}${r.err.trim().takeIf { it.isNotEmpty() }?.let { ": $it" } ?: ""}). " +
                "Check Magisk > Superuser > Plait Backup."
        }
    }
}
