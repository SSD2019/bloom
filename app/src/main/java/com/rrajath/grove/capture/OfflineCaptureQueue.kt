package com.rrajath.grove.capture

import com.rrajath.grove.data.PendingCaptureDao
import com.rrajath.grove.data.PendingCaptureEntity
import com.rrajath.grove.org.OrgKeywords
import com.rrajath.grove.remote.EmacsUnreachableException
import com.rrajath.grove.vault.Vault
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.LocalDate

/**
 * Durable FIFO queue for captures created while the remote Emacs server is unreachable.
 */
class OfflineCaptureQueue(private val dao: PendingCaptureDao) {

    private val json = Json { ignoreUnknownKeys = true }

    val pendingCount: Flow<Int> = dao.countFlow()

    suspend fun enqueue(
        targetFile: String,
        location: TargetLocation,
        entry: String,
        bodyOnly: Boolean,
        today: LocalDate = LocalDate.now(),
        error: String? = null,
    ): Long = dao.insert(
        PendingCaptureEntity(
            targetFile = targetFile,
            locationJson = json.encodeToString(location),
            entry = entry,
            bodyOnly = bodyOnly,
            captureEpochDay = today.toEpochDay(),
            createdAt = System.currentTimeMillis(),
            lastError = error,
        ),
    )

    data class FlushResult(val flushed: Int, val remaining: Int, val stoppedOffline: Boolean)

    /**
     * Replays queued captures against [vault] in FIFO order.
     * Stops immediately on [EmacsUnreachableException] so order is preserved.
     */
    suspend fun flush(vault: Vault, keywords: OrgKeywords): FlushResult {
        val items = dao.listAll()
        var flushed = 0
        for (item in items) {
            try {
                val location = json.decodeFromString<TargetLocation>(item.locationJson)
                val today = LocalDate.ofEpochDay(item.captureEpochDay)
                val existing = vault.open(item.targetFile)
                if (existing == null) {
                    vault.createFile(item.targetFile)
                }
                val currentText = existing?.text ?: ""
                val insertion = CaptureInserter.insert(
                    docText = currentText,
                    location = location,
                    entry = item.entry,
                    today = today,
                    keywords = keywords,
                    bodyOnly = item.bodyOnly,
                )
                vault.save(item.targetFile, insertion.newText)
                dao.deleteById(item.id)
                flushed++
            } catch (e: EmacsUnreachableException) {
                dao.setError(item.id, e.message)
                return FlushResult(flushed = flushed, remaining = items.size - flushed, stoppedOffline = true)
            } catch (e: Exception) {
                // Record non-connectivity error (e.g. missing target heading) and continue to next
                dao.setError(item.id, e.message ?: e::class.simpleName)
            }
        }
        return FlushResult(flushed = flushed, remaining = dao.listAll().size, stoppedOffline = false)
    }
}
