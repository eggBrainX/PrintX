package com.example.rendering

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.text.HtmlCompat
import com.example.domain.model.PrintOptions
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ContactEntry(
    val id: String,
    val name: String,
    val phones: List<String>,
    val emails: List<String>
)

data class CalendarEventEntry(
    val id: String,
    val title: String,
    val startTimeMillis: Long,
    val endTimeMillis: Long,
    val location: String,
    val description: String
)

object DocumentGenerators {

    fun generateTestPagePdf(context: Context, printerName: String, options: PrintOptions): File {
        val file = File(context.cacheDir, "test_page_${System.currentTimeMillis()}.pdf")
        val pdf = PdfDocument()
        val w = options.paperSize.widthPoints
        val h = options.paperSize.heightPoints
        val page = pdf.startPage(PdfDocument.PageInfo.Builder(w, h, 1).create())
        val c = page.canvas
        c.drawColor(Color.WHITE)

        val borderPaint = Paint().apply {
            color = Color.parseColor("#0F172A")
            style = Paint.Style.STROKE
            strokeWidth = 2f
        }
        c.drawRect(28f, 28f, w - 28f, h - 28f, borderPaint)

        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0F172A")
            textSize = 22f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val subPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#475569")
            textSize = 11f
        }
        val monoPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0F172A")
            textSize = 10f
            typeface = Typeface.MONOSPACE
        }

        c.drawText("LAN PRINT — HALAMAN UJI CETAK", 48f, 68f, titlePaint)
        c.drawText("Printer: $printerName  •  Kertas: ${options.paperSize.label} (${options.paperSize.dimensionLabel})", 48f, 88f, subPaint)
        val ts = SimpleDateFormat("dd MMM yyyy HH:mm:ss", Locale("id", "ID")).format(Date())
        c.drawText("Waktu Uji: $ts  •  Resolusi Target: ${options.dpi} DPI", 48f, 104f, subPaint)

        // Color & CMYK calibration swatches
        val swatchColors = listOf(
            Color.CYAN to "C",
            Color.MAGENTA to "M",
            Color.YELLOW to "Y",
            Color.BLACK to "K",
            Color.RED to "R",
            Color.GREEN to "G",
            Color.BLUE to "B"
        )
        val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        swatchColors.forEachIndexed { idx, (col, label) ->
            val left = 48f + idx * 62f
            barPaint.color = col
            c.drawRect(left, 128f, left + 52f, 170f, barPaint)
            c.drawText(label, left + 22f, 185f, monoPaint)
        }

        // Grayscale step wedge (0% to 100%)
        for (i in 0..9) {
            val v = 255 - (i * 255 / 9)
            barPaint.color = Color.rgb(v, v, v)
            val left = 48f + i * 44f
            c.drawRect(left, 205f, left + 40f, 238f, barPaint)
            c.drawRect(left, 205f, left + 40f, 238f, borderPaint.apply { strokeWidth = 0.5f })
        }

        // Typography size ladder
        var y = 278f
        listOf(18f, 14f, 11f, 9f, 7f).forEach { sz ->
            val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.BLACK
                textSize = sz
            }
            c.drawText("Uji ketajaman teks ${sz.toInt()}pt — ABCDEFG abcdefg 0123456789", 48f, y, tp)
            y += sz + 10f
        }

        // Alignment grid & diagonal hairlines
        val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#64748B")
            strokeWidth = 0.7f
            style = Paint.Style.STROKE
        }
        val boxTop = y + 18f
        val boxBottom = (h - 60f).coerceAtLeast(boxTop + 120f)
        c.drawRect(48f, boxTop, w - 48f, boxBottom, gridPaint)
        c.drawLine(48f, boxTop, w - 48f, boxBottom, gridPaint)
        c.drawLine(w - 48f, boxTop, 48f, boxBottom, gridPaint)
        c.drawCircle(w / 2f, (boxTop + boxBottom) / 2f, 45f, gridPaint)

        pdf.finishPage(page)
        FileOutputStream(file).use { pdf.writeTo(it) }
        pdf.close()
        return file
    }

    fun generateContactsPdf(context: Context, contacts: List<ContactEntry>): File {
        val file = File(context.cacheDir, "contacts_${System.currentTimeMillis()}.pdf")
        val pdf = PdfDocument()
        val w = 595
        val h = 842
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0F172A")
            textSize = 18f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val namePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0F172A")
            textSize = 12f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val detailPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#475569")
            textSize = 10.5f
        }
        val linePaint = Paint().apply {
            color = Color.parseColor("#CBD5E1")
            strokeWidth = 0.7f
        }

        var pageNum = 1
        var page = pdf.startPage(PdfDocument.PageInfo.Builder(w, h, pageNum).create())
        var c = page.canvas
        c.drawColor(Color.WHITE)
        c.drawText("Daftar Kontak (${contacts.size})", 44f, 54f, titlePaint)
        var y = 84f

        contacts.forEach { contact ->
            val neededHeight = 36f + contact.phones.size * 14f + contact.emails.size * 14f
            if (y + neededHeight > h - 48f) {
                pdf.finishPage(page)
                pageNum++
                page = pdf.startPage(PdfDocument.PageInfo.Builder(w, h, pageNum).create())
                c = page.canvas
                c.drawColor(Color.WHITE)
                y = 54f
            }
            c.drawText(contact.name, 44f, y, namePaint)
            y += 16f
            contact.phones.forEach { phone ->
                c.drawText("Telp: $phone", 52f, y, detailPaint)
                y += 14f
            }
            contact.emails.forEach { email ->
                c.drawText("Email: $email", 52f, y, detailPaint)
                y += 14f
            }
            c.drawLine(44f, y + 4f, w - 44f, y + 4f, linePaint)
            y += 16f
        }

        pdf.finishPage(page)
        FileOutputStream(file).use { pdf.writeTo(it) }
        pdf.close()
        return file
    }

    fun generateCalendarPdf(context: Context, events: List<CalendarEventEntry>, rangeLabel: String): File {
        val file = File(context.cacheDir, "calendar_${System.currentTimeMillis()}.pdf")
        val pdf = PdfDocument()
        val w = 595
        val h = 842
        val dfDate = SimpleDateFormat("EEE, dd MMM yyyy HH:mm", Locale("id", "ID"))

        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0F172A")
            textSize = 18f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val itemTitlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0284C7")
            textSize = 12.5f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#334155")
            textSize = 10.5f
        }
        val cardBorder = Paint().apply {
            color = Color.parseColor("#CBD5E1")
            style = Paint.Style.STROKE
            strokeWidth = 1f
        }

        var pageNum = 1
        var page = pdf.startPage(PdfDocument.PageInfo.Builder(w, h, pageNum).create())
        var c = page.canvas
        c.drawColor(Color.WHITE)
        c.drawText("Agenda Kalender — $rangeLabel", 44f, 54f, titlePaint)
        var y = 84f

        events.forEach { ev ->
            if (y + 84f > h - 48f) {
                pdf.finishPage(page)
                pageNum++
                page = pdf.startPage(PdfDocument.PageInfo.Builder(w, h, pageNum).create())
                c = page.canvas
                c.drawColor(Color.WHITE)
                y = 54f
            }
            val boxBottom = y + 66f
            c.drawRoundRect(RectF(44f, y, w - 44f, boxBottom), 8f, 8f, cardBorder)
            c.drawText(ev.title.ifBlank { "(Tanpa Judul)" }, 56f, y + 20f, itemTitlePaint)
            c.drawText(
                "${dfDate.format(Date(ev.startTimeMillis))} — ${dfDate.format(Date(ev.endTimeMillis))}",
                56f,
                y + 37f,
                bodyPaint
            )
            val loc = ev.location.ifBlank { "Tidak ada lokasi" }
            c.drawText("Lokasi: $loc", 56f, y + 53f, bodyPaint)
            y = boxBottom + 12f
        }

        pdf.finishPage(page)
        FileOutputStream(file).use { pdf.writeTo(it) }
        pdf.close()
        return file
    }

    fun generateTextOrEmailPdf(context: Context, title: String, content: String, isHtml: Boolean): File {
        val plainText = if (isHtml) {
            HtmlCompat.fromHtml(content, HtmlCompat.FROM_HTML_MODE_LEGACY).toString()
        } else {
            content
        }
        val file = File(context.cacheDir, "text_doc_${System.currentTimeMillis()}.pdf")
        val pdf = PdfDocument()
        val w = 595
        val h = 842
        val margin = 48
        val contentW = w - margin * 2

        val headerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#0F172A")
            textSize = 15f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        }
        val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#1E293B")
            textSize = 11.5f
        }

        val layout = StaticLayout.Builder
            .obtain(plainText, 0, plainText.length, bodyPaint, contentW)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(4f, 1.15f)
            .build()

        var lineIdx = 0
        var pageNum = 1
        while (lineIdx < layout.lineCount || pageNum == 1) {
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(w, h, pageNum).create())
            val canvas = page.canvas
            canvas.drawColor(Color.WHITE)

            var yCursor = 52f
            if (pageNum == 1 && title.isNotBlank()) {
                canvas.drawText(title.take(64), margin.toFloat(), yCursor, headerPaint)
                yCursor += 26f
            }

            val maxY = h - 48f
            while (lineIdx < layout.lineCount) {
                val lineHeight = (layout.getLineBottom(lineIdx) - layout.getLineTop(lineIdx)).toFloat()
                if (yCursor + lineHeight > maxY) break
                val start = layout.getLineStart(lineIdx)
                val end = layout.getLineEnd(lineIdx)
                val lineText = plainText.substring(start, end).trimEnd('\n', '\r')
                canvas.drawText(lineText, margin.toFloat(), yCursor + lineHeight * 0.75f, bodyPaint)
                yCursor += lineHeight
                lineIdx++
            }
            pdf.finishPage(page)
            pageNum++
            if (lineIdx >= layout.lineCount) break
        }

        FileOutputStream(file).use { pdf.writeTo(it) }
        pdf.close()
        return file
    }

    fun generatePdfFromBitmapSnapshots(context: Context, snapshots: List<Bitmap>): File {
        val file = File(context.cacheDir, "webpage_${System.currentTimeMillis()}.pdf")
        val pdf = PdfDocument()
        val pageW = 595
        val pageH = 842
        snapshots.forEachIndexed { idx, bmp ->
            val page = pdf.startPage(PdfDocument.PageInfo.Builder(pageW, pageH, idx + 1).create())
            val canvas = page.canvas
            canvas.drawColor(Color.WHITE)
            val scale = minOf((pageW - 32f) / bmp.width, (pageH - 32f) / bmp.height)
            val dw = bmp.width * scale
            val dh = bmp.height * scale
            val dst = RectF(16f, 16f, 16f + dw, 16f + dh)
            canvas.drawBitmap(bmp, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
            pdf.finishPage(page)
        }
        FileOutputStream(file).use { pdf.writeTo(it) }
        pdf.close()
        return file
    }
}
