package app.phase.stem.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

enum class TrackStatus {
    /** Copying/decoding the imported file. */
    IMPORTING,
    /** Full mix playable; not separated yet. */
    READY,
    /** Waiting for the separation worker. */
    QUEUED,
    SEPARATING,
    SEPARATED,
    FAILED,
}

@Entity(tableName = "tracks")
data class TrackEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String?,
    val album: String?,
    val durationMs: Long,
    val sourceFileName: String,
    val mimeType: String?,
    val hasArtwork: Boolean,
    val importedAt: Long,
    val lastPlayedAt: Long? = null,
    val favorite: Boolean = false,
    val sampleRate: Int = 0,
    val stemSampleRate: Int = 0,
    val status: TrackStatus = TrackStatus.IMPORTING,
    val progress: Float = 0f,
    val statusMessage: String? = null,
    val separationModel: String? = null,
    val bpm: Float? = null,
    val lyricsLrc: String? = null,
    val lastMixJson: String? = null,
    val sizeBytes: Long = 0,
) {
    val isSeparated get() = status == TrackStatus.SEPARATED
    val isPlayable get() = status != TrackStatus.IMPORTING && sampleRate > 0
}

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val trackId: String,
    val name: String,
    val mixJson: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(tableName = "presets")
data class PresetEntity(
    @PrimaryKey val id: String,
    val name: String,
    val mixJson: String,
    val createdAt: Long,
)

@Entity(tableName = "exports")
data class ExportEntity(
    @PrimaryKey val id: String,
    val trackId: String?,
    val title: String,
    val path: String,
    val format: String,
    val kind: String, // "export" | "recording"
    val durationMs: Long,
    val sizeBytes: Long,
    val createdAt: Long,
)

@Dao
interface TrackDao {
    @Query("SELECT * FROM tracks ORDER BY importedAt DESC")
    fun all(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks")
    suspend fun allOnce(): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE id = :id")
    fun observe(id: String): Flow<TrackEntity?>

    @Query("SELECT * FROM tracks WHERE id = :id")
    suspend fun get(id: String): TrackEntity?

    @Query("SELECT * FROM tracks WHERE lastPlayedAt IS NOT NULL ORDER BY lastPlayedAt DESC LIMIT :limit")
    fun recentlyPlayed(limit: Int): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE status IN ('QUEUED','SEPARATING')")
    suspend fun pendingSeparations(): List<TrackEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(t: TrackEntity)

    @Update
    suspend fun update(t: TrackEntity)

    @Query("UPDATE tracks SET status = :status, progress = :progress, statusMessage = :message WHERE id = :id")
    suspend fun setStatus(id: String, status: TrackStatus, progress: Float, message: String?)

    @Query("UPDATE tracks SET favorite = :fav WHERE id = :id")
    suspend fun setFavorite(id: String, fav: Boolean)

    @Query("UPDATE tracks SET lastPlayedAt = :t WHERE id = :id")
    suspend fun touchPlayed(id: String, t: Long)

    @Query("UPDATE tracks SET lastMixJson = :json WHERE id = :id")
    suspend fun saveMix(id: String, json: String)

    @Query("UPDATE tracks SET lyricsLrc = :lrc WHERE id = :id")
    suspend fun saveLyrics(id: String, lrc: String?)

    @Query("UPDATE tracks SET bpm = :bpm WHERE id = :id")
    suspend fun saveBpm(id: String, bpm: Float?)

    @Query("DELETE FROM tracks WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY updatedAt DESC")
    fun all(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun get(id: String): ProjectEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(p: ProjectEntity)

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM projects WHERE trackId = :trackId")
    suspend fun deleteForTrack(trackId: String)
}

@Dao
interface PresetDao {
    @Query("SELECT * FROM presets ORDER BY createdAt DESC")
    fun all(): Flow<List<PresetEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(p: PresetEntity)

    @Query("DELETE FROM presets WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface ExportDao {
    @Query("SELECT * FROM exports ORDER BY createdAt DESC")
    fun all(): Flow<List<ExportEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(e: ExportEntity)

    @Query("DELETE FROM exports WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM exports WHERE trackId = :trackId")
    suspend fun forTrack(trackId: String): List<ExportEntity>
}

@Database(
    entities = [TrackEntity::class, ProjectEntity::class, PresetEntity::class, ExportEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class PhaseDatabase : RoomDatabase() {
    abstract fun tracks(): TrackDao
    abstract fun projects(): ProjectDao
    abstract fun presets(): PresetDao
    abstract fun exports(): ExportDao

    companion object {
        fun create(context: Context) = Room.databaseBuilder(context, PhaseDatabase::class.java, "phase.db").build()
    }
}
