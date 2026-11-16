package app.plait.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionsTest {
    @Test fun addresses() {
        assertEquals("192.168.1.175", ConnParse.ip4("AF01A8C0"))
        assertEquals("127.0.0.1", ConnParse.ip4("0100007F"))
        assertEquals("172.217.113.4", ConnParse.ip4("0471D9AC"))
        assertEquals("::", ConnParse.ip6("00000000000000000000000000000000"))
        assertEquals("127.0.0.1", ConnParse.ip6("0000000000000000FFFF00000100007F"))       // IPv4 carried in IPv6
        assertEquals("::1", ConnParse.ip6("00000000000000000000000001000000"))
        assertEquals("2606:4700:4700::1111", ConnParse.ip6(fake6()))
    }

    /** 2606:4700:4700::1111 as the kernel prints it: each 32-bit word with its bytes reversed. */
    private fun fake6(): String = listOf("26064700", "47000000", "00000000", "00001111").joinToString("") { it.chunked(2).reversed().joinToString("") }

    private val tcp = """
  sl  local_address                         remote_address                        st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
   0: 00000000000000000000000000000000:9395 00000000000000000000000000000000:0000 0A 00000000:00000000 00:00000000 00000000  2000        0 2071954 1 0000000000000000 100 0 0 10 0
   1: 0000000000000000FFFF00000100007F:9EFD 00000000000000000000000000000000:0000 0A 00000000:00000000 00:00000000 00000000 10485        0 1299684 1 0000000000000000 100 0 0 10 0
   2: 0000000000000000FFFF0000AF01A8C0:D2B0 0000000000000000FFFF00000471D9AC:01BB 01 00000000:00000000 02:000A2C30 00000000 10388        0 6124000 2 0000000000000000 20 4 29 10 -1
"""
    private val udp = """
  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode ref pointer drops            
  172: AF01A8C0:DD18 0471D9AC:01BB 01 00000000:00000000 00:00000000 00000000 10388        0 6124666 2 0000000000000000 1       
  984: AF01A8C0:0044 00000000:0000 07 00000000:00000000 00:00000000 00000000     0        0 41234 2 0000000000000000 0
"""

    @Test fun tcpAndUdpTables() {
        val t = ConnParse.table(tcp, "TCP", true)
        assertEquals(3, t.size)
        assertEquals("LISTEN", t[0].state); assertEquals(2000, t[0].uid); assertEquals(0x9395, t[0].localPort); assertTrue(t[0].listening)
        assertEquals("127.0.0.1", t[1].localIp); assertEquals(10485, t[1].uid)
        assertEquals("ESTABLISHED", t[2].state); assertEquals("192.168.1.175", t[2].localIp); assertEquals("172.217.113.4", t[2].remoteIp); assertEquals(443, t[2].remotePort); assertEquals(10388, t[2].uid)
        val u = ConnParse.table(udp, "UDP", false)
        assertEquals(2, u.size)
        assertEquals("CONNECTED", u[0].state); assertEquals("172.217.113.4", u[0].remoteIp); assertEquals(443, u[0].remotePort); assertEquals(10388, u[0].uid)
        assertFalse(u[0].listening)
        assertEquals("UNCONNECTED", u[1].state); assertTrue(u[1].listening); assertEquals(68, u[1].localPort)
    }

    @Test fun keysAreStableAndFinishedSocketsAreFlagged() {
        val c = Conn("TCP", false, "10.0.0.2", 5000, "1.1.1.1", 443, "TIME_WAIT", 0)
        assertEquals("TCP 10.0.0.2:5000 1.1.1.1:443 0", c.key); assertTrue(c.finished)
        assertTrue(Conn("TCP", false, "127.0.0.1", 1, "127.0.0.1", 2, "ESTABLISHED", 1).loopback)
    }

    @Test fun packageOwners() {
        val m = ConnParse.packagesByUid("package:com.example.a uid:10081\npackage:com.example.b uid:10081\npackage:com.android.shell uid:2000\n")
        assertEquals(listOf("com.example.a", "com.example.b"), m[10081]); assertEquals(listOf("com.android.shell"), m[2000])
    }
}
