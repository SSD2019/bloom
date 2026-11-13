package com.rrajath.grove.vault

import android.content.Context
import android.net.Uri
import com.rrajath.grove.attach.AttachmentFileProvider
import com.rrajath.grove.remote.EmacsClient
import com.rrajath.grove.remote.EmacsFileEntryDto
import com.rrajath.grove.remote.EmacsUnreachableException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap

/**
 * [FileStore] backed by a remote Emacs server (`bloom-server.el`) via [EmacsClient].
 *
 * - `.org` and text files are cached under [vaultCacheDir] so browsing, search,
 *   widgets, and read mode work offline.
 * - Writes (`write`, `create`, `rename`, `delete`) write-through to Emacs and
 *   update the local cache on success.
 * - Attachments (`openInput`, `contentUri`, etc.) are lazy-fetched on demand and
 *   cached under [attachCacheDir].
 */
class RemoteFileStore(
    private val client: EmacsClient,
    private val context: Context?,
    private val vaultCacheDir: File,
    private val attachCacheDir: File,
) : FileStore {

    private val cachedRevisions = ConcurrentHashMap<String, Pair<Long, Long>>()

    constructor(context: Context, client: EmacsClient) : this(
        client = client,
        context = context,
        vaultCacheDir = File(context.filesDir, "remote-cache/vault"),
        attachCacheDir = File(context.cacheDir, "remote-attach"),
    )

    private fun safeCacheFile(base: File, relPath: String): File {
        val clean = relPath.trimStart('/')
        val target = File(base, clean).canonicalFile
        val root = base.canonicalFile
        require(target.path == root.path || target.path.startsWith(root.path + File.separator)) {
            "Path escapes cache root: $relPath"
        }
        return target
    }

    private fun cacheTextFile(name: String, content: String, lastModified: Long, size: Long) {
        val file = safeCacheFile(vaultCacheDir, name)
        file.parentFile?.mkdirs()
        file.writeText(content, Charsets.UTF_8)
        if (lastModified > 0) file.setLastModified(lastModified)
        cachedRevisions[name] = lastModified to size
    }

    private fun removeCachedTextFile(name: String) {
        val file = safeCacheFile(vaultCacheDir, name)
        if (file.exists()) file.delete()
        cachedRevisions.remove(name)
    }

    private fun listLocalVaultCache(): List<FileEntry> {
        if (!vaultCacheDir.exists()) return emptyList()
        val rootPath = vaultCacheDir.canonicalPath
        return vaultCacheDir.walkTopDown()
            .filter { it.isFile }
            .map { f ->
                val rel = f.canonicalPath.removePrefix(rootPath).trimStart(File.separatorChar)
                    .replace(File.separatorChar, '/')
                val rev = cachedRevisions[rel]
                FileEntry(
                    name = rel,
                    lastModified = rev?.first ?: f.lastModified(),
                    size = rev?.second ?: f.length(),
                )
            }
            .toList()
    }

    override suspend fun list(): List<FileEntry> = withContext(Dispatchers.IO) {
        try {
            val remote = client.listFiles().map { it.toFileEntry() }
            val remoteNames = remote.mapTo(HashSet()) { it.name }
            // Prune deleted files from local cache
            if (vaultCacheDir.exists()) {
                val rootPath = vaultCacheDir.canonicalPath
                vaultCacheDir.walkTopDown().filter { it.isFile }.forEach { f ->
                    val rel = f.canonicalPath.removePrefix(rootPath).trimStart(File.separatorChar)
                        .replace(File.separatorChar, '/')
                    if (rel !in remoteNames) {
                        f.delete()
                        cachedRevisions.remove(rel)
                    }
                }
            }
            remote
        } catch (_: EmacsUnreachableException) {
            listLocalVaultCache()
        }
    }

    override suspend fun listDir(dir: String): List<FileEntry> = withContext(Dispatchers.IO) {
        try {
            client.listDir(dir).map { it.toFileEntry() }
        } catch (_: EmacsUnreachableException) {
            val trimmed = dir.trim('/')
            val prefix = if (trimmed.isEmpty()) "" else "$trimmed/"
            listLocalVaultCache().filter {
                it.name.startsWith(prefix) && '/' !in it.name.removePrefix(prefix)
            }
        }
    }

    override suspend fun stat(name: String): FileEntry? = withContext(Dispatchers.IO) {
        try {
            client.statFile(name)?.toFileEntry()
        } catch (_: EmacsUnreachableException) {
            val f = safeCacheFile(vaultCacheDir, name)
            if (f.isFile) {
                val rev = cachedRevisions[name]
                FileEntry(name, rev?.first ?: f.lastModified(), rev?.second ?: f.length())
            } else {
                null
            }
        }
    }

    override suspend fun read(name: String): String = withContext(Dispatchers.IO) {
        val cached = safeCacheFile(vaultCacheDir, name)
        try {
            val remote = client.readFile(name)
            cacheTextFile(name, remote.content, remote.lastModified, remote.size)
            remote.content
        } catch (e: EmacsUnreachableException) {
            if (cached.isFile) {
                cached.readText(Charsets.UTF_8)
            } else {
                throw e
            }
        }
    }

    override suspend fun write(name: String, content: String) = withContext(Dispatchers.IO) {
        val entry = client.writeFile(name, content)
        cacheTextFile(name, content, entry.lastModified, entry.size)
    }

    override suspend fun create(name: String): Boolean = withContext(Dispatchers.IO) {
        val created = client.createFile(name)
        if (created) {
            cacheTextFile(name, "", System.currentTimeMillis(), 0L)
        }
        created
    }

    override suspend fun rename(oldName: String, newName: String): Boolean = withContext(Dispatchers.IO) {
        val ok = client.renameFile(oldName, newName)
        if (ok) {
            val oldFile = safeCacheFile(vaultCacheDir, oldName)
            val newFile = safeCacheFile(vaultCacheDir, newName)
            if (oldFile.isFile) {
                newFile.parentFile?.mkdirs()
                oldFile.copyTo(newFile, overwrite = true)
                oldFile.delete()
            }
            cachedRevisions.remove(oldName)?.let { cachedRevisions[newName] = it }
        }
        ok
    }

    override suspend fun delete(name: String): Boolean = withContext(Dispatchers.IO) {
        val ok = client.deleteFile(name)
        if (ok) removeCachedTextFile(name)
        ok
    }

    override suspend fun exists(name: String): Boolean = stat(name) != null

    // --- Raw Attachment Access (lazy-fetched & disk-cached) ---

    private suspend fun ensureCachedAttachment(name: String): File? = withContext(Dispatchers.IO) {
        val cached = safeCacheFile(attachCacheDir, name)
        val metaFile = safeCacheFile(attachCacheDir, "$name.bloom-meta")
        val remoteStat = try {
            client.statRaw(name)
        } catch (_: EmacsUnreachableException) {
            return@withContext if (cached.isFile) cached else null
        } ?: return@withContext null

        val expectedMeta = "${remoteStat.lastModified}:${remoteStat.size}"
        if (cached.isFile && metaFile.isFile && metaFile.readText() == expectedMeta) {
            return@withContext cached
        }

        val bytes = client.downloadRaw(name)
        cached.parentFile?.mkdirs()
        cached.writeBytes(bytes)
        metaFile.writeText(expectedMeta)
        cached
    }

    override suspend fun openInput(name: String): InputStream {
        val file = ensureCachedAttachment(name) ?: throw FileNotFoundException("Remote attachment not found: $name")
        return file.inputStream()
    }

    override suspend fun createBinary(name: String): Boolean = withContext(Dispatchers.IO) {
        if (client.statRaw(name) != null) return@withContext false
        client.uploadRaw(name, ByteArray(0))
        true
    }

    override suspend fun openOutput(name: String): OutputStream {
        val buffer = ByteArrayOutputStream()
        return object : OutputStream() {
            private var closed = false
            override fun write(b: Int) = buffer.write(b)
            override fun write(b: ByteArray, off: Int, len: Int) = buffer.write(b, off, len)
            override fun close() {
                if (closed) return
                closed = true
                val bytes = buffer.toByteArray()
                runBlocking(Dispatchers.IO) {
                    val entry = client.uploadRaw(name, bytes)
                    val cached = safeCacheFile(attachCacheDir, name)
                    cached.parentFile?.mkdirs()
                    cached.writeBytes(bytes)
                    safeCacheFile(attachCacheDir, "$name.bloom-meta")
                        .writeText("${entry.lastModified}:${entry.size}")
                }
            }
        }
    }

    override suspend fun statRaw(name: String): FileEntry? = withContext(Dispatchers.IO) {
        try {
            client.statRaw(name)?.toFileEntry()
        } catch (_: EmacsUnreachableException) {
            val cached = safeCacheFile(attachCacheDir, name)
            if (cached.isFile) FileEntry(name, cached.lastModified(), cached.length()) else null
        }
    }

    override suspend fun renameRaw(name: String, newLeaf: String): Boolean = withContext(Dispatchers.IO) {
        client.renameRaw(name, newLeaf)
    }

    override suspend fun deleteRaw(name: String): Boolean = withContext(Dispatchers.IO) {
        val ok = client.deleteRaw(name)
        if (ok) {
            safeCacheFile(attachCacheDir, name).delete()
            safeCacheFile(attachCacheDir, "$name.bloom-meta").delete()
        }
        ok
    }

    override suspend fun dirExists(dir: String): Boolean = withContext(Dispatchers.IO) {
        try {
            client.dirExists(dir)
        } catch (_: EmacsUnreachableException) {
            safeCacheFile(attachCacheDir, dir).isDirectory
        }
    }

    override suspend fun listDirRaw(dir: String): List<FileEntry> = withContext(Dispatchers.IO) {
        client.listRaw(dir).files.map { it.toFileEntry() }
    }

    override suspend fun listSubdirs(dir: String): List<String> = withContext(Dispatchers.IO) {
        client.listRaw(dir).subdirs
    }

    override suspend fun deleteDir(dir: String): Boolean = withContext(Dispatchers.IO) {
        val ok = client.deleteDir(dir)
        if (ok) safeCacheFile(attachCacheDir, dir).deleteRecursively()
        ok
    }

    override suspend fun moveDir(src: String, dstParent: String): Boolean = withContext(Dispatchers.IO) {
        client.moveDir(src, dstParent)
    }

    override suspend fun contentUri(name: String): Uri? {
        val ctx = context ?: return null
        val file = ensureCachedAttachment(name) ?: return null
        return AttachmentFileProvider.uriFor(ctx, file)
    }

    private fun EmacsFileEntryDto.toFileEntry(): FileEntry =
        FileEntry(name = name, lastModified = lastModified, size = size)
}

