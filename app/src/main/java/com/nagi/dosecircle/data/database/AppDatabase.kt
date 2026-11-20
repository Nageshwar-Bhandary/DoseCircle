package com.nagi.dosecircle.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.nagi.dosecircle.data.entity.DoseEvent
import com.nagi.dosecircle.data.entity.Medication
import com.nagi.dosecircle.data.entity.Person
import com.nagi.dosecircle.data.entity.Schedule
import com.nagi.dosecircle.data.entity.ScheduleTime

@Database(
    entities = [
        Person::class,
        Medication::class,
        Schedule::class,
        ScheduleTime::class,
        DoseEvent::class
    ],
    version = 1,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "dosecircle_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
