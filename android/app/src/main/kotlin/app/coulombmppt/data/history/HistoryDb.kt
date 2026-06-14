package app.coulombmppt.data.history

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [LiveSampleRow::class, AlertRow::class],
    version  = 2,
    exportSchema = false,
)
abstract class HistoryDb : RoomDatabase() {

    abstract fun liveSampleDao(): LiveSampleDao
    abstract fun alertsDao(): AlertsDao

    companion object {
        @Volatile private var instance: HistoryDb? = null

        fun get(context: Context): HistoryDb =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    HistoryDb::class.java,
                    "coulombmppt-history.db",
                ).fallbackToDestructiveMigration().build().also { instance = it }
            }
    }
}
