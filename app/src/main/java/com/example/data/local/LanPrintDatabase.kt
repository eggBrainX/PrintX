package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        SavedPrinterEntity::class,
        PrintPresetEntity::class,
        PrintHistoryEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class LanPrintDatabase : RoomDatabase() {
    abstract fun savedPrinterDao(): SavedPrinterDao
    abstract fun printPresetDao(): PrintPresetDao
    abstract fun printHistoryDao(): PrintHistoryDao

    companion object {
        @Volatile
        private var INSTANCE: LanPrintDatabase? = null

        fun getInstance(context: Context): LanPrintDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    LanPrintDatabase::class.java,
                    "lan_print.db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
