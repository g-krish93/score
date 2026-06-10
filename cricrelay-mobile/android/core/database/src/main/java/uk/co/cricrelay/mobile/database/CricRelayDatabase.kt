package uk.co.cricrelay.mobile.database

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import uk.co.cricrelay.shared.model.StreamMatch

@Entity(tableName = "streams")
data class StreamEntity(
    @PrimaryKey val slug: String,
    val label: String,
    val overlayEmbedUrl: String,
    val relaySource: String,
    val relayPaused: Boolean,
    val scoringMode: String,
    val scoringActive: Boolean,
    val scoringStale: Boolean,
    val isLive: Boolean,
    val broadcastStatus: String,
    val cachedAt: Long = System.currentTimeMillis(),
)

fun StreamEntity.toDomain(): StreamMatch = StreamMatch(
    slug = slug,
    label = label,
    overlayEmbedUrl = overlayEmbedUrl,
    relaySource = relaySource,
    relayPaused = relayPaused,
    scoringMode = scoringMode,
    scoringActive = scoringActive,
    scoringStale = scoringStale,
    isLive = isLive,
    broadcast = uk.co.cricrelay.shared.model.BroadcastStatus(status = broadcastStatus),
)

fun StreamMatch.toEntity(): StreamEntity = StreamEntity(
    slug = slug,
    label = label,
    overlayEmbedUrl = overlayEmbedUrl,
    relaySource = relaySource,
    relayPaused = relayPaused,
    scoringMode = scoringMode,
    scoringActive = scoringActive,
    scoringStale = scoringStale,
    isLive = isLive,
    broadcastStatus = broadcast.status,
)

@Dao
interface StreamDao {
    @Query("SELECT * FROM streams ORDER BY cachedAt DESC")
    suspend fun getAll(): List<StreamEntity>

    @Query("SELECT * FROM streams WHERE slug = :slug LIMIT 1")
    suspend fun getBySlug(slug: String): StreamEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(items: List<StreamEntity>)

    @Query("DELETE FROM streams")
    suspend fun clear()
}

@Database(entities = [StreamEntity::class], version = 1, exportSchema = false)
abstract class CricRelayDatabase : androidx.room.RoomDatabase() {
    abstract fun streamDao(): StreamDao
}
