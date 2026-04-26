package com.devtool.gpsmocker.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [LandmarkEntity::class], version = 1, exportSchema = false)
abstract class LandmarkDatabase : RoomDatabase() {
    abstract fun landmarkDao(): LandmarkDao

    companion object {
        @Volatile private var INSTANCE: LandmarkDatabase? = null

        fun get(context: Context): LandmarkDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    LandmarkDatabase::class.java,
                    "landmarks.db"
                ).build().also { INSTANCE = it }
            }
    }
}
