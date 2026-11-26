package com.example.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.domain.model.PrinterDevice
import com.example.domain.model.PrinterProtocol
import com.example.network.NetworkStatus
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.StatusReadyGreen

@Composable
fun PrintersScreen(
    savedPrinters: List<PrinterDevice>,
    discoveredPrinters: List<PrinterDevice>,
    selectedPrinter: PrinterDevice?,
    networkStatus: NetworkStatus,
    isScanning: Boolean,
    scanMessage: String,
    isProbingManual: Boolean,
    onStartScan: () -> Unit,
    onStopScan: () -> Unit,
    onSelectPrinter: (PrinterDevice) -> Unit,
    onSetDefaultPrinter: (PrinterDevice) -> Unit,
    onDeletePrinter: (String) -> Unit,
    onAddManualPrinter: (
        name: String,
        host: String,
        port: Int,
        path: String,
        protocol: PrinterProtocol,
        onRequirePin: (host: String, port: Int, useHttps: Boolean) -> Unit,
        onComplete: () -> Unit
    ) -> Unit,
    onPairCompanion: (host: String, port: Int, useHttps: Boolean, pin: String, onPaired: () -> Unit) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var showManualDialog by remember { mutableStateOf(false) }
    var showCompanionGuide by remember { mutableStateOf(false) }
    var pairingTarget by remember { mutableStateOf<Triple<String, Int, Boolean>?>(null) }

    val discoveryPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.NEARBY_WIFI_DEVICES
    } else {
        Manifest.permission.ACCESS_FINE_LOCATION
    }

    val permLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) onStartScan() else onStartScan() // Subnet TCP scan still works even if mDNS restricted
    }

    fun triggerScan() {
        val granted = ContextCompat.checkSelfPermission(context, discoveryPermission) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            onStartScan()
        } else {
            permLauncher.launch(discoveryPermission)
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Network Status & Action Controls
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Wifi,
                            contentDescription = null,
                            tint = if (networkStatus.isWifiConnected) StatusReadyGreen else MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (networkStatus.isWifiConnected) {
                                    "Terhubung ke Wi-Fi Lokal"
                                } else {
                                    "Wi-Fi Tidak Terhubung"
                                },
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = networkStatus.localIp?.let { "IP Perangkat: $it" }
                                    ?: "Aktifkan Wi-Fi pada subnet yang sama dengan printer",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(
                            onClick = { showCompanionGuide = true },
                            modifier = Modifier.minimumInteractiveComponentSize()
                        ) {
                            Icon(Icons.Default.HelpOutline, contentDescription = "Panduan Companion Server")
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { if (isScanning) onStopScan() else triggerScan() },
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .testTag("btn_scan_network")
                        ) {
                            if (isScanning) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Hentikan")
                            } else {
                                Icon(Icons.Default.Radar, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Scan Jaringan")
                            }
                        }

                        OutlinedButton(
                            onClick = { showManualDialog = true },
                            modifier = Modifier
                                .weight(1f)
                                .minimumInteractiveComponentSize()
                                .testTag("btn_add_manual_ip")
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Tambah IP")
                        }
                    }

                    if (scanMessage.isNotBlank()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = scanMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // Discovered Printers Section
        if (discoveredPrinters.isNotEmpty()) {
            item {
                Text(
                    text = "Ditemukan di Jaringan (${discoveredPrinters.size})",
                    style = MaterialTheme.typography.titleMedium
                )
            }
            items(discoveredPrinters, key = { "disc_${it.id}" }) { printer ->
                PrinterListCard(
                    printer = printer,
                    isSelected = selectedPrinter?.id == printer.id,
                    isSavedSection = false,
                    onClick = {
                        if (printer.id.startsWith("companion_pair_")) {
                            pairingTarget = Triple(
                                printer.host,
                                printer.port,
                                printer.protocol == PrinterProtocol.COMPANION_HTTPS
                            )
                        } else {
                            onSelectPrinter(printer)
                        }
                    },
                    onSetDefault = null,
                    onDelete = null
                )
            }
        }

        // Saved Printers Section
        item {
            Text(
                text = "Printer Tersimpan (${savedPrinters.size})",
                style = MaterialTheme.typography.titleMedium
            )
        }

        if (savedPrinters.isEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.Print,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(40.dp)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "Belum Ada Printer Tersimpan",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Tekan Scan Jaringan untuk mencari mesin fotokopi (IPP/RAW 9100) dan laptop Companion Server, atau Tambah IP secara manual.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(savedPrinters, key = { "saved_${it.id}" }) { printer ->
                PrinterListCard(
                    printer = printer,
                    isSelected = selectedPrinter?.id == printer.id,
                    isSavedSection = true,
                    onClick = { onSelectPrinter(printer) },
                    onSetDefault = { onSetDefaultPrinter(printer) },
                    onDelete = { onDeletePrinter(printer.id) }
                )
            }
        }
    }

    if (showManualDialog) {
        ManualPrinterDialog(
            isProbing = isProbingManual,
            onDismiss = { showManualDialog = false },
            onConfirm = { name, host, port, path, protocol ->
                onAddManualPrinter(
                    name,
                    host,
                    port,
                    path,
                    protocol,
                    { h, p, https ->
                        showManualDialog = false
                        pairingTarget = Triple(h, p, https)
                    },
                    { showManualDialog = false }
                )
            }
        )
    }

    pairingTarget?.let { (host, port, useHttps) ->
        CompanionPairingDialog(
            host = host,
            port = port,
            isPairing = isProbingManual,
            onDismiss = { pairingTarget = null },
            onPair = { pin ->
                onPairCompanion(host, port, useHttps, pin) {
                    pairingTarget = null
                }
            }
        )
    }

    if (showCompanionGuide) {
        CompanionGuideDialog(onDismiss = { showCompanionGuide = false })
    }
}

