package com.rrajath.grove.remote

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Resolves the active Emacs server URL from a primary URL (e.g. LAN) and an optional
 * fallback URL (e.g. WireGuard VPN), caching the reachable endpoint for the session.
 */
class EmacsEndpointResolver(
    private val primaryUrlProvider: () -> String,
    private val fallbackUrlProvider: () -> String,
    private val probe: suspend (String) -> Boolean = { url -> defaultProbe(url) },
) {
    data class ResolveResult(
        val url: String,
        val reachable: Boolean,
        val usedFallback: Boolean,
    )

    private val mutex = Mutex()

    @Volatile
    private var cachedUrl: String? = null

    /** Fast synchronous accessor for [EmacsClient.baseUrlProvider]. */
    fun currentOrDefault(): String {
        val primary = primaryUrlProvider().trim()
        val fallback = fallbackUrlProvider().trim()
        val cached = cachedUrl
        if (cached != null && (cached == primary || cached == fallback)) {
            return cached
        }
        return primary.ifBlank { fallback }
    }

    /** Clears the session cache so the next [resolve] probes primary then fallback. */
    fun invalidate() {
        cachedUrl = null
    }

    /**
     * Probes [primaryUrlProvider] first, then [fallbackUrlProvider] if primary is unreachable.
     * Caches the winning URL until [invalidate] or [forceReprobe] is called.
     */
    suspend fun resolve(forceReprobe: Boolean = false): ResolveResult {
        val primary = primaryUrlProvider().trim()
        val fallback = fallbackUrlProvider().trim()

        if (!forceReprobe) {
            val cached = cachedUrl
            if (cached != null && (cached == primary || cached == fallback)) {
                return ResolveResult(
                    url = cached,
                    reachable = true,
                    usedFallback = cached == fallback && fallback != primary,
                )
            }
        }

        return mutex.withLock {
            val p = primaryUrlProvider().trim()
            val f = fallbackUrlProvider().trim()
            if (!forceReprobe) {
                val cached = cachedUrl
                if (cached != null && (cached == p || cached == f)) {
                    return@withLock ResolveResult(
                        url = cached,
                        reachable = true,
                        usedFallback = cached == f && f != p,
                    )
                }
            }

            if (p.isNotBlank() && probe(p)) {
                cachedUrl = p
                return@withLock ResolveResult(url = p, reachable = true, usedFallback = false)
            }
            if (f.isNotBlank() && f != p && probe(f)) {
                cachedUrl = f
                return@withLock ResolveResult(url = f, reachable = true, usedFallback = true)
            }

            cachedUrl = null
            ResolveResult(url = p.ifBlank { f }, reachable = false, usedFallback = false)
        }
    }

    companion object {
        private val probeClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(1500, TimeUnit.MILLISECONDS)
                .readTimeout(1500, TimeUnit.MILLISECONDS)
                .writeTimeout(1500, TimeUnit.MILLISECONDS)
                .build()
        }

        suspend fun defaultProbe(url: String): Boolean {
            val trimmed = url.trim()
            if (trimmed.isEmpty()) return false
            return try {
                EmacsClient(baseUrlProvider = { trimmed }, http = probeClient).ping()
                true
            } catch (_: Exception) {
                false
            }
        }
    }
}

