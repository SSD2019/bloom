package com.rrajath.grove.remote

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * HTTP/JSON client for `bloom-server.el` running inside a remote Emacs instance.
 */
class EmacsClient(
    private val baseUrlProvider: () -> String,
    private val http: OkHttpClient = defaultHttpClient(),
    private val onUnreachable: (suspend () -> Boolean)? = null,
    private val onReachable: (() -> Unit)? = null,
) {
    private val json = Json { ignoreUnknownKeys = true }

    private fun baseUrl(): HttpUrl {
        val raw = baseUrlProvider().trim().trimEnd('/')
        val normalized = if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "http://$raw"
        return normalized.toHttpUrlOrNull()
            ?: throw EmacsUnreachableException("Invalid Emacs server URL: '$raw'")
    }

    private fun buildUrl(pathSegments: List<String>, query: Map<String, String> = emptyMap()): HttpUrl {
        val builder = baseUrl().newBuilder()
        for (seg in pathSegments) builder.addPathSegment(seg)
        for ((k, v) in query) builder.addQueryParameter(k, v)
        return builder.build()
    }

    suspend fun ping(): EmacsPingResponse = getJson(listOf("v1", "ping"))

    suspend fun getConfig(): EmacsConfigDto = getJson(listOf("v1", "config"))

    suspend fun listFiles(): List<EmacsFileEntryDto> =
        getJson<EmacsFilesResponse>(listOf("v1", "files")).files

    suspend fun listDir(dir: String): List<EmacsFileEntryDto> =
        getJson<EmacsFilesResponse>(listOf("v1", "files", "dir"), mapOf("dir" to dir)).files

    suspend fun statFile(path: String): EmacsFileEntryDto? =
        getJsonOrNull(listOf("v1", "file", "stat"), mapOf("path" to path))

    suspend fun readFile(path: String): EmacsReadFileResponse =
        getJson(listOf("v1", "file"), mapOf("path" to path))

    suspend fun writeFile(path: String, content: String): EmacsFileEntryDto = withContext(Dispatchers.IO) {
        val body = content.toRequestBody(TEXT_UTF8)
        executeJsonWithRetry {
            val url = buildUrl(listOf("v1", "file"), mapOf("path" to path))
            Request.Builder().url(url).put(body).build()
        }
    }

    suspend fun createFile(path: String): Boolean = withContext(Dispatchers.IO) {
        executeRawWithRetry(
            requestFactory = {
                val url = buildUrl(listOf("v1", "file", "create"), mapOf("path" to path))
                Request.Builder().url(url).post("".toRequestBody(null)).build()
            },
        ) { resp ->
            when (resp.code) {
                200, 201 -> true
                409 -> false
                else -> throw EmacsServerException(resp.code, resp.body?.string().orEmpty())
            }
        }
    }

    suspend fun renameFile(oldPath: String, newPath: String): Boolean = withContext(Dispatchers.IO) {
        val payload = json.encodeToString(RenameRequestDto(oldPath, newPath)).toRequestBody(JSON_MEDIA)
        executeRawWithRetry(
            requestFactory = {
                val url = buildUrl(listOf("v1", "file", "rename"))
                Request.Builder().url(url).post(payload).build()
            },
        ) { resp ->
            when (resp.code) {
                200 -> true
                404, 409 -> false
                else -> throw EmacsServerException(resp.code, resp.body?.string().orEmpty())
            }
        }
    }

    suspend fun deleteFile(path: String): Boolean = withContext(Dispatchers.IO) {
        executeRawWithRetry(
            requestFactory = {
                val url = buildUrl(listOf("v1", "file"), mapOf("path" to path))
                Request.Builder().url(url).delete().build()
            },
        ) { resp ->
            when (resp.code) {
                200 -> true
                404 -> false
                else -> throw EmacsServerException(resp.code, resp.body?.string().orEmpty())
            }
        }
    }

    // --- Raw Attachment Endpoints ---

    suspend fun statRaw(path: String): EmacsFileEntryDto? =
        getJsonOrNull(listOf("v1", "raw", "stat"), mapOf("path" to path))

    suspend fun dirExists(dir: String): Boolean =
        getJson<EmacsDirExistsResponse>(listOf("v1", "raw", "dir-exists"), mapOf("dir" to dir)).exists

    suspend fun listRaw(dir: String): EmacsRawListResponse =
        getJson(listOf("v1", "raw", "list"), mapOf("dir" to dir))

    suspend fun downloadRaw(path: String): ByteArray = withContext(Dispatchers.IO) {
        executeRawWithRetry(
            requestFactory = {
                val url = buildUrl(listOf("v1", "raw", "file"), mapOf("path" to path))
                Request.Builder().url(url).get().build()
            },
        ) { resp ->
            if (resp.isSuccessful) {
                resp.body?.bytes() ?: ByteArray(0)
            } else {
                throw EmacsServerException(resp.code, resp.body?.string().orEmpty())
            }
        }
    }

    suspend fun uploadRaw(path: String, bytes: ByteArray): EmacsFileEntryDto = withContext(Dispatchers.IO) {
        executeJsonWithRetry {
            val url = buildUrl(listOf("v1", "raw", "file"), mapOf("path" to path))
            Request.Builder().url(url).put(bytes.toRequestBody(OCTET_STREAM)).build()
        }
    }

    suspend fun renameRaw(path: String, newLeaf: String): Boolean = withContext(Dispatchers.IO) {
        val payload = json.encodeToString(RawRenameRequestDto(path, newLeaf)).toRequestBody(JSON_MEDIA)
        executeRawWithRetry(
            requestFactory = {
                val url = buildUrl(listOf("v1", "raw", "rename"))
                Request.Builder().url(url).post(payload).build()
            },
        ) { resp ->
            when (resp.code) {
                200 -> true
                404, 409 -> false
                else -> throw EmacsServerException(resp.code, resp.body?.string().orEmpty())
            }
        }
    }

    suspend fun deleteRaw(path: String): Boolean = withContext(Dispatchers.IO) {
        executeRawWithRetry(
            requestFactory = {
                val url = buildUrl(listOf("v1", "raw", "file"), mapOf("path" to path))
                Request.Builder().url(url).delete().build()
            },
        ) { resp ->
            when (resp.code) {
                200 -> true
                404 -> false
                else -> throw EmacsServerException(resp.code, resp.body?.string().orEmpty())
            }
        }
    }

    suspend fun deleteDir(dir: String): Boolean = withContext(Dispatchers.IO) {
        executeRawWithRetry(
            requestFactory = {
                val url = buildUrl(listOf("v1", "raw", "dir"), mapOf("dir" to dir))
                Request.Builder().url(url).delete().build()
            },
        ) { resp ->
            when (resp.code) {
                200 -> true
                404 -> false
                else -> throw EmacsServerException(resp.code, resp.body?.string().orEmpty())
            }
        }
    }

    suspend fun moveDir(src: String, dstParent: String): Boolean = withContext(Dispatchers.IO) {
        val payload = json.encodeToString(RawMoveDirRequestDto(src, dstParent)).toRequestBody(JSON_MEDIA)
        executeRawWithRetry(
            requestFactory = {
                val url = buildUrl(listOf("v1", "raw", "move-dir"))
                Request.Builder().url(url).post(payload).build()
            },
        ) { resp ->
            when (resp.code) {
                200 -> true
                404, 409 -> false
                else -> throw EmacsServerException(resp.code, resp.body?.string().orEmpty())
            }
        }
    }

    private suspend inline fun <reified T> getJson(
        segments: List<String>,
        query: Map<String, String> = emptyMap(),
    ): T = withContext(Dispatchers.IO) {
        executeJsonWithRetry {
            val url = buildUrl(segments, query)
            Request.Builder().url(url).get().build()
        }
    }

    private suspend inline fun <reified T> getJsonOrNull(
        segments: List<String>,
        query: Map<String, String> = emptyMap(),
    ): T? = withContext(Dispatchers.IO) {
        executeRawWithRetry(
            requestFactory = {
                val url = buildUrl(segments, query)
                Request.Builder().url(url).get().build()
            },
        ) { resp ->
            when {
                resp.code == 404 -> null
                resp.isSuccessful -> json.decodeFromString<T>(resp.body?.string().orEmpty())
                else -> throw EmacsServerException(resp.code, resp.body?.string().orEmpty())
            }
        }
    }

    private suspend inline fun <reified T> executeJsonWithRetry(
        crossinline requestFactory: () -> Request,
    ): T = executeRawWithRetry(requestFactory) { resp ->
        val bodyStr = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) throw EmacsServerException(resp.code, bodyStr)
        json.decodeFromString(bodyStr)
    }

    private suspend inline fun <T> executeRawWithRetry(
        crossinline requestFactory: () -> Request,
        crossinline block: (okhttp3.Response) -> T,
    ): T {
        return try {
            executeRaw(requestFactory(), block)
        } catch (e: EmacsUnreachableException) {
            val switched = onUnreachable?.invoke() == true
            if (!switched) throw e
            http.connectionPool.evictAll()
            executeRaw(requestFactory(), block)
        }
    }

    private inline fun <T> executeRaw(request: Request, block: (okhttp3.Response) -> T): T {
        val resp = try {
            http.newCall(request).execute()
        } catch (e: IOException) {
            throw EmacsUnreachableException("Cannot reach Emacs server at ${request.url}: ${e.message}", e)
        }
        onReachable?.invoke()
        return resp.use(block)
    }

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        private val TEXT_UTF8 = "text/plain; charset=utf-8".toMediaType()
        private val OCTET_STREAM = "application/octet-stream".toMediaType()

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }
}

