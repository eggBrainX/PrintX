package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.domain.model.CardGridPreset
import com.example.domain.model.ColorMode
import com.example.domain.model.DuplexMode
import com.example.domain.model.FinishingOption
import com.example.domain.model.ImageFilter
import com.example.domain.model.MarginType
import com.example.domain.model.MediaType
import com.example.domain.model.NUpOrder
import com.example.domain.model.OptimizeContent
import com.example.domain.model.Orientation
import com.example.domain.model.PagePosition
import com.example.domain.model.PageRangeFilter
import com.example.domain.model.PaperSize
import com.example.domain.model.PaperTray
import com.example.domain.model.PrintOptions
import com.example.domain.model.PrintQuality
import com.example.domain.model.PrinterCapabilities
import com.example.domain.model.RenderingIntent
import com.example.domain.model.ScaleType
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PaperOptionsPanel(
    options: PrintOptions,
    capabilities: PrinterCapabilities,
    onUpdate: ((PrintOptions) -> PrintOptions) -> Unit
) {
    var showCustomPaperDialog by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Ukuran Kertas", style = MaterialTheme.typography.titleSmall)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            PaperSize.STANDARD_LIST.forEach { paper ->
                val supported = capabilities.supportsPaper(paper)
                val selected = !options.paperSize.isCustom && options.paperSize.id == paper.id
                FilterChip(
                    selected = selected,
                    onClick = { onUpdate { it.copy(paperSize = paper) } },
                    label = {
                        Text(
                            text = if (supported) "${paper.label} (${paper.dimensionLabel})" else "${paper.label}*",
                            maxLines = 1
                        )
                    }
                )
            }
            FilterChip(
                selected = options.paperSize.isCustom,
                onClick = { showCustomPaperDialog = true },
                label = {
                    Text(
                        if (options.paperSize.isCustom) "Kustom (${options.paperSize.dimensionLabel})" else "Ukuran Kustom..."
                    )
                }
            )
        }

        if (!capabilities.supportsPaper(options.paperSize)) {
            Text(
                text = "* Printer melaporkan ukuran ${options.paperSize.label} mungkin tidak tersedia; akan dikirim sebagai ukuran kustom / diskalakan.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary
            )
        }

        Text("Orientasi", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Orientation.entries.forEach { ori ->
                FilterChip(
                    selected = options.orientation == ori,
                    onClick = { onUpdate { it.copy(orientation = ori) } },
                    label = { Text(ori.label) }
                )
            }
        }

        Text("Jenis Kertas", style = MaterialTheme.typography.titleSmall)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            MediaType.entries.forEach { media ->
                FilterChip(
                    selected = options.mediaType == media,
                    onClick = { onUpdate { it.copy(mediaType = media) } },
                    label = { Text(media.label) }
                )
            }
        }

        Text("Sumber / Baki Kertas", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PaperTray.entries.forEach { tray ->
                FilterChip(
                    selected = options.tray == tray,
                    onClick = { onUpdate { it.copy(tray = tray) } },
                    label = { Text(tray.label) }
                )
            }
        }
    }

    if (showCustomPaperDialog) {
        var wText by remember { mutableStateOf(options.paperSize.widthMm.toInt().toString()) }
        var hText by remember { mutableStateOf(options.paperSize.heightMm.toInt().toString()) }
        AlertDialog(
            onDismissRequest = { showCustomPaperDialog = false },
            title = { Text("Ukuran Kertas Kustom (mm)") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = wText,
                        onValueChange = { wText = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("Lebar (mm)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = hText,
                        onValueChange = { hText = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("Tinggi (mm)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val w = wText.toFloatOrNull() ?: 210f
                    val h = hText.toFloatOrNull() ?: 297f
                    onUpdate { it.copy(paperSize = PaperSize.custom(w, h)) }
                    showCustomPaperDialog = false
                }) {
                    Text("Terapkan")
                }
            },
            dismissButton = {
                TextButton(onClick = { showCustomPaperDialog = false }) { Text("Batal") }
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LayoutOptionsPanel(
    options: PrintOptions,
    capabilities: PrinterCapabilities,
    onUpdate: ((PrintOptions) -> PrintOptions) -> Unit
) {
    val (maxCols, maxRows) = options.maxCardGridFit()

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // KTP / Kartu / Pasfoto Grid Section
        Card(
            colors = CardDefaults.cardColors(
                containerColor = if (options.hasCardGrid) {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                }
            ),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.GridOn, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Grid KTP (5,5×9 cm) & Pasfoto", style = MaterialTheme.typography.titleSmall)
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    CardGridPreset.entries.forEach { preset ->
                        FilterChip(
                            selected = options.cardGridPreset == preset,
                            onClick = {
                                onUpdate {
                                    if (preset == CardGridPreset.NONE) {
                                        it.copy(cardGridPreset = preset)
                                    } else {
                                        val next = it.copy(
                                            cardGridPreset = preset,
                                            cardWidthMm = preset.defaultWidthMm,
                                            cardHeightMm = preset.defaultHeightMm
                                        )
                                        val (fitC, fitR) = next.maxCardGridFit()
                                        next.copy(
                                            cardColumns = next.cardColumns.coerceAtMost(fitC),
                                            cardRows = next.cardRows.coerceAtMost(fitR)
                                        )
                                    }
                                }
                            },
                            label = { Text(preset.label) }
                        )
                    }
                }

                if (options.hasCardGrid) {
                    Text(
                        text = "Muat maksimal di ${options.paperSize.label}: $maxCols kolom × $maxRows baris (${maxCols * maxRows} kartu). Terpasang: ${options.cardColumns} × ${options.cardRows} = ${options.cardColumns * options.cardRows} kartu/lembar.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )

                    if (options.cardGridPreset == CardGridPreset.CUSTOM_CARD) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = options.cardWidthMm.toString(),
                                onValueChange = { txt ->
                                    txt.toFloatOrNull()?.let { v -> onUpdate { it.copy(cardWidthMm = v.coerceIn(10f, 300f)) } }
                                },
                                label = { Text("Lebar (mm)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = options.cardHeightMm.toString(),
                                onValueChange = { txt ->
                                    txt.toFloatOrNull()?.let { v -> onUpdate { it.copy(cardHeightMm = v.coerceIn(10f, 300f)) } }
                                },
                                label = { Text("Tinggi (mm)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }

                    // Columns & Rows Steppers
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Jumlah Kolom", style = MaterialTheme.typography.bodyMedium)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = { onUpdate { it.copy(cardColumns = (it.cardColumns - 1).coerceAtLeast(1)) } },
                                modifier = Modifier.minimumInteractiveComponentSize().testTag("btn_card_col_minus")
                            ) { Icon(Icons.Default.Remove, contentDescription = "Kurangi kolom") }
                            Text("${options.cardColumns}", style = MaterialTheme.typography.titleMedium)
                            IconButton(
                                onClick = { onUpdate { it.copy(cardColumns = (it.cardColumns + 1).coerceAtMost(10)) } },
                                modifier = Modifier.minimumInteractiveComponentSize().testTag("btn_card_col_plus")
                            ) { Icon(Icons.Default.Add, contentDescription = "Tambah kolom") }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Jumlah Baris", style = MaterialTheme.typography.bodyMedium)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = { onUpdate { it.copy(cardRows = (it.cardRows - 1).coerceAtLeast(1)) } },
                                modifier = Modifier.minimumInteractiveComponentSize().testTag("btn_card_row_minus")
                            ) { Icon(Icons.Default.Remove, contentDescription = "Kurangi baris") }
                            Text("${options.cardRows}", style = MaterialTheme.typography.titleMedium)
                            IconButton(
                                onClick = { onUpdate { it.copy(cardRows = (it.cardRows + 1).coerceAtMost(15)) } },
                                modifier = Modifier.minimumInteractiveComponentSize().testTag("btn_card_row_plus")
                            ) { Icon(Icons.Default.Add, contentDescription = "Tambah baris") }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { onUpdate { it.copy(cardColumns = 1, cardRows = maxRows) } },
                            modifier = Modifier.weight(1f)
                        ) { Text("1 Kolom ($maxRows Baris)") }
                        OutlinedButton(
                            onClick = { onUpdate { it.copy(cardColumns = maxCols, cardRows = maxRows) } },
                            modifier = Modifier.weight(1f)
                        ) { Text("Isi Penuh ($maxCols×$maxRows)") }
                    }

                    LabeledSlider(
                        label = "Jarak Antar Kartu",
                        valueText = "${options.cardGapMm.toInt()} mm",
                        value = options.cardGapMm,
                        range = 0f..15f,
                        onValueChange = { g -> onUpdate { it.copy(cardGapMm = g) } }
                    )

                    OptionSwitchRow(
                        title = "Garis Potong (Cut Marks)",
                        checked = options.cardCutMarks,
                        onCheckedChange = { c -> onUpdate { it.copy(cardCutMarks = c) } }
                    )
                    OptionSwitchRow(
                        title = "Mode 2 Sisi (Depan & Belakang)",
                        subtitle = "Jika ada 2 gambar, selang-seling sisi depan & belakang di grid",
                        checked = options.cardTwoSidedMode,
                        onCheckedChange = { t -> onUpdate { it.copy(cardTwoSidedMode = t) } }
                    )
                }
            }
        }

        // Watermark & Page Numbers
        HorizontalDivider()
        Text("Watermark & Nomor Halaman", style = MaterialTheme.typography.titleSmall)
        OutlinedTextField(
            value = options.watermarkText,
            onValueChange = { txt -> onUpdate { it.copy(watermarkText = txt) } },
            label = { Text("Teks Watermark (mis. SALINAN / RAHASIA)") },
            placeholder = { Text("Kosongkan jika tanpa watermark") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("input_watermark")
        )
        if (options.watermarkText.isNotBlank()) {
            OptionSwitchRow(
                title = "Watermark Diagonal (35°)",
                checked = options.watermarkDiagonal,
                onCheckedChange = { d -> onUpdate { it.copy(watermarkDiagonal = d) } }
            )
            LabeledSlider(
                label = "Transparansi Watermark",
                valueText = "${(options.watermarkOpacity * 100).roundToInt()}%",
                value = options.watermarkOpacity,
                range = 0.08f..0.75f,
                onValueChange = { o -> onUpdate { it.copy(watermarkOpacity = o) } }
            )
        }
        OptionSwitchRow(
            title = "Cetak Nomor Halaman Otomatis (Hal 1 / N)",
            checked = options.showPageNumbers,
            onCheckedChange = { p -> onUpdate { it.copy(showPageNumbers = p) } }
        )

        HorizontalDivider()

        // Copies & Collate
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Jumlah Salinan", style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { onUpdate { it.copy(copies = (it.copies - 1).coerceAtLeast(1)) } },
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Icon(Icons.Default.Remove, contentDescription = "Kurangi salinan")
                }
                Text(
                    text = "${options.copies}",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
                IconButton(
                    onClick = { onUpdate { it.copy(copies = (it.copies + 1).coerceAtMost(999)) } },
                    modifier = Modifier.minimumInteractiveComponentSize()
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Tambah salinan")
                }
            }
        }

        OptionSwitchRow(
            title = "Collate (Urutkan per set)",
            checked = options.collate,
            onCheckedChange = { c -> onUpdate { it.copy(collate = c) } }
        )

        OptionSwitchRow(
            title = "Balik Urutan Halaman",
            checked = options.reverseOrder,
            onCheckedChange = { r -> onUpdate { it.copy(reverseOrder = r) } }
        )

        OutlinedTextField(
            value = options.pageRange,
            onValueChange = { txt -> onUpdate { it.copy(pageRange = txt) } },
            label = { Text("Rentang Halaman (mis. 1-3,5,8-10)") },
            placeholder = { Text("Kosongkan untuk semua halaman") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PageRangeFilter.entries.forEach { f ->
                FilterChip(
                    selected = options.pageRangeFilter == f,
                    onClick = { onUpdate { it.copy(pageRangeFilter = f) } },
                    label = { Text(f.label) }
                )
            }
        }

        Text(
            text = if (capabilities.supportsDuplex) "Duplex (Dua Sisi)" else "Duplex (Tidak dilaporkan oleh printer)",
            style = MaterialTheme.typography.titleSmall
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DuplexMode.entries.forEach { d ->
                FilterChip(
                    selected = options.duplex == d,
                    enabled = d == DuplexMode.OFF || capabilities.supportsDuplex || !capabilities.attributesProbed,
                    onClick = { onUpdate { it.copy(duplex = d) } },
                    label = { Text(d.label) }
                )
            }
        }

        Text("Skala", style = MaterialTheme.typography.titleSmall)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ScaleType.entries.forEach { st ->
                FilterChip(
                    selected = options.scaleType == st,
                    onClick = { onUpdate { it.copy(scaleType = st) } },
                    label = { Text(st.label) }
                )
            }
        }
        if (options.scaleType == ScaleType.PERCENT) {
            LabeledSlider(
                label = "Persentase Skala",
                valueText = "${options.scalePercent}%",
                value = options.scalePercent.toFloat(),
                range = 25f..400f,
                onValueChange = { v -> onUpdate { it.copy(scalePercent = v.roundToInt()) } }
            )
        }

        Text("Margin", style = MaterialTheme.typography.titleSmall)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            MarginType.entries.forEach { mt ->
                FilterChip(
                    selected = options.marginType == mt,
                    onClick = { onUpdate { it.copy(marginType = mt) } },
                    label = { Text(mt.label) }
                )
            }
        }
        if (options.marginType == MarginType.CUSTOM) {
            LabeledSlider(
                label = "Margin Kustom",
                valueText = "${options.customMarginTopMm.toInt()} mm",
                value = options.customMarginTopMm,
                range = 0f..40f,
                onValueChange = { v ->
                    onUpdate {
                        it.copy(
                            customMarginTopMm = v,
                            customMarginBottomMm = v,
                            customMarginLeftMm = v,
                            customMarginRightMm = v
                        )
                    }
                }
            )
        }

        if (!options.hasCardGrid) {
            Text("Halaman per Lembar (N-Up)", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1, 2, 4, 6, 9, 16).forEach { n ->
                    FilterChip(
                        selected = options.pagesPerSheet == n,
                        onClick = { onUpdate { it.copy(pagesPerSheet = n) } },
                        label = { Text(if (n == 1) "1 (Normal)" else "$n-up") }
                    )
                }
            }
            if (options.pagesPerSheet > 1) {
                OptionSwitchRow(
                    title = "Garis Tepi Tiap Sel N-Up",
                    checked = options.nUpBorder,
                    onCheckedChange = { b -> onUpdate { it.copy(nUpBorder = b) } }
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NUpOrder.entries.forEach { ord ->
                        FilterChip(
                            selected = options.nUpOrder == ord,
                            onClick = { onUpdate { it.copy(nUpOrder = ord) } },
                            label = { Text(ord.label) }
                        )
                    }
                }
            }
        }

        Text("Posisi, Putar & Poster", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PagePosition.entries.forEach { pos ->
                FilterChip(
                    selected = options.position == pos,
                    onClick = { onUpdate { it.copy(position = pos) } },
                    label = { Text(pos.label) }
                )
            }
            OutlinedButton(
                onClick = { onUpdate { it.copy(rotationDegrees = (it.rotationDegrees + 90) % 360) } }
            ) {
                Icon(Icons.Default.RotateRight, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text("Putar (${options.rotationDegrees}°)")
            }
            FilterChip(
                selected = options.mirror,
                onClick = { onUpdate { it.copy(mirror = !it.mirror) } },
                leadingIcon = { Icon(Icons.Default.Flip, contentDescription = null) },
                label = { Text("Cermin") }
            )
        }

        if (!options.hasCardGrid) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(1 to "Poster: Nonaktif", 2 to "Poster 2×2 (4 lembar)", 3 to "Poster 3×3 (9 lembar)").forEach { (g, lbl) ->
                    FilterChip(
                        selected = options.posterGrid == g,
                        onClick = { onUpdate { it.copy(posterGrid = g) } },
                        label = { Text(lbl) }
                    )
                }
            }
        }

        Text("Finishing (Mesin Fotokopi)", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FinishingOption.entries.forEach { fin ->
                val active = fin in options.finishing
                FilterChip(
                    selected = active,
                    onClick = {
                        onUpdate {
                            val next = it.finishing.toMutableSet()
                            if (active) next.remove(fin) else next.add(fin)
                            it.copy(finishing = next)
                        }
                    },
                    label = { Text(fin.label) }
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun QualityOptionsPanel(
    options: PrintOptions,
    capabilities: PrinterCapabilities,
    onUpdate: ((PrintOptions) -> PrintOptions) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Mode Warna", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ColorMode.entries.forEach { cm ->
                val enabled = cm == ColorMode.MONOCHROME || capabilities.supportsColor || !capabilities.attributesProbed
                FilterChip(
                    selected = options.colorMode == cm,
                    enabled = enabled,
                    onClick = { onUpdate { it.copy(colorMode = cm) } },
                    label = { Text(cm.label) }
                )
            }
        }

        Text("Kualitas Cetak", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrintQuality.entries.forEach { q ->
                FilterChip(
                    selected = options.quality == q,
                    onClick = { onUpdate { it.copy(quality = q) } },
                    label = { Text(q.label) }
                )
            }
        }

        Text("Resolusi (DPI)", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(150, 300, 600, 1200).forEach { dpi ->
                FilterChip(
                    selected = options.dpi == dpi,
                    onClick = { onUpdate { it.copy(dpi = dpi) } },
                    label = { Text("$dpi dpi") }
                )
            }
        }

        OptionSwitchRow(
            title = "Hemat Toner / Tinta",
            checked = options.tonerSave,
            onCheckedChange = { t -> onUpdate { it.copy(tonerSave = t) } }
        )

        Text("Optimasi Isi", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OptimizeContent.entries.forEach { opt ->
                FilterChip(
                    selected = options.contentOptimize == opt,
                    onClick = { onUpdate { it.copy(contentOptimize = opt) } },
                    label = { Text(opt.label) }
                )
            }
        }

        Text("Rendering Intent (Foto)", style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RenderingIntent.entries.forEach { ri ->
                FilterChip(
                    selected = options.renderingIntent == ri,
                    onClick = { onUpdate { it.copy(renderingIntent = ri) } },
                    label = { Text(ri.label) }
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ImageAdjustmentsPanel(
    options: PrintOptions,
    isPdfDocument: Boolean,
    onUpdate: ((PrintOptions) -> PrintOptions) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Penyesuaian Gambar & Dokumen", style = MaterialTheme.typography.titleSmall)
            TextButton(
                onClick = { onUpdate { it.resetImageAdjustments() } },
                modifier = Modifier.testTag("btn_reset_image_adjustments")
            ) {
                Icon(Icons.Default.RestartAlt, contentDescription = null)
                Spacer(modifier = Modifier.width(4.dp))
                Text("Reset")
            }
        }

        if (isPdfDocument) {
            OptionSwitchRow(
                title = "Terapkan Penyesuaian ke PDF (Rasterisasi)",
                subtitle = "Aktifkan jika ingin mengubah kecerahan/kontras PDF; jika mati, PDF dikirim vektor tajam.",
                checked = options.applyAdjustmentsToPdf,
                onCheckedChange = { a -> onUpdate { it.copy(applyAdjustmentsToPdf = a) } }
            )
        }

        Text("Filter Cepat", style = MaterialTheme.typography.labelLarge)
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ImageFilter.entries.forEach { flt ->
                FilterChip(
                    selected = options.filter == flt,
                    onClick = { onUpdate { it.copy(filter = flt) } },
                    label = { Text(flt.label) }
                )
            }
        }

        OptionSwitchRow(
            title = "Mode Dokumen: Bersihkan Latar",
            subtitle = "Ubah latar kertas foto yang kuning/abu-abu menjadi putih bersih",
            checked = options.cleanBackground,
            onCheckedChange = { c -> onUpdate { it.copy(cleanBackground = c) } }
        )

        OptionSwitchRow(
            title = "Hitamkan Teks",
            checked = options.darkenText,
            onCheckedChange = { d -> onUpdate { it.copy(darkenText = d) } }
        )

        OptionSwitchRow(
            title = "Auto-Enhance Kontras Foto",
            checked = options.autoEnhance,
            onCheckedChange = { a -> onUpdate { it.copy(autoEnhance = a) } }
        )

        LabeledSlider(
            label = "Kecerahan",
            valueText = "${options.brightness}",
            value = options.brightness.toFloat(),
            range = -100f..100f,
            onValueChange = { v -> onUpdate { it.copy(brightness = v.roundToInt()) } }
        )
        LabeledSlider(
            label = "Kontras",
            valueText = "${options.contrast}",
            value = options.contrast.toFloat(),
            range = -100f..100f,
            onValueChange = { v -> onUpdate { it.copy(contrast = v.roundToInt()) } }
        )
        LabeledSlider(
            label = "Ketajaman (Sharpness)",
            valueText = "${options.sharpness}",
            value = options.sharpness.toFloat(),
            range = 0f..100f,
            onValueChange = { v -> onUpdate { it.copy(sharpness = v.roundToInt()) } }
        )
        LabeledSlider(
            label = "Saturasi",
            valueText = "${options.saturation}",
            value = options.saturation.toFloat(),
            range = -100f..100f,
            onValueChange = { v -> onUpdate { it.copy(saturation = v.roundToInt()) } }
        )
        LabeledSlider(
            label = "Gamma / Midtone",
            valueText = String.format("%.2f", options.gamma),
            value = options.gamma,
            range = 0.5f..2.5f,
            onValueChange = { v -> onUpdate { it.copy(gamma = v) } }
        )
        LabeledSlider(
            label = "Suhu Warna (Hangat/Dingin)",
            valueText = "${options.warmth}",
            value = options.warmth.toFloat(),
            range = -100f..100f,
            onValueChange = { v -> onUpdate { it.copy(warmth = v.roundToInt()) } }
        )
        LabeledSlider(
            label = "Kegelapan Cetak (Density)",
            valueText = "Level ${options.darkness}",
            value = options.darkness.toFloat(),
            range = 1f..5f,
            steps = 3,
            onValueChange = { v -> onUpdate { it.copy(darkness = v.roundToInt()) } }
        )

        OptionSwitchRow(
            title = "Ambang Hitam-Putih Manual (Threshold)",
            checked = options.bwThreshold != null,
            onCheckedChange = { enabled ->
                onUpdate { it.copy(bwThreshold = if (enabled) 140 else null) }
            }
        )
        options.bwThreshold?.let { th ->
            LabeledSlider(
                label = "Nilai Ambang B&W",
                valueText = "$th",
                value = th.toFloat(),
                range = 20f..235f,
                onValueChange = { v -> onUpdate { it.copy(bwThreshold = v.roundToInt()) } }
            )
        }
    }
}

@Composable
private fun OptionSwitchRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .minimumInteractiveComponentSize(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(text = title, style = MaterialTheme.typography.bodyMedium)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    onValueChange: (Float) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(text = label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = valueText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValueChange,
            valueRange = range,
            steps = steps
        )
    }
}
