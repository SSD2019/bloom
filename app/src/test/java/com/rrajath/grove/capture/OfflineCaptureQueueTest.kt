package com.rrajath.grove.capture

import com.rrajath.grove.data.PendingCaptureDao
import com.rrajath.grove.data.PendingCaptureEntity
import com.rrajath.grove.org.OrgKeywords
import com.rrajath.grove.vault.JvmFileStore
import com.rrajath.grove.vault.Vault
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.LocalDate

class OfflineCaptureQueueTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private class FakePendingDao : PendingCaptureDao {
        private val items = mutableListOf<PendingCaptureEntity>()
        private val count = MutableStateFlow(0)
        private var nextId = 1L

        override suspend fun listAll(): List<PendingCaptureEntity> = items.toList()

        override fun countFlow(): Flow<Int> = count

        override suspend fun insert(item: PendingCaptureEntity): Long {
            val id = nextId++
            items.add(item.copy(id = id))
            count.value = items.size
            return id
        }

        override suspend fun deleteById(id: Long) {
            items.removeAll { it.id == id }
            count.value = items.size
        }

        override suspend fun setError(id: Long, error: String?) {
            val idx = items.indexOfFirst { it.id == id }
            if (idx >= 0) items[idx] = items[idx].copy(lastError = error)
        }
    }

    @Test
    fun `enqueue and flush replays captures in FIFO order`() = runTest {
        val dao = FakePendingDao()
        val queue = OfflineCaptureQueue(dao)
        val store = JvmFileStore(tmp.root)
        val vault = Vault(store, OrgKeywords.DEFAULT)

        queue.enqueue(
            targetFile = "inbox.org",
            location = TargetLocation.BottomOfFile,
            entry = "* First offline task",
            bodyOnly = false,
            today = LocalDate.of(2026, 11, 13),
        )
        queue.enqueue(
            targetFile = "inbox.org",
            location = TargetLocation.BottomOfFile,
            entry = "* Second offline task",
            bodyOnly = false,
            today = LocalDate.of(2026, 11, 13),
        )

        val result = queue.flush(vault, OrgKeywords.DEFAULT)
        assertEquals(2, result.flushed)
        assertEquals(0, result.remaining)

        val content = vault.open("inbox.org")!!.text
        val firstIdx = content.indexOf("* First offline task")
        val secondIdx = content.indexOf("* Second offline task")
        assertTrue(firstIdx >= 0 && secondIdx > firstIdx)
    }

    @Test
    fun `flush stops on 5xx server error preserving FIFO order`() = runTest {
        val dao = FakePendingDao()
        val queue = OfflineCaptureQueue(dao)

        val failingStore = object : com.rrajath.grove.vault.FileStore by JvmFileStore(tmp.root) {
            override suspend fun write(name: String, content: String) {
                throw com.rrajath.grove.remote.EmacsServerException(502, "Bad Gateway")
            }
        }
        val vault = Vault(failingStore, OrgKeywords.DEFAULT)

        queue.enqueue("inbox.org", TargetLocation.BottomOfFile, "* Task 1", false)
        queue.enqueue("inbox.org", TargetLocation.BottomOfFile, "* Task 2", false)

        val result = queue.flush(vault, OrgKeywords.DEFAULT)
        assertEquals(0, result.flushed)
        assertEquals(2, result.remaining)
        assertTrue(result.stoppedOffline)
    }
}

