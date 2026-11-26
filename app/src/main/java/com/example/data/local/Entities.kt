package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "saved_printers")
data class SavedPrinterEntity(
    @PrimaryKey val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val path: String,
    val protocolName: String,
    val companionPrinterName: String?,
    val pairingToken: String?,
    val tlsCertFingerprint: String?,
    val capabilitiesJson: String,
    val lastOptionsJson: String,
    val isDefault: Boolean,
    val lastUsedAt: Long
)

@Entity(tableName = "print_presets")
data class PrintPresetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String,
    val optionsJson: String,
    val isBuiltIn: Boolean,
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "print_history")
data class PrintHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val jobTitle: String,
    val printerName: String,
    val printerEndpoint: String,
    val contentTypeName: String,
    val statusName: String,
    val pageCount: Int,
    val copies: Int,
    val optionsSummary: String,
    val errorMessage: String?,
    val createdAt: Long = System.currentTimeMillis()
)
