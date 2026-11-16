package app.plait.scan

import java.io.ByteArrayOutputStream
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeNoException
import org.junit.Test

class LookupToolsTest {
    // ---- DNS, offline: a response built by hand, with name compression
    private fun response(): ByteArray {
        val o = ByteArrayOutputStream()
        fun u16(v: Int) { o.write(v shr 8 and 0xff); o.write(v and 0xff) }
        fun u32(v: Long) { u16((v shr 16).toInt()); u16((v and 0xffff).toInt()) }
        fun name(n: String) { n.split('.').forEach { o.write(it.length); o.write(it.toByteArray()) }; o.write(0) }
        u16(0x1234); u16(0x8180); u16(1); u16(4); u16(0); u16(1)          // header: response, RD+RA, 1 question, 4 answers, 1 additional (EDNS)
        name("example.com"); u16(1); u16(1)                                // question at offset 12
        // answer 1: pointer to the question name, A
        o.write(0xC0); o.write(12); u16(1); u16(1); u32(300); u16(4); o.write(byteArrayOf(93.toByte(), 184.toByte(), 216.toByte(), 34))
        // answer 2: AAAA 2606:2800:220:1:248:1893:25c8:1946
        o.write(0xC0); o.write(12); u16(28); u16(1); u32(300); u16(16)
        listOf(0x2606, 0x2800, 0x0220, 0x0001, 0x0248, 0x1893, 0x25c8, 0x1946).forEach { u16(it) }
        // answer 3: MX 10 mail.example.com (name inside rdata uses a pointer for the example.com part)
        o.write(0xC0); o.write(12); u16(15); u16(1); u32(60); u16(2 + 1 + 4 + 2); u16(10); o.write(4); o.write("mail".toByteArray()); o.write(0xC0); o.write(12)
        // answer 4: TXT with two strings
        o.write(0xC0); o.write(12); u16(16); u16(1); u32(60); u16(1 + 5 + 1 + 3); o.write(5); o.write("v=spf".toByteArray()); o.write(3); o.write("abc".toByteArray())
        // additional: EDNS OPT (must not be listed as a record)
        o.write(0); u16(41); u16(1232); u32(0); u16(0)
        return o.toByteArray()
    }

    @Test fun parsesCompressedResponse() {
        val a = Dns.parse(response(), "test", 12, false)
        assertEquals("NOERROR", a.rcodeName)
        assertTrue("recursion available" in a.flags)
        assertEquals(4, a.answers.size)
        assertEquals(listOf("93.184.216.34", "2606:2800:220:1:248:1893:25c8:1946", "10 mail.example.com", "\"v=spf\" \"abc\""), a.answers.map { it.data })
        assertEquals(listOf("A", "AAAA", "MX", "TXT"), a.answers.map { it.typeName })
        assertEquals(300L, a.answers[0].ttl)
        assertEquals("example.com", a.answers[0].name)
        assertEquals(0, a.additional.size)
    }

    @Test fun reverseNames() {
        assertEquals("1.1.1.1.in-addr.arpa", Dns.reverseName("1.1.1.1"))
        assertEquals("4.3.2.1.in-addr.arpa", Dns.reverseName("1.2.3.4"))
        assertEquals("1.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.0.8.b.d.0.1.0.0.2.ip6.arpa", Dns.reverseName("2001:db8::1"))
        assertNull(Dns.reverseName("not an ip"))
    }

    // ---- DNS, live (skipped when there is no network)
    private fun <T> live(block: () -> T): T = try { block() } catch (e: Exception) { assumeNoException(e); throw e }

    @Test fun liveDns() {
        val a = live { Dns.query("example.com", 1, "1.1.1.1") }
        assertEquals("NOERROR", a.rcodeName); assertTrue(a.answers.any { it.typeName == "A" })
        val mx = live { Dns.query("gmail.com", 15, "1.1.1.1") }
        assertTrue(mx.answers.any { it.typeName == "MX" && it.data.contains("google") })
        val ptr = live { Dns.query(Dns.reverseName("1.1.1.1")!!, 12, "1.1.1.1") }
        assertTrue(ptr.answers.any { it.data.contains("one.one.one.one") })
        val nx = live { Dns.query("this-does-not-exist.invalid", 1, "1.1.1.1") }
        assertEquals("NXDOMAIN", nx.rcodeName)
        val soa = live { Dns.query("example.com", 6, "8.8.8.8") }
        assertTrue(soa.answers.first().data.contains("serial"))
    }

    // ---- TLS
    @Test fun tlsTargets() {
        assertEquals("example.com" to 443, Tls.target("example.com"))
        assertEquals("example.com" to 8443, Tls.target("https://example.com:8443/path?x=1"))
        assertEquals("example.com" to 993, Tls.target("example.com:993"))
        assertEquals("::1" to 443, Tls.target("[::1]"))
        assertEquals("::1" to 8443, Tls.target("[::1]:8443"))
        assertNull(Tls.target("")); assertNull(Tls.target("host:notaport"))
    }

    @Test fun protocolProbingToleratesVersionsThePhoneLacks() {
        // like Android: no TLS 1.0 or 1.1 offered. This used to crash the app (null in a ConcurrentHashMap).
        val r = Tls.probeProtocols(setOf("TLSv1.3", "TLSv1.2"), 1000) { p -> p == "TLSv1.3" }
        assertEquals(mapOf("TLSv1.3" to true, "TLSv1.2" to false, "TLSv1.1" to null, "TLSv1" to null), r)
        val boom = Tls.probeProtocols(setOf("TLSv1.2"), 1000) { throw IllegalStateException("handshake blew up") }
        assertEquals(false, boom["TLSv1.2"]); assertNull(boom["TLSv1.3"])
    }