@Composable
private fun PrinterListCard(
    printer: PrinterDevice,
    isSelected: Boolean,
    isSavedSection: Boolean,
    onClick: () -> Unit,
    onSetDefault: (() -> Unit)?,
    onDelete: (() -> Unit)?
) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("printer_item_${printer.id}")
            .clickable(onClick = onClick),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f)
            } else {
                MaterialTheme.colorScheme.surface
            }
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (printer.protocol.isViaLaptop) Icons.Default.Computer else Icons.Default.Print,
                contentDescription = null,
                tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(30.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = printer.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    if (isSelected) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Aktif",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
                Text(
                    text = printer.endpointLabel,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFontFamily),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = printer.protocol.label,
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = if (printer.capabilities.supportsColor) "Warna" else "Mono",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    if (printer.capabilities.supportsDuplex) {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = "Duplex",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }

            if (isSavedSection && onSetDefault != null && onDelete != null) {
                IconButton(
                    onClick = onSetDefault,
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Icon(
                        imageVector = if (printer.isDefault) Icons.Default.Star else Icons.Default.StarBorder,
                        contentDescription = "Jadikan Utama",
                        tint = if (printer.isDefault) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Icon(
                        imageVector = Icons.Default.DeleteOutline,
                        contentDescription = "Hapus Printer",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun ManualPrinterDialog(
    isProbing: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (name: String, host: String, port: Int, path: String, protocol: PrinterProtocol) -> Unit
) {
    var selectedProtocol by remember { mutableStateOf(PrinterProtocol.IPP) }
    var name by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("192.168.1.") }
    var portText by remember { mutableStateOf("631") }
    var path by remember { mutableStateOf("/ipp/print") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tambah Printer Manual") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("Protokol", style = MaterialTheme.typography.labelMedium)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        PrinterProtocol.IPP,
                        PrinterProtocol.RAW_9100,
                        PrinterProtocol.LPR_515,
                        PrinterProtocol.COMPANION_HTTP
                    ).forEach { proto ->
                        FilterChip(
                            selected = selectedProtocol == proto,
                            onClick = {
                                selectedProtocol = proto
                                portText = proto.defaultPort.toString()
                                path = when (proto) {
                                    PrinterProtocol.IPP, PrinterProtocol.IPPS -> "/ipp/print"
                                    PrinterProtocol.LPR_515 -> "lp"
                                    else -> ""
                                }
                            },
                            label = { Text(proto.label.substringBefore(" "), maxLines = 1) }
                        )
                    }
                }

                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("Alamat IP / Hostname") },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("input_manual_host")
                )
                OutlinedTextField(
                    value = portText,
                    onValueChange = { portText = it.filter { ch -> ch.isDigit() } },
                    label = { Text("Port") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                if (selectedProtocol == PrinterProtocol.IPP || selectedProtocol == PrinterProtocol.LPR_515) {
                    OutlinedTextField(
                        value = path,
                        onValueChange = { path = it },
                        label = {
                            Text(if (selectedProtocol == PrinterProtocol.LPR_515) "Nama Antrian (lp)" else "Path IPP (/ipp/print)")
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nama Printer (opsional)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                enabled = !isProbing && host.isNotBlank(),
                onClick = {
                    val port = portText.toIntOrNull() ?: selectedProtocol.defaultPort
                    onConfirm(name, host, port, path, selectedProtocol)
                },
                modifier = Modifier.testTag("btn_confirm_manual_printer")
            ) {
                if (isProbing) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text("Hubungkan & Simpan")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Batal") }
        }
    )
}

@Composable
private fun CompanionPairingDialog(
    host: String,
    port: Int,
    isPairing: Boolean,
    onDismiss: () -> Unit,
    onPair: (String) -> Unit
) {
    var pin by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Pairing Companion Server") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Masukkan PIN 6 digit yang tampil pada Companion Server di $host:$port.")
                OutlinedTextField(
                    value = pin,
                    onValueChange = { pin = it },
                    label = { Text("PIN Pairing") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                enabled = !isPairing && pin.isNotBlank(),
                onClick = { onPair(pin) }
            ) {
                Text("Pair")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Batal") }
        }
    )
}

@Composable
private fun CompanionGuideDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val script = com.example.network.CompanionServerScript.pythonScript
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/x-python")
    ) { destUri ->
        if (destUri != null) {
            context.contentResolver.openOutputStream(destUri)?.use { out ->
                out.write(script.toByteArray(Charsets.UTF_8))
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Companion Server Siap Pakai") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Simpan atau salin skrip Python FastAPI ini ke laptop (Windows/Linux/macOS) untuk mencetak ke printer USB dan mengonversi DOCX/XLSX/PPTX:",
                    style = MaterialTheme.typography.bodySmall
                )
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        text = script.lineSequence().take(24).joinToString("\n") + "\n...",
                        style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                        modifier = Modifier.padding(10.dp)
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(
                        onClick = {
                            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            cm.setPrimaryClip(ClipData.newPlainText("lanprint_companion.py", script))
                        }
                    ) {
                        Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Salin Skrip")
                    }
                    TextButton(
                        onClick = { saveLauncher.launch("lanprint_companion.py") }
                    ) {
                        Text("Simpan .py")
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text("Tutup") }
        }
    )
}
