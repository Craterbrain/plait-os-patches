package app.plait.scan

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class BtDumpTest {
    /** A small invented report in the same shape as the real one (no real devices). */
    private val sample = """
Bluetooth Status
  enabled: true
  state: ON
  address: XX:XX:XX:XX:AA:01
  name: Test Phone
  time since enabled: 2h 3m

ScanMode: SCAN_MODE_CONNECTABLE
BluetoothAdapterProperties
  Name: Test Phone
  Discovering: true

BluetoothRemoteDevices
  Bonded devices: 1
    XX:XX:XX:XX:03:D3(Public ) => XX:XX:XX:XX:03:D3(Public ) [ DUAL ] [0x240418] [ACL BR/EDR:Y LE:N] [ Encryption status(BR/EDR): null LE: null] Test Headphones
        [BR/EDR UUIDs]: 0000110b-0000-1000-8000-00805f9b34fb 0000110e-0000-1000-8000-00805f9b34fb 66666666-6666-6666-6666-666666666666
        [LE UUIDs    ]: 0000180f-0000-1000-8000-00805f9b34fb
        [Packages    ]: [org.example.app]

  Other devices: 2
    XX:XX:XX:XX:90:D4(Public ) => XX:XX:XX:XX:XX:XX(Unknown) [  LE  ] [0x001F00] [ACL BR/EDR:N LE:N] [ Encryption status(BR/EDR): null LE: null] Test Printer
    XX:XX:XX:XX:41:1C(Public ) => XX:XX:XX:XX:XX:XX(Unknown) [ DUAL ] [0x001F00] [ACL BR/EDR:N LE:N] [ Encryption status(BR/EDR): null LE: null] null

Connection Events:
  11-07 19:56:30.104 DISCONNECTED xx:xx:xx:xx:03:d3 reason=19
  11-07 19:56:30.104 DISCONNECTED xx:xx:xx:xx:03:d3 reason=19
  11-07 18:38:49.419 CONNECTED    xx:xx:xx:xx:03:d3

Link Key Types:
  xx:xx:xx:xx:03:d3
    BR: UNAUTH_COMB
    LE:

LE Scanner:
  app_if: 3, appName: org.example.gms, tag: nearby_fast_pair
  app_if: 2, appName: app.plait.scan

LE Scanner Map:
  Entries: 2

  app.plait.scan (Registered):
    LE scans               (started/stopped)                                   : 1 / 0
    Scan time(ms)          (active/suspend/total)                              : 8352 / 0 / 8352
    Total number of results                                                    : 22
    Ongoing 1 scans:
      11-07 23:05:11.155 - Elapsed: 8352ms Results: 22 id: (2) CB Regular Scan

  org.example.gms:
    LE scans               (started/stopped)                                   : 163 / 160
    Scan time(ms)          (active/suspend/total)                              : 89275648 / 0 / 89275648
    Total number of results                                                    : 16563
    Last 5 scans:
      11-07 22:22:39.152 - Duration: 1573860ms Results: 0 id: (3) [nearby_fast_pair] CB Regular Scan
        └ App Importance: Foreground Service
""".trimStart()

    @Test
    fun parsesTheInventedReport() {
        val d = BtDumpParser.parse(sample)
        assertEquals(true, d.enabled)
        assertEquals("Test Phone", d.name)
        assertEquals("SCAN_MODE_CONNECTABLE", d.scanMode)
        assertEquals(true, d.discovering)
        assertEquals(1, d.paired.size)
        val h = d.paired[0]
        assertEquals("Test Headphones", h.name)
        assertEquals("03:d3", h.tail)
        assertEquals(0x240418, h.deviceClass)
        assertTrue(h.connectedClassic)
        assertEquals(listOf(0x110b, 0x110e, 0x180f), h.uuids)
        assertEquals(listOf("org.example.app"), h.packages)
        assertEquals("UNAUTH_COMB", h.linkKeyClassic)
        assertEquals(2, d.others.size)
        assertEquals(null, d.others[1].name) // "null" is not a name
        assertEquals(mapOf("03:d3" to "Test Headphones", "90:d4" to "Test Printer"), d.nameHints())
        assertEquals(2, d.events.size) // the repeated line collapses
        assertEquals(false, d.events[0].connected)
        assertEquals(2, d.scanners.size)
        val mine = d.scanners.first { it.pkg == "app.plait.scan" }
        assertEquals(1, mine.ongoing)
        assertEquals(22L, mine.results)
        assertEquals(listOf("nearby_fast_pair"), d.scanners.first { it.pkg == "org.example.gms" }.tags)
        assertEquals("Foreground Service", d.scanners.first { it.pkg == "org.example.gms" }.importance)
    }

    /** Runs against a real report when BT_DUMP names a file saved from the phone (never committed). */
    @Test
    fun parsesARealReport() {
        val path = System.getenv("BT_DUMP")
        assumeTrue(path != null)
        var text = File(path!!).readText()
        val i = text.indexOf("out=")
        val j = text.lastIndexOf(", exit=")
        if (i >= 0 && j > i) text = text.substring(i + 4, j)
        val d = BtDumpParser.parse(text)
        println("adapter: ${d.name} ${d.state} up ${d.uptime} scanMode=${d.scanMode} discovering=${d.discovering} profiles=${d.profiles.size}")
        d.paired.forEach { println("paired: ${it.name} ${it.type} class=0x%06x conn=${it.connectedClassic}/${it.connectedLe} uuids=${it.uuids.size} pkgs=${it.packages} key=${it.linkKeyClassic}/${it.linkKeyLe}".format(it.deviceClass)) }
        println("others: ${d.others.size}, named: ${d.others.count { it.name != null }}")
        d.scanners.forEach { println("scanner: ${it.pkg} tags=${it.tags} started=${it.started} active=${it.activeMs}ms results=${it.results} ongoing=${it.ongoing} ${it.importance}") }
        d.events.take(5).forEach { println("event: ${it.time} ${if (it.connected) "up" else "down"} ${it.tail} ${it.reason}") }
        println("hints: ${d.nameHints().size}")
        assertNotNull(d.state)
        assertTrue(d.paired.isNotEmpty())
        assertTrue(d.scanners.isNotEmpty())
    }
}
