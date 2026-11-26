package com.example.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Scanner
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.example.domain.model.CardGridPreset
import com.example.network.EsclScannerInfo
import com.example.network.ImapEmailSummary
import com.example.rendering.EscPosEncoder
import com.example.rendering.ImageProcessor
import com.example.ui.theme.JetBrainsMonoFontFamily
import java.io.File
import java.io.FileOutputStream

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CameraScanScreen(
    initialKtpMode: Boolean,
    onBack: () -> Unit,
    onContinueToPrint: (uris: List<Uri>, preset: CardGridPreset?, cleanDoc: Boolean) -> Unit
) {
    val context = LocalContext.current
    var selectedGrid by remember {
        mutableStateOf(if (initialKtpMode) CardGridPreset.KTP_ID else CardGridPreset.NONE)
    }
    var cleanDocumentMode by remember { mutableStateOf(true) }
    var frontBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var backBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var activeSlot by remember { mutableIntStateOf(0) } // 0 = front, 1 = back

    var xRange by remember { mutableStateOf(0f..1f) }
    var yRange by remember { mutableStateOf(0f..1f) }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }

    fun loadUriAsBitmap(uri: Uri): Bitmap? {
        return context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it)
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        val uri = pendingCameraUri
        if (success && uri != null) {
            val bmp = loadUriAsBitmap(uri)
            if (bmp != null) {
                if (activeSlot == 0) frontBitmap = bmp else backBitmap = bmp
            }
        }
    }

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            val bmp = loadUriAsBitmap(uri)
            if (bmp != null) {
                if (activeSlot == 0) frontBitmap = bmp else backBitmap = bmp
            }
        }
    }

    fun launchCameraForSlot(slot: Int) {
        activeSlot = slot
        val file = File(context.cacheDir, "cam_scan_${slot}_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        pendingCameraUri = uri
        cameraLauncher.launch(uri)
    }

    fun saveAndProceed() {
        val outUris = mutableListOf<Uri>()
        listOfNotNull(frontBitmap, backBitmap).forEachIndexed { idx, bmp ->
            val cropped = if (idx == 0) {
                ImageProcessor.cropBitmap(bmp, xRange.start, yRange.start, xRange.endInclusive, yRange.endInclusive)
            } else bmp
            val f = File(context.cacheDir, "scan_final_${idx}_${System.currentTimeMillis()}.jpg")
            FileOutputStream(f).use { cropped.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            outUris.add(Uri.fromFile(f))
        }
        onContinueToPrint(
            outUris,
            if (selectedGrid == CardGridPreset.NONE) null else selectedGrid,
            cleanDocumentMode
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (initialKtpMode) "Cetak KTP & Pasfoto" else "Scan Kamera & Crop") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali")
                    }
                }
            )
        },
        bottomBar = {
            Box(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Button(
                    enabled = frontBitmap != null,
                    onClick = { saveAndProceed() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .minimumInteractiveComponentSize()
                        .testTag("btn_scan_continue_print")
                ) {
                    Icon(Icons.Default.Print, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Lanjut ke Atur Kolom/Baris & Cetak")
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Pilih Jenis Layout Target", style = MaterialTheme.typography.titleSmall)
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                CardGridPreset.entries.forEach { preset ->
                    FilterChip(
                        selected = selectedGrid == preset,
                        onClick = { selectedGrid = preset },
                        label = { Text(preset.label) }
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Mode Dokumen (Bersihkan Latar)", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Putihkan latar kertas/kartu dan hitamkan tulisan otomatis",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = cleanDocumentMode, onCheckedChange = { cleanDocumentMode = it })
            }

            // Front Side Capture Card
            ScanSlotCard(
                title = if (selectedGrid == CardGridPreset.KTP_ID) "Sisi Depan KTP / Dokumen 1" else "Foto Dokumen",
                bitmap = frontBitmap,
                onCamera = { launchCameraForSlot(0) },
                onGallery = {
                    activeSlot = 0
                    galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                onRotate = {
                    frontBitmap = frontBitmap?.let {
                        val m = android.graphics.Matrix().apply { postRotate(90f) }
                        Bitmap.createBitmap(it, 0, 0, it.width, it.height, m, true)
                    }
                }
            )

            // Crop Sliders for Front Image
            if (frontBitmap != null) {
                Text("Potong Tepi (Crop Horizontal & Vertikal)", style = MaterialTheme.typography.labelMedium)
                RangeSlider(
                    value = xRange,
                    onValueChange = { xRange = it },
                    valueRange = 0f..1f
                )
                RangeSlider(
                    value = yRange,
                    onValueChange = { yRange = it },
                    valueRange = 0f..1f
                )
            }

            // Optional Back Side for KTP
            if (selectedGrid == CardGridPreset.KTP_ID || selectedGrid == CardGridPreset.CUSTOM_CARD) {
                ScanSlotCard(
                    title = "Sisi Belakang KTP / Kartu (Opsional)",
                    bitmap = backBitmap,
                    onCamera = { launchCameraForSlot(1) },
                    onGallery = {
                        activeSlot = 1
                        galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    onRotate = {
                        backBitmap = backBitmap?.let {
                            val m = android.graphics.Matrix().apply { postRotate(90f) }
                            Bitmap.createBitmap(it, 0, 0, it.width, it.height, m, true)
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun ScanSlotCard(
    title: String,
    bitmap: Bitmap?,
    onCamera: () -> Unit,
    onGallery: () -> Unit,
    onRotate: () -> Unit
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (bitmap != null) {
                    IconButton(onClick = onRotate) {
                        Icon(Icons.Default.RotateRight, contentDescription = "Putar 90 derajat")
                    }
                }
            }
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = title,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(170.dp)
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(onClick = onCamera, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Kamera")
                }
                OutlinedButton(onClick = onGallery, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("Galeri")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NetworkScannerScreen(
    discoveredScanners: List<EsclScannerInfo>,
    manualScanners: List<EsclScannerInfo>,
    isScanningDoc: Boolean,
    lastScannedFile: File?,
    onDiscover: () -> Unit,
    onProbeManualScanner: (host: String, port: Int, useHttps: Boolean) -> Unit,
    onRunScan: (scanner: EsclScannerInfo, dpi: Int, colorMode: String, format: String) -> Unit,
    onOpenScannedForPrint: (File) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val allScanners = (discoveredScanners + manualScanners).distinctBy { it.id }
    var selectedScanner by remember(allScanners) { mutableStateOf(allScanners.firstOrNull()) }
    var manualIp by remember { mutableStateOf("192.168.1.") }
    var dpi by remember { mutableIntStateOf(300) }
    var colorMode by remember { mutableStateOf("RGB24") }
    var format by remember { mutableStateOf("application/pdf") }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(if (lastScannedFile?.extension == "pdf") "application/pdf" else "image/jpeg")
    ) { destUri ->
        val src = lastScannedFile ?: return@rememberLauncherForActivityResult
        if (destUri != null) {
            context.contentResolver.openOutputStream(destUri)?.use { out ->
                src.inputStream().use { inp -> inp.copyTo(out) }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scanner LAN (eSCL / AirScan)") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = manualIp,
                    onValueChange = { manualIp = it },
                    label = { Text("IP Mesin Fotokopi / Scanner") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Button(onClick = { onProbeManualScanner(manualIp, 80, false) }) {
                    Text("Cek")
                }
            }

            OutlinedButton(
                onClick = onDiscover,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Default.Radar, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Cari Scanner mDNS (_uscan._tcp)")
            }

            if (allScanners.isNotEmpty()) {
                Text("Pilih Scanner", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    allScanners.forEach { sc ->
                        FilterChip(
                            selected = selectedScanner?.id == sc.id,
                            onClick = { selectedScanner = sc },
                            label = { Text(sc.name) }
                        )
                    }
                }
            }

            Text("Pengaturan Scan", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(150, 300, 600).forEach { d ->
                    FilterChip(selected = dpi == d, onClick = { dpi = d }, label = { Text("$d DPI") })
                }
                FilterChip(
                    selected = colorMode == "RGB24",
                    onClick = { colorMode = "RGB24" },
                    label = { Text("Warna") }
                )
                FilterChip(
                    selected = colorMode == "Grayscale8",
                    onClick = { colorMode = "Grayscale8" },
                    label = { Text("Abu-abu") }
                )
                FilterChip(
                    selected = format == "application/pdf",
                    onClick = { format = "application/pdf" },
                    label = { Text("PDF") }
                )
                FilterChip(
                    selected = format == "image/jpeg",
                    onClick = { format = "image/jpeg" },
                    label = { Text("JPEG") }
                )
            }

            Button(
                enabled = selectedScanner != null && !isScanningDoc,
                onClick = { selectedScanner?.let { onRunScan(it, dpi, colorMode, format) } },
                modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize()
            ) {
                if (isScanningDoc) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Memindai dari Mesin...")
                } else {
                    Icon(Icons.Default.Scanner, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Mulai Scan Dokumen")
                }
            }

            lastScannedFile?.let { file ->
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Hasil Scan: ${file.name}", style = MaterialTheme.typography.titleSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = { onOpenScannedForPrint(file) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Print, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Fotokopi / Cetak")
                            }
                            OutlinedButton(
                                onClick = { saveLauncher.launch(file.name) },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Save, contentDescription = null)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Simpan")
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThermalPrintScreen(
    defaultHost: String,
    onPrintThermal: (host: String, port: Int, bytes: ByteArray, title: String) -> Unit,
    onBack: () -> Unit
) {
    var host by remember { mutableStateOf(defaultHost.ifBlank { "192.168.1.100" }) }
    var portText by remember { mutableStateOf("9100") }
    var widthMm by remember { mutableIntStateOf(58) }
    var header by remember { mutableStateOf("TOKO MAJU JAYA\nJl. Merdeka No. 12") }
    var body by remember { mutableStateOf("1x Kertas A4 Rim     48.000\n2x Tinta Hitam      130.000\n---------------------------\nTOTAL              178.000") }
    var footer by remember { mutableStateOf("Terima kasih atas kunjungan Anda") }
    var qrContent by remember { mutableStateOf("INV-2026-001") }
    var cutPaper by remember { mutableStateOf(true) }

    val previewBmp = remember(header, body, footer, qrContent, widthMm) {
        EscPosEncoder.buildReceiptPreviewBitmap(header, body, footer, qrContent, widthMm)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cetak Struk / Label Thermal (ESC/POS)") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali")
                    }
                }
            )
        },
        bottomBar = {
            Box(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Button(
                    enabled = host.isNotBlank(),
                    onClick = {
                        val bytes = EscPosEncoder.encodeBitmapToEscPos(previewBmp, cutPaper)
                        onPrintThermal(host, portText.toIntOrNull() ?: 9100, bytes, header.lineSequence().firstOrNull() ?: "Struk Thermal")
                    },
                    modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize()
                ) {
                    Icon(Icons.Default.Print, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Kirim ke Printer Thermal ($widthMm mm)")
                }
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("IP Printer Thermal") },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                    value = portText,
                    onValueChange = { portText = it.filter { c -> c.isDigit() } },
                    label = { Text("Port") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.width(96.dp)
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                FilterChip(selected = widthMm == 58, onClick = { widthMm = 58 }, label = { Text("Lebar 58 mm") })
                FilterChip(selected = widthMm == 80, onClick = { widthMm = 80 }, label = { Text("Lebar 80 mm") })
                Spacer(modifier = Modifier.weight(1f))
                Text("Potong", style = MaterialTheme.typography.bodySmall)
                Switch(checked = cutPaper, onCheckedChange = { cutPaper = it })
            }

            OutlinedTextField(value = header, onValueChange = { header = it }, label = { Text("Header Struk/Label") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = body, onValueChange = { body = it }, label = { Text("Isi Baris / Item") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = qrContent, onValueChange = { qrContent = it }, label = { Text("Isi Kode QR (opsional)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = footer, onValueChange = { footer = it }, label = { Text("Footer") }, singleLine = true, modifier = Modifier.fillMaxWidth())

            Text("Pratinjau Kertas Thermal", style = MaterialTheme.typography.titleSmall)
            Surface(
                color = Color.White,
                shape = RoundedCornerShape(8.dp),
                tonalElevation = 2.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Image(
                    bitmap = previewBmp.asImageBitmap(),
                    contentDescription = "Pratinjau struk thermal",
                    contentScale = ContentScale.FillWidth,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImapEmailScreen(
    emails: List<ImapEmailSummary>,
    isFetching: Boolean,
    onFetch: (host: String, port: Int, email: String, appPass: String) -> Unit,
    onPrintEmail: (subject: String, body: String) -> Unit,
    onBack: () -> Unit
) {
    var host by remember { mutableStateOf("imap.gmail.com") }
    var portText by remember { mutableStateOf("993") }
    var email by remember { mutableStateOf("") }
    var appPass by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cetak Email (IMAP TLS)") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali")
                    }
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = host,
                                onValueChange = { host = it },
                                label = { Text("Server IMAP") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = portText,
                                onValueChange = { portText = it.filter { c -> c.isDigit() } },
                                label = { Text("Port") },
                                singleLine = true,
                                modifier = Modifier.width(86.dp)
                            )
                        }
                        OutlinedTextField(
                            value = email,
                            onValueChange = { email = it },
                            label = { Text("Alamat Email") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        OutlinedTextField(
                            value = appPass,
                            onValueChange = { appPass = it },
                            label = { Text("App Password / Sandi IMAP") },
                            visualTransformation = PasswordVisualTransformation(),
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Button(
                            enabled = !isFetching && email.isNotBlank() && appPass.isNotBlank(),
                            onClick = { onFetch(host, portText.toIntOrNull() ?: 993, email, appPass) },
                            modifier = Modifier.fillMaxWidth().minimumInteractiveComponentSize()
                        ) {
                            if (isFetching) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Mengambil Kotak Masuk...")
                            } else {
                                Icon(Icons.Default.Email, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Ambil 15 Email Terbaru")
                            }
                        }
                    }
                }
            }

            items(emails, key = { it.uid }) { mail ->
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val fullBody = "Dari: ${mail.from}\nTanggal: ${mail.date}\n\n${mail.previewBody}"
                            onPrintEmail(mail.subject, fullBody)
                        }
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = mail.subject,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${mail.from} • ${mail.date}",
                            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (mail.previewBody.isNotBlank()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = mail.previewBody,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}
