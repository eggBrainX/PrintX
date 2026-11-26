package com.example.domain.model

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class PaperSize(
    val id: String,
    val label: String,
    val widthMm: Float,
    val heightMm: Float,
    val pwgMediaName: String,
    val isCustom: Boolean = false
) {
    val widthPoints: Int get() = (widthMm * 72f / 25.4f).toInt().coerceAtLeast(72)
    val heightPoints: Int get() = (heightMm * 72f / 25.4f).toInt().coerceAtLeast(72)
    val widthHundredthsMm: Int get() = (widthMm * 100f).toInt()
    val heightHundredthsMm: Int get() = (heightMm * 100f).toInt()
    val aspectRatio: Float get() = if (heightMm > 0f) widthMm / heightMm else 0.707f
    val dimensionLabel: String get() = "${widthMm.toInt()}×${heightMm.toInt()} mm"

    companion object {
        val A3 = PaperSize("A3", "A3", 297f, 420f, "iso_a3_297x420mm")
        val A4 = PaperSize("A4", "A4", 210f, 297f, "iso_a4_210x297mm")
        val A5 = PaperSize("A5", "A5", 148f, 210f, "iso_a5_148x210mm")
        val A6 = PaperSize("A6", "A6", 105f, 148f, "iso_a6_105x148mm")
        val B4 = PaperSize("B4", "B4 (JIS)", 257f, 364f, "jis_b4_257x364mm")
        val B5 = PaperSize("B5", "B5 (JIS)", 182f, 257f, "jis_b5_182x257mm")
        // F4 Indonesia (215x330 mm) differs from PWG Folio (210x330 mm)
        val F4 = PaperSize("F4", "F4 / Folio ID", 215f, 330f, "custom_f4_215x330mm")
        val LETTER = PaperSize("LETTER", "Letter", 215.9f, 279.4f, "na_letter_8.5x11in")
        val LEGAL = PaperSize("LEGAL", "Legal", 215.9f, 355.6f, "na_legal_8.5x14in")
        val TABLOID = PaperSize("TABLOID", "Tabloid / Ledger", 279.4f, 431.8f, "na_ledger_11x17in")
        val EXECUTIVE = PaperSize("EXECUTIVE", "Executive", 184.15f, 266.7f, "na_executive_7.25x10.5in")
        val STATEMENT = PaperSize("STATEMENT", "Statement", 139.7f, 215.9f, "na_invoice_5.5x8.5in")
        val PHOTO_4X6 = PaperSize("4X6", "Foto 4×6\"", 101.6f, 152.4f, "na_index-4x6_4x6in")
        val PHOTO_5X7 = PaperSize("5X7", "Foto 5×7\"", 127f, 177.8f, "na_5x7_5x7in")
        val ENV_DL = PaperSize("ENV_DL", "Amplop DL", 110f, 220f, "iso_dl_110x220mm")
        val ENV_C5 = PaperSize("ENV_C5", "Amplop C5", 162f, 229f, "iso_c5_162x229mm")
        val ENV_NO10 = PaperSize("ENV_10", "Amplop No.10", 104.8f, 241.3f, "na_number-10_4.125x9.5in")

        fun custom(widthMm: Float, heightMm: Float): PaperSize {
            val w = widthMm.coerceIn(50f, 600f)
            val h = heightMm.coerceIn(50f, 1000f)
            return PaperSize(
                id = "CUSTOM",
                label = "Kustom",
                widthMm = w,
                heightMm = h,
                pwgMediaName = "custom_${w.toInt()}x${h.toInt()}mm_${w.toInt()}x${h.toInt()}mm",
                isCustom = true
            )
        }

        val STANDARD_LIST = listOf(
            A4, F4, A3, A5, A6, B4, B5,
            LETTER, LEGAL, TABLOID, EXECUTIVE, STATEMENT,
            PHOTO_4X6, PHOTO_5X7, ENV_DL, ENV_C5, ENV_NO10
        )

        fun fromPwgName(pwg: String): PaperSize? {
            val lower = pwg.lowercase()
            return STANDARD_LIST.firstOrNull {
                it.pwgMediaName.equals(lower, ignoreCase = true) ||
                    lower.contains(it.id.lowercase())
            }
        }
    }
}

