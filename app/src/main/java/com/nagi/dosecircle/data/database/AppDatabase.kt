package com.nagi.dosecircle.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.nagi.dosecircle.data.dao.DoseEventDao
import com.nagi.dosecircle.data.dao.MedicationDao
import com.nagi.dosecircle.data.dao.PersonDao
import com.nagi.dosecircle.data.dao.ScheduleDao
import com.nagi.dosecircle.data.dao.ScheduleTimeDao
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
    version = 2,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun personDao(): PersonDao
    abstract fun medicationDao(): MedicationDao
    abstract fun scheduleDao(): ScheduleDao
    abstract fun scheduleTimeDao(): ScheduleTimeDao
    abstract fun doseEventDao(): DoseEventDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "dosecircle_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
