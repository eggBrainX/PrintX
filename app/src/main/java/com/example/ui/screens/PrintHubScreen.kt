package com.example.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MailOutline
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Scanner
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.domain.model.ActivePrintJob
import com.example.domain.model.PrintPreset
import com.example.domain.model.PrinterDevice
import com.example.network.NetworkStatus
import com.example.ui.components.PrinterDashboardSection

@Composable
fun PrintHubScreen(
    savedPrinters: List<PrinterDevice>,
    selectedPrinter: PrinterDevice?,
    networkStatus: NetworkStatus,
    isRefreshingDashboard: Boolean,
    presets: List<PrintPreset>,
    activeJobs: List<ActivePrintJob>,
    onRefreshDashboard: () -> Unit,
    onSelectPrinter: (PrinterDevice) -> Unit,
    onPickImages: (List<Uri>) -> Unit,
    onPickDocument: (Uri) -> Unit,
    onOpenCameraScan: (ktpMode: Boolean) -> Unit,
    onOpenNetworkScanner: () -> Unit,
    onOpenThermalPrint: () -> Unit,
    onOpenWebPrint: () -> Unit,
    onOpenContactsPrint: () -> Unit,
    onOpenCalendarPrint: () -> Unit,
    onOpenTextEmailPrint: () -> Unit,
    onOpenImapEmail: () -> Unit,
    onPrintTestPage: () -> Unit,
    onApplyPreset: (PrintPreset) -> Unit,
    onNavigateToPrinters: () -> Unit,
    onNavigateToHistory: () -> Unit,
    modifier: Modifier = Modifier
) {
    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia(maxItems = 20)
    ) { uris ->
        if (uris.isNotEmpty()) onPickImages(uris)
    }

    val docPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) onPickDocument(uri)
    }

    val runningJobs = activeJobs.filter { !it.status.isTerminal }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Network Printer Dashboard (Connection Status, Ink/Toner Levels, Paper Levels)
        item {
            PrinterDashboardSection(
                printers = savedPrinters,
                selectedPrinter = selectedPrinter,
                networkStatus = networkStatus,
                isRefreshing = isRefreshingDashboard,
                onRefreshStatus = onRefreshDashboard,
                onSelectPrinter = onSelectPrinter,
                onAddOrScanPrinters = onNavigateToPrinters
            )
        }

        // Active Printing Strip
        if (runningJobs.isNotEmpty()) {
            item {
                val job = runningJobs.first()
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("active_job_banner")
                        .clickable { onNavigateToHistory() },
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "${job.status.label}: ${job.title}",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = "${(job.progress * 100).toInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { job.progress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(CircleShape)
                        )
                        if (job.statusDetail.isNotBlank()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = job.statusDetail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            }
        }

        // 2. File Picker UI (PDF & Photo Selection to Queue for Printing)
        item {
            Text(
                text = "Pilih File & Sumber Cetak",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SourceBentoCard(
                    title = "Foto & Gambar",
                    subtitle = "Pilih JPG/PNG dari galeri",
                    icon = Icons.Default.PhotoLibrary,
                    accentColor = MaterialTheme.colorScheme.primary,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    testTag = "btn_print_photos",
                    modifier = Modifier.weight(1f),
                    onClick = {
                        photoPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    }
                )
                SourceBentoCard(
                    title = "PDF & Office",
                    subtitle = "PDF, DOCX, XLSX, PPTX",
                    icon = Icons.Default.Description,
                    accentColor = MaterialTheme.colorScheme.secondary,
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    testTag = "btn_print_documents",
                    modifier = Modifier.weight(1f),
                    onClick = {
                        docPicker.launch(
                            arrayOf(
                                "application/pdf",
                                "application/msword",
                                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                                "application/vnd.ms-excel",
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                                "application/vnd.ms-powerpoint",
                                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                                "application/vnd.oasis.opendocument.text",
                                "text/plain",
                                "application/rtf"
                            )
                        )
                    }
                )
            }
        }

        // 3. Scan, KTP Grid, Fotokopi & Thermal Suite
        item {
            Text(
                text = "Scan, KTP & Printer Khusus",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CompactActionTile(
                    title = "Cetak KTP / Pasfoto",
                    subtitle = "5,5×9 cm • Kolom×Baris",
                    icon = Icons.Default.Badge,
                    testTag = "btn_ktp_grid",
                    modifier = Modifier.weight(1f),
                    onClick = { onOpenCameraScan(true) }
                )
                CompactActionTile(
                    title = "Scan Kamera",
                    subtitle = "Foto, Crop & Bersihkan",
                    icon = Icons.Default.CameraAlt,
                    testTag = "btn_camera_scan",
                    modifier = Modifier.weight(1f),
                    onClick = { onOpenCameraScan(false) }
                )
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CompactActionTile(
                    title = "Scanner LAN (eSCL)",
                    subtitle = "Scan mesin ke HP",
                    icon = Icons.Default.Scanner,
                    testTag = "btn_network_scanner",
                    modifier = Modifier.weight(1f),
                    onClick = onOpenNetworkScanner
                )
                CompactActionTile(
                    title = "Thermal ESC/POS",
                    subtitle = "Struk & QR 58/80mm",
                    icon = Icons.Default.ReceiptLong,
                    testTag = "btn_thermal_print",
                    modifier = Modifier.weight(1f),
                    onClick = onOpenThermalPrint
                )
            }
        }

        // 4. Web, Email, Contacts, Calendar
        item {
            Text(
                text = "Konten Web & Ponsel",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CompactActionTile(
                    title = "Halaman Web",
                    icon = Icons.Default.Language,
                    testTag = "btn_print_web",
                    modifier = Modifier.weight(1f),
                    onClick = onOpenWebPrint
                )
                CompactActionTile(
                    title = "Email IMAP",
                    icon = Icons.Default.MailOutline,
                    testTag = "btn_print_imap",
                    modifier = Modifier.weight(1f),
                    onClick = onOpenImapEmail
                )
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CompactActionTile(
                    title = "Teks / Catatan",
                    icon = Icons.Default.Email,
                    testTag = "btn_print_text_email",
                    modifier = Modifier.weight(1f),
                    onClick = onOpenTextEmailPrint
                )
                CompactActionTile(
                    title = "Kontak",
                    icon = Icons.Default.Contacts,
                    testTag = "btn_print_contacts",
                    modifier = Modifier.weight(1f),
                    onClick = onOpenContactsPrint
                )
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                CompactActionTile(
                    title = "Kalender",
                    icon = Icons.Default.CalendarMonth,
                    testTag = "btn_print_calendar",
                    modifier = Modifier.weight(1f),
                    onClick = onOpenCalendarPrint
                )
                Spacer(modifier = Modifier.weight(1f))
            }
        }

        // Presets Strip
        if (presets.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Preset Opsi Cetak",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            item {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(presets, key = { it.id }) { preset ->
                        Card(
                            modifier = Modifier
                                .width(220.dp)
                                .testTag("preset_card_${preset.id}")
                                .clickable { onApplyPreset(preset) },
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            ),
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                            )
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = preset.name,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = preset.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }
        }

        // Quick Test Page
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Speed,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Halaman Uji Cetak",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            text = "Tes warna, skala abu-abu, dan presisi garis printer Anda.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    FilledTonalButton(
                        onClick = onPrintTestPage,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("btn_test_page")
                    ) {
                        Text("Uji Cetak")
                    }
                }
            }
        }
    }
}

@Composable
private fun SourceBentoCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accentColor: Color,
    containerColor: Color,
    testTag: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    ElevatedCard(
        modifier = modifier
            .height(132.dp)
            .testTag(testTag)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .let { m ->
                        m.padding(0.dp)
                    },
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.material3.Surface(
                    color = containerColor,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = icon,
                            contentDescription = title,
                            tint = accentColor,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun CompactActionTile(
    title: String,
    subtitle: String? = null,
    icon: ImageVector,
    testTag: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .testTag(testTag)
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}