enum class Orientation(val label: String, val ippValue: Int) {
    AUTO("Otomatis", 3),
    PORTRAIT("Potret", 3),
    LANDSCAPE("Lanskap", 4)
}

enum class MediaType(val label: String, val ippKeyword: String) {
    PLAIN("Kertas Biasa", "stationery"),
    THICK("Kertas Tebal", "cardstock"),
    RECYCLED("Daur Ulang", "stationery-recycled"),
    PHOTO("Kertas Foto", "photographic"),
    GLOSSY("Foto Glossy", "photographic-glossy"),
    MATTE("Foto Doff / Matte", "photographic-matte"),
    LABEL("Stiker / Label", "labels"),
    ENVELOPE("Amplop", "envelope"),
    LETTERHEAD("Kop Surat", "stationery-letterhead")
}

enum class PaperTray(val label: String, val ippKeyword: String) {
    AUTO("Otomatis", "auto"),
    TRAY_1("Baki 1", "tray-1"),
    TRAY_2("Baki 2", "tray-2"),
    TRAY_3("Baki 3", "tray-3"),
    BYPASS("Bypass / Manual", "by-pass-tray")
}

enum class ScaleType(val label: String) {
    FIT("Muat ke Halaman (Fit)"),
    FILL("Isi Halaman (Crop)"),
    ACTUAL("Ukuran Asli (100%)"),
    PERCENT("Skala Manual (%)")
}

enum class MarginType(val label: String, val topMm: Float, val rightMm: Float, val bottomMm: Float, val leftMm: Float) {
    DEFAULT("Standar (10 mm)", 10f, 10f, 10f, 10f),
    NARROW("Sempit (4 mm)", 4f, 4f, 4f, 4f),
    BORDERLESS("Tanpa Tepi (0 mm)", 0f, 0f, 0f, 0f),
    CUSTOM("Kustom (mm)", 10f, 10f, 10f, 10f)
}

enum class PagePosition(val label: String) {
    CENTER("Tengah"),
    TOP_LEFT("Kiri Atas")
}

enum class NUpOrder(val label: String) {
    LEFT_TO_RIGHT("Kiri → Kanan, Atas → Bawah"),
    TOP_TO_BOTTOM("Atas → Bawah, Kiri → Kanan")
}

enum class PageRangeFilter(val label: String) {
    ALL("Semua Halaman"),
    ODD("Ganjil Saja"),
    EVEN("Genap Saja")
}

enum class DuplexMode(val label: String, val ippKeyword: String) {
    OFF("Satu Sisi", "one-sided"),
    LONG_EDGE("Dua Sisi (Buku)", "two-sided-long-edge"),
    SHORT_EDGE("Dua Sisi (Kalender)", "two-sided-short-edge")
}

enum class FinishingOption(val label: String, val ippEnum: Int) {
    STAPLE("Staple", 4),
    PUNCH("Lubang (Punch)", 5),
    FOLD("Lipat", 6),
    OFFSET("Offset Output", 10)
}

enum class ColorMode(val label: String, val ippKeyword: String) {
    COLOR("Warna", "color"),
    MONOCHROME("Hitam-Putih", "monochrome"),
    AUTO("Otomatis", "auto")
}

enum class PrintQuality(val label: String, val ippEnum: Int) {
    DRAFT("Draft / Hemat", 3),
    NORMAL("Normal", 4),
    HIGH("Tinggi / Foto", 5)
}

enum class OptimizeContent(val label: String, val ippKeyword: String) {
    AUTO("Otomatis", "auto"),
    TEXT("Teks", "text"),
    GRAPHICS("Grafik", "graphics"),
    TEXT_GRAPHICS("Teks + Grafik", "text-and-graphics"),
    PHOTO("Foto", "photo")
}

enum class RenderingIntent(val label: String, val ippKeyword: String) {
    PERCEPTUAL("Perseptual", "perceptual"),
    RELATIVE("Relatif", "relative"),
    SATURATION("Saturasi", "saturation")
}

