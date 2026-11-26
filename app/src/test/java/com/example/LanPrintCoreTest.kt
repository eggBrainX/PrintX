package com.example

import com.example.data.local.JsonConverters
import com.example.domain.model.CardGridPreset
import com.example.domain.model.ColorMode
import com.example.domain.model.DuplexMode
import com.example.domain.model.FinishingOption
import com.example.domain.model.ImageFilter
import com.example.domain.model.MarginType
import com.example.domain.model.PageRangeFilter
import com.example.domain.model.PaperSize
import com.example.domain.model.PrintOptions
import com.example.rendering.EscPosEncoder
import com.example.rendering.PdfComposer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LanPrintCoreTest {

    @Test
    fun f4IndonesiaDimensionsAreExact() {
        val f4 = PaperSize.F4
        assertEquals(215f, f4.widthMm, 0.01f)
        assertEquals(330f, f4.heightMm, 0.01f)
        assertEquals(21500, f4.widthHundredthsMm)
        assertEquals(33000, f4.heightHundredthsMm)
        assertEquals("custom_f4_215x330mm", f4.pwgMediaName)
    }

    @Test
    fun printOptionsJsonRoundTripPreservesAllSettings() {
        val original = PrintOptions(
            paperSize = PaperSize.F4,
            copies = 3,
            duplex = DuplexMode.LONG_EDGE,
            colorMode = ColorMode.MONOCHROME,
            finishing = setOf(FinishingOption.STAPLE, FinishingOption.PUNCH),
            brightness = 15,
            contrast = -10,
            sharpness = 40,
            cleanBackground = true,
            bwThreshold = 150,
            filter = ImageFilter.SHARP_BW,
            cardGridPreset = CardGridPreset.KTP_ID,
            cardWidthMm = 55f,
            cardHeightMm = 90f,
            cardColumns = 2,
            cardRows = 3,
            watermarkText = "SALINAN",
            showPageNumbers = true
        )
        val json = JsonConverters.optionsToJson(original)
        val restored = JsonConverters.optionsFromJson(json)
        assertEquals(original, restored)
    }

    @Test
    fun pageRangeParsingHandlesRangesAndOddEvenFilters() {
        val indices = PdfComposer.resolvePageIndices(12, "1-3, 5, 8-10", PageRangeFilter.ALL)
        assertEquals(listOf(0, 1, 2, 4, 7, 8, 9), indices)

        val oddOnly = PdfComposer.resolvePageIndices(10, "1-6", PageRangeFilter.ODD)
        assertEquals(listOf(0, 2, 4), oddOnly)

        val evenOnly = PdfComposer.resolvePageIndices(10, "1-6", PageRangeFilter.EVEN)
        assertEquals(listOf(1, 3, 5), evenOnly)
    }

    @Test
    fun nUpGridComputesExpectedColumnsAndRows() {
        assertEquals(2 to 2, PdfComposer.nUpGrid(4, isLandscape = false))
        assertEquals(3 to 3, PdfComposer.nUpGrid(9, isLandscape = false))
        assertEquals(4 to 4, PdfComposer.nUpGrid(16, isLandscape = false))
        assertTrue(PdfComposer.nUpGrid(2, isLandscape = true) == (2 to 1))
    }

    @Test
    fun ktpCardGridMaxFitCalculatesExpectedColumnsAndRows() {
        val a4Default = PrintOptions(
            paperSize = PaperSize.A4,
            marginType = MarginType.DEFAULT,
            cardGridPreset = CardGridPreset.KTP_ID,
            cardWidthMm = 55f,
            cardHeightMm = 90f,
            cardGapMm = 4f
        )
        assertEquals(3 to 2, a4Default.maxCardGridFit())

        val a4Narrow = a4Default.copy(marginType = MarginType.NARROW)
        assertEquals(3 to 3, a4Narrow.maxCardGridFit())
    }

    @Test
    fun escPosEncoderProducesValidRasterCommandBytes() {
        val black = 0xFF000000.toInt()
        val white = 0xFFFFFFFF.toInt()
        // 8x1 row: alternating black and white pixels -> 0b10101010 = 0xAA
        val pixels = intArrayOf(black, white, black, white, black, white, black, white)
        val encoded = EscPosEncoder.encodePixelsToEscPos(pixels, width = 8, height = 1, cutPaper = true)

        // Starts with ESC @ (0x1B, 0x40) and GS v 0 0 (0x1D, 0x76, 0x30, 0x00)
        assertEquals(0x1B.toByte(), encoded[0])
        assertEquals(0x40.toByte(), encoded[1])
        assertEquals(0x1D.toByte(), encoded[2])
        assertEquals(0x76.toByte(), encoded[3])
        assertEquals(0x30.toByte(), encoded[4])
        assertEquals(0x00.toByte(), encoded[5])
        // xL=1, xH=0, yL=1, yH=0
        assertEquals(1.toByte(), encoded[6])
        assertEquals(0.toByte(), encoded[7])
        assertEquals(1.toByte(), encoded[8])
        assertEquals(0.toByte(), encoded[9])
        // Raster byte = 0xAA
        assertEquals(0xAA.toByte(), encoded[10])
        // Ends with partial cut GS V 66 0
        assertEquals(0x1D.toByte(), encoded[encoded.size - 4])
        assertEquals(0x56.toByte(), encoded[encoded.size - 3])
        assertEquals(0x42.toByte(), encoded[encoded.size - 2])
        assertEquals(0x00.toByte(), encoded[encoded.size - 1])
    }
}
