package app.coulombmppt.data.history

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

// One alert event. Inserted by AlertEngine when a metric crosses a
// configured threshold and stays across (hysteresis is enforced in the
// engine, not here). Stored in the same Room DB as live samples so we
// can show a single 7-day "history" view if the user wants it.
@Entity(
    tableName = "alert",
    indices = [Index(value = ["controllerId", "tsMs"])],
)
data class AlertRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "controllerId") val controllerId: String,
    @ColumnInfo(name = "tsMs")         val tsMs: Long,
    /** "CRIT" or "WARN". String so future severities don't trigger a
     *  schema migration. */
    @ColumnInfo(name = "severity")     val severity: String,
    /** Stable enum-ish identifier — see Alert.Kind. */
    @ColumnInfo(name = "kind")         val kind: String,
    /** The measurement that triggered the alert, in its natural unit. */
    @ColumnInfo(name = "observed")     val observed: Double,
    /** The threshold that was crossed. */
    @ColumnInfo(name = "threshold")    val threshold: Double,
    @ColumnInfo(name = "message")      val message: String,
    /** When the user dismissed this alert, or null if still active. */
    @ColumnInfo(name = "dismissedMs")  val dismissedMs: Long? = null,
)

@Dao
interface AlertsDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(row: AlertRow): Long

    /** Newest first, capped — drives the Alerts screen list. */
    @Query("SELECT * FROM alert WHERE tsMs >= :sinceMs ORDER BY tsMs DESC LIMIT :limit")
    fun streamSince(sinceMs: Long, limit: Int = 200): Flow<List<AlertRow>>

    /** Recent alerts for one specific controller — drives the banner on
     *  UnitDetailScreen. */
    @Query("SELECT * FROM alert WHERE controllerId = :controllerId AND tsMs >= :sinceMs AND dismissedMs IS NULL ORDER BY tsMs DESC")
    fun streamActiveFor(controllerId: String, sinceMs: Long): Flow<List<AlertRow>>

    @Query("UPDATE alert SET dismissedMs = :nowMs WHERE id = :id")
    suspend fun dismiss(id: Long, nowMs: Long = System.currentTimeMillis())

    @Query("UPDATE alert SET dismissedMs = :nowMs WHERE controllerId = :controllerId AND dismissedMs IS NULL")
    suspend fun dismissAllFor(controllerId: String, nowMs: Long = System.currentTimeMillis()): Int

    @Query("DELETE FROM alert WHERE tsMs < :cutoffMs")
    suspend fun prune(cutoffMs: Long): Int
}