enum class ImageFilter(val label: String) {
    NONE("Asli"),
    GRAYSCALE("Grayscale"),
    SHARP_BW("Hitam-Putih Tajam"),
    SEPIA("Sepia"),
    INVERT("Negatif (Invert)")
}

@JsonClass(generateAdapter = true)
data class PrintOptions(
    // A. Kertas
    val paperSize: PaperSize = PaperSize.A4,
    val orientation: Orientation = Orientation.AUTO,
    val mediaType: MediaType = MediaType.PLAIN,
    val tray: PaperTray = PaperTray.AUTO,
    // B. Tata letak & skala
    val scaleType: ScaleType = ScaleType.FIT,
    val scalePercent: Int = 100,
    val marginType: MarginType = MarginType.DEFAULT,
    val customMarginTopMm: Float = 10f,
    val customMarginRightMm: Float = 10f,
    val customMarginBottomMm: Float = 10f,
    val customMarginLeftMm: Float = 10f,
    val pagesPerSheet: Int = 1,
    val nUpOrder: NUpOrder = NUpOrder.LEFT_TO_RIGHT,
    val nUpBorder: Boolean = false,
    val position: PagePosition = PagePosition.CENTER,
    val offsetXMm: Float = 0f,
    val offsetYMm: Float = 0f,
    val rotationDegrees: Int = 0,
    val mirror: Boolean = false,
    val posterGrid: Int = 1, // 1 = nonaktif, 2 = 2x2, 3 = 3x3
    val pageRange: String = "",
    val pageRangeFilter: PageRangeFilter = PageRangeFilter.ALL,
    val copies: Int = 1,
    val collate: Boolean = true,
    val reverseOrder: Boolean = false,
    val duplex: DuplexMode = DuplexMode.OFF,
    val finishing: Set<FinishingOption> = emptySet(),
    val outputBin: String = "auto",
    // C. Kualitas & warna
    val colorMode: ColorMode = ColorMode.COLOR,
    val quality: PrintQuality = PrintQuality.NORMAL,
    val dpi: Int = 300,
    val tonerSave: Boolean = false,
    val contentOptimize: OptimizeContent = OptimizeContent.AUTO,
    val renderingIntent: RenderingIntent = RenderingIntent.PERCEPTUAL,
    // D. Penyesuaian gambar
    val brightness: Int = 0,      // -100..100
    val contrast: Int = 0,        // -100..100
    val sharpness: Int = 0,       // 0..100
    val saturation: Int = 0,      // -100..100
    val gamma: Float = 1.0f,      // 0.5..2.5
    val warmth: Int = 0,          // -100..100
    val darkness: Int = 3,        // 1..5
    val bwThreshold: Int? = null, // null = off, 0..255
    val cleanBackground: Boolean = false,
    val darkenText: Boolean = false,
    val autoEnhance: Boolean = false,
    val filter: ImageFilter = ImageFilter.NONE,
    val applyAdjustmentsToPdf: Boolean = false
) {
    val effectiveMarginsMm: List<Float>
        get() = when (marginType) {
            MarginType.CUSTOM -> listOf(
                customMarginTopMm.coerceIn(0f, 50f),
                customMarginRightMm.coerceIn(0f, 50f),
                customMarginBottomMm.coerceIn(0f, 50f),
                customMarginLeftMm.coerceIn(0f, 50f)
            )
            else -> listOf(marginType.topMm, marginType.rightMm, marginType.bottomMm, marginType.leftMm)
        }

    val hasImageAdjustments: Boolean
        get() = brightness != 0 ||
            contrast != 0 ||
            sharpness != 0 ||
            saturation != 0 ||
            gamma != 1.0f ||
            warmth != 0 ||
            darkness != 3 ||
            bwThreshold != null ||
            cleanBackground ||
            darkenText ||
            autoEnhance ||
            filter != ImageFilter.NONE

    fun resetImageAdjustments(): PrintOptions = copy(
        brightness = 0,
        contrast = 0,
        sharpness = 0,
        saturation = 0,
        gamma = 1.0f,
        warmth = 0,
        darkness = 3,
        bwThreshold = null,
        cleanBackground = false,
        darkenText = false,
        autoEnhance = false,
        filter = ImageFilter.NONE
    )
}
