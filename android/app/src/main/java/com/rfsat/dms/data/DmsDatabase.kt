package com.rfsat.dms.data

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

@Entity(tableName = "events")
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMs: Long,
    val cameraRole: String,
    val type: String,
    val severity: String,
    val confidence: Float,
    val detail: String,
    /** Path to evidence snapshot/clip inside app-private storage. */
    val evidencePath: String?,
    /** SHA-256 of the evidence file at write time — tamper evidence. */
    val evidenceSha256: String?,
)

@Dao
interface EventDao {
    @Insert suspend fun insert(e: EventEntity): Long

    @Query("SELECT * FROM events ORDER BY timestampMs DESC LIMIT :limit")
    fun latest(limit: Int = 200): Flow<List<EventEntity>>

    @Query("SELECT * FROM events WHERE timestampMs BETWEEN :from AND :to ORDER BY timestampMs")
    suspend fun between(from: Long, to: Long): List<EventEntity>

    @Query("DELETE FROM events WHERE timestampMs < :before")
    suspend fun prune(before: Long)
}

@Database(entities = [EventEntity::class], version = 1, exportSchema = true)
abstract class DmsDatabase : RoomDatabase() {
    abstract fun events(): EventDao

    companion object {
        @Volatile private var inst: DmsDatabase? = null
        fun get(ctx: Context): DmsDatabase = inst ?: synchronized(this) {
            inst ?: Room.databaseBuilder(ctx, DmsDatabase::class.java, "dms.db")
                .build().also { inst = it }
        }
    }
}
