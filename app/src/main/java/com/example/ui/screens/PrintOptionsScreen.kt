package com.example.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.domain.model.ContentType
import com.example.domain.model.Orientation
import com.example.domain.model.PrintOptions
import com.example.domain.model.PrintPreset
import com.example.domain.model.PrintableDocument
import com.example.domain.model.PrinterCapabilities
import com.example.domain.model.PrinterDevice
import com.example.domain.model.ScaleType
import com.example.rendering.PdfComposer
import com.example.ui.theme.JetBrainsMonoFontFamily

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrintOptionsScreen(
    document: PrintableDocument?,
    selectedPrinter: PrinterDevice?,
    options: PrintOptions,
    presets: List<PrintPreset>,
    previewBitmap: Bitmap?,
    previewPageIndex: Int,
    totalPages: Int,
    isRenderingPreview: Boolean,
    onBack: () -> Unit,
    onUpdateOptions: ((PrintOptions) -> PrintOptions) -> Unit,
    onSelectPage: (Int) -> Unit,
    onApplyPreset: (PrintPreset) -> Unit,
    onSavePreset: (String) -> Unit,
    onChoosePrinter: () -> Unit,
    onPrintNow: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    var showSavePresetDialog by remember { mutableStateOf(false) }

    val tabs = listOf("Kertas", "Tata Letak", "Kualitas", "Gambar")
    val capabilities = selectedPrinter?.capabilities ?: PrinterCapabilities()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = document?.title ?: "Opsi Cetak",
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = selectedPrinter?.name ?: "Belum ada printer dipilih",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable { onChoosePrinter() }
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { showSavePresetDialog = true },
                        modifier = Modifier.testTag("btn_save_preset")
                    ) {
                        Icon(Icons.Default.BookmarkAdd, contentDescription = "Simpan Preset")
                    }
                }
            )
        },
        bottomBar = {
            Surface(
                tonalElevation = 6.dp,
                shadowElevation = 8.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "${options.paperSize.label} • ${options.copies} salinan • ${options.colorMode.label}",
                            style = MaterialTheme.typography.labelMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = selectedPrinter?.let { "${it.name} (${it.protocol.label})" }
                                ?: "Ketuk Printer untuk memilih tujuan",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Button(
                        onClick = onPrintNow,
                        enabled = document != null,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("btn_print_now")
                    ) {
                        Icon(Icons.Default.Print, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Cetak")
                    }
                }
            }
        }
    ) { innerPadding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            val isWide = maxWidth >= 720.dp

            if (isWide) {
                // Canonical Tablet/Foldable Two-Pane Layout
                Row(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .weight(0.46f)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                            .padding(16.dp)
                    ) {
                        LivePreviewPane(
                            document = document,
                            selectedPrinter = selectedPrinter,
                            options = options,
                            previewBitmap = previewBitmap,
                            previewPageIndex = previewPageIndex,
                            totalPages = totalPages,
                            isRenderingPreview = isRenderingPreview,
                            onSelectPage = onSelectPage
                        )
                    }
                    Column(
                        modifier = Modifier
                            .weight(0.54f)
                            .fillMaxHeight()
                    ) {
                        PresetQuickStrip(presets = presets, onApplyPreset = onApplyPreset)
                        TabRow(selectedTabIndex = selectedTab) {
                            tabs.forEachIndexed { idx, title ->
                                Tab(
                                    selected = selectedTab == idx,
                                    onClick = { selectedTab = idx },
                                    text = { Text(title, maxLines = 1) }
                                )
                            }
                        }
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .verticalScroll(rememberScrollState())
                                .padding(16.dp)
                        ) {
                            OptionsTabContent(
                                selectedTab = selectedTab,
                                options = options,
                                capabilities = capabilities,
                                isPdfDocument = document?.contentType == ContentType.PDF,
                                onUpdateOptions = onUpdateOptions
                            )
                        }
                    }
                }
            } else {
                // Vertical Phone Layout
                Column(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(250.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
                            .padding(12.dp)
                    ) {
                        LivePreviewPane(
                            document = document,
                            selectedPrinter = selectedPrinter,
                            options = options,
                            previewBitmap = previewBitmap,
                            previewPageIndex = previewPageIndex,
                            totalPages = totalPages,
                            isRenderingPreview = isRenderingPreview,
                            onSelectPage = onSelectPage
                        )
                    }

                    PresetQuickStrip(presets = presets, onApplyPreset = onApplyPreset)

                    TabRow(selectedTabIndex = selectedTab) {
                        tabs.forEachIndexed { idx, title ->
                            Tab(
                                selected = selectedTab == idx,
                                onClick = { selectedTab = idx },
                                text = { Text(title, maxLines = 1) }
                            )
                        }
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp)
                    ) {
                        OptionsTabContent(
                            selectedTab = selectedTab,
                            options = options,
                            capabilities = capabilities,
                            isPdfDocument = document?.contentType == ContentType.PDF,
                            onUpdateOptions = onUpdateOptions
                        )
                    }
                }
            }
        }
    }

    if (showSavePresetDialog) {
        var presetName by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showSavePresetDialog = false },
            title = { Text("Simpan Preset Opsi Cetak") },
            text = {
                OutlinedTextField(
                    value = presetName,
                    onValueChange = { presetName = it },
                    label = { Text("Nama Preset (mis. Fotokopi F4)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    enabled = presetName.isNotBlank(),
                    onClick = {
                        onSavePreset(presetName)
                        showSavePresetDialog = false
                    }
                ) {
                    Text("Simpan")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSavePresetDialog = false }) { Text("Batal") }
            }
        )
    }
}

