package io.github.nabin47.uptimeloom.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** A work area: being inside any enabled area counts as work. */
@Entity(tableName = "places")
data class Place(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val lat: Double,
    val lng: Double,
    val radiusM: Int,
    val enabled: Boolean = true,
)

/** One stretch of work. [end] is null while it's still running. */
@Entity(tableName = "sessions", indices = [Index("start")])
data class Session(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val start: Long,
    val end: Long?,
    val placeId: Long? = null,
    /** "auto" (work area) or "manual" (check-in / added by hand). */
    val source: String = SOURCE_AUTO,
    val note: String = "",
    val edited: Boolean = false,
) {
    companion object {
        const val SOURCE_AUTO = "auto"
        const val SOURCE_MANUAL = "manual"
    }
}

/** Which work areas the phone is currently inside (and since when). */
@Entity(tableName = "presence")
data class Presence(
    @PrimaryKey val placeId: Long,
    val since: Long,
)

/** Leave, holiday or sick day: not a missed workday, lowers the target. */
@Entity(tableName = "days_off")
data class DayOff(
    @PrimaryKey val date: String, // yyyy-MM-dd
    val note: String = "",
)

/** Small diagnostic log of what the tracker received ("Activity" screen). */
@Entity(tableName = "events", indices = [Index("time")])
data class EventLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val time: Long,
    val kind: String,
    val detail: String,
)

@Dao
interface PlaceDao {
    @Query("SELECT * FROM places ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<Place>>

    @Query("SELECT * FROM places")
    suspend fun all(): List<Place>

    @Query("SELECT * FROM places WHERE enabled = 1")
    suspend fun enabled(): List<Place>

    @Query("SELECT * FROM places WHERE id = :id")
    suspend fun byId(id: Long): Place?

    @Insert
    suspend fun insert(place: Place): Long

    @Update
    suspend fun update(place: Place)

    @Query("DELETE FROM places WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions ORDER BY start")
    fun observeAll(): Flow<List<Session>>

    @Query("SELECT * FROM sessions ORDER BY start")
    suspend fun all(): List<Session>

    @Query("SELECT * FROM sessions WHERE `end` IS NULL ORDER BY start DESC LIMIT 1")
    suspend fun open(): Session?

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun byId(id: Long): Session?

    @Insert
    suspend fun insert(session: Session): Long

    @Update
    suspend fun update(session: Session)

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface PresenceDao {
    @Query("SELECT * FROM presence")
    fun observeAll(): Flow<List<Presence>>

    @Query("SELECT * FROM presence")
    suspend fun all(): List<Presence>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(p: Presence)

    @Query("DELETE FROM presence WHERE placeId = :placeId")
    suspend fun delete(placeId: Long)

    @Query("DELETE FROM presence")
    suspend fun clear()
}

@Dao
interface DayOffDao {
    @Query("SELECT * FROM days_off ORDER BY date")
    fun observeAll(): Flow<List<DayOff>>

    @Query("SELECT * FROM days_off")
    suspend fun all(): List<DayOff>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(d: DayOff)

    @Query("DELETE FROM days_off WHERE date = :date")
    suspend fun delete(date: String)
}

@Dao
interface EventDao {
    @Query("SELECT * FROM events ORDER BY time DESC LIMIT 300")
    fun observeRecent(): Flow<List<EventLog>>

    @Insert
    suspend fun insert(e: EventLog)

    @Query("DELETE FROM events WHERE time < :before")
    suspend fun prune(before: Long)
}

@Database(
    entities = [Place::class, Session::class, Presence::class, DayOff::class, EventLog::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDb : RoomDatabase() {
    abstract fun places(): PlaceDao
    abstract fun sessions(): SessionDao
    abstract fun presence(): PresenceDao
    abstract fun daysOff(): DayOffDao
    abstract fun events(): EventDao

    companion object {
        @Volatile private var instance: AppDb? = null

        fun get(context: Context): AppDb = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDb::class.java, "uptime.db")
                .build().also { instance = it }
        }
    }
}
