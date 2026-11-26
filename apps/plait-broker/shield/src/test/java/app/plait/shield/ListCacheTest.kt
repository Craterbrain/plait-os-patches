package app.plait.shield

import com.sun.net.httpserver.HttpServer
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The blocklist cache against a real local HTTP server: conditional requests, bad downloads, offline fallback. */
class ListCacheTest {
    private lateinit var server: HttpServer
    private lateinit var dir: File
    private val store = object : Store {
        val map = HashMap<String, String>()
        override fun get(key: String) = map[key]
        override fun put(key: String, value: String?) { if (value == null) map.remove(key) else map[key] = value }
    }

    // What the server answers: its body and ETag, or a fixed status
    @Volatile private var body = hosts(1..100)
    @Volatile private var etag = "\"v1\""
    @Volatile private var status = 200
    @Volatile private var requests = 0
    @Volatile private var fullDownloads = 0

    private fun hosts(range: IntRange) = range.joinToString("\n", prefix = "# a list\n") { "0.0.0.0 ad$it.example.com" }

    private lateinit var list: Blocklist
    private lateinit var cache: ListCache

    @Before fun start() {
        dir = Files.createTempDirectory("lists").toFile()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/list") { x ->
            requests++
            when {
                status != 200 -> { x.sendResponseHeaders(status, -1) }
                x.requestHeaders.getFirst("If-None-Match") == etag -> { x.sendResponseHeaders(304, -1) }
                else -> {
                    fullDownloads++
                    val bytes = body.toByteArray()
                    x.responseHeaders.add("ETag", etag)
                    x.sendResponseHeaders(200, bytes.size.toLong())
                    x.responseBody.use { it.write(bytes) }
                }
            }
            x.close()
        }
        server.start()
        list = Blocklist("test", "Test list", "", "http://127.0.0.1:${server.address.port}/list", "Ads", approxEntries = 100)
        cache = ListCache(dir, store)
    }

    @After fun stop() {
        server.stop(0)
        dir.deleteRecursively()
    }

    @Test fun firstFetchDownloadsAndSaves() {
        val f = cache.fetch(list)
        assertTrue(f.changed)
        assertNull(f.note)
        assertTrue(cache.file(list).readText().contains("ad100.example.com"))
        assertEquals("\"v1\"", store.get("etag:test"))
        assertNotNull(cache.checkedAt(list))
    }

    @Test fun unchangedListCostsOne304AndNoDownload() {
        cache.fetch(list)
        val f = cache.fetch(list)
        assertFalse(f.changed)
        assertEquals(1, fullDownloads)
        assertEquals(2, requests)
    }

    @Test fun changedListIsDownloadedAgain() {
        val first = cache.fetch(list)
        body = hosts(1..120); etag = "\"v2\""
        val second = cache.fetch(list)
        assertTrue(second.changed)
        assertNotEquals(first.sha256, second.sha256)
        assertTrue(cache.file(list).readText().contains("ad120.example.com"))
    }

    @Test fun errorPageDoesNotReplaceTheSavedCopy() {
        cache.fetch(list)
        body = "<html><body>Sign in to this Wi-Fi</body></html>"; etag = "\"portal\""
        val f = cache.fetch(list)
        assertFalse(f.changed)
        assertNotNull(f.note)
        assertTrue(cache.file(list).readText().contains("ad100.example.com"))
        assertFalse(File(dir, "test.part").exists())
    }

    @Test fun unreachableServerUsesTheSavedCopy() {
        cache.fetch(list)
        status = 503
        val f = cache.fetch(list)
        assertFalse(f.changed)
        assertTrue(f.note!!.contains("HTTP 503"))
    }

    @Test(expected = IOException::class) fun nothingSavedAndUnreachableFails() {
        status = 503
        cache.fetch(list)
    }

    @Test fun keepOnlyForgetsListsNoLongerApplied() {
        cache.fetch(list)
        cache.keepOnly(emptySet())
        assertFalse(cache.file(list).exists())
        assertNull(store.get("etag:test"))
    }

    @Test fun mergeReadsSavedCopiesAndLeavesOutNeverBlockNames() {
        val f = cache.fetch(list)
        val names = Merge.merge(listOf(f), setOf("ad7.example.com"))
        assertEquals(99, names.size)
        assertFalse("ad7.example.com" in names)
    }

    @Test fun signatureFollowsContentAndNeverBlockNames() {
        val f = cache.fetch(list)
        val a = ListCache.signature(listOf(f), emptySet())
        assertEquals(a, ListCache.signature(listOf(cache.fetch(list)), emptySet()))
        assertNotEquals(a, ListCache.signature(listOf(f), setOf("example.com")))
    }
}
