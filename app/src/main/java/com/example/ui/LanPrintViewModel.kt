package com.example.ui

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.LanPrintApplication
import com.example.domain.model.ActivePrintJob
import com.example.domain.model.CardGridPreset
import com.example.domain.model.ContentType
import com.example.domain.model.PrintHistoryItem
import com.example.domain.model.PrintJobStatus
import com.example.domain.model.PrintOptions
import com.example.domain.model.PrintPreset
import com.example.domain.model.PrintableDocument
import com.example.domain.model.PrinterCapabilities
import com.example.domain.model.PrinterDevice
import com.example.domain.model.PrinterProtocol
import com.example.network.EsclScannerInfo
import com.example.network.ImapEmailSummary
import com.example.network.NetworkStatus
import com.example.print.DeviceContentReader
import com.example.rendering.CalendarEventEntry
import com.example.rendering.ContactEntry
import com.example.rendering.DocumentGenerators
import com.example.rendering.PdfComposer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Calendar
import java.util.UUID

enum class AppTab(val route: String, val label: String) {
    PRINT_HUB("print_hub", "Cetak"),
    PRINTERS("printers", "Printer"),
    HISTORY("history", "Riwayat")
}

object AppRoutes {
    const val PRINT_HUB = "print_hub"
    const val PRINTERS = "printers"
    const val HISTORY = "history"
    const val PRINT_OPTIONS = "print_options"
    const val WEB_PRINT = "web_print"
    const val CONTACTS_PRINT = "contacts_print"
    const val CALENDAR_PRINT = "calendar_print"
    const val TEXT_EMAIL_PRINT = "text_email_print"
    const val CAMERA_SCAN = "camera_scan"
    const val NETWORK_SCANNER = "network_scanner"
    const val THERMAL_PRINT = "thermal_print"
    const val IMAP_EMAIL = "imap_email"
}

class LanPrintViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as LanPrintApplication
    private val repo = app.repository
    private val discovery = app.discoveryManager
    private val dispatcher = app.dispatcher
    private val ippClient = app.ippClient
    private val rawLprClient = app.rawAndLprClient
    private val companionClient = app.companionClient
    private val esclClient = app.esclScannerClient
    private val imapClient = app.imapClient

    val savedPrinters: StateFlow<List<PrinterDevice>> = repo.savedPrinters
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val presets: StateFlow<List<PrintPreset>> = repo.presets
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val printHistory: StateFlow<List<PrintHistoryItem>> = repo.printHistory
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val discoveredPrinters: StateFlow<List<PrinterDevice>> = discovery.discoveredPrinters
    val discoveredScanners: StateFlow<List<EsclScannerInfo>> = discovery.discoveredScanners
    val isScanning: StateFlow<Boolean> = discovery.isScanning
    val scanMessage: StateFlow<String> = discovery.scanMessage
    val activeJobs: StateFlow<List<ActivePrintJob>> = dispatcher.activeJobs

    private val _networkStatus = MutableStateFlow(discovery.getNetworkStatus())
    val networkStatus: StateFlow<NetworkStatus> = _networkStatus.asStateFlow()

    private val _isRefreshingDashboard = MutableStateFlow(false)
    val isRefreshingDashboard: StateFlow<Boolean> = _isRefreshingDashboard.asStateFlow()

    private val _selectedPrinter = MutableStateFlow<PrinterDevice?>(null)
    val selectedPrinter: StateFlow<PrinterDevice?> = _selectedPrinter.asStateFlow()

    private val _currentDocument = MutableStateFlow<PrintableDocument?>(null)
    val currentDocument: StateFlow<PrintableDocument?> = _currentDocument.asStateFlow()

    private val _currentOptions = MutableStateFlow(PrintOptions())
    val currentOptions: StateFlow<PrintOptions> = _currentOptions.asStateFlow()

    private val _previewPageIndex = MutableStateFlow(0)
    val previewPageIndex: StateFlow<Int> = _previewPageIndex.asStateFlow()

    private val _totalPages = MutableStateFlow(1)
    val totalPages: StateFlow<Int> = _totalPages.asStateFlow()

    private val _previewBitmap = MutableStateFlow<Bitmap?>(null)
    val previewBitmap: StateFlow<Bitmap?> = _previewBitmap.asStateFlow()

    private val _isRenderingPreview = MutableStateFlow(false)
    val isRenderingPreview: StateFlow<Boolean> = _isRenderingPreview.asStateFlow()

    private val _contacts = MutableStateFlow<List<ContactEntry>>(emptyList())
    val contacts: StateFlow<List<ContactEntry>> = _contacts.asStateFlow()

    private val _calendarEvents = MutableStateFlow<List<CalendarEventEntry>>(emptyList())
    val calendarEvents: StateFlow<List<CalendarEventEntry>> = _calendarEvents.asStateFlow()

    private val _manualScanners = MutableStateFlow<List<EsclScannerInfo>>(emptyList())
    val manualScanners: StateFlow<List<EsclScannerInfo>> = _manualScanners.asStateFlow()

    private val _isScanningDoc = MutableStateFlow(false)
    val isScanningDoc: StateFlow<Boolean> = _isScanningDoc.asStateFlow()

    private val _lastScannedFile = MutableStateFlow<File?>(null)
    val lastScannedFile: StateFlow<File?> = _lastScannedFile.asStateFlow()

    private val _imapEmails = MutableStateFlow<List<ImapEmailSummary>>(emptyList())
    val imapEmails: StateFlow<List<ImapEmailSummary>> = _imapEmails.asStateFlow()

    private val _isFetchingImap = MutableStateFlow(false)
    val isFetchingImap: StateFlow<Boolean> = _isFetchingImap.asStateFlow()

    private val _pendingNavEvent = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val pendingNavEvent: SharedFlow<String> = _pendingNavEvent.asSharedFlow()

    private val _snackbarMessage = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val snackbarMessage: SharedFlow<String> = _snackbarMessage.asSharedFlow()

    private val _isProbingManual = MutableStateFlow(false)
    val isProbingManual: StateFlow<Boolean> = _isProbingManual.asStateFlow()

    private var previewRenderJob: Job? = null

    init {
        viewModelScope.launch {
            savedPrinters.collect { list ->
                val cur = _selectedPrinter.value
                if (cur == null && list.isNotEmpty()) {
                    val def = list.firstOrNull { it.isDefault } ?: list.first()
                    selectPrinter(def)
                } else if (cur != null) {
                    val updated = list.firstOrNull { it.id == cur.id }
                    if (updated != null && updated != cur) {
                        _selectedPrinter.value = updated
                    }
                }
            }
        }
    }

    fun refreshNetworkStatus() {
        _networkStatus.value = discovery.getNetworkStatus()
    }

    fun refreshDashboardStatus() {
        viewModelScope.launch {
            _isRefreshingDashboard.value = true
            refreshNetworkStatus()
            val printers = savedPrinters.value
            printers.forEach { printer ->
                val updated = discovery.probePrinterStatus(printer)
                repo.savePrinter(updated)
                if (_selectedPrinter.value?.id == updated.id) {
                    _selectedPrinter.value = updated
                }
            }
            _isRefreshingDashboard.value = false
        }
    }

    fun startDiscovery(includeSubnetScan: Boolean = true) {
        refreshNetworkStatus()
        discovery.startDiscovery(viewModelScope, includeSubnetScan)
    }

    fun stopDiscovery() {
        discovery.stopDiscovery()
    }

    fun selectPrinter(printer: PrinterDevice) {
        _selectedPrinter.value = printer
        viewModelScope.launch {
            repo.savePrinter(printer)
            val savedOpts = repo.getLastOptionsForPrinter(printer.id)
            _currentOptions.value = savedOpts
            schedulePreviewRender()
            // Probe live ink/paper status in background
            val probed = discovery.probePrinterStatus(printer)
            repo.savePrinter(probed)
            if (_selectedPrinter.value?.id == probed.id) {
                _selectedPrinter.value = probed
            }
        }
    }

    fun setDefaultPrinter(printer: PrinterDevice) {
        viewModelScope.launch {
            repo.savePrinter(printer.copy(isDefault = true))
            repo.setDefaultPrinter(printer.id)
            _selectedPrinter.value = printer.copy(isDefault = true)
            _snackbarMessage.tryEmit("${printer.name} dijadikan printer utama")
        }
    }

    fun deleteSavedPrinter(printerId: String) {
        viewModelScope.launch {
            repo.deletePrinter(printerId)
            if (_selectedPrinter.value?.id == printerId) {
                _selectedPrinter.value = null
            }
            _snackbarMessage.tryEmit("Printer dihapus")
        }
    }

    fun addAndProbeManualPrinter(
        name: String,
        host: String,
        port: Int,
        path: String,
        protocol: PrinterProtocol,
        onRequirePin: (host: String, port: Int, useHttps: Boolean) -> Unit,
        onComplete: () -> Unit
    ) {
        viewModelScope.launch {
            _isProbingManual.value = true
            val cleanHost = host.trim()
            try {
                when (protocol) {
                    PrinterProtocol.COMPANION_HTTP, PrinterProtocol.COMPANION_HTTPS -> {
                        val useHttps = protocol == PrinterProtocol.COMPANION_HTTPS
                        val res = companionClient.fetchPrinters(cleanHost, port, useHttps)
                        res.onSuccess { list ->
                            if (list.isEmpty()) {
                                _snackbarMessage.tryEmit("Terhubung ke Companion, tapi belum ada printer terpasang di laptop")
                            } else {
                                list.forEach { repo.savePrinter(it) }
                                selectPrinter(list.first())
                                _snackbarMessage.tryEmit("${list.size} printer USB laptop berhasil ditambahkan")
                                onComplete()
                            }
                        }.onFailure { err ->
                            if (err is SecurityException) {
                                onRequirePin(cleanHost, port, useHttps)
                            } else {
                                _snackbarMessage.tryEmit("Gagal terhubung ke Companion: ${err.localizedMessage}")
                            }
                        }
                    }

                    PrinterProtocol.IPP, PrinterProtocol.IPPS -> {
                        val cleanPath = if (path.isBlank()) "/ipp/print" else if (path.startsWith("/")) path else "/$path"
                        val scheme = if (protocol == PrinterProtocol.IPPS) "https" else "http"
                        val url = "$scheme://$cleanHost:$port$cleanPath"
                        val probe = ippClient.getPrinterAttributes(url)
                        val caps = probe.getOrElse {
                            val reachable = rawLprClient.probePort(cleanHost, port)
                            if (!reachable) {
                                _snackbarMessage.tryEmit("Tidak dapat menjangkau $cleanHost:$port (${it.localizedMessage})")
                                return@launch
                            }
                            PrinterCapabilities(makeAndModel = "IPP Manual", printerStateMessage = "Port Terbuka")
                        }
                        val device = PrinterDevice(
                            id = "${protocol.name}_${cleanHost}_$port",
                            name = name.ifBlank { caps.makeAndModel.ifBlank { "Printer IPP ($cleanHost)" } },
                            host = cleanHost,
                            port = port,
                            path = cleanPath,
                            protocol = protocol,
                            capabilities = caps,
                            isSaved = true
                        )
                        repo.savePrinter(device)
                        selectPrinter(device)
                        _snackbarMessage.tryEmit("Printer ${device.name} disimpan")
                        onComplete()
                    }

                    PrinterProtocol.RAW_9100, PrinterProtocol.LPR_515 -> {
                        val reachable = rawLprClient.probePort(cleanHost, port)
                        val device = PrinterDevice(
                            id = "${protocol.name}_${cleanHost}_$port",
                            name = name.ifBlank { "${protocol.label} ($cleanHost)" },
                            host = cleanHost,
                            port = port,
                            path = path.ifBlank { "lp" },
                            protocol = protocol,
                            capabilities = PrinterCapabilities(
                                makeAndModel = protocol.label,
                                printerStateMessage = if (reachable) "Online" else "Disimpan (Belum Terjangkau)"
                            ),
                            isSaved = true,
                            isOnline = reachable
                        )
                        repo.savePrinter(device)
                        selectPrinter(device)
                        _snackbarMessage.tryEmit(
                            if (reachable) "Printer ${device.name} aktif & disimpan"
                            else "Disimpan offline — pastikan IP $cleanHost:$port menyala"
                        )
                        onComplete()
                    }
                }
            } finally {
                _isProbingManual.value = false
            }
        }
    }

    fun pairWithCompanion(host: String, port: Int, useHttps: Boolean, pin: String, onPaired: () -> Unit) {
        viewModelScope.launch {
            _isProbingManual.value = true
            try {
                val scheme = if (useHttps) "https" else "http"
                val baseUrl = "$scheme://$host:$port"
                val pairRes = companionClient.pair(baseUrl, pin)
                pairRes.onSuccess { resp ->
                    val printersRes = companionClient.fetchPrinters(host, port, useHttps, resp.token)
                    printersRes.onSuccess { list ->
                        list.forEach { repo.savePrinter(it) }
                        if (list.isNotEmpty()) selectPrinter(list.first())
                        _snackbarMessage.tryEmit("Pairing berhasil! ${list.size} printer laptop ditambahkan")
                        onPaired()
                    }.onFailure {
                        _snackbarMessage.tryEmit("Pairing berhasil, namun gagal mengambil printer: ${it.localizedMessage}")
                    }
                }.onFailure {
                    _snackbarMessage.tryEmit(it.localizedMessage ?: "PIN pairing salah")
                }
            } finally {
                _isProbingManual.value = false
            }
        }
    }

    fun openImagesForPrint(
        uris: List<Uri>,
        presetCardGrid: CardGridPreset? = null,
        cleanDocumentMode: Boolean = false
    ) {
        if (uris.isEmpty()) return
        if (presetCardGrid != null) {
            _currentOptions.update { opts ->
                opts.copy(
                    cardGridPreset = presetCardGrid,
                    cardWidthMm = presetCardGrid.defaultWidthMm,
                    cardHeightMm = presetCardGrid.defaultHeightMm,
                    cardColumns = if (presetCardGrid == CardGridPreset.KTP_ID) 1 else 3,
                    cardRows = if (presetCardGrid == CardGridPreset.KTP_ID) 3 else 4,
                    cardTwoSidedMode = uris.size >= 2,
                    cleanBackground = cleanDocumentMode || opts.cleanBackground,
                    darkenText = cleanDocumentMode || opts.darkenText
                )
            }
        } else if (cleanDocumentMode) {
            _currentOptions.update { it.copy(cleanBackground = true, darkenText = true) }
        }
        val title = if (uris.size == 1) resolveDisplayName(uris.first()) else "${uris.size} Foto"
        val doc = PrintableDocument(
            id = UUID.randomUUID().toString(),
            title = title,
            contentType = ContentType.IMAGE,
            uris = uris,
            sourceMimeType = "image/*",
            originalFileName = title,
            estimatedPages = uris.size
        )
        setDocumentAndNavigate(doc)
    }

    fun openDocumentUriForPrint(uri: Uri) {
        val name = resolveDisplayName(uri)
        val mime = app.contentResolver.getType(uri).orEmpty()
        val ext = name.substringAfterLast('.', "").lowercase()
        val isOffice = ext in setOf("doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt", "rtf") ||
            mime.contains("officedocument") || mime.contains("msword") || mime.contains("ms-excel") || mime.contains("ms-powerpoint")

        val isImage = mime.startsWith("image/") || ext in setOf("jpg", "jpeg", "png", "webp", "bmp")
        val isText = mime.startsWith("text/") || ext == "txt"

        when {
            isImage -> openImagesForPrint(listOf(uri))
            isText -> {
                viewModelScope.launch {
                    val text = withContext(Dispatchers.IO) {
                        app.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
                    }
                    openTextOrEmailForPrint(name, text, isHtml = false)
                }
            }
            isOffice -> {
                val doc = PrintableDocument(
                    id = UUID.randomUUID().toString(),
                    title = name,
                    contentType = ContentType.OFFICE_DOC,
                    uris = listOf(uri),
                    sourceMimeType = mime.ifBlank { "application/octet-stream" },
                    originalFileName = name,
                    estimatedPages = 1,
                    requiresCompanionConversion = true
                )
                setDocumentAndNavigate(doc)
            }
            else -> {
                val doc = PrintableDocument(
                    id = UUID.randomUUID().toString(),
                    title = name,
                    contentType = ContentType.PDF,
                    uris = listOf(uri),
                    sourceMimeType = "application/pdf",
                    originalFileName = name,
                    estimatedPages = 1
                )
                setDocumentAndNavigate(doc)
            }
        }
    }

    fun openTestPageForPrint() {
        viewModelScope.launch {
            val printerName = _selectedPrinter.value?.name ?: "Printer LAN"
            val pdfFile = withContext(Dispatchers.IO) {
                DocumentGenerators.generateTestPagePdf(app, printerName, _currentOptions.value)
            }
            val doc = PrintableDocument(
                id = UUID.randomUUID().toString(),
                title = "Halaman Uji Cetak",
                contentType = ContentType.TEST_PAGE,
                uris = listOf(Uri.fromFile(pdfFile)),
                localPdfPath = pdfFile.absolutePath,
                estimatedPages = 1
            )
            setDocumentAndNavigate(doc)
        }
    }

    fun openTextOrEmailForPrint(title: String, body: String, isHtml: Boolean) {
        viewModelScope.launch {
            val pdfFile = withContext(Dispatchers.IO) {
                DocumentGenerators.generateTextOrEmailPdf(app, title, body, isHtml)
            }
            val doc = PrintableDocument(
                id = UUID.randomUUID().toString(),
                title = title.ifBlank { "Catatan / Email" },
                contentType = ContentType.TEXT_EMAIL,
                uris = listOf(Uri.fromFile(pdfFile)),
                localPdfPath = pdfFile.absolutePath,
                rawTextOrHtml = body,
                isHtml = isHtml,
                estimatedPages = 1
            )
            setDocumentAndNavigate(doc)
        }
    }

    fun openWebBitmapsForPrint(title: String, snapshots: List<Bitmap>) {
        if (snapshots.isEmpty()) return
        viewModelScope.launch {
            val pdfFile = withContext(Dispatchers.IO) {
                DocumentGenerators.generatePdfFromBitmapSnapshots(app, snapshots)
            }
            val doc = PrintableDocument(
                id = UUID.randomUUID().toString(),
                title = title.ifBlank { "Halaman Web" },
                contentType = ContentType.WEB_PAGE,
                uris = listOf(Uri.fromFile(pdfFile)),
                localPdfPath = pdfFile.absolutePath,
                estimatedPages = snapshots.size
            )
            setDocumentAndNavigate(doc)
        }
    }

    fun loadContacts() {
        viewModelScope.launch {
            _contacts.value = DeviceContentReader.loadContacts(app)
        }
    }

    fun openContactsForPrint(selected: List<ContactEntry>) {
        if (selected.isEmpty()) return
        viewModelScope.launch {
            val pdfFile = withContext(Dispatchers.IO) {
                DocumentGenerators.generateContactsPdf(app, selected)
            }
            val doc = PrintableDocument(
                id = UUID.randomUUID().toString(),
                title = "Daftar Kontak (${selected.size})",
                contentType = ContentType.CONTACTS,
                uris = listOf(Uri.fromFile(pdfFile)),
                localPdfPath = pdfFile.absolutePath,
                estimatedPages = 1
            )
            setDocumentAndNavigate(doc)
        }
    }

    fun loadCalendarEvents(daysAhead: Int) {
        viewModelScope.launch {
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
            }
            val start = cal.timeInMillis
            val end = start + daysAhead * 24L * 3600_000L
            _calendarEvents.value = DeviceContentReader.loadCalendarEvents(app, start, end)
        }
    }

    fun openCalendarForPrint(events: List<CalendarEventEntry>, rangeLabel: String) {
        if (events.isEmpty()) return
        viewModelScope.launch {
            val pdfFile = withContext(Dispatchers.IO) {
                DocumentGenerators.generateCalendarPdf(app, events, rangeLabel)
            }
            val doc = PrintableDocument(
                id = UUID.randomUUID().toString(),
                title = "Agenda $rangeLabel",
                contentType = ContentType.CALENDAR,
                uris = listOf(Uri.fromFile(pdfFile)),
                localPdfPath = pdfFile.absolutePath,
                estimatedPages = 1
            )
            setDocumentAndNavigate(doc)
        }
    }

    fun probeManualEsclScanner(host: String, port: Int, useHttps: Boolean) {
        viewModelScope.launch {
            esclClient.getCapabilities(host.trim(), port, useHttps)
                .onSuccess { info ->
                    _manualScanners.update { list -> (list.filterNot { it.id == info.id } + info) }
                    _snackbarMessage.tryEmit("Scanner ${info.name} siap")
                }
                .onFailure { err ->
                    _snackbarMessage.tryEmit("Gagal terhubung ke eSCL scanner: ${err.localizedMessage}")
                }
        }
    }

    fun runNetworkScan(scanner: EsclScannerInfo, dpi: Int, colorMode: String, format: String) {
        viewModelScope.launch {
            _isScanningDoc.value = true
            esclClient.scanDocument(app, scanner, dpi, colorMode, format)
                .onSuccess { file ->
                    _lastScannedFile.value = file
                    _snackbarMessage.tryEmit("Scan selesai: ${file.name}")
                }
                .onFailure { err ->
                    _snackbarMessage.tryEmit("Gagal scan: ${err.localizedMessage}")
                }
            _isScanningDoc.value = false
        }
    }

    fun openScannedFileForPrint(file: File) {
        val uri = Uri.fromFile(file)
        if (file.extension.equals("pdf", ignoreCase = true)) {
            val doc = PrintableDocument(
                id = UUID.randomUUID().toString(),
                title = file.name,
                contentType = ContentType.PDF,
                uris = listOf(uri),
                localPdfPath = file.absolutePath,
                estimatedPages = 1
            )
            setDocumentAndNavigate(doc)
        } else {
            openImagesForPrint(listOf(uri), cleanDocumentMode = true)
        }
    }

    fun printThermalEscPos(host: String, port: Int, escPosBytes: ByteArray, title: String) {
        viewModelScope.launch {
            val tmpFile = withContext(Dispatchers.IO) {
                File(app.cacheDir, "escpos_${System.currentTimeMillis()}.bin").apply {
                    FileOutputStream(this).use { it.write(escPosBytes) }
                }
            }
            rawLprClient.sendRaw9100(host.trim(), port, tmpFile) { }
                .onSuccess {
                    repo.addHistory(
                        PrintHistoryItem(
                            jobTitle = title,
                            printerName = "Thermal ESC/POS ($host)",
                            printerEndpoint = "$host:$port (RAW 9100)",
                            contentType = ContentType.TEXT_EMAIL,
                            status = PrintJobStatus.DONE,
                            pageCount = 1,
                            copies = 1,
                            optionsSummary = "ESC/POS Thermal"
                        )
                    )
                    _snackbarMessage.tryEmit("Struk/Label berhasil dikirim ke $host:$port")
                }
                .onFailure { err ->
                    _snackbarMessage.tryEmit("Gagal mencetak ke thermal printer: ${err.localizedMessage}")
                }
        }
    }

    fun fetchImapEmails(host: String, port: Int, email: String, appPassword: String) {
        viewModelScope.launch {
            _isFetchingImap.value = true
            imapClient.fetchRecentEmails(host, port, email, appPassword)
                .onSuccess { list ->
                    _imapEmails.value = list
                    if (list.isEmpty()) _snackbarMessage.tryEmit("Kotak masuk kosong")
                }
                .onFailure { err ->
                    _snackbarMessage.tryEmit("Gagal login IMAP: ${err.localizedMessage}")
                }
            _isFetchingImap.value = false
        }
    }

    fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action ?: return
        val type = intent.type.orEmpty()

        when (action) {
            Intent.ACTION_SEND -> {
                @Suppress("DEPRECATION")
                val streamUri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
                val extraText = intent.getStringExtra(Intent.EXTRA_TEXT)
                val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT) ?: "Konten Dibagikan"
                if (streamUri != null) {
                    openDocumentUriForPrint(streamUri)
                } else if (!extraText.isNullOrBlank()) {
                    if (extraText.trim().startsWith("http://") || extraText.trim().startsWith("https://")) {
                        _pendingNavEvent.tryEmit("${AppRoutes.WEB_PRINT}?url=${Uri.encode(extraText.trim())}")
                    } else {
                        val isHtml = type.contains("html") || extraText.contains("<html", ignoreCase = true)
                        openTextOrEmailForPrint(subject, extraText, isHtml)
                    }
                }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                @Suppress("DEPRECATION")
                val uris = intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
                if (uris.isNotEmpty()) {
                    if (type.startsWith("image/")) {
                        openImagesForPrint(uris)
                    } else {
                        openDocumentUriForPrint(uris.first())
                    }
                }
            }
            Intent.ACTION_VIEW -> {
                intent.data?.let { openDocumentUriForPrint(it) }
            }
        }
    }

    private fun setDocumentAndNavigate(doc: PrintableDocument) {
        _currentDocument.value = doc
        _previewPageIndex.value = 0
        viewModelScope.launch {
            val count = PdfComposer.getPageCount(app, doc)
            _totalPages.value = count
            _currentDocument.value = doc.copy(estimatedPages = count)
            schedulePreviewRender()
            _pendingNavEvent.tryEmit(AppRoutes.PRINT_OPTIONS)
        }
    }

    fun updateOptions(transform: (PrintOptions) -> PrintOptions) {
        _currentOptions.update(transform)
        schedulePreviewRender()
    }

    fun applyPreset(preset: PrintPreset) {
        _currentOptions.value = preset.options
        schedulePreviewRender()
        _snackbarMessage.tryEmit("Preset '${preset.name}' diterapkan")
    }

    fun saveCurrentAsPreset(name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            val opts = _currentOptions.value
            val desc = "${opts.paperSize.label} • ${opts.colorMode.label} • ${opts.quality.label}"
            repo.savePreset(name, desc, opts)
            _snackbarMessage.tryEmit("Preset '$name' disimpan")
        }
    }

    fun deletePreset(presetId: Long) {
        viewModelScope.launch {
            repo.deletePreset(presetId)
        }
    }

    fun setPreviewPage(index: Int) {
        val maxIdx = (_totalPages.value - 1).coerceAtLeast(0)
        _previewPageIndex.value = index.coerceIn(0, maxIdx)
        schedulePreviewRender()
    }

    private fun schedulePreviewRender() {
        val doc = _currentDocument.value ?: return
        if (doc.contentType == ContentType.OFFICE_DOC) {
            _previewBitmap.value = null
            return
        }
        previewRenderJob?.cancel()
        previewRenderJob = viewModelScope.launch {
            delay(80)
            _isRenderingPreview.value = true
            val bmp = PdfComposer.renderPreviewBitmap(
                context = app,
                doc = doc,
                pageIndex = _previewPageIndex.value,
                options = _currentOptions.value
            )
            _previewBitmap.value = bmp
            _isRenderingPreview.value = false
        }
    }

    fun executePrint(onDispatched: () -> Unit) {
        val doc = _currentDocument.value ?: return
        val printer = _selectedPrinter.value
        if (printer == null) {
            _snackbarMessage.tryEmit("Pilih atau tambahkan printer terlebih dahulu")
            _pendingNavEvent.tryEmit(AppRoutes.PRINTERS)
            return
        }
        if (doc.requiresCompanionConversion && !printer.protocol.isViaLaptop) {
            _snackbarMessage.tryEmit("Dokumen Office (.docx/.xlsx/.pptx) butuh Companion Server di laptop")
            return
        }
        dispatcher.submitPrintJob(doc, printer, _currentOptions.value)
        _snackbarMessage.tryEmit("Mengirim '${doc.title}' ke ${printer.name}...")
        onDispatched()
    }

    fun cancelActiveJob(localId: String) {
        val job = activeJobs.value.firstOrNull { it.localId == localId }
        val printer = savedPrinters.value.firstOrNull { it.id == job?.printerId } ?: _selectedPrinter.value
        dispatcher.cancelJob(localId, printer)
    }

    fun dismissCompletedJobs() {
        dispatcher.dismissCompletedJobs()
    }

    fun deleteHistoryItem(id: Long) {
        viewModelScope.launch { repo.deleteHistory(id) }
    }

    fun clearHistory() {
        viewModelScope.launch { repo.clearHistory() }
    }

    private fun resolveDisplayName(uri: Uri): String {
        if (uri.scheme == "file") {
            return File(uri.path.orEmpty()).name.ifBlank { "Dokumen" }
        }
        runCatching {
            app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) return c.getString(idx) ?: "Dokumen"
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "Dokumen"
    }
}
