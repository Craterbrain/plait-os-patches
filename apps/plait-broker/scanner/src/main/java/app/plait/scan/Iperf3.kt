package app.plait.scan

import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * A client for iperf3's TCP test (what `iperf3 -c host [-R] [-P n] [-t secs]` does), written from the protocol so it talks to
 * any stock iperf3 server. One control connection carries one-byte state changes and two small JSON documents; the data
 * flows over separate connections, one per stream, each opened with the same 37-byte cookie.
 */
class Iperf3(
    private val host: String,
    private val port: Int = 5201,
    private val seconds: Int = 10,
    private val streams: Int = 1,
    private val reverse: Boolean = false,
    private val blockSize: Int = 128 * 1024,
) {
    data class Interval(val second: Int, val mbps: Double)

    data class Summary(
        val upload: Boolean, val seconds: Double, val senderBytes: Long, val receiverBytes: Long, val senderMbps: Double, val receiverMbps: Double,
        val retransmits: Long?, val streams: Int, val intervals: List<Interval>,
    )

    class Iperf3Exception(msg: String) : IOException(msg)

    private companion object {
        const val TEST_START = 1; const val TEST_RUNNING = 2; const val TEST_END = 4; const val PARAM_EXCHANGE = 9; const val CREATE_STREAMS = 10
        const val SERVER_TERMINATE = 11; const val EXCHANGE_RESULTS = 13; const val DISPLAY_RESULTS = 14; const val IPERF_DONE = 16
        const val ACCESS_DENIED = -1; const val SERVER_ERROR = -2
        const val COOKIE_SIZE = 37
    }

    private val cancelled = AtomicBoolean(false)
    private val sockets = java.util.Collections.synchronizedList(ArrayList<Socket>())

    fun cancel() {
        cancelled.set(true)
        synchronized(sockets) { sockets.forEach { runCatching { it.close() } } }
    }

    /** The ids iperf3 gives streams: 1, then 3, 4, 5… (2 is skipped for compatibility with old versions). */
    private fun streamIds() = (0 until streams).map { if (it == 0) 1 else it + 2 }

    private fun connect(): Socket {
        val s = Socket()
        sockets += s
        s.connect(InetSocketAddress(host, port), 5000)
        s.tcpNoDelay = true
        return s
    }

    private fun writeJson(out: java.io.OutputStream, j: JSONObject) {
        val b = j.toString().toByteArray(Charsets.UTF_8)
        out.write(byteArrayOf((b.size ushr 24).toByte(), (b.size ushr 16).toByte(), (b.size ushr 8).toByte(), b.size.toByte()))
        out.write(b); out.flush()
    }

    private fun readJson(inp: DataInputStream): JSONObject {
        val len = inp.readInt()
        if (len <= 0 || len > 1_000_000) throw Iperf3Exception("the server sent a results block of $len bytes")
        val b = ByteArray(len); inp.readFully(b)
        return JSONObject(String(b, Charsets.UTF_8))
    }

    /** Runs the test; [onInterval] gets the speed of each second as it passes. Blocks until the test ends. */
    fun run(onInterval: (Interval) -> Unit = {}, onPhase: (String) -> Unit = {}): Summary {
        val cookie = ByteArray(COOKIE_SIZE).also { c ->
            val alphabet = "abcdefghijklmnopqrstuvwxyz234567"
            val r = SecureRandom()
            for (i in 0 until COOKIE_SIZE - 1) c[i] = alphabet[r.nextInt(alphabet.length)].code.toByte()
        }
        val data = ArrayList<Socket>()
        val counters = List(streams) { AtomicLong() }
        val stop = AtomicBoolean(false)
        val intervals = ArrayList<Interval>()
        var started = 0L
        var elapsed = 0.0
        var timer: Thread? = null
        var serverJson: JSONObject? = null
        try {
            onPhase("Connecting to $host:$port…")
            val ctrl = connect()
            ctrl.soTimeout = 15_000
            val out = ctrl.getOutputStream()
            val inp = DataInputStream(ctrl.getInputStream())
            out.write(cookie); out.flush()
            while (true) {
                val state = try { inp.readByte().toInt() } catch (e: SocketTimeoutException) { throw Iperf3Exception("the server stopped answering") }
                when (state) {
                    PARAM_EXCHANGE -> {
                        onPhase("Agreeing on the test…")
                        val p = JSONObject().put("tcp", true).put("omit", 0).put("time", seconds).put("parallel", streams).put("len", blockSize).put("client_version", "3.22")
                        if (reverse) p.put("reverse", true)
                        writeJson(out, p)
                    }
                    CREATE_STREAMS -> {
                        for (i in 0 until streams) {
                            val s = connect()
                            s.getOutputStream().apply { write(cookie); flush() }
                            data += s
                        }
                    }
                    TEST_START -> {}
                    TEST_RUNNING -> {
                        onPhase(if (reverse) "Downloading…" else "Uploading…")
                        started = System.nanoTime()
                        // one worker per stream: send as fast as the connection allows, or read as fast as it delivers
                        val payload = ByteArray(blockSize).also { SecureRandom().nextBytes(it) }
                        data.forEachIndexed { i, s ->
                            Thread {
                                try {
                                    if (reverse) {
                                        s.soTimeout = 500
                                        val buf = ByteArray(blockSize)
                                        val ins = s.getInputStream()
                                        while (!stop.get()) {
                                            try { val n = ins.read(buf); if (n < 0) break; counters[i].addAndGet(n.toLong()) } catch (e: SocketTimeoutException) { }
                                        }
                                    } else {
                                        val os = s.getOutputStream()
                                        while (!stop.get()) { os.write(payload); counters[i].addAndGet(payload.size.toLong()) }
                                    }
                                } catch (e: IOException) { }
                            }.also { it.isDaemon = true }.start()
                        }
                        // the clock: one reading per second, then stop and tell the server
                        timer = Thread {
                            try {
                                var last = 0L
                                for (sec in 1..seconds) {
                                    val target = started + sec * 1_000_000_000L
                                    while (System.nanoTime() < target) Thread.sleep(maxOf(1L, minOf(50L, (target - System.nanoTime()) / 1_000_000)))
                                    val total = counters.sumOf { it.get() }
                                    val iv = Interval(sec, (total - last) * 8.0 / 1_000_000.0)
                                    last = total
                                    synchronized(intervals) { intervals += iv }
                                    onInterval(iv)
                                }
                                elapsed = (System.nanoTime() - started) / 1e9
                                stop.set(true)
                                out.write(byteArrayOf(TEST_END.toByte())); out.flush()
                            } catch (e: Exception) { stop.set(true) }
                        }.also { it.isDaemon = true; it.start() }
                    }
                    EXCHANGE_RESULTS -> {
                        onPhase("Collecting results…")
                        timer?.join(2000)
                        val ids = streamIds()
                        val arr = JSONArray()
                        ids.forEachIndexed { i, id ->
                            arr.put(JSONObject().put("id", id).put("bytes", counters[i].get()).put("retransmits", -1).put("jitter", 0).put("errors", 0)
                                .put("omitted_errors", 0).put("packets", 0).put("omitted_packets", 0).put("start_time", 0).put("end_time", elapsed))
                        }
                        writeJson(out, JSONObject().put("cpu_util_total", 0.0).put("cpu_util_user", 0.0).put("cpu_util_system", 0.0).put("sender_has_retransmits", -1).put("streams", arr))
                        serverJson = readJson(inp)
                    }
                    DISPLAY_RESULTS -> { out.write(byteArrayOf(IPERF_DONE.toByte())); out.flush(); break }
                    ACCESS_DENIED -> throw Iperf3Exception("the server refused the test (it may be busy with another one)")
                    SERVER_ERROR -> throw Iperf3Exception("the server reported an error")
                    SERVER_TERMINATE -> throw Iperf3Exception("the server ended the test")
                    else -> throw Iperf3Exception("unexpected state $state from the server: is this an iperf3 server?")
                }
            }
        } catch (e: IOException) {
            if (cancelled.get()) throw Iperf3Exception("stopped")
            throw if (e is Iperf3Exception) e else Iperf3Exception(e.message ?: e.javaClass.simpleName)
        } finally {
            stop.set(true)
            synchronized(sockets) { sockets.forEach { runCatching { it.close() } } }
        }

        val mine = counters.sumOf { it.get() }
        val theirs = serverJson?.optJSONArray("streams")?.let { a -> (0 until a.length()).sumOf { a.getJSONObject(it).optLong("bytes") } } ?: 0L
        val retrans = serverJson?.optJSONArray("streams")?.let { a -> (0 until a.length()).sumOf { a.getJSONObject(it).optLong("retransmits", -1).coerceAtLeast(0) } }
        val secs = elapsed.coerceAtLeast(0.001)
        // the sender's own count is what it wrote; the receiver's is what actually arrived
        val senderBytes = if (reverse) theirs else mine
        val receiverBytes = if (reverse) mine else theirs
        return Summary(!reverse, secs, senderBytes, receiverBytes, senderBytes * 8 / 1e6 / secs, receiverBytes * 8 / 1e6 / secs, if (reverse) retrans else null, streams, intervals.toList())
    }
}
