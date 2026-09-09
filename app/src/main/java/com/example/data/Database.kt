package com.example.data

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
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "playlist_tracks")
data class TrackEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val genre: String,
    val audioUri: String?,
    val addedBy: String,
    val votes: Int = 0,
    val orderIndex: Int = 0
)

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlist_tracks ORDER BY orderIndex ASC")
    fun getAllTracks(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM playlist_tracks ORDER BY orderIndex ASC")
    suspend fun getAllTracksSnapshot(): List<TrackEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrack(track: TrackEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tracks: List<TrackEntity>)

    @Query("DELETE FROM playlist_tracks WHERE id = :trackId")
    suspend fun deleteTrack(trackId: String)

    @Query("UPDATE playlist_tracks SET votes = votes + 1 WHERE id = :trackId")
    suspend fun upvoteTrack(trackId: String)

    @Query("DELETE FROM playlist_tracks")
    suspend fun clearPlaylist()
}

@Database(entities = [TrackEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun playlistDao(): PlaylistDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "hamseda_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
