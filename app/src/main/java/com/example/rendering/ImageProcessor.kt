package com.example.rendering

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import com.example.domain.model.ColorMode
import com.example.domain.model.ImageFilter
import com.example.domain.model.PrintOptions
import kotlin.math.pow
import kotlin.math.roundToInt

object ImageProcessor {

    fun processBitmap(source: Bitmap, options: PrintOptions, forPreview: Boolean = false): Bitmap {
        var current = applyTransform(source, options.rotationDegrees, options.mirror)

        val needsColorMatrix = options.brightness != 0 ||
            options.contrast != 0 ||
            options.saturation != 0 ||
            options.warmth != 0 ||
            options.darkness != 3 ||
            options.filter != ImageFilter.NONE ||
            options.colorMode == ColorMode.MONOCHROME

        if (needsColorMatrix) {
            current = applyColorMatrix(current, options)
        }

        val needsPixelPass = options.gamma != 1.0f ||
            options.autoEnhance ||
            options.cleanBackground ||
            options.darkenText ||
            options.bwThreshold != null ||
            options.filter == ImageFilter.SHARP_BW

        if (needsPixelPass) {
            current = applyPixelAdjustments(current, options)
        }

        if (options.sharpness > 0) {
            val scaleFactor = if (forPreview) 0.5f else 1f
            val strength = (options.sharpness / 100f) * 1.4f * scaleFactor.coerceAtLeast(0.7f)
            current = applyUnsharpMask(current, strength)
        }

        return current
    }

