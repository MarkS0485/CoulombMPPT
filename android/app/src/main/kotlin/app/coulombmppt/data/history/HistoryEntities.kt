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

// One downsampled live-sample row. Inserted at the configured rate
// (default ~ every 10 s) by HistoryRecorder, scoped per controller.
// Index on (controllerId, tsMs) keeps "fetch last N hours for unit X"
// fast even with months of data.
@Entity(
    tableName = "live_sample",
    indices = [Index(value = ["controllerId", "tsMs"])],
)
data class LiveSampleRow(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "controllerId") val controllerId: String,
    @ColumnInfo(name = "tsMs")         val tsMs: Long,
    @ColumnInfo(name = "vBat")         val batteryVoltage: Double,
    @ColumnInfo(name = "iCharge")      val chargeCurrent: Double,
    @ColumnInfo(name = "iDischarge")   val dischargeCurrent: Double,
    @ColumnInfo(name = "pvW")          val pvWatts: Double,
    @ColumnInfo(name = "loadW")        val loadWatts: Double,
    @ColumnInfo(name = "tempC")        val temperatureC: Double,
    @ColumnInfo(name = "socPercent")   val socPercent: Double,
)

// One time-bucketed, averaged sample. Returned by the downsampling query so a
// chart over any span (a few minutes up to the full 30-day retention) renders
// with a bounded number of points instead of tens of thousands of raw rows.
// Column aliases in the query must match these field names so Room can map them.
data class LiveSampleBucket(
    val tsMs:             Long,
    val batteryVoltage:   Double,
    val chargeCurrent:    Double,
    val dischargeCurrent: Double,
    val pvWatts:          Double,
    val loadWatts:        Double,
    val temperatureC:     Double,
    val socPercent:       Double,
)

@Dao
interface LiveSampleDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(row: LiveSampleRow)

    /** All rows for a controller newer than `sinceMs`, ascending by time —
     *  shape the chart expects (left-to-right = oldest-to-newest). */
    @Query("SELECT * FROM live_sample WHERE controllerId = :controllerId AND tsMs >= :sinceMs ORDER BY tsMs ASC")
    fun streamSince(controllerId: String, sinceMs: Long): Flow<List<LiveSampleRow>>

    /** One-shot list (not a Flow) for a controller newer than `sinceMs` — used
     *  by the PC sync to read the window it needs to upload. */
    @Query("SELECT * FROM live_sample WHERE controllerId = :controllerId AND tsMs >= :sinceMs ORDER BY tsMs ASC")
    suspend fun listSince(controllerId: String, sinceMs: Long): List<LiveSampleRow>

    /** Downsampled view of [startMs, endMs]: rows averaged into time buckets
     *  of `bucketMs`. The chart picks `bucketMs` from the visible span so the
     *  result is roughly a fixed number of points regardless of how much raw
     *  data the window covers — this is what makes navigation over any time
     *  scale fast. `bucketMs` must be ≥ 1. */
    @Query(
        """
        SELECT MIN(tsMs)        AS tsMs,
               AVG(vBat)        AS batteryVoltage,
               AVG(iCharge)     AS chargeCurrent,
               AVG(iDischarge)  AS dischargeCurrent,
               AVG(pvW)         AS pvWatts,
               AVG(loadW)       AS loadWatts,
               AVG(tempC)       AS temperatureC,
               AVG(socPercent)  AS socPercent
        FROM live_sample
        WHERE controllerId = :controllerId AND tsMs >= :startMs AND tsMs <= :endMs
        GROUP BY tsMs / :bucketMs
        ORDER BY tsMs ASC
        """
    )
    suspend fun listBuckets(
        controllerId: String,
        startMs: Long,
        endMs: Long,
        bucketMs: Long,
    ): List<LiveSampleBucket>

    /** Oldest sample timestamp for a controller, or null if it has none.
     *  Drives the "All" range and clamps how far the viewport can pan back. */
    @Query("SELECT MIN(tsMs) FROM live_sample WHERE controllerId = :controllerId")
    suspend fun earliestTs(controllerId: String): Long?

    /** All rows in a closed [startMs, endMs) window, ordered ascending.
     *  Used by EnergyComputer to compute energy totals for a single calendar day. */
    @Query("""
        SELECT * FROM live_sample
        WHERE controllerId = :controllerId AND tsMs >= :startMs AND tsMs < :endMs
        ORDER BY tsMs ASC
    """)
    suspend fun listRange(controllerId: String, startMs: Long, endMs: Long): List<LiveSampleRow>

    /** Just the timestamps in the window — used to dedup downloaded PC rows
     *  before insert (the autogen PK means OnConflict.IGNORE can't dedup by
     *  (controllerId, tsMs) for us). */
    @Query("SELECT tsMs FROM live_sample WHERE controllerId = :controllerId AND tsMs >= :sinceMs")
    suspend fun timestampsSince(controllerId: String, sinceMs: Long): List<Long>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(rows: List<LiveSampleRow>)

    /** Drop everything older than `cutoffMs`. Called on a schedule to keep
     *  the DB bounded to the retention window. */
    @Query("DELETE FROM live_sample WHERE tsMs < :cutoffMs")
    suspend fun prune(cutoffMs: Long): Int

    @Query("DELETE FROM live_sample WHERE controllerId = :controllerId")
    suspend fun deleteAllFor(controllerId: String): Int

    @Query("SELECT COUNT(*) FROM live_sample WHERE controllerId = :controllerId")
    suspend fun countFor(controllerId: String): Int
}
