package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SavedPrinterDao {
    @Query("SELECT * FROM saved_printers ORDER BY isDefault DESC, lastUsedAt DESC, name ASC")
    fun observeAll(): Flow<List<SavedPrinterEntity>>

    @Query("SELECT * FROM saved_printers WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): SavedPrinterEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(printer: SavedPrinterEntity)

    @Query("UPDATE saved_printers SET isDefault = CASE WHEN id = :id THEN 1 ELSE 0 END")
    suspend fun setDefaultPrinter(id: String)

    @Query("UPDATE saved_printers SET lastOptionsJson = :optionsJson, lastUsedAt = :usedAt WHERE id = :id")
    suspend fun updateLastOptions(id: String, optionsJson: String, usedAt: Long)

    @Query("DELETE FROM saved_printers WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface PrintPresetDao {
    @Query("SELECT * FROM print_presets ORDER BY isBuiltIn DESC, id ASC")
    fun observeAll(): Flow<List<PrintPresetEntity>>

    @Query("SELECT COUNT(*) FROM print_presets")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(preset: PrintPresetEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(presets: List<PrintPresetEntity>)

    @Query("DELETE FROM print_presets WHERE id = :id")
    suspend fun deleteById(id: Long)
}

@Dao
interface PrintHistoryDao {
    @Query("SELECT * FROM print_history ORDER BY createdAt DESC LIMIT 100")
    fun observeRecent(): Flow<List<PrintHistoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: PrintHistoryEntity): Long

    @Query("DELETE FROM print_history WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM print_history")
    suspend fun clearAll()
}
