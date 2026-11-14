package com.rrajath.grove.remote

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EmacsEndpointResolverTest {

    @Test
    fun primaryReachable_skipsFallbackAndCaches() = runTest {
        val probed = mutableListOf<String>()
        val resolver = EmacsEndpointResolver(
            primaryUrlProvider = { "http://192.168.1.50:8765" },
            fallbackUrlProvider = { "http://10.0.0.1:8765" },
            probe = { url ->
                probed.add(url)
                url == "http://192.168.1.50:8765"
            },
        )

        val first = resolver.resolve()
        assertTrue(first.reachable)
        assertFalse(first.usedFallback)
        assertEquals("http://192.168.1.50:8765", first.url)
        assertEquals(listOf("http://192.168.1.50:8765"), probed)

        // Second call hits session cache.
        val second = resolver.resolve()
        assertEquals("http://192.168.1.50:8765", second.url)
        assertEquals(1, probed.size)

        // Invalidating forces re-probe.
        resolver.invalidate()
        resolver.resolve()
        assertEquals(2, probed.size)
    }

    @Test
    fun primaryUnreachable_fallsBackToSecondary() = runTest {
        val probed = mutableListOf<String>()
        val resolver = EmacsEndpointResolver(
            primaryUrlProvider = { "http://192.168.1.50:8765" },
            fallbackUrlProvider = { "http://10.0.0.1:8765" },
            probe = { url ->
                probed.add(url)
                url == "http://10.0.0.1:8765"
            },
        )

        val res = resolver.resolve()
        assertTrue(res.reachable)
        assertTrue(res.usedFallback)
        assertEquals("http://10.0.0.1:8765", res.url)
        assertEquals("http://10.0.0.1:8765", resolver.currentOrDefault())
        assertEquals(listOf("http://192.168.1.50:8765", "http://10.0.0.1:8765"), probed)
    }

    @Test
    fun bothUnreachable_returnsUnreachableWithPrimaryDefault() = runTest {
        val resolver = EmacsEndpointResolver(
            primaryUrlProvider = { "http://192.168.1.50:8765" },
            fallbackUrlProvider = { "http://10.0.0.1:8765" },
            probe = { false },
        )

        val res = resolver.resolve()
        assertFalse(res.reachable)
        assertFalse(res.usedFallback)
        assertEquals("http://192.168.1.50:8765", res.url)
    }

    @Test
    fun emacsClient_retriesWithFallbackWhenPrimaryFails() = runTest {
        val deadServer = MockWebServer()
        deadServer.start()
        val deadUrl = deadServer.url("/").toString()
        deadServer.shutdown()

        val liveServer = MockWebServer()
        liveServer.start()
        liveServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"status":"ok","version":"1","orgDirectory":"/org"}"""),
        )
        liveServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"status":"ok","version":"1","orgDirectory":"/org"}"""),
        )
        val liveUrl = liveServer.url("/").toString()

        try {
            val resolver = EmacsEndpointResolver(
                primaryUrlProvider = { deadUrl },
                fallbackUrlProvider = { liveUrl },
            )
            val client = EmacsClient(
                baseUrlProvider = { resolver.currentOrDefault() },
                onUnreachable = {
                    val previous = resolver.currentOrDefault()
                    val resolved = resolver.resolve(forceReprobe = true)
                    resolved.reachable && resolved.url != previous
                },
            )

            val ping = client.ping()
            assertEquals("ok", ping.status)
            assertEquals("/org", ping.orgDirectory)
            assertEquals(liveUrl, resolver.currentOrDefault())
        } finally {
            liveServer.shutdown()
        }
    }
}