    private fun applyTransform(source: Bitmap, rotationDegrees: Int, mirror: Boolean): Bitmap {
        val rot = ((rotationDegrees % 360) + 360) % 360
        if (rot == 0 && !mirror) return source
        val matrix = Matrix()
        if (mirror) {
            matrix.postScale(-1f, 1f, source.width / 2f, source.height / 2f)
        }
        if (rot != 0) {
            matrix.postRotate(rot.toFloat())
        }
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    private fun applyColorMatrix(source: Bitmap, options: PrintOptions): Bitmap {
        val result = Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(result)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

        val combined = ColorMatrix()

        // Filter preset
        when (options.filter) {
            ImageFilter.GRAYSCALE, ImageFilter.SHARP_BW -> {
                combined.setSaturation(0f)
            }
            ImageFilter.SEPIA -> {
                combined.setSaturation(0f)
                val sepia = ColorMatrix(
                    floatArrayOf(
                        1f, 0f, 0f, 0f, 24f,
                        0f, 0.92f, 0f, 0f, 10f,
                        0f, 0f, 0.74f, 0f, -12f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
                combined.postConcat(sepia)
            }
            ImageFilter.INVERT -> {
                val invert = ColorMatrix(
                    floatArrayOf(
                        -1f, 0f, 0f, 0f, 255f,
                        0f, -1f, 0f, 0f, 255f,
                        0f, 0f, -1f, 0f, 255f,
                        0f, 0f, 0f, 1f, 0f
                    )
                )
                combined.postConcat(invert)
            }
            ImageFilter.NONE -> Unit
        }

        if (options.colorMode == ColorMode.MONOCHROME && options.filter == ImageFilter.NONE) {
            val mono = ColorMatrix().apply { setSaturation(0f) }
            combined.postConcat(mono)
        }

        // Saturation (-100..100)
        if (options.saturation != 0 && options.filter == ImageFilter.NONE && options.colorMode != ColorMode.MONOCHROME) {
            val sat = (1f + options.saturation / 100f).coerceIn(0f, 2.2f)
            val satMatrix = ColorMatrix().apply { setSaturation(sat) }
            combined.postConcat(satMatrix)
        }

        // Contrast (-100..100) + Brightness (-100..100) + Darkness (1..5)
        val contrastScale = (1f + options.contrast / 100f).coerceIn(0.2f, 2.5f)
        val darknessBias = (3 - options.darkness) * 18f
        val brightnessOffset = (options.brightness * 1.4f) + darknessBias
        val translate = 128f * (1f - contrastScale) + brightnessOffset

        val warmthR = (options.warmth * 0.35f)
        val warmthB = (-options.warmth * 0.35f)

        val bcMatrix = ColorMatrix(
            floatArrayOf(
                contrastScale, 0f, 0f, 0f, translate + warmthR,
                0f, contrastScale, 0f, 0f, translate,
                0f, 0f, contrastScale, 0f, translate + warmthB,
                0f, 0f, 0f, 1f, 0f
            )
        )
        combined.postConcat(bcMatrix)

        paint.colorFilter = ColorMatrixColorFilter(combined)
        canvas.drawBitmap(source, 0f, 0f, paint)
        return result
    }

    private fun applyPixelAdjustments(source: Bitmap, options: PrintOptions): Bitmap {
        val w = source.width
        val h = source.height
        val pixels = IntArray(w * h)
        source.getPixels(pixels, 0, w, 0, 0, w, h)

        val gammaInv = 1f / options.gamma.coerceIn(0.5f, 2.5f)
        val gammaLut = IntArray(256) { i ->
            ((i / 255.0).pow(gammaInv.toDouble()) * 255.0).roundToInt().coerceIn(0, 255)
        }

        var minLum = 255
        var maxLum = 0
        if (options.autoEnhance) {
            val step = ((w * h) / 4096).coerceAtLeast(1)
            for (i in pixels.indices step step) {
                val p = pixels[i]
                val lum = (Color.red(p) * 30 + Color.green(p) * 59 + Color.blue(p) * 11) / 100
                if (lum < minLum) minLum = lum
                if (lum > maxLum) maxLum = lum
            }
        }
        val lumSpan = (maxLum - minLum).coerceAtLeast(32)

        val threshold = options.bwThreshold ?: if (options.filter == ImageFilter.SHARP_BW) 148 else null

        for (i in pixels.indices) {
            val p = pixels[i]
            val a = Color.alpha(p)
            var r = gammaLut[Color.red(p)]
            var g = gammaLut[Color.green(p)]
            var b = gammaLut[Color.blue(p)]

            if (options.autoEnhance && lumSpan < 245) {
                r = (((r - minLum) * 255) / lumSpan).coerceIn(0, 255)
                g = (((g - minLum) * 255) / lumSpan).coerceIn(0, 255)
                b = (((b - minLum) * 255) / lumSpan).coerceIn(0, 255)
            }

            val lum = (r * 30 + g * 59 + b * 11) / 100

            if (options.cleanBackground) {
                // Push off-white / gray paper tint smoothly toward pure white
                if (lum > 175) {
                    val factor = ((lum - 175) / 80f).coerceIn(0f, 1f)
                    r = (r + (255 - r) * factor).roundToInt().coerceIn(0, 255)
                    g = (g + (255 - g) * factor).roundToInt().coerceIn(0, 255)
                    b = (b + (255 - b) * factor).roundToInt().coerceIn(0, 255)
                }
            }

            if (options.darkenText && lum < 145) {
                val factor = 0.45f
                r = (r * factor).roundToInt().coerceIn(0, 255)
                g = (g * factor).roundToInt().coerceIn(0, 255)
                b = (b * factor).roundToInt().coerceIn(0, 255)
            }

            if (threshold != null) {
                val finalLum = (r * 30 + g * 59 + b * 11) / 100
                val bw = if (finalLum >= threshold) 255 else 0
                r = bw
                g = bw
                b = bw
            }

            pixels[i] = Color.argb(a, r, g, b)
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }

    private fun applyUnsharpMask(source: Bitmap, amount: Float): Bitmap {
        val w = source.width
        val h = source.height
        if (w < 3 || h < 3) return source

        val src = IntArray(w * h)
        val dst = IntArray(w * h)
        source.getPixels(src, 0, w, 0, 0, w, h)
        System.arraycopy(src, 0, dst, 0, src.size)

        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val idx = row + x
                val c = src[idx]
                val up = src[idx - w]
                val down = src[idx + w]
                val left = src[idx - 1]
                val right = src[idx + 1]

                val avgR = (Color.red(up) + Color.red(down) + Color.red(left) + Color.red(right)) / 4
                val avgG = (Color.green(up) + Color.green(down) + Color.green(left) + Color.green(right)) / 4
                val avgB = (Color.blue(up) + Color.blue(down) + Color.blue(left) + Color.blue(right)) / 4

                val nr = (Color.red(c) + (Color.red(c) - avgR) * amount).roundToInt().coerceIn(0, 255)
                val ng = (Color.green(c) + (Color.green(c) - avgG) * amount).roundToInt().coerceIn(0, 255)
                val nb = (Color.blue(c) + (Color.blue(c) - avgB) * amount).roundToInt().coerceIn(0, 255)

                dst[idx] = Color.argb(Color.alpha(c), nr, ng, nb)
            }
        }

        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(dst, 0, w, 0, 0, w, h)
        return out
    }
}
