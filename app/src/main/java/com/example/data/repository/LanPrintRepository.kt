package com.example.data.repository

import com.example.data.local.JsonConverters
import com.example.data.local.LanPrintDatabase
import com.example.data.local.PrintHistoryEntity
import com.example.data.local.PrintPresetEntity
import com.example.data.local.SavedPrinterEntity
import com.example.domain.model.ColorMode
import com.example.domain.model.ContentType
import com.example.domain.model.DuplexMode
import com.example.domain.model.MarginType
import com.example.domain.model.MediaType
import com.example.domain.model.OptimizeContent
import com.example.domain.model.PaperSize
import com.example.domain.model.PrintHistoryItem
import com.example.domain.model.PrintJobStatus
import com.example.domain.model.PrintOptions
import com.example.domain.model.PrintPreset
import com.example.domain.model.PrintQuality
import com.example.domain.model.PrinterDevice
import com.example.domain.model.PrinterProtocol
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class LanPrintRepository(private val db: LanPrintDatabase) {

    private val printerDao = db.savedPrinterDao()
    private val presetDao = db.printPresetDao()
    private val historyDao = db.printHistoryDao()

    val savedPrinters: Flow<List<PrinterDevice>> = printerDao.observeAll().map { list ->
        list.map { it.toDomain() }
    }

    val presets: Flow<List<PrintPreset>> = presetDao.observeAll().map { list ->
        list.map { it.toDomain() }
    }

    val printHistory: Flow<List<PrintHistoryItem>> = historyDao.observeRecent().map { list ->
        list.map { it.toDomain() }
    }

    suspend fun ensureDefaultPresets() {
        if (presetDao.count() == 0) {
            val defaults = listOf(
                PrintPresetEntity(
                    name = "Dokumen Hemat",
                    description = "A4 • Hitam-Putih • Draft • Hemat Toner • 2 Sisi",
                    optionsJson = JsonConverters.optionsToJson(
                        PrintOptions(
                            paperSize = PaperSize.A4,
                            colorMode = ColorMode.MONOCHROME,
                            quality = PrintQuality.DRAFT,
                            tonerSave = true,
                            duplex = DuplexMode.LONG_EDGE,
                            contentOptimize = OptimizeContent.TEXT
                        )
                    ),
                    isBuiltIn = true
                ),
                PrintPresetEntity(
                    name = "Foto Tajam",
                    description = "Foto 4×6\" • Warna • Kualitas Tinggi • Tanpa Tepi",
                    optionsJson = JsonConverters.optionsToJson(
                        PrintOptions(
                            paperSize = PaperSize.PHOTO_4X6,
                            mediaType = MediaType.GLOSSY,
                            marginType = MarginType.BORDERLESS,
                            colorMode = ColorMode.COLOR,
                            quality = PrintQuality.HIGH,
                            dpi = 600,
                            contentOptimize = OptimizeContent.PHOTO,
                            autoEnhance = true
                        )
                    ),
                    isBuiltIn = true
                ),
                PrintPresetEntity(
                    name = "Fotokopi F4 Bersih",
                    description = "F4 (215×330mm) • Bersihkan Latar • Hitamkan Teks",
                    optionsJson = JsonConverters.optionsToJson(
                        PrintOptions(
                            paperSize = PaperSize.F4,
                            colorMode = ColorMode.MONOCHROME,
                            quality = PrintQuality.NORMAL,
                            cleanBackground = true,
                            darkenText = true,
                            contrast = 20,
                            sharpness = 25
                        )
                    ),
                    isBuiltIn = true
                )
            )
            presetDao.insertAll(defaults)
        }
    }

    suspend fun savePrinter(printer: PrinterDevice, options: PrintOptions? = null) {
        val existing = printerDao.getById(printer.id)
        val optionsJson = options?.let { JsonConverters.optionsToJson(it) }
            ?: existing?.lastOptionsJson
            ?: JsonConverters.optionsToJson(PrintOptions())
        printerDao.upsert(
            SavedPrinterEntity(
                id = printer.id,
                name = printer.name,
                host = printer.host,
                port = printer.port,
                path = printer.path,
                protocolName = printer.protocol.name,
                companionPrinterName = printer.companionPrinterName,
                pairingToken = printer.pairingToken ?: existing?.pairingToken,
                tlsCertFingerprint = printer.tlsCertFingerprint ?: existing?.tlsCertFingerprint,
                capabilitiesJson = JsonConverters.capabilitiesToJson(printer.capabilities),
                lastOptionsJson = optionsJson,
                isDefault = printer.isDefault || (existing?.isDefault == true),
                lastUsedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun setDefaultPrinter(id: String) {
        printerDao.setDefaultPrinter(id)
    }

    suspend fun getLastOptionsForPrinter(printerId: String): PrintOptions {
        val entity = printerDao.getById(printerId) ?: return PrintOptions()
        return JsonConverters.optionsFromJson(entity.lastOptionsJson)
    }

    suspend fun saveLastOptionsForPrinter(printerId: String, options: PrintOptions) {
        printerDao.updateLastOptions(
            id = printerId,
            optionsJson = JsonConverters.optionsToJson(options),
            usedAt = System.currentTimeMillis()
        )
    }

    suspend fun deletePrinter(id: String) {
        printerDao.deleteById(id)
    }

    suspend fun savePreset(name: String, description: String, options: PrintOptions) {
        presetDao.insert(
            PrintPresetEntity(
                name = name.trim(),
                description = description,
                optionsJson = JsonConverters.optionsToJson(options),
                isBuiltIn = false
            )
        )
    }

    suspend fun deletePreset(id: Long) {
        presetDao.deleteById(id)
    }

    suspend fun addHistory(item: PrintHistoryItem) {
        historyDao.insert(
            PrintHistoryEntity(
                jobTitle = item.jobTitle,
                printerName = item.printerName,
                printerEndpoint = item.printerEndpoint,
                contentTypeName = item.contentType.name,
                statusName = item.status.name,
                pageCount = item.pageCount,
                copies = item.copies,
                optionsSummary = item.optionsSummary,
                errorMessage = item.errorMessage,
                createdAt = item.createdAt
            )
        )
    }

    suspend fun deleteHistory(id: Long) {
        historyDao.deleteById(id)
    }

    suspend fun clearHistory() {
        historyDao.clearAll()
    }

    private fun SavedPrinterEntity.toDomain(): PrinterDevice {
        val proto = runCatching { PrinterProtocol.valueOf(protocolName) }.getOrDefault(PrinterProtocol.IPP)
        return PrinterDevice(
            id = id,
            name = name,
            host = host,
            port = port,
            path = path,
            protocol = proto,
            companionPrinterName = companionPrinterName,
            pairingToken = pairingToken,
            tlsCertFingerprint = tlsCertFingerprint,
            capabilities = JsonConverters.capabilitiesFromJson(capabilitiesJson),
            isSaved = true,
            isDefault = isDefault,
            lastUsedAt = lastUsedAt
        )
    }

    private fun PrintPresetEntity.toDomain(): PrintPreset {
        return PrintPreset(
            id = id,
            name = name,
            description = description,
            options = JsonConverters.optionsFromJson(optionsJson),
            isBuiltIn = isBuiltIn
        )
    }

    private fun PrintHistoryEntity.toDomain(): PrintHistoryItem {
        val ct = runCatching { ContentType.valueOf(contentTypeName) }.getOrDefault(ContentType.PDF)
        val st = runCatching { PrintJobStatus.valueOf(statusName) }.getOrDefault(PrintJobStatus.DONE)
        return PrintHistoryItem(
            id = id,
            jobTitle = jobTitle,
            printerName = printerName,
            printerEndpoint = printerEndpoint,
            contentType = ct,
            status = st,
            pageCount = pageCount,
            copies = copies,
            optionsSummary = optionsSummary,
            errorMessage = errorMessage,
            createdAt = createdAt
        )
    }
}
