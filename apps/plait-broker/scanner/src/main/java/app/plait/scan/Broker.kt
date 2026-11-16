package app.plait.scan

import rikka.shizuku.Shizuku

/** Plait Broker through the plain Shizuku API: nothing here runs with privileges of its own. */
object Broker {
    data class Result(val exit: Int, val out: String, val err: String) {
        val ok get() = exit == 0
    }

    enum class State { NOT_RUNNING, NO_ACCESS, READY }

    fun running(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    fun allowed(): Boolean = running() && runCatching { Shizuku.checkSelfPermission() == 0 }.getOrDefault(false)

    fun state(): State = when {
        !running() -> State.NOT_RUNNING
        !allowed() -> State.NO_ACCESS
        else -> State.READY
    }

    fun requestAccess() {
        if (running()) Shizuku.requestPermission(REQUEST_CODE)
    }

    /** Runs [cmd] through the broker. */
    fun run(vararg cmd: String): Result {
        // newProcess is not public in the Shizuku API; most Shizuku apps call it this way
        val m = Shizuku::class.java.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java)
        m.isAccessible = true
        val p = m.invoke(null, arrayOf(*cmd), null, null) as Process
        p.outputStream.close()
        val out = p.inputStream.bufferedReader().readText()
        val err = p.errorStream.bufferedReader().readText()
        return Result(p.waitFor(), out.trim(), err.trim())
    }

    private const val REQUEST_CODE = 43
}