@Composable
private fun PresetQuickStrip(
    presets: List<PrintPreset>,
    onApplyPreset: (PrintPreset) -> Unit
) {
    if (presets.isEmpty()) return
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items(presets, key = { it.id }) { preset ->
            FilterChip(
                selected = false,
                onClick = { onApplyPreset(preset) },
                label = { Text(preset.name) }
            )
        }
    }
}

@Composable
private fun OptionsTabContent(
    selectedTab: Int,
    options: PrintOptions,
    capabilities: PrinterCapabilities,
    isPdfDocument: Boolean,
    onUpdateOptions: ((PrintOptions) -> PrintOptions) -> Unit
) {
    when (selectedTab) {
        0 -> PaperOptionsPanel(options, capabilities, onUpdateOptions)
        1 -> LayoutOptionsPanel(options, capabilities, onUpdateOptions)
        2 -> QualityOptionsPanel(options, capabilities, onUpdateOptions)
        else -> ImageAdjustmentsPanel(options, isPdfDocument, onUpdateOptions)
    }
}

@Composable
private fun LivePreviewPane(
    document: PrintableDocument?,
    selectedPrinter: PrinterDevice?,
    options: PrintOptions,
    previewBitmap: Bitmap?,
    previewPageIndex: Int,
    totalPages: Int,
    isRenderingPreview: Boolean,
    onSelectPage: (Int) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Companion warning if Office doc is selected with non-Companion printer
        if (document?.requiresCompanionConversion == true && selectedPrinter?.protocol?.isViaLaptop != true) {
            Surface(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Dokumen Office membutuhkan Companion Server (LibreOffice) di laptop.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                }
            }
        }

        // Paper Sheet Preview
        val isLandscape = when (options.orientation) {
            Orientation.LANDSCAPE -> true
            Orientation.PORTRAIT -> false
            Orientation.AUTO -> (previewBitmap?.width ?: 0) > (previewBitmap?.height ?: 1)
        }
        val ratio = if (isLandscape) {
            (1f / options.paperSize.aspectRatio).coerceIn(0.4f, 2.2f)
        } else {
            options.paperSize.aspectRatio.coerceIn(0.4f, 2.2f)
        }

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .aspectRatio(ratio, matchHeightConstraintsFirst = true)
                    .shadow(6.dp, RoundedCornerShape(4.dp))
                    .background(Color.White, RoundedCornerShape(4.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (document?.contentType == ContentType.OFFICE_DOC) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Description,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(40.dp)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = document.originalFileName,
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(0xFF0F172A),
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "Dirender oleh LibreOffice di Laptop",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF64748B)
                        )
                    }
                } else if (previewBitmap != null && options.hasCardGrid) {
                    // Live Card / KTP (5.5 x 9 cm) / Pasfoto millimeter Grid Preview
                    val paperWmm = if (isLandscape) options.paperSize.heightMm else options.paperSize.widthMm
                    val paperHmm = if (isLandscape) options.paperSize.widthMm else options.paperSize.heightMm
                    val cols = options.cardColumns.coerceIn(1, 10)
                    val rows = options.cardRows.coerceIn(1, 15)
                    val cardWFrac = (options.cardWidthMm / paperWmm).coerceIn(0.05f, 0.95f)
                    val cardHFrac = (options.cardHeightMm / paperHmm).coerceIn(0.05f, 0.95f)
                    val gapWFrac = (options.cardGapMm / paperWmm).coerceIn(0f, 0.1f)
                    val gapHFrac = (options.cardGapMm / paperHmm).coerceIn(0f, 0.1f)
                    val totalWFrac = cols * cardWFrac + (cols - 1) * gapWFrac
                    val totalHFrac = rows * cardHFrac + (rows - 1) * gapHFrac
                    val startXFrac = ((1f - totalWFrac) / 2f).coerceAtLeast(0.03f)
                    val startYFrac = ((1f - totalHFrac) / 2f).coerceAtLeast(0.03f)

                    Box(modifier = Modifier.fillMaxSize()) {
                        val imgBmp = previewBitmap.asImageBitmap()
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            for (r in 0 until rows) {
                                for (c in 0 until cols) {
                                    val x = (startXFrac + c * (cardWFrac + gapWFrac)) * size.width
                                    val y = (startYFrac + r * (cardHFrac + gapHFrac)) * size.height
                                    val w = cardWFrac * size.width
                                    val h = cardHFrac * size.height
                                    if (x < size.width && y < size.height) {
                                        drawImage(
                                            image = imgBmp,
                                            dstOffset = androidx.compose.ui.unit.IntOffset(x.toInt(), y.toInt()),
                                            dstSize = androidx.compose.ui.unit.IntSize(
                                                w.toInt().coerceAtLeast(1),
                                                h.toInt().coerceAtLeast(1)
                                            )
                                        )
                                        if (options.cardCutMarks) {
                                            drawRect(
                                                color = Color(0xFF64748B),
                                                topLeft = Offset(x, y),
                                                size = androidx.compose.ui.geometry.Size(w, h),
                                                style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx())
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                } else if (previewBitmap != null) {
                    val contentScale = when (options.scaleType) {
                        ScaleType.FILL -> ContentScale.Crop
                        else -> ContentScale.Fit
                    }
                    val padPercent = (options.effectiveMarginsMm.first() / options.paperSize.heightMm)
                        .coerceIn(0f, 0.16f)
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding((padPercent * 140).dp)
                    ) {
                        Image(
                            bitmap = previewBitmap.asImageBitmap(),
                            contentDescription = "Pratinjau halaman ${previewPageIndex + 1}",
                            contentScale = contentScale,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                // Watermark live preview overlay
                if (options.watermarkText.isNotBlank()) {
                    Text(
                        text = options.watermarkText,
                        style = MaterialTheme.typography.titleLarge,
                        color = Color(0xFF475569).copy(alpha = options.watermarkOpacity.coerceIn(0.1f, 0.75f)),
                        modifier = Modifier.padding(8.dp)
                    )
                }

                // Margin + N-Up / Poster guide overlay
                val guideColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val dash = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
                    val marginRatio = (options.effectiveMarginsMm.first() / options.paperSize.heightMm).coerceIn(0f, 0.16f)
                    val pad = size.minDimension * marginRatio
                    if (pad > 1f) {
                        drawRect(
                            color = guideColor,
                            topLeft = Offset(pad, pad),
                            size = androidx.compose.ui.geometry.Size(size.width - pad * 2, size.height - pad * 2),
                            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1.dp.toPx(), pathEffect = dash)
                        )
                    }
                    if (options.pagesPerSheet > 1) {
                        val (cols, rows) = PdfComposer.nUpGrid(options.pagesPerSheet, isLandscape)
                        for (c in 1 until cols) {
                            val x = size.width * c / cols
                            drawLine(guideColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx(), pathEffect = dash)
                        }
                        for (r in 1 until rows) {
                            val y = size.height * r / rows
                            drawLine(guideColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx(), pathEffect = dash)
                        }
                    }
                }

                if (isRenderingPreview) {
                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                }
            }
        }

        // Page Navigation + Paper Dim badge
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "${options.paperSize.label} (${options.paperSize.dimensionLabel})",
                style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFontFamily),
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { onSelectPage(previewPageIndex - 1) },
                    enabled = previewPageIndex > 0
                ) {
                    Icon(Icons.Default.ChevronLeft, contentDescription = "Halaman sebelumnya")
                }
                Text(
                    text = "Hal ${previewPageIndex + 1} / $totalPages",
                    style = MaterialTheme.typography.labelSmall
                )
                IconButton(
                    onClick = { onSelectPage(previewPageIndex + 1) },
                    enabled = previewPageIndex < totalPages - 1
                ) {
                    Icon(Icons.Default.ChevronRight, contentDescription = "Halaman berikutnya")
                }
            }
        }
    }
}
