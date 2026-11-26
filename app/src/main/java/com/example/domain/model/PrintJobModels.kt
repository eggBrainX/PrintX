package com.example.domain.model

import android.net.Uri

enum class ContentType(val label: String) {
    IMAGE("Gambar / Foto"),
    PDF("Dokumen PDF"),
    OFFICE_DOC("Dokumen Office"),
    WEB_PAGE("Halaman Web"),
    TEXT_EMAIL("Teks / Email"),
    CONTACTS("Kontak"),
    CALENDAR("Agenda Kalender"),
    TEST_PAGE("Halaman Uji Cetak"),
    SYSTEM_PRINT("Cetak Sistem")
}

enum class PrintJobStatus(val label: String, val isTerminal: Boolean) {
    QUEUED("Dalam Antrian", false),
    RENDERING("Merender Halaman", false),
    CONVERTING("Mengonversi di Laptop", false),
    SENDING("Mengirim ke Printer", false),
    PRINTING("Sedang Mencetak", false),
    DONE("Selesai Dicetak", true),
    FAILED("Gagal", true),
    CANCELLED("Dibatalkan", true)
}

data class PrintableDocument(
    val id: String,
    val title: String,
    val contentType: ContentType,
    val uris: List<Uri> = emptyList(),
    val localPdfPath: String? = null,
    val rawTextOrHtml: String? = null,
    val isHtml: Boolean = false,
    val sourceMimeType: String = "application/pdf",
    val originalFileName: String = title,
    val estimatedPages: Int = 1,
    val requiresCompanionConversion: Boolean = false
)

data class ActivePrintJob(
    val localId: String,
    val remoteJobId: String? = null,
    val title: String,
    val printerName: String,
    val printerId: String,
    val contentType: ContentType,
    val status: PrintJobStatus,
    val progress: Float = 0f,
    val statusDetail: String = "",
    val pageCount: Int = 1,
    val copies: Int = 1,
    val startedAt: Long = System.currentTimeMillis()
)

data class PrintPreset(
    val id: Long = 0,
    val name: String,
    val description: String,
    val options: PrintOptions,
    val isBuiltIn: Boolean = false
)

data class PrintHistoryItem(
    val id: Long = 0,
    val jobTitle: String,
    val printerName: String,
    val printerEndpoint: String,
    val contentType: ContentType,
    val status: PrintJobStatus,
    val pageCount: Int,
    val copies: Int,
    val optionsSummary: String,
    val errorMessage: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)
