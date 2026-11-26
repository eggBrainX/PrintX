package com.example.rendering

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Encodes rendered PDF pages into PostScript Level 2 or ESC/POS payloads
 * when targeting USB OTG / SMB raw printer drivers that do not accept native PDF.
 */
object PrinterPayloadFormatter {

    suspend fun preparePayloadForRawOrDriver(
        context: Context,
        pdfFile: File,
        supportedCommands: List<String>,
        isColor: Boolean
    ): File = withContext(Dispatchers.IO) {
        val cmdsUpper = supportedCommands.map { it.uppercase() }
        val supportsPdf = cmdsUpper.isEmpty() || cmdsUpper.any { it.contains("PDF") }
        val isEscPos = cmdsUpper.any { it.contains("ESCPOS") || it.contains("ESC/POS") || it == "POS" }
        val isPostScript = !supportsPdf && cmdsUpper.any {
            it.contains("POSTSCRIPT") || it.contains("BR-SCRIPT") || it == "PS"
        }

        if (isEscPos) {
            val escPosFile = File(context.cacheDir, "usb_escpos_${System.currentTimeMillis()}.bin")
            val baos = ByteArrayOutputStream()
            ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
                PdfRenderer(pfd).use { renderer ->
                    for (i in 0 until renderer.pageCount) {
                        renderer.openPage(i).use { page ->
                            val targetW = 384
                            val targetH = ((page.height.toFloat() / page.width.toFloat()) * targetW)
                                .toInt().coerceIn(64, 2400)
                            val bmp = Bitmap.createBitmap(targetW, targetH, Bitmap.Config.ARGB_8888)
                            bmp.eraseColor(Color.WHITE)
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)
                            baos.write(EscPosEncoder.encodeBitmapToEscPos(bmp, cutPaper = i == renderer.pageCount - 1))
                            bmp.recycle()
                        }
                    }
                }
            }
            FileOutputStream(escPosFile).use { it.write(baos.toByteArray()) }
            return@withContext escPosFile
        }

        if (isPostScript) {
            val psFile = File(context.cacheDir, "usb_ps_${System.currentTimeMillis()}.ps")
            encodePdfToPostScript(pdfFile, psFile, isColor)
            return@withContext psFile
        }

        pdfFile
    }

    private fun encodePdfToPostScript(pdfFile: File, outFile: File, isColor: Boolean) {
        ParcelFileDescriptor.open(pdfFile, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { renderer ->
                FileOutputStream(outFile).bufferedWriter(Charsets.US_ASCII).use { writer ->
                    writer.write("%!PS-Adobe-3.0\n")
                    writer.write("%%Creator: LAN Print Android\n")
                    writer.write("%%Pages: ${renderer.pageCount}\n")
                    writer.write("%%EndComments\n")

                    for (i in 0 until renderer.pageCount) {
                        renderer.openPage(i).use { page ->
                            val pageW = page.width
                            val pageH = page.height
                            // Render at ~150 DPI for fast USB/SMB PostScript transfer
                            val scale = 2.0f
                            val w = (pageW * scale).toInt().coerceIn(200, 1800)
                            val h = (pageH * scale).toInt().coerceIn(200, 2600)
                            val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                            bmp.eraseColor(Color.WHITE)
                            page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)

                            writer.write("%%Page: ${i + 1} ${i + 1}\n")
                            writer.write("<< /PageSize [$pageW $pageH] >> setpagedevice\n")
                            writer.write("gsave\n")
                            writer.write("0 0 translate\n")
                            writer.write("$pageW $pageH scale\n")

                            val pixels = IntArray(w * h)
                            bmp.getPixels(pixels, 0, w, 0, 0, w, h)
                            bmp.recycle()

                            if (isColor) {
                                writer.write("/picstr ${w * 3} string def\n")
                                writer.write("$w $h 8 [$w 0 0 -$h 0 $h]\n")
                                writer.write("{ currentfile picstr readhexstring pop } false 3 colorimage\n")
                                val lineSb = StringBuilder(96)
                                for (p in pixels) {
                                    lineSb.append(HEX[(p shr 20) and 0xF]).append(HEX[(p shr 16) and 0xF])
                                    lineSb.append(HEX[(p shr 12) and 0xF]).append(HEX[(p shr 8) and 0xF])
                                    lineSb.append(HEX[(p shr 4) and 0xF]).append(HEX[p and 0xF])
                                    if (lineSb.length >= 78) {
                                        writer.write(lineSb.toString())
                                        writer.write("\n")
                                        lineSb.setLength(0)
                                    }
                                }
                                if (lineSb.isNotEmpty()) {
                                    writer.write(lineSb.toString())
                                    writer.write("\n")
                                }
                            } else {
                                writer.write("/picstr $w string def\n")
                                writer.write("$w $h 8 [$w 0 0 -$h 0 $h]\n")
                                writer.write("{ currentfile picstr readhexstring pop } image\n")
                                val lineSb = StringBuilder(96)
                                for (p in pixels) {
                                    val r = (p shr 16) and 0xFF
                                    val g = (p shr 8) and 0xFF
                                    val b = p and 0xFF
                                    val lum = (r * 30 + g * 59 + b * 11) / 100
                                    lineSb.append(HEX[(lum shr 4) and 0xF]).append(HEX[lum and 0xF])
                                    if (lineSb.length >= 78) {
                                        writer.write(lineSb.toString())
                                        writer.write("\n")
                                        lineSb.setLength(0)
                                    }
                                }
                                if (lineSb.isNotEmpty()) {
                                    writer.write(lineSb.toString())
                                    writer.write("\n")
                                }
                            }
                            writer.write("grestore\n")
                            writer.write("showpage\n")
                        }
                    }
                    writer.write("%%EOF\n")
                }
            }
        }
    }

    private val HEX = "0123456789ABCDEF".toCharArray()
}
