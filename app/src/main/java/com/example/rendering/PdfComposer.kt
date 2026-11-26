package com.example.rendering

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.text.TextPaint
import com.example.domain.model.ContentType
import com.example.domain.model.NUpOrder
import com.example.domain.model.Orientation
import com.example.domain.model.PagePosition
import com.example.domain.model.PageRangeFilter
import com.example.domain.model.PrintOptions
import com.example.domain.model.PrintableDocument
import com.example.domain.model.ScaleType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object PdfComposer {

    suspend fun getPageCount(context: Context, doc: PrintableDocument): Int = withContext(Dispatchers.IO) {
        when (doc.contentType) {
            ContentType.IMAGE -> doc.uris.size.coerceAtLeast(1)
            ContentType.OFFICE_DOC -> 1
            else -> {
                val pfd = openPdfDescriptor(context, doc) ?: return@withContext doc.estimatedPages.coerceAtLeast(1)
                pfd.use { descriptor ->
                    PdfRenderer(descriptor).use { renderer ->
                        renderer.pageCount.coerceAtLeast(1)
                    }
                }
            }
        }
    }

    suspend fun renderPreviewBitmap(
        context: Context,
        doc: PrintableDocument,
        pageIndex: Int,
        options: PrintOptions,
        maxDimPx: Int = 1100
    ): Bitmap? = withContext(Dispatchers.IO) {
        val raw = loadSourcePageBitmap(context, doc, pageIndex, maxDimPx) ?: return@withContext null
        ImageProcessor.processBitmap(raw, options, forPreview = true)
    }

    suspend fun buildOutputPdf(
        context: Context,
        doc: PrintableDocument,
        options: PrintOptions,
        targetPrinterIsDirectPdfCapable: Boolean,
        onProgress: (Float) -> Unit = {}
    ): File = withContext(Dispatchers.IO) {
        val outFile = File(context.cacheDir, "print_job_${System.currentTimeMillis()}.pdf")

        // If Office document, copy raw file directly so Companion Server converts it via LibreOffice
        if (doc.contentType == ContentType.OFFICE_DOC && doc.uris.isNotEmpty()) {
            val rawCopy = File(context.cacheDir, doc.originalFileName)
            context.contentResolver.openInputStream(doc.uris.first())?.use { inp ->
                FileOutputStream(rawCopy).use { out -> inp.copyTo(out) }
            }
            return@withContext rawCopy
        }

        // Fast vector passthrough for PDF when no raster/layout/watermark rewrite is needed
        val isUnmodifiedPdf = (doc.contentType == ContentType.PDF ||
            doc.contentType == ContentType.WEB_PAGE ||
            doc.contentType == ContentType.CONTACTS ||
            doc.contentType == ContentType.CALENDAR ||
            doc.contentType == ContentType.TEXT_EMAIL ||
            doc.contentType == ContentType.TEST_PAGE ||
            doc.contentType == ContentType.SYSTEM_PRINT) &&
            targetPrinterIsDirectPdfCapable &&
            !options.applyAdjustmentsToPdf &&
            !options.hasCardGrid &&
            !options.hasWatermarkOrPageNum &&
            options.pagesPerSheet == 1 &&
            options.posterGrid == 1 &&
            options.pageRange.isBlank() &&
            options.pageRangeFilter == PageRangeFilter.ALL &&
            !options.reverseOrder &&
            options.rotationDegrees % 360 == 0 &&
            !options.mirror

        if (isUnmodifiedPdf) {
            if (doc.localPdfPath != null) {
                val localFile = File(doc.localPdfPath)
                if (localFile.exists()) return@withContext localFile
            }
            if (doc.uris.isNotEmpty()) {
                context.contentResolver.openInputStream(doc.uris.first())?.use { inp ->
                    FileOutputStream(outFile).use { out -> inp.copyTo(out) }
                }
                return@withContext outFile
            }
        }

        val totalSourcePages = getPageCount(context, doc)
        var selectedIndices = resolvePageIndices(totalSourcePages, options.pageRange, options.pageRangeFilter)
        if (options.reverseOrder) selectedIndices = selectedIndices.reversed()
        if (selectedIndices.isEmpty()) selectedIndices = (0 until totalSourcePages).toList()

        val pdfDoc = PdfDocument()
        val margins = options.effectiveMarginsMm
        val marginTopPt = mmToPt(margins[0])
        val marginRightPt = mmToPt(margins[1])
        val marginBottomPt = mmToPt(margins[2])
        val marginLeftPt = mmToPt(margins[3])
        val offsetXPt = mmToPt(options.offsetXMm)
        val offsetYPt = mmToPt(options.offsetYMm)

        val renderDpi = options.dpi.coerceIn(150, 300)
        val maxRenderPx = (renderDpi * 11).coerceIn(1200, 3200)

        val nUp = options.pagesPerSheet.coerceIn(1, 16)
        val poster = options.posterGrid.coerceIn(1, 3)

        try {
            var sheetIndex = 0
            if (options.hasCardGrid) {
                // Card / KTP (5.5 x 9 cm) / Pasfoto precision millimeter grid
                val firstBmp = loadSourcePageBitmap(context, doc, selectedIndices.first(), maxRenderPx)
                val secondBmp = if (selectedIndices.size > 1) {
                    loadSourcePageBitmap(context, doc, selectedIndices[1], maxRenderPx)
                } else null

                val procFirst = firstBmp?.let { ImageProcessor.processBitmap(it, options) }
                val procSecond = secondBmp?.let { ImageProcessor.processBitmap(it, options) }

                val baseW = options.paperSize.widthPoints
                val baseH = options.paperSize.heightPoints
                val isLand = options.orientation == Orientation.LANDSCAPE
                val pageW = if (isLand) max(baseW, baseH) else min(baseW, baseH)
                val pageH = if (isLand) min(baseW, baseH) else max(baseW, baseH)

                sheetIndex = 1
                val pageInfo = PdfDocument.PageInfo.Builder(pageW, pageH, sheetIndex).create()
                val page = pdfDoc.startPage(pageInfo)
                page.canvas.drawColor(Color.WHITE)

                val cols = options.cardColumns.coerceIn(1, 10)
                val rows = options.cardRows.coerceIn(1, 15)
                val cardW = mmToPt(options.cardWidthMm.coerceIn(10f, 300f))
                val cardH = mmToPt(options.cardHeightMm.coerceIn(10f, 300f))
                val gapPt = mmToPt(options.cardGapMm.coerceIn(0f, 30f))

                val totalGridW = cols * cardW + (cols - 1) * gapPt
                val totalGridH = rows * cardH + (rows - 1) * gapPt
                val startX = ((pageW - totalGridW) / 2f).coerceAtLeast(marginLeftPt) + offsetXPt
                val startY = ((pageH - totalGridH) / 2f).coerceAtLeast(marginTopPt) + offsetYPt

                val cutPaint = Paint().apply {
                    color = Color.parseColor("#64748B")
                    strokeWidth = 0.7f
                    style = Paint.Style.STROKE
                }

                var slotNum = 0
                for (r in 0 until rows) {
                    for (c in 0 until cols) {
                        val left = startX + c * (cardW + gapPt)
                        val top = startY + r * (cardH + gapPt)
                        val slotRect = RectF(left, top, left + cardW, top + cardH)

                        val bmpToDraw = when {
                            procSecond != null && options.cardTwoSidedMode ->
                                if ((c % 2 == 1) || (cols == 1 && r % 2 == 1)) procSecond else procFirst
                            procSecond != null && selectedIndices.size > 1 ->
                                if (slotNum % 2 == 1) procSecond else procFirst
                            else -> procFirst
                        }

                        if (bmpToDraw != null) {
                            val dstRect = computeScaledRect(
                                bmpToDraw.width.toFloat(),
                                bmpToDraw.height.toFloat(),
                                slotRect,
                                ScaleType.FILL,
                                100,
                                PagePosition.CENTER
                            )
                            page.canvas.save()
                            page.canvas.clipRect(slotRect)
                            page.canvas.drawBitmap(bmpToDraw, null, dstRect, Paint(Paint.FILTER_BITMAP_FLAG))
                            page.canvas.restore()
                        }

                        if (options.cardCutMarks) {
                            drawCornerCutMarks(page.canvas, slotRect, cutPaint)
                        }
                        slotNum++
                    }
                }

                drawOverlayDecorations(page.canvas, pageW, pageH, sheetIndex, 1, options)
                pdfDoc.finishPage(page)
                onProgress(0.9f)
            } else if (poster > 1 && nUp == 1) {
                val totalSheets = selectedIndices.size * poster * poster
                selectedIndices.forEachIndexed { idx, srcIdx ->
                    val rawBmp = loadSourcePageBitmap(context, doc, srcIdx, maxRenderPx)
                    if (rawBmp != null) {
                        val processed = ImageProcessor.processBitmap(rawBmp, options)
                        val (pageW, pageH) = resolveSheetPoints(options, processed.width, processed.height)
                        for (row in 0 until poster) {
                            for (col in 0 until poster) {
                                sheetIndex++
                                val pageInfo = PdfDocument.PageInfo.Builder(pageW, pageH, sheetIndex).create()
                                val page = pdfDoc.startPage(pageInfo)
                                page.canvas.drawColor(Color.WHITE)

                                val tileW = processed.width / poster
                                val tileH = processed.height / poster
                                val srcRect = Rect(
                                    col * tileW,
                                    row * tileH,
                                    if (col == poster - 1) processed.width else (col + 1) * tileW,
                                    if (row == poster - 1) processed.height else (row + 1) * tileH
                                )
                                val contentRect = RectF(
                                    marginLeftPt,
                                    marginTopPt,
                                    pageW - marginRightPt,
                                    pageH - marginBottomPt
                                )
                                val dstRect = computeScaledRect(
                                    srcRect.width().toFloat(),
                                    srcRect.height().toFloat(),
                                    contentRect,
                                    ScaleType.FIT,
                                    100,
                                    PagePosition.CENTER
                                )
                                page.canvas.drawBitmap(processed, srcRect, dstRect, Paint(Paint.FILTER_BITMAP_FLAG))
                                drawOverlayDecorations(page.canvas, pageW, pageH, sheetIndex, totalSheets, options)
                                pdfDoc.finishPage(page)
                            }
                        }
                    }
                    onProgress(((idx + 1).toFloat() / selectedIndices.size.toFloat()).coerceIn(0f, 0.9f))
                }
            } else {
                val chunks = selectedIndices.chunked(nUp)
                chunks.forEachIndexed { chunkIdx, group ->
                    val firstBmp = loadSourcePageBitmap(context, doc, group.first(), maxRenderPx)
                    val (pageW, pageH) = resolveSheetPoints(
                        options,
                        firstBmp?.width ?: 1000,
                        firstBmp?.height ?: 1414
                    )
                    sheetIndex++
                    val pageInfo = PdfDocument.PageInfo.Builder(pageW, pageH, sheetIndex).create()
                    val page = pdfDoc.startPage(pageInfo)
                    page.canvas.drawColor(Color.WHITE)

                    val contentRect = RectF(
                        marginLeftPt + offsetXPt,
                        marginTopPt + offsetYPt,
                        (pageW - marginRightPt + offsetXPt).coerceAtLeast(marginLeftPt + 20f),
                        (pageH - marginBottomPt + offsetYPt).coerceAtLeast(marginTopPt + 20f)
                    )

                    val (cols, rows) = nUpGrid(nUp, pageW > pageH)
                    val cellW = contentRect.width() / cols
                    val cellH = contentRect.height() / rows
                    val borderPaint = Paint().apply {
                        color = Color.LTGRAY
                        style = Paint.Style.STROKE
                        strokeWidth = 0.8f
                    }

                    group.forEachIndexed { slotIdx, srcIdx ->
                        val bmp = if (slotIdx == 0 && firstBmp != null) {
                            firstBmp
                        } else {
                            loadSourcePageBitmap(context, doc, srcIdx, maxRenderPx)
                        }
                        if (bmp != null) {
                            val processed = ImageProcessor.processBitmap(bmp, options)
                            val col = if (options.nUpOrder == NUpOrder.LEFT_TO_RIGHT) slotIdx % cols else slotIdx / rows
                            val row = if (options.nUpOrder == NUpOrder.LEFT_TO_RIGHT) slotIdx / cols else slotIdx % rows
                            val cellRect = RectF(
                                contentRect.left + col * cellW + (if (nUp > 1) 4f else 0f),
                                contentRect.top + row * cellH + (if (nUp > 1) 4f else 0f),
                                contentRect.left + (col + 1) * cellW - (if (nUp > 1) 4f else 0f),
                                contentRect.top + (row + 1) * cellH - (if (nUp > 1) 4f else 0f)
                            )
                            val dstRect = computeScaledRect(
                                processed.width.toFloat(),
                                processed.height.toFloat(),
                                cellRect,
                                options.scaleType,
                                options.scalePercent,
                                options.position
                            )
                            page.canvas.save()
                            page.canvas.clipRect(cellRect)
                            page.canvas.drawBitmap(processed, null, dstRect, Paint(Paint.FILTER_BITMAP_FLAG))
                            page.canvas.restore()

                            if (options.nUpBorder && nUp > 1) {
                                page.canvas.drawRect(cellRect, borderPaint)
                            }
                        }
                    }
                    drawOverlayDecorations(page.canvas, pageW, pageH, sheetIndex, chunks.size, options)
                    pdfDoc.finishPage(page)
                    onProgress(((chunkIdx + 1).toFloat() / chunks.size.toFloat()).coerceIn(0f, 0.9f))
                }
            }

            FileOutputStream(outFile).use { fos ->
                pdfDoc.writeTo(fos)
            }
        } finally {
            pdfDoc.close()
        }

        outFile
    }

    private fun drawCornerCutMarks(canvas: Canvas, rect: RectF, paint: Paint) {
        val len = 8f
        // Top-left
        canvas.drawLine(rect.left - 2f, rect.top, rect.left + len, rect.top, paint)
        canvas.drawLine(rect.left, rect.top - 2f, rect.left, rect.top + len, paint)
        // Top-right
        canvas.drawLine(rect.right - len, rect.top, rect.right + 2f, rect.top, paint)
        canvas.drawLine(rect.right, rect.top - 2f, rect.right, rect.top + len, paint)
        // Bottom-left
        canvas.drawLine(rect.left - 2f, rect.bottom, rect.left + len, rect.bottom, paint)
        canvas.drawLine(rect.left, rect.bottom - len, rect.left, rect.bottom + 2f, paint)
        // Bottom-right
        canvas.drawLine(rect.right - len, rect.bottom, rect.right + 2f, rect.bottom, paint)
        canvas.drawLine(rect.right, rect.bottom - len, rect.right, rect.bottom + 2f, paint)
    }

    private fun drawOverlayDecorations(
        canvas: Canvas,
        pageW: Int,
        pageH: Int,
        pageNumber: Int,
        totalPages: Int,
        options: PrintOptions
    ) {
        if (options.watermarkText.isNotBlank()) {
            val alpha = (options.watermarkOpacity.coerceIn(0.05f, 0.85f) * 255).roundToInt()
            val wmPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(alpha, 100, 116, 139)
                textSize = (min(pageW, pageH) * 0.085f).coerceIn(22f, 64f)
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
                textAlign = Paint.Align.CENTER
            }
            canvas.save()
            canvas.translate(pageW / 2f, pageH / 2f)
            if (options.watermarkDiagonal) {
                canvas.rotate(-35f)
            }
            canvas.drawText(options.watermarkText.take(40), 0f, wmPaint.textSize / 3f, wmPaint)
            canvas.restore()
        }

        if (options.showPageNumbers) {
            val numPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.parseColor("#334155")
                textSize = 10.5f
                typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
                textAlign = Paint.Align.CENTER
            }
            val label = "${options.pageNumberPrefix}$pageNumber / $totalPages"
            canvas.drawText(label, pageW / 2f, pageH - 18f, numPaint)
        }
    }

    fun resolvePageIndices(totalPages: Int, rangeExpr: String, filter: PageRangeFilter): List<Int> {
        if (totalPages <= 0) return emptyList()
        val base = if (rangeExpr.isBlank()) {
            (1..totalPages).toList()
        } else {
            val result = linkedSetOf<Int>()
            rangeExpr.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { part ->
                if (part.contains("-")) {
                    val bounds = part.split("-")
                    val start = bounds.getOrNull(0)?.trim()?.toIntOrNull()?.coerceIn(1, totalPages)
                    val end = bounds.getOrNull(1)?.trim()?.toIntOrNull()?.coerceIn(1, totalPages)
                    if (start != null && end != null) {
                        val range = if (start <= end) start..end else start downTo end
                        range.forEach { result.add(it) }
                    }
                } else {
                    part.toIntOrNull()?.takeIf { it in 1..totalPages }?.let { result.add(it) }
                }
            }
            if (result.isEmpty()) (1..totalPages).toList() else result.toList()
        }

        return base.filter { pageNum ->
            when (filter) {
                PageRangeFilter.ALL -> true
                PageRangeFilter.ODD -> pageNum % 2 == 1
                PageRangeFilter.EVEN -> pageNum % 2 == 0
            }
        }.map { it - 1 }
    }

    fun nUpGrid(nUp: Int, isLandscape: Boolean): Pair<Int, Int> {
        return when (nUp) {
            2 -> if (isLandscape) 2 to 1 else 1 to 2
            4 -> 2 to 2
            6 -> if (isLandscape) 3 to 2 else 2 to 3
            9 -> 3 to 3
            16 -> 4 to 4
            else -> 1 to 1
        }
    }

    private fun resolveSheetPoints(options: PrintOptions, srcW: Int, srcH: Int): Pair<Int, Int> {
        val baseW = options.paperSize.widthPoints
        val baseH = options.paperSize.heightPoints
        val landscape = when (options.orientation) {
            Orientation.LANDSCAPE -> true
            Orientation.PORTRAIT -> false
            Orientation.AUTO -> srcW > srcH
        }
        return if (landscape) max(baseW, baseH) to min(baseW, baseH) else min(baseW, baseH) to max(baseW, baseH)
    }

    private fun computeScaledRect(
        srcW: Float,
        srcH: Float,
        bounds: RectF,
        scaleType: ScaleType,
        scalePercent: Int,
        position: PagePosition
    ): RectF {
        val boxW = bounds.width().coerceAtLeast(1f)
        val boxH = bounds.height().coerceAtLeast(1f)
        val scale = when (scaleType) {
            ScaleType.FIT -> min(boxW / srcW, boxH / srcH)
            ScaleType.FILL -> max(boxW / srcW, boxH / srcH)
            ScaleType.ACTUAL -> 1f
            ScaleType.PERCENT -> (scalePercent.coerceIn(25, 400) / 100f) * min(boxW / srcW, boxH / srcH)
        }
        val drawW = srcW * scale
        val drawH = srcH * scale
        val left = if (position == PagePosition.CENTER) bounds.left + (boxW - drawW) / 2f else bounds.left
        val top = if (position == PagePosition.CENTER) bounds.top + (boxH - drawH) / 2f else bounds.top
        return RectF(left, top, left + drawW, top + drawH)
    }

    private fun loadSourcePageBitmap(
        context: Context,
        doc: PrintableDocument,
        pageIndex: Int,
        maxDimPx: Int
    ): Bitmap? {
        if (doc.contentType == ContentType.IMAGE) {
            val uri = doc.uris.getOrNull(pageIndex) ?: doc.uris.firstOrNull() ?: return null
            return decodeSampledBitmapFromUri(context, uri, maxDimPx)
        }
        if (doc.contentType == ContentType.OFFICE_DOC) {
            return null
        }
        val pfd = openPdfDescriptor(context, doc) ?: return null
        return pfd.use { descriptor ->
            PdfRenderer(descriptor).use { renderer ->
                val idx = pageIndex.coerceIn(0, (renderer.pageCount - 1).coerceAtLeast(0))
                renderer.openPage(idx).use { page ->
                    val scale = (maxDimPx.toFloat() / max(page.width, page.height).toFloat()).coerceIn(0.5f, 3f)
                    val bmpW = (page.width * scale).roundToInt().coerceAtLeast(1)
                    val bmpH = (page.height * scale).roundToInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                    bmp
                }
            }
        }
    }

    private fun openPdfDescriptor(context: Context, doc: PrintableDocument): ParcelFileDescriptor? {
        doc.localPdfPath?.let { path ->
            val file = File(path)
            if (file.exists()) {
                return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            }
        }
        val uri = doc.uris.firstOrNull() ?: return null
        return runCatching { context.contentResolver.openFileDescriptor(uri, "r") }.getOrNull()
    }

    private fun decodeSampledBitmapFromUri(context: Context, uri: Uri, maxDimPx: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null

        var inSampleSize = 1
        while (opts.outWidth / inSampleSize > maxDimPx * 2 || opts.outHeight / inSampleSize > maxDimPx * 2) {
            inSampleSize *= 2
        }
        val decodeOpts = BitmapFactory.Options().apply { this.inSampleSize = inSampleSize }
        return context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, decodeOpts)
        }
    }

    fun mmToPt(mm: Float): Float = mm * 72f / 25.4f
}
