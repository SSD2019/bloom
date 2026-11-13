package com.rrajath.grove.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "pending_captures")
data class PendingCaptureEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val targetFile: String,
    val locationJson: String,
    val entry: String,
    val bodyOnly: Boolean,
    val captureEpochDay: Long,
    val createdAt: Long,
    val lastError: String? = null,
)

@Dao
interface PendingCaptureDao {
    @Query("SELECT * FROM pending_captures ORDER BY createdAt ASC, id ASC")
    suspend fun listAll(): List<PendingCaptureEntity>

    @Query("SELECT COUNT(*) FROM pending_captures")
    fun countFlow(): Flow<Int>

    @Insert
    suspend fun insert(item: PendingCaptureEntity): Long

    @Query("DELETE FROM pending_captures WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("UPDATE pending_captures SET lastError = :error WHERE id = :id")
    suspend fun setError(id: Long, error: String?)
}

@Database(
    entities = [PendingCaptureEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class PendingCaptureDatabase : RoomDatabase() {
    abstract fun pendingCaptureDao(): PendingCaptureDao

    companion object {
        fun open(context: Context): PendingCaptureDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                PendingCaptureDatabase::class.java,
                "pending-captures.db",
            ).build()
    }
}

