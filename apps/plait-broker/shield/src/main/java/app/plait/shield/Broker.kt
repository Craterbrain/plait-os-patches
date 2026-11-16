package app.plait.shield

import java.io.OutputStream
import rikka.shizuku.Shizuku

/** Plait Broker through the plain Shizuku API: nothing here runs with privileges of its own. */
object Broker {
    data class Result(val exit: Int, val out: String, val err: String) {
        val ok get() = exit == 0
    }

    fun running(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun allowed(): Boolean = running() && runCatching { Shizuku.checkSelfPermission() == 0 }.getOrDefault(false)

    fun requestAccess() {
        if (running()) Shizuku.requestPermission(REQUEST_CODE)
    }

    /** Runs [cmd] through the broker; [stdin] (if any) writes the command's input and must not close it. */
    fun run(vararg cmd: String, stdin: ((OutputStream) -> Unit)? = null): Result {
        // newProcess is not public in the Shizuku API; most Shizuku apps call it this way
        val m = Shizuku::class.java.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
        m.isAccessible = true
        val p = m.invoke(null, arrayOf(*cmd), null, null) as Process
        if (stdin != null) p.outputStream.use { stdin(it) } else p.outputStream.close()
        val out = p.inputStream.bufferedReader().readText()
        val err = p.errorStream.bufferedReader().readText()
        return Result(p.waitFor(), out.trim(), err.trim())
    }

    private const val REQUEST_CODE = 42
}

/** Android's Private DNS: off, automatic (opportunistic), or a provider's hostname (strict). */
data class PrivateDns(val mode: String, val host: String?) {
    val description: String
        get() = when (mode) {
            "hostname" -> PROVIDERS.firstOrNull { it.host == host }?.let { "${it.name} (${it.host})" } ?: (host ?: "?")
            "off" -> "Off: your network's DNS"
            else -> "Automatic: encrypted when your network's DNS supports it"
        }

    companion object {
        fun read(): PrivateDns? {
            val mode = Broker.run("settings", "get", "global", "private_dns_mode")
            if (!mode.ok) return null
            val host = Broker.run("settings", "get", "global", "private_dns_specifier").out.takeIf { it.isNotEmpty() && it != "null" }
            return PrivateDns(mode.out.ifEmpty { "opportunistic" }.let { if (it == "null") "opportunistic" else it }, host)
        }

        fun set(mode: String, host: String?): Broker.Result {
            if (host != null) {
                val r = Broker.run("settings", "put", "global", "private_dns_specifier", host)
                if (!r.ok) return r
            }
            return Broker.run("settings", "put", "global", "private_dns_mode", mode)
        }
    }
}
