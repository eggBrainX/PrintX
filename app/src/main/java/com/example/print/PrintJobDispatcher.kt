package com.example.print

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.example.R
import com.example.data.repository.LanPrintRepository
import com.example.domain.model.ActivePrintJob
import com.example.domain.model.ContentType
import com.example.domain.model.PrintHistoryItem
import com.example.domain.model.PrintJobStatus
import com.example.domain.model.PrintOptions
import com.example.domain.model.PrintableDocument
import com.example.domain.model.PrinterDevice
import com.example.domain.model.PrinterProtocol
import com.example.network.CompanionClient
import com.example.network.IppClient
import com.example.network.RawAndLprClient
import com.example.rendering.PdfComposer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class PrintJobDispatcher(
    private val context: Context,
    private val repository: LanPrintRepository,
    val ippClient: IppClient,
    val rawAndLprClient: RawAndLprClient,
    val companionClient: CompanionClient
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runningJobs = ConcurrentHashMap<String, Job>()

    private val _activeJobs = MutableStateFlow<List<ActivePrintJob>>(emptyList())
    val activeJobs: StateFlow<List<ActivePrintJob>> = _activeJobs.asStateFlow()

    init {
        createNotificationChannel()
    }

    fun submitPrintJob(
        document: PrintableDocument,
        printer: PrinterDevice,
        options: PrintOptions
    ): String {
        val localId = UUID.randomUUID().toString()
        val initialJob = ActivePrintJob(
            localId = localId,
            title = document.title,
            printerName = printer.name,
            printerId = printer.id,
            contentType = document.contentType,
            status = PrintJobStatus.QUEUED,
            progress = 0.05f,
            statusDetail = "Menyiapkan dokumen...",
            pageCount = document.estimatedPages,
            copies = options.copies
        )
        _activeJobs.update { listOf(initialJob) + it }
        startForegroundIfPossible(document.title)

        val job = scope.launch {
            var generatedFile: File? = null
            try {
                repository.saveLastOptionsForPrinter(printer.id, options)

                updateJob(localId) {
                    it.copy(
                        status = PrintJobStatus.RENDERING,
                        progress = 0.15f,
                        statusDetail = if (document.requiresCompanionConversion) {
                            "Menyiapkan file Office untuk konversi di laptop..."
                        } else {
                            "Merender halaman sesuai pengaturan..."
                        }
                    )
                }

                val isDirectPdfCapable = printer.protocol.isViaLaptop ||
                    printer.capabilities.supportedMimeTypes.any { it.equals("application/pdf", true) }

                generatedFile = PdfComposer.buildOutputPdf(
                    context = context,
                    doc = document,
                    options = options,
                    targetPrinterIsDirectPdfCapable = isDirectPdfCapable,
                    onProgress = { renderProg ->
                        updateJob(localId) {
                            it.copy(progress = 0.15f + renderProg * 0.35f)
                        }
                    }
                )

                val totalPages = if (document.contentType == ContentType.OFFICE_DOC) {
                    document.estimatedPages
                } else {
                    PdfComposer.getPageCount(context, document.copy(localPdfPath = generatedFile.absolutePath, contentType = ContentType.PDF))
                }

                updateJob(localId) {
                    it.copy(
                        pageCount = totalPages,
                        status = PrintJobStatus.SENDING,
                        progress = 0.55f,
                        statusDetail = "Mengirim ke ${printer.name} (${printer.protocol.label})..."
                    )
                }

                when (printer.protocol) {
                    PrinterProtocol.COMPANION_HTTP, PrinterProtocol.COMPANION_HTTPS -> {
                        val submitResult = companionClient.submitJob(
                            printer = printer,
                            file = generatedFile,
                            fileName = generatedFile.name,
                            mimeType = if (document.contentType == ContentType.OFFICE_DOC) document.sourceMimeType else "application/pdf",
                            options = options
                        )
                        val resp = submitResult.getOrThrow()
                        updateJob(localId) {
                            it.copy(
                                remoteJobId = resp.jobId,
                                status = PrintJobStatus.CONVERTING,
                                progress = 0.7f,
                                statusDetail = resp.message ?: "Diterima oleh Companion Server (${resp.jobId})"
                            )
                        }
                        pollCompanionJob(localId, printer, resp.jobId, document, totalPages, options)
                    }

                    PrinterProtocol.IPP, PrinterProtocol.IPPS -> {
                        val docBytes = generatedFile.readBytes()
                        val ippRes = ippClient.printJob(
                            ippUrl = printer.ippHttpUrl,
                            documentBytes = docBytes,
                            mimeType = "application/pdf",
                            jobName = document.title,
                            options = options,
                            capabilities = printer.capabilities
                        ).getOrThrow()

                        if (!ippRes.success) {
                            throw IllegalStateException(ippRes.statusMessage)
                        }
                        finishJobSuccess(
                            localId = localId,
                            remoteJobId = ippRes.jobId?.toString(),
                            document = document,
                            printer = printer,
                            pageCount = totalPages,
                            options = options,
                            detail = ippRes.statusMessage
                        )
                    }

                    PrinterProtocol.RAW_9100 -> {
                        repeat(options.copies.coerceIn(1, 50)) { copyIdx ->
                            rawAndLprClient.sendRaw9100(
                                host = printer.host,
                                port = printer.port,
                                file = generatedFile,
                                onProgress = { p ->
                                    updateJob(localId) {
                                        it.copy(
                                            progress = 0.55f + p * 0.4f,
                                            statusDetail = "Mengirim salinan ${copyIdx + 1}/${options.copies} via RAW 9100..."
                                        )
                                    }
                                }
                            ).getOrThrow()
                        }
                        finishJobSuccess(
                            localId = localId,
                            remoteJobId = null,
                            document = document,
                            printer = printer,
                            pageCount = totalPages,
                            options = options,
                            detail = "Terkirim via RAW Port ${printer.port}"
                        )
                    }

                    PrinterProtocol.LPR_515 -> {
                        repeat(options.copies.coerceIn(1, 50)) { copyIdx ->
                            rawAndLprClient.sendLpr515(
                                host = printer.host,
                                port = printer.port,
                                queueName = printer.path.trimStart('/'),
                                jobName = document.title,
                                file = generatedFile,
                                onProgress = { p ->
                                    updateJob(localId) {
                                        it.copy(
                                            progress = 0.55f + p * 0.4f,
                                            statusDetail = "Mengirim salinan ${copyIdx + 1}/${options.copies} via LPR..."
                                        )
                                    }
                                }
                            ).getOrThrow()
                        }
                        finishJobSuccess(
                            localId = localId,
                            remoteJobId = null,
                            document = document,
                            printer = printer,
                            pageCount = totalPages,
                            options = options,
                            detail = "Terkirim via LPR Port ${printer.port}"
                        )
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                markJobTerminal(localId, PrintJobStatus.CANCELLED, "Dibatalkan oleh pengguna", document, printer, options, e.message)
            } catch (e: Exception) {
                val msg = e.localizedMessage ?: "Gagal mengirim ke printer"
                markJobTerminal(localId, PrintJobStatus.FAILED, msg, document, printer, options, msg)
                showResultNotification(document.title, "Gagal mencetak: $msg", isError = true)
            } finally {
                runningJobs.remove(localId)
                stopForegroundIfIdle()
            }
        }
        runningJobs[localId] = job
        return localId
    }

    private suspend fun pollCompanionJob(
        localId: String,
        printer: PrinterDevice,
        remoteJobId: String,
        document: PrintableDocument,
        pageCount: Int,
        options: PrintOptions
    ) {
        repeat(60) {
            delay(1500)
            val statusRes = companionClient.getJobStatus(printer, remoteJobId).getOrNull() ?: return@repeat
            when (statusRes.status.lowercase()) {
                "queued" -> updateJob(localId) {
                    it.copy(status = PrintJobStatus.QUEUED, progress = 0.72f, statusDetail = statusRes.message ?: "Dalam antrian laptop...")
                }
                "converting" -> updateJob(localId) {
                    it.copy(status = PrintJobStatus.CONVERTING, progress = 0.8f, statusDetail = statusRes.message ?: "Mengonversi dengan LibreOffice...")
                }
                "printing" -> updateJob(localId) {
                    it.copy(status = PrintJobStatus.PRINTING, progress = 0.9f, statusDetail = statusRes.message ?: "Mencetak ke printer USB...")
                }
                "done", "completed" -> {
                    finishJobSuccess(localId, remoteJobId, document, printer, pageCount, options, statusRes.message ?: "Selesai dicetak melalui laptop")
                    return
                }
                "error", "failed" -> {
                    throw IllegalStateException(statusRes.message ?: "Printer laptop melaporkan error")
                }
            }
        }
        finishJobSuccess(localId, remoteJobId, document, printer, pageCount, options, "Dikirim ke antrian laptop")
    }

    private suspend fun finishJobSuccess(
        localId: String,
        remoteJobId: String?,
        document: PrintableDocument,
        printer: PrinterDevice,
        pageCount: Int,
        options: PrintOptions,
        detail: String
    ) {
        updateJob(localId) {
            it.copy(
                remoteJobId = remoteJobId ?: it.remoteJobId,
                status = PrintJobStatus.DONE,
                progress = 1f,
                statusDetail = detail
            )
        }
        repository.addHistory(
            PrintHistoryItem(
                jobTitle = document.title,
                printerName = printer.name,
                printerEndpoint = printer.endpointLabel,
                contentType = document.contentType,
                status = PrintJobStatus.DONE,
                pageCount = pageCount,
                copies = options.copies,
                optionsSummary = summarizeOptions(options)
            )
        )
        showResultNotification(document.title, "${printer.name}: $detail", isError = false)
    }

    private suspend fun markJobTerminal(
        localId: String,
        status: PrintJobStatus,
        detail: String,
        document: PrintableDocument,
        printer: PrinterDevice,
        options: PrintOptions,
        errorMsg: String?
    ) {
        updateJob(localId) {
            it.copy(status = status, statusDetail = detail)
        }
        repository.addHistory(
            PrintHistoryItem(
                jobTitle = document.title,
                printerName = printer.name,
                printerEndpoint = printer.endpointLabel,
                contentType = document.contentType,
                status = status,
                pageCount = document.estimatedPages,
                copies = options.copies,
                optionsSummary = summarizeOptions(options),
                errorMessage = errorMsg
            )
        )
    }

    fun cancelJob(localId: String, printer: PrinterDevice?) {
        val active = _activeJobs.value.firstOrNull { it.localId == localId }
        runningJobs[localId]?.cancel()
        if (active?.remoteJobId != null && printer != null) {
            scope.launch {
                when (printer.protocol) {
                    PrinterProtocol.COMPANION_HTTP, PrinterProtocol.COMPANION_HTTPS ->
                        companionClient.cancelJob(printer, active.remoteJobId)
                    PrinterProtocol.IPP, PrinterProtocol.IPPS ->
                        active.remoteJobId.toIntOrNull()?.let { ippClient.cancelJob(printer.ippHttpUrl, it) }
                    else -> Unit
                }
            }
        }
        updateJob(localId) {
            it.copy(status = PrintJobStatus.CANCELLED, statusDetail = "Dibatalkan")
        }
    }

    fun dismissCompletedJobs() {
        _activeJobs.update { list -> list.filter { !it.status.isTerminal } }
    }

    private fun updateJob(localId: String, transform: (ActivePrintJob) -> ActivePrintJob) {
        _activeJobs.update { list ->
            list.map { if (it.localId == localId) transform(it) else it }
        }
    }

    fun summarizeOptions(options: PrintOptions): String {
        val parts = mutableListOf<String>()
        parts += options.paperSize.label
        parts += options.colorMode.label
        parts += "${options.copies}x"
        if (options.duplex != com.example.domain.model.DuplexMode.OFF) parts += options.duplex.label
        if (options.pagesPerSheet > 1) parts += "${options.pagesPerSheet}-up"
        return parts.joinToString(" • ")
    }

    private fun startForegroundIfPossible(title: String) {
        runCatching {
            val intent = Intent(context, SendJobService::class.java).apply {
                putExtra(SendJobService.EXTRA_JOB_TITLE, title)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    private fun stopForegroundIfIdle() {
        if (_activeJobs.value.none { !it.status.isTerminal }) {
            runCatching { context.stopService(Intent(context, SendJobService::class.java)) }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.channel_print_jobs_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.channel_print_jobs_desc)
            }
            nm.createNotificationChannel(channel)
        }
    }

    private fun showResultNotification(title: String, message: String, isError: Boolean) {
        runCatching {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val notif = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_send)
                .setContentTitle(if (isError) "Gagal Cetak: $title" else "Cetak Selesai: $title")
                .setContentText(message)
                .setAutoCancel(true)
                .build()
            nm.notify(System.currentTimeMillis().toInt(), notif)
        }
    }

    companion object {
        const val CHANNEL_ID = "lan_print_jobs"
    }
}
