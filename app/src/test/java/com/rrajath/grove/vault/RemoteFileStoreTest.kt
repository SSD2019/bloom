package com.rrajath.grove.vault

import com.rrajath.grove.remote.EmacsClient
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class RemoteFileStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var store: RemoteFileStore

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val client = EmacsClient(baseUrlProvider = { server.url("/").toString() })
        store = RemoteFileStore(
            client = client,
            context = null,
            vaultCacheDir = tmp.newFolder("vault-cache"),
            attachCacheDir = tmp.newFolder("attach-cache"),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `read fetches from emacs and serves from cache when offline`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"name":"inbox.org","lastModified":1000,"size":12,"content":"* Hello\n"}"""),
        )

        val online = store.read("inbox.org")
        assertEquals("* Hello\n", online)

        // Shut down server to simulate WireGuard disconnect
        server.shutdown()

        val offline = store.read("inbox.org")
        assertEquals("* Hello\n", offline)
    }

    @Test
    fun `write sends PUT to emacs and updates local cache`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"name":"tasks.org","lastModified":2000,"size":10}"""),
        )

        store.write("tasks.org", "* Updated\n")
        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertTrue(req.path!!.startsWith("/v1/file"))
        assertEquals("* Updated\n", req.body.readUtf8())

        server.shutdown()
        assertEquals("* Updated\n", store.read("tasks.org"))
    }

    @Test
    fun `raw attachment upload and lazy download`() = runTest {
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"name":"data/ab/123456/img.svgz","lastModified":3000,"size":5}"""),
        )

        store.openOutput("data/ab/123456/img.svgz").use { it.write(payload) }
        val putReq = server.takeRequest()
        assertEquals("PUT", putReq.method)
        assertArrayEquals(payload, putReq.body.readByteArray())

        // Now statRaw + downloadRaw for a file not yet in local cache
        val downloadPayload = byteArrayOf(9, 8, 7)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"name":"data/cd/789/other.png","lastModified":4000,"size":3}"""),
        )
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(okio.Buffer().write(downloadPayload)),
        )

        val readBytes = store.openInput("data/cd/789/other.png").use { it.readBytes() }
        assertArrayEquals(downloadPayload, readBytes)

        val statReq = server.takeRequest()
        assertEquals("GET", statReq.method)
        assertTrue(statReq.path!!.contains("/v1/raw/stat"))

        val getReq = server.takeRequest()
        assertEquals("GET", getReq.method)
        assertTrue(getReq.path!!.contains("/v1/raw/file"))
    }

    @Test
    fun `renameRaw and moveDir update local attachment cache`() = runTest {
        val payload = byteArrayOf(1, 2, 3)
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"name":"data/ab/123/pic.png","lastModified":1000,"size":3}"""),
        )
        store.openOutput("data/ab/123/pic.png").use { it.write(payload) }
        server.takeRequest()

        // Rename pic.png to photo.png
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))
        assertTrue(store.renameRaw("data/ab/123/pic.png", "photo.png"))
        server.takeRequest()

        // Move directory data/ab/123 to data/archived
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"ok":true}"""))
        assertTrue(store.moveDir("data/ab/123", "data/archived"))
        server.takeRequest()

        // Shut down server; the moved and renamed file should be readable offline from cache
        server.shutdown()
        val readOffline = store.openInput("data/archived/123/photo.png").use { it.readBytes() }
        assertArrayEquals(payload, readOffline)
    }
}

