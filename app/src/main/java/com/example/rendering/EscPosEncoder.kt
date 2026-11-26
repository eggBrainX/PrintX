package com.example.rendering

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/**
 * Real ESC/POS binary encoder for 58mm (384px) and 80mm (576px) Wi-Fi/TCP 9100 thermal printers.
 */
object EscPosEncoder {

    fun buildReceiptPreviewBitmap(
        header: String,
        bodyLines: String,
        footer: String,
        qrContent: String,
        paperWidthMm: Int // 58 or 80
    ): Bitmap {
        val widthPx = if (paperWidthMm <= 58) 384 else 576
        val pad = 16
        val contentW = widthPx - pad * 2

        val headerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = if (paperWidthMm <= 58) 26f else 32f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        }
        val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.BLACK
            textSize = if (paperWidthMm <= 58) 20f else 24f
            typeface = Typeface.MONOSPACE
        }
        val footerPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.DKGRAY
            textSize = if (paperWidthMm <= 58) 18f else 21f
            typeface = Typeface.MONOSPACE
        }

        val headerLayout = if (header.isNotBlank()) {
            StaticLayout.Builder.obtain(header, 0, header.length, headerPaint, contentW)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .build()
        } else null

        val bodyLayout = if (bodyLines.isNotBlank()) {
            StaticLayout.Builder.obtain(bodyLines, 0, bodyLines.length, bodyPaint, contentW)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(4f, 1.15f)
                .build()
        } else null

        val footerLayout = if (footer.isNotBlank()) {
            StaticLayout.Builder.obtain(footer, 0, footer.length, footerPaint, contentW)
                .setAlignment(Layout.Alignment.ALIGN_CENTER)
                .build()
        } else null

        val qrSize = if (qrContent.isNotBlank()) (widthPx * 0.45f).toInt() else 0
        val totalHeight = (pad * 2 +
            (headerLayout?.height ?: 0) +
            (if (headerLayout != null) 20 else 0) +
            (bodyLayout?.height ?: 0) +
            (if (bodyLayout != null) 20 else 0) +
            qrSize +
            (if (qrSize > 0) 24 else 0) +
            (footerLayout?.height ?: 0) +
            32).coerceAtLeast(180)

        val bmp = Bitmap.createBitmap(widthPx, totalHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)

        var y = pad.toFloat()
        if (headerLayout != null) {
            canvas.save()
            canvas.translate(pad.toFloat(), y)
            headerLayout.draw(canvas)
            canvas.restore()
            y += headerLayout.height + 8f
            val linePaint = Paint().apply { color = Color.BLACK; strokeWidth = 2f }
            canvas.drawLine(pad.toFloat(), y, (widthPx - pad).toFloat(), y, linePaint)
            y += 12f
        }

        if (bodyLayout != null) {
            canvas.save()
            canvas.translate(pad.toFloat(), y)
            bodyLayout.draw(canvas)
            canvas.restore()
            y += bodyLayout.height + 16f
        }

        if (qrContent.isNotBlank()) {
            val qrBmp = generateDeterministicMatrixCode(qrContent, qrSize)
            val left = (widthPx - qrSize) / 2f
            canvas.drawBitmap(qrBmp, left, y, null)
            y += qrSize + 16f
        }

        if (footerLayout != null) {
            canvas.save()
            canvas.translate(pad.toFloat(), y)
            footerLayout.draw(canvas)
            canvas.restore()
        }

        return bmp
    }

    /**
     * Converts a Bitmap into ESC/POS GS v 0 monochrome raster command bytes + feed & cut.
     */
    fun encodeBitmapToEscPos(bitmap: Bitmap, cutPaper: Boolean = true): ByteArray {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        return encodePixelsToEscPos(pixels, width, height, cutPaper)
    }

    fun encodePixelsToEscPos(
        pixels: IntArray,
        width: Int,
        height: Int,
        cutPaper: Boolean = true
    ): ByteArray {
        val baos = ByteArrayOutputStream()
        // ESC @ (Initialize printer)
        baos.write(byteArrayOf(0x1B, 0x40))

        val widthBytes = (width + 7) / 8

        // GS v 0 m xL xH yL yH (raster bit image)
        baos.write(
            byteArrayOf(
                0x1D,
                0x76,
                0x30,
                0x00,
                (widthBytes and 0xFF).toByte(),
                ((widthBytes shr 8) and 0xFF).toByte(),
                (height and 0xFF).toByte(),
                ((height shr 8) and 0xFF).toByte()
            )
        )

        for (y in 0 until height) {
            for (xb in 0 until widthBytes) {
                var byteVal = 0
                for (bit in 0 until 8) {
                    val x = xb * 8 + bit
                    if (x < width) {
                        val p = pixels[y * width + x]
                        val r = (p shr 16) and 0xFF
                        val g = (p shr 8) and 0xFF
                        val b = p and 0xFF
                        val lum = (r * 30 + g * 59 + b * 11) / 100
                        if (lum < 140) {
                            byteVal = byteVal or (1 shl (7 - bit))
                        }
                    }
                }
                baos.write(byteVal)
            }
        }

        // Feed 4 lines
        baos.write(byteArrayOf(0x1B, 0x64, 0x04))
        // GS V 66 0 (Partial cut)
        if (cutPaper) {
            baos.write(byteArrayOf(0x1D, 0x56, 0x42, 0x00))
        }
        return baos.toByteArray()
    }

    private fun generateDeterministicMatrixCode(data: String, sizePx: Int): Bitmap {
        val modules = 21
        val cell = (sizePx / modules.toFloat()).roundToInt().coerceAtLeast(3)
        val dim = cell * modules
        val bmp = Bitmap.createBitmap(dim, dim, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val p = Paint().apply { color = Color.BLACK }

        fun drawFinder(ox: Int, oy: Int) {
            c.drawRect((ox * cell).toFloat(), (oy * cell).toFloat(), ((ox + 7) * cell).toFloat(), ((oy + 7) * cell).toFloat(), p)
            val wp = Paint().apply { color = Color.WHITE }
            c.drawRect(((ox + 1) * cell).toFloat(), ((oy + 1) * cell).toFloat(), ((ox + 6) * cell).toFloat(), ((oy + 6) * cell).toFloat(), wp)
            c.drawRect(((ox + 2) * cell).toFloat(), ((oy + 2) * cell).toFloat(), ((ox + 5) * cell).toFloat(), ((oy + 5) * cell).toFloat(), p)
        }
        drawFinder(0, 0)
        drawFinder(modules - 7, 0)
        drawFinder(0, modules - 7)

        val bytes = data.toByteArray(Charsets.UTF_8)
        var bitIdx = 0
        for (y in 0 until modules) {
            for (x in 0 until modules) {
                val inTopLeft = x < 8 && y < 8
                val inTopRight = x >= modules - 8 && y < 8
                val inBottomLeft = x < 8 && y >= modules - 8
                if (inTopLeft || inTopRight || inBottomLeft) continue
                val b = bytes[bitIdx % bytes.size].toInt()
                val on = ((b shr (bitIdx % 8)) and 1) == 1 || ((x + y) % 3 == 0)
                if (on) {
                    c.drawRect((x * cell).toFloat(), (y * cell).toFloat(), ((x + 1) * cell).toFloat(), ((y + 1) * cell).toFloat(), p)
                }
                bitIdx++
            }
        }
        return bmp
    }
}
