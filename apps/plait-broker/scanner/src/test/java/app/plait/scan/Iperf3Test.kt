package app.plait.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Runs against a real iperf3 server when IPERF3_PORT names one on 127.0.0.1 (start it with `iperf3 -s -p PORT -B 127.0.0.1`). */
class Iperf3Test {
    private fun port(): Int {
        val p = System.getenv("IPERF3_PORT")
        assumeTrue("set IPERF3_PORT to run", p != null)
        return p!!.toInt()
    }

    @Test fun upload() {
        val seen = ArrayList<Iperf3.Interval>()
        val s = Iperf3("127.0.0.1", port(), 3, 1, false).run({ seen += it })
        println("upload: ${"%.0f".format(s.senderMbps)} Mbps sent, ${"%.0f".format(s.receiverMbps)} Mbps received, ${s.senderBytes} / ${s.receiverBytes} bytes, ${seen.size} intervals")
        assertEquals(3, seen.size); assertTrue(s.upload)
        assertTrue("no data sent", s.senderBytes > 1_000_000)
        // the server counted nearly everything we wrote (a little may still be in flight when the test ends)
        assertTrue("server saw ${s.receiverBytes} of ${s.senderBytes}", s.receiverBytes > s.senderBytes * 0.9 && s.receiverBytes <= s.senderBytes)
    }

    @Test fun download() {
        val s = Iperf3("127.0.0.1", port(), 3, 1, true).run()
        println("download: ${"%.0f".format(s.receiverMbps)} Mbps received, ${s.senderBytes} sent by the server, retransmits ${s.retransmits}")
        assertTrue(!s.upload); assertTrue(s.receiverBytes > 1_000_000)
        assertTrue("received ${s.receiverBytes} of ${s.senderBytes}", s.receiverBytes > s.senderBytes * 0.9)
    }

    @Test fun parallelStreams() {
        val s = Iperf3("127.0.0.1", port(), 2, 4, false).run()
        println("4 streams up: ${"%.0f".format(s.receiverMbps)} Mbps")
        assertEquals(4, s.streams); assertTrue(s.receiverBytes > 1_000_000)
        val d = Iperf3("127.0.0.1", port(), 2, 3, true).run()
        println("3 streams down: ${"%.0f".format(d.receiverMbps)} Mbps")
        assertTrue(d.receiverBytes > 1_000_000)
    }

    @Test fun refusedOrWrongServer() {
        val e = runCatching { Iperf3("127.0.0.1", 1, 2).run() }.exceptionOrNull()
        assertTrue(e is Iperf3.Iperf3Exception)
    }
}
