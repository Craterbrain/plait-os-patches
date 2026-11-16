package app.plait.scan

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ToolsCoreTest {
    // ---- ping
    @Test fun pingLines() {
        val r = PingParse.line("64 bytes from 1.1.1.1: icmp_seq=3 ttl=56 time=17.3 ms") as PingEvent.Reply
        assertEquals(3, r.seq); assertEquals(56, r.ttl); assertEquals(17.3, r.ms, 0.001); assertEquals("1.1.1.1", r.from)
        val v6 = PingParse.line("64 bytes from 2606:4700:4700::1111: icmp_seq=1 ttl=57 time=21.5 ms") as PingEvent.Reply
        assertEquals("2606:4700:4700::1111", v6.from)
        assertEquals(PingEvent.Missed(4), PingParse.line("no answer yet for icmp_seq=4"))
        assertNotNull(PingParse.line("ping: unknown host nope.invalid"))
        assertNull(PingParse.line("--- 1.1.1.1 ping statistics ---"))
    }

    @Test fun pingStats() {
        val s = PingStats()
        s.reply(10.0); s.reply(20.0); s.missed(); s.reply(15.0)
        assertEquals(4, s.sent); assertEquals(3, s.received)
        assertEquals(25.0, s.lossPercent, 0.001)
        assertEquals(10.0, s.min!!, 0.0); assertEquals(20.0, s.max!!, 0.0); assertEquals(15.0, s.avg!!, 0.001)
        assertEquals(7.5, s.jitter!!, 0.001) // |20-10|=10, |15-20|=5
    }

    // ---- path MTU against a pretend network
    private fun fake(pathMtu: Int, silentAbove: Boolean = false, overhead: Int = 28): suspend (List<Int>) -> List<Mtu.Probe> = { sizes ->
        sizes.map { payload ->
            when {
                payload + overhead <= pathMtu -> Mtu.Probe.Ok
                silentAbove -> Mtu.Probe.Silent                     // a black hole that drops big packets without saying so
                else -> Mtu.Probe.TooBig(pathMtu)                   // a device that reports its MTU
            }
        }
    }

    @Test fun mtuFindsOrdinaryAndOddPaths() = runBlocking {
        for (mtu in listOf(1500, 1492, 1480, 1400, 1280, 576, 9000, 4470, 1301)) {
            val withHint = Mtu.discover(false, fake(mtu))
            assertEquals("hint, mtu $mtu", mtu, withHint.mtu)
            val blackHole = Mtu.discover(false, fake(mtu, silentAbove = true))
            assertEquals("black hole, mtu $mtu", mtu, blackHole.mtu)
        }
    }

    @Test fun mtuV6() = runBlocking {
        assertEquals(1500, Mtu.discover(true, fake(1500, overhead = 48)).mtu)
        assertEquals(1280, Mtu.discover(true, fake(1280, silentAbove = true, overhead = 48)).mtu)
    }

    @Test fun mtuNoAnswer() = runBlocking {
        val r = Mtu.discover(false, { sizes -> sizes.map { Mtu.Probe.Silent } })
        assertNull(r.mtu)
    }

    @Test fun mtuClassify() {
        assertEquals(Mtu.Probe.TooBig(1500), Mtu.classify("ping: local error: Message too long, mtu=1500"))
        assertEquals(Mtu.Probe.TooBig(1400), Mtu.classify("From 10.0.0.1 icmp_seq=1 Frag needed and DF set (mtu = 1400)"))
        assertEquals(Mtu.Probe.Ok, Mtu.classify("1480 bytes from 1.1.1.1: icmp_seq=1 ttl=56 time=16.8 ms"))
        assertEquals(Mtu.Probe.Silent, Mtu.classify("PING 1.1.1.1 (1.1.1.1) 1600(1628) bytes of data."))
    }

    // ---- Wake on LAN
    @Test fun wol() {
        val mac = Wol.parseMac("AA-bb-cc-01-02-03")!!
        assertArrayEquals(byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 1, 2, 3), mac)
        assertEquals("aa:bb:cc:01:02:03", Wol.format(mac))
        assertNotNull(Wol.parseMac("aabb.cc01.0203")); assertNotNull(Wol.parseMac("aabbcc010203"))
        assertNull(Wol.parseMac("aa:bb:cc")); assertNull(Wol.parseMac("gg:bb:cc:01:02:03"))
        val p = Wol.magicPacket(mac)
        assertEquals(102, p.size)
        assertEquals(List(6) { 0xFF.toByte() }, p.take(6))
        assertArrayEquals(mac, p.copyOfRange(96, 102))
    }

    // ---- Wi-Fi table
    @Test fun wifiTable() {
        val text = """
    BSSID              Frequency      RSSI           Age(sec)     SSID                                 Flags
  9e:05:d6:bb:d1:b5       5180        -61            >1000.0    Test Net 5G                       [RSN-SAE-CCMP-128][ESS][MFPR][MFPC]
  9c:05:d6:fb:d1:b5       2462        -51            2.5    Home Wifi                         [WPA2-PSK-CCMP-128][RSN-PSK-CCMP-128][ESS]
  9e:05:d6:ab:d1:b5       5180        -60            >1000.0                                      [WPA2-PSK-CCMP-128][RSN-PSK-CCMP-128][ESS]
  84:a3:29:44:3e:bd       2437        -86            >1000.0    Open Cafe                          [ESS]
""".trimStart('\n')
        val aps = WifiParse.scanResults(text)
        assertEquals(4, aps.size)
        assertEquals("Test Net 5G", aps[0].ssid); assertEquals(36, aps[0].channel); assertEquals("5 GHz", aps[0].band); assertEquals("WPA3", aps[0].security)
        assertEquals("Home Wifi", aps[1].ssid); assertEquals(11, aps[1].channel); assertEquals("2.4 GHz", aps[1].band); assertEquals("WPA2", aps[1].security); assertEquals(2.5, aps[1].ageSec!!, 0.0)
        assertEquals(true, aps[2].hidden); assertEquals("WPA2", aps[2].security)
        assertEquals("Open", aps[3].security); assertEquals(6, aps[3].channel)
        assertEquals(14, WifiAp.channelOf(2484)); assertEquals(149, WifiAp.channelOf(5745)); assertEquals(1, WifiAp.channelOf(5955))
    }

    @Test fun wifiLink() {
        val text = "Wifi is enabled\nWifi is connected to \"Test Net \"\nWifiInfo: SSID: \"Test Net \", BSSID: 9e:05:d6:8b:d1:b5, MAC: 82:40:ba:e0:c5:60, IP: /192.168.1.175, Security type: 2, Supplicant state: COMPLETED, Wi-Fi standard: 11ax, RSSI: -64, Link speed: 544Mbps, Tx Link speed: 544Mbps, Frequency: 5180MHz, Net ID: 3"
        val l = WifiParse.link(text)!!
        assertEquals("Test Net", l.ssid); assertEquals("9e:05:d6:8b:d1:b5", l.bssid); assertEquals(-64, l.rssi); assertEquals(544, l.linkMbps); assertEquals(5180, l.freq); assertEquals("11ax", l.standard)
        assertNull(WifiParse.link("Wifi is disabled"))
    }

    // ---- CIDR
    @Test fun cidrV4() {
        val n = Cidr.parseV4("192.168.1.77/24")!!
        assertEquals("192.168.1.0", n.network); assertEquals("192.168.1.255", n.broadcast)
        assertEquals("192.168.1.1", n.first); assertEquals("192.168.1.254", n.last)
        assertEquals(254L, n.hosts); assertEquals(256L, n.total); assertEquals("255.255.255.0", n.mask); assertEquals("0.0.0.255", n.wildcard)
        assertEquals("C", n.kind); assertEquals("Private (RFC 1918)", n.scope)
        assertEquals("11000000.10101000.00000001.01001101", n.binaryAddress)
        val a = Cidr.parseV4("10.1.2.3 255.255.0.0")!!
        assertEquals("10.1.0.0", a.network); assertEquals(16, a.prefix); assertEquals(65534L, a.hosts)
        assertEquals(16, Cidr.parseV4("10.1.2.3/0.0.255.255")!!.prefix)       // wildcard form
        assertEquals(2L, Cidr.parseV4("10.0.0.0/31")!!.hosts)
        assertEquals(1L, Cidr.parseV4("10.0.0.5/32")!!.hosts)
        assertEquals(4294967296L, Cidr.parseV4("0.0.0.0/0")!!.total)
        assertNull(Cidr.parseV4("300.1.1.1/24")); assertNull(Cidr.parseV4("10.0.0.0/33")); assertNull(Cidr.parseV4("10.0.0.0 255.0.255.0"))
        assertEquals("Carrier-grade NAT (RFC 6598)", Cidr.parseV4("100.64.1.1/10")!!.scope)
        assertEquals(listOf("10.0.0.0/26", "10.0.0.64/26", "10.0.0.128/26", "10.0.0.192/26"), Cidr.split(Cidr.parseV4("10.0.0.0/24")!!, 26))
    }

    @Test fun cidrV6() {
        val n = Cidr.parseV6("2001:db8:abcd:12::1/64")!!
        assertEquals("2001:db8:abcd:12::", n.network); assertEquals("2001:db8:abcd:12:ffff:ffff:ffff:ffff", n.last)
        assertEquals("18446744073709551616", n.total); assertEquals("Global unicast", n.kind)
        assertEquals("::1", Cidr.parseV6("::1")!!.address)
        assertEquals("fe80::1", Cidr.parseV6("fe80:0:0:0:0:0:0:1")!!.address); assertEquals("Link-local", Cidr.parseV6("fe80::1/10")!!.kind)
        assertEquals("fc00::", Cidr.parseV6("fd12:3456::/7")!!.network); assertEquals("Unique local (private)", Cidr.parseV6("fd12::1")!!.kind)
        assertNotNull(Cidr.parseV6("::ffff:192.0.2.1")); assertNull(Cidr.parseV6("2001:::1")); assertNull(Cidr.parseV6("1:2:3:4:5:6:7:8:9"))
    }
}
