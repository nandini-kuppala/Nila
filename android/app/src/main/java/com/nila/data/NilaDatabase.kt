package com.nila.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [EventRecord::class, CareRecord::class, BabyProfile::class,
                ConversationRecord::class, HealthRecord::class, MotherProfile::class,
                MedicineScanRecord::class],
    version = 3,
    exportSchema = true,
)
abstract class NilaDatabase : RoomDatabase() {
    abstract fun events(): EventDao
    abstract fun care(): CareDao
    abstract fun baby(): BabyDao
    abstract fun conversations(): ConversationDao
    abstract fun healthRecords(): HealthRecordDao
    abstract fun mother(): MotherDao
    abstract fun medicineScans(): MedicineScanDao

    companion object {
        @Volatile private var instance: NilaDatabase? = null

        fun get(context: Context): NilaDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                NilaDatabase::class.java,
                "nila.db",
            )
                // No cloud sync and no export. Everything about a baby stays in
                // this file, which is also why backup is disabled in the manifest.
                .fallbackToDestructiveMigration()
                .build()
                .also { instance = it }
        }
    }
}
