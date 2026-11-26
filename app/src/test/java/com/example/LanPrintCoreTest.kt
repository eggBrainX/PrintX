package com.example

import com.example.data.local.JsonConverters
import com.example.domain.model.ColorMode
import com.example.domain.model.DuplexMode
import com.example.domain.model.FinishingOption
import com.example.domain.model.ImageFilter
import com.example.domain.model.PageRangeFilter
import com.example.domain.model.PaperSize
import com.example.domain.model.PrintOptions
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
            filter = ImageFilter.SHARP_BW
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
}