    @Test fun liveTls() {
        val r = live { Tls.inspect("example.com", 443) }
        assertTrue(r.protocol.startsWith("TLSv1.")); assertTrue(r.chain.isNotEmpty())
        assertTrue("trusted: ${r.trustError}", r.trusted); assertTrue(r.nameMatches)
        assertTrue(r.chain[0].sans.any { it == "example.com" || it.startsWith("*.") || it.endsWith("example.com") })
        assertEquals(64 + 31, r.chain[0].sha256.length) // 32 bytes as AA:BB:...
        assertEquals(true, r.protocols["TLSv1.2"])
    }

    @Test fun liveTlsBadCertificates() {
        val expired = live { Tls.inspect("expired.badssl.com", 443) }
        assertFalse(expired.trusted); assertTrue(expired.findings.any { it.contains("expired") })
        val wrong = live { Tls.inspect("wrong.host.badssl.com", 443) }
        assertFalse(wrong.nameMatches); assertTrue(wrong.findings.any { it.contains("does not list the name") })
        val self = live { Tls.inspect("self-signed.badssl.com", 443) }
        assertFalse(self.trusted); assertTrue(self.chain[0].selfSigned); assertTrue(self.findings.any { it.contains("Self-signed") })
    }

    // ---- WHOIS / RDAP / geolocation, offline
    @Test fun whoisReferral() {
        assertEquals("whois.verisign-grs.com", Whois.referral("% IANA WHOIS server\n\ndomain:       COM\n\nrefer:        whois.verisign-grs.com\n"))
        assertEquals("whois.arin.net", Whois.referral("refer:        whois.arin.net\n\ninetnum: 1.0.0.0"))
        assertEquals("whois.markmonitor.com", Whois.referral("Domain Name: EXAMPLE.COM\n   Registrar WHOIS Server: whois.markmonitor.com\n"))
        assertNull(Whois.referral("no referral here"))
    }

    @Test fun rdapSummaries() {
        val d = JSONObject("""{"ldhName":"EXAMPLE.COM","status":["client delete prohibited","active"],
          "events":[{"eventAction":"registration","eventDate":"1995-08-14T04:00:00Z"},{"eventAction":"expiration","eventDate":"2026-08-13T04:00:00Z"}],
          "entities":[{"roles":["registrar"],"vcardArray":["vcard",[["version",{},"text","4.0"],["fn",{},"text","Example Registrar, Inc."]]],
                       "entities":[{"roles":["abuse"],"vcardArray":["vcard",[["fn",{},"text","Abuse Desk"]]]}]}],
          "nameservers":[{"ldhName":"A.IANA-SERVERS.NET"},{"ldhName":"B.IANA-SERVERS.NET"}],"secureDNS":{"delegationSigned":true}}""")
        val s = Rdap.domain(d).toMap()
        assertEquals("EXAMPLE.COM", s["Name"]); assertEquals("1995-08-14", s["Registration"]); assertEquals("2026-08-13", s["Expiration"])
        assertEquals("Example Registrar, Inc.", s["registrar"]); assertEquals("Abuse Desk", s["abuse"])
        assertEquals("a.iana-servers.net\nb.iana-servers.net", s["Name servers"]); assertEquals("signed", s["DNSSEC"])
        val ip = JSONObject("""{"name":"APNIC-LABS","handle":"1.1.1.0 - 1.1.1.255","startAddress":"1.1.1.0","endAddress":"1.1.1.255","country":"AU","type":"ASSIGNED PORTABLE",
          "cidr0_cidrs":[{"v4prefix":"1.1.1.0","length":24}],"entities":[{"roles":["registrant"],"vcardArray":["vcard",[["fn",{},"text","APNIC Research and Development"]]]}]}""")
        val m = Rdap.ip(ip).toMap()
        assertEquals("APNIC-LABS", m["Network"]); assertEquals("1.1.1.0/24", m["Range"]); assertEquals("AU", m["Country"]); assertEquals("APNIC Research and Development", m["registrant"])
    }

    @Test fun geoParse() {
        val g = GeoIp.parse(JSONObject("""{"ip":"1.1.1.1","success":true,"country":"Australia","region":"Queensland","city":"Brisbane","latitude":-27.46,"longitude":153.02,
          "flag":{"emoji":"🇦🇺"},"connection":{"asn":13335,"org":"Apnic Research And Development","isp":"Cloudflare, Inc.","domain":"cloudflare.com"},"timezone":{"id":"Australia/Brisbane"}}"""))
        assertEquals("Brisbane", g.city); assertEquals(13335L, g.asn); assertEquals("Cloudflare, Inc.", g.isp); assertEquals("Australia/Brisbane", g.timezone); assertEquals(-27.46, g.lat!!, 0.001)
        val bad = GeoIp.parse(JSONObject("""{"success":false,"message":"Invalid IP address"}"""))
        assertEquals("Invalid IP address", bad.error); assertNull(bad.city)
    }

    @Test fun liveWhois() {
        val text = live { Whois.lookup("example.com") }
        assertNotNull(text); assertTrue(text, text.contains("example", ignoreCase = true))
    }
}
