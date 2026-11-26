package com.example.print

import android.net.Uri
import android.print.PrintAttributes
import android.print.PrintJobInfo
import android.print.PrinterCapabilitiesInfo
import android.print.PrinterId
import android.print.PrinterInfo
import android.printservice.PrintJob
import android.printservice.PrintService
import android.printservice.PrinterDiscoverySession
import com.example.LanPrintApplication
import com.example.domain.model.ColorMode
import com.example.domain.model.ContentType
import com.example.domain.model.DuplexMode
import com.example.domain.model.PaperSize
import com.example.domain.model.PrintOptions
import com.example.domain.model.PrintableDocument
import com.example.domain.model.PrinterDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class LanPrintService : PrintService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var cachedPrinters: List<PrinterDevice> = emptyList()

    override fun onCreatePrinterDiscoverySession(): PrinterDiscoverySession {
        return object : PrinterDiscoverySession() {
            override fun onStartPrinterDiscovery(priorityList: MutableList<PrinterId>) {
                serviceScope.launch {
                    val app = application as LanPrintApplication
                    val saved = app.repository.savedPrinters.first()
                    cachedPrinters = saved
                    val androidPrinters = saved.map { device ->
                        val pid = generatePrinterId(device.id)
                        val caps = PrinterCapabilitiesInfo.Builder(pid)
                            .addMediaSize(PrintAttributes.MediaSize.ISO_A4, true)
                            .addMediaSize(PrintAttributes.MediaSize.ISO_A3, false)
                            .addMediaSize(PrintAttributes.MediaSize.NA_LETTER, false)
                            .addResolution(PrintAttributes.Resolution("300dpi", "300 DPI", 300, 300), true)
                            .setColorModes(
                                if (device.capabilities.supportsColor) {
                                    PrintAttributes.COLOR_MODE_COLOR or PrintAttributes.COLOR_MODE_MONOCHROME
                                } else {
                                    PrintAttributes.COLOR_MODE_MONOCHROME
                                },
                                PrintAttributes.COLOR_MODE_COLOR
                            )
                            .build()

                        PrinterInfo.Builder(pid, device.name, PrinterInfo.STATUS_IDLE)
                            .setDescription(device.endpointLabel)
                            .setCapabilities(caps)
                            .build()
                    }
                    addPrinters(androidPrinters)
                }
            }

            override fun onStopPrinterDiscovery() {}
            override fun onValidatePrinters(printerIds: MutableList<PrinterId>) {}
            override fun onStartPrinterStateTracking(printerId: PrinterId) {}
            override fun onStopPrinterStateTracking(printerId: PrinterId) {}
            override fun onDestroy() {}
        }
    }

    override fun onRequestCancelPrintJob(printJob: PrintJob) {
        printJob.cancel()
    }

    override fun onPrintJobQueued(printJob: PrintJob) {
        printJob.start()
        val info: PrintJobInfo = printJob.info
        val targetId = info.printerId?.localId
        val pfd = printJob.document.data

        if (pfd == null) {
            printJob.fail("Dokumen cetak tidak tersedia")
            return
        }

        serviceScope.launch {
            try {
                val app = application as LanPrintApplication
                val saved = cachedPrinters.ifEmpty { app.repository.savedPrinters.first() }
                val device = saved.firstOrNull { it.id == targetId } ?: saved.firstOrNull()
                if (device == null) {
                    printJob.fail("Belum ada printer tersimpan di LAN Print")
                    return@launch
                }

                val tempPdf = File(cacheDir, "sysprint_${System.currentTimeMillis()}.pdf")
                FileInputStream(pfd.fileDescriptor).use { input ->
                    FileOutputStream(tempPdf).use { output ->
                        input.copyTo(output)
                    }
                }
                pfd.close()

                val attrs = info.attributes
                val paper = when (attrs?.mediaSize) {
                    PrintAttributes.MediaSize.ISO_A3 -> PaperSize.A3
                    PrintAttributes.MediaSize.ISO_A5 -> PaperSize.A5
                    PrintAttributes.MediaSize.NA_LETTER -> PaperSize.LETTER
                    PrintAttributes.MediaSize.NA_LEGAL -> PaperSize.LEGAL
                    else -> PaperSize.A4
                }
                val color = if (attrs?.colorMode == PrintAttributes.COLOR_MODE_MONOCHROME) {
                    ColorMode.MONOCHROME
                } else {
                    ColorMode.COLOR
                }
                val duplex = when (attrs?.duplexMode) {
                    PrintAttributes.DUPLEX_MODE_LONG_EDGE -> DuplexMode.LONG_EDGE
                    PrintAttributes.DUPLEX_MODE_SHORT_EDGE -> DuplexMode.SHORT_EDGE
                    else -> DuplexMode.OFF
                }

                val options = PrintOptions(
                    paperSize = paper,
                    colorMode = color,
                    duplex = duplex,
                    copies = info.copies.coerceIn(1, 999)
                )

                val doc = PrintableDocument(
                    id = "sys_${System.currentTimeMillis()}",
                    title = info.label ?: "Dokumen Sistem",
                    contentType = ContentType.SYSTEM_PRINT,
                    uris = listOf(Uri.fromFile(tempPdf)),
                    localPdfPath = tempPdf.absolutePath,
                    estimatedPages = 1
                )

                app.dispatcher.submitPrintJob(doc, device, options)
                printJob.complete()
            } catch (e: Exception) {
                printJob.fail(e.localizedMessage ?: "Gagal mencetak")
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
