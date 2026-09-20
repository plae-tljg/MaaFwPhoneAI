package com.aliothmoon.maafw.brain

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs

/** Screenshot decoding/encoding for the DeepSeek vision loop. */
object ImageTools {

    data class VisionImage(val dataUrl: String, val visionWidth: Int, val visionHeight: Int,
                           val rawWidth: Int, val rawHeight: Int) {
        /** raw = image * scaleFactor */
        val scaleFactor: Double get() = rawWidth.toDouble() / visionWidth.toDouble()
    }

    fun rawSize(path: String): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, options)
        return options.outWidth to options.outHeight
    }

    fun encode(path: String, shortSide: Int = 720, quality: Int = 70): VisionImage? {
        val bitmap = BitmapFactory.decodeFile(path) ?: return null
        return try {
            encodeBitmap(bitmap, shortSide, quality)
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Crop a small region around a raw screenshot point and encode it for a
     * focused vision question (for example, "is this toggle active?").
     * The returned VisionImage's rawWidth/rawHeight are the crop size.
     */
    fun encodeCrop(
        source: String,
        cx: Int,
        cy: Int,
        half: Int = 50,
        shortSide: Int = 240,
        quality: Int = 75,
    ): VisionImage? {
        val bitmap = BitmapFactory.decodeFile(source) ?: return null
        val left = (cx - half).coerceAtLeast(0)
        val top = (cy - half).coerceAtLeast(0)
        val right = (cx + half).coerceAtMost(bitmap.width)
        val bottom = (cy + half).coerceAtMost(bitmap.height)
        if (right - left < 20 || bottom - top < 20) {
            bitmap.recycle()
            return null
        }
        val crop = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
        return try {
            encodeBitmap(crop, shortSide, quality)
        } finally {
            crop.recycle()
            bitmap.recycle()
        }
    }

    private fun encodeBitmap(bitmap: Bitmap, shortSide: Int, quality: Int): VisionImage {
        val rawW = bitmap.width
        val rawH = bitmap.height
        val short = minOf(rawW, rawH).coerceAtLeast(1)
        val ratio = shortSide.toDouble() / short
        val vw = (rawW * ratio).toInt().coerceAtLeast(1)
        val vh = (rawH * ratio).toInt().coerceAtLeast(1)
        val small = Bitmap.createScaledBitmap(bitmap, vw, vh, true)
        val out = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, quality, out)
        if (small !== bitmap) small.recycle()
        val url = "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        return VisionImage(url, vw, vh, rawW, rawH)
    }

    data class LocalDiff(val delta: Double, val x: Int, val y: Int)

    /**
     * Largest local block change around a tapped point. A tap can land on the
     * edge of a control (e.g. a like button); a global region average can be
     * diluted by surrounding background. This finds the actual control/panel
     * that changed and returns its centre.
     */
    fun maxLocalDifference(
        pathA: String,
        pathB: String,
        cx: Int,
        cy: Int,
        searchRadius: Int = 100,
        block: Int = 24,
    ): LocalDiff? {
        val a = BitmapFactory.decodeFile(pathA) ?: return null
        val b = BitmapFactory.decodeFile(pathB) ?: return null
        return try {
            val width = minOf(a.width, b.width)
            val height = minOf(a.height, b.height)
            var best = LocalDiff(0.0, cx, cy)
            val x0 = (cx - searchRadius).coerceAtLeast(0)
            val x1 = (cx + searchRadius).coerceAtMost(width)
            val y0 = (cy - searchRadius).coerceAtLeast(0)
            val y1 = (cy + searchRadius).coerceAtMost(height)
            var y = y0
            while (y + block <= y1) {
                var x = x0
                while (x + block <= x1) {
                    var sum = 0L
                    var n = 0
                    for (py in y until y + block step 2) {
                        for (px in x until x + block step 2) {
                            val ca = a.getPixel(px, py)
                            val cb = b.getPixel(px, py)
                            sum += abs(Color.red(ca) - Color.red(cb)) +
                                abs(Color.green(ca) - Color.green(cb)) +
                                abs(Color.blue(ca) - Color.blue(cb))
                            n++
                        }
                    }
                    if (n > 0) {
                        val delta = sum.toDouble() / n / 3.0
                        if (delta > best.delta) best = LocalDiff(delta, x + block / 2, y + block / 2)
                    }
                    x += block
                }
                y += block
            }
            best
        } finally {
            a.recycle()
            b.recycle()
        }
    }

    /**
     * Most colourful local block within a small radius of a point. After a
     * toggle tap, the active control usually gains an accent colour; this
     * finds its actual centre even when the tap landed on its edge or the
     * layout shifted a little between frames.
     */
    fun mostColorfulBlock(
        path: String,
        cx: Int,
        cy: Int,
        searchRadius: Int = 60,
        block: Int = 24,
    ): LocalDiff? {
        val bitmap = BitmapFactory.decodeFile(path) ?: return null
        return try {
            val x0 = (cx - searchRadius).coerceAtLeast(0)
            val x1 = (cx + searchRadius).coerceAtMost(bitmap.width)
            val y0 = (cy - searchRadius).coerceAtLeast(0)
            val y1 = (cy + searchRadius).coerceAtMost(bitmap.height)
            var best: LocalDiff? = null
            var bestColor = 0.0
            var y = y0
            while (y + block <= y1) {
                var x = x0
                while (x + block <= x1) {
                    var sum = 0L
                    var n = 0
                    for (py in y until y + block step 2) {
                        for (px in x until x + block step 2) {
                            val c = bitmap.getPixel(px, py)
                            sum += maxOf(Color.red(c), Color.green(c), Color.blue(c)) -
                                minOf(Color.red(c), Color.green(c), Color.blue(c))
                            n++
                        }
                    }
                    if (n > 0) {
                        val color = sum.toDouble() / n
                        if (color > bestColor) {
                            bestColor = color
                            best = LocalDiff(color, x + block / 2, y + block / 2)
                        }
                    }
                    x += block
                }
                y += block
            }
            best
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Pick the changed, colourful local block nearest to a tap point. This
     * handles a tap landing slightly off control centre and screens that
     * re-layout between frames: the block must both change and carry the
     * accent colour of an active control.
     */
    fun changedColorfulBlock(
        pathA: String,
        pathB: String,
        cx: Int,
        cy: Int,
        searchRadius: Int = 80,
        block: Int = 24,
    ): LocalDiff? {
        val a = BitmapFactory.decodeFile(pathA) ?: return null
        val b = BitmapFactory.decodeFile(pathB) ?: return null
        return try {
            val width = minOf(a.width, b.width)
            val height = minOf(a.height, b.height)
            val x0 = (cx - searchRadius).coerceAtLeast(0)
            val x1 = (cx + searchRadius).coerceAtMost(width)
            val y0 = (cy - searchRadius).coerceAtLeast(0)
            val y1 = (cy + searchRadius).coerceAtMost(height)
            var best: LocalDiff? = null
            var bestScore = 0.0
            var y = y0
            while (y + block <= y1) {
                var x = x0
                while (x + block <= x1) {
                    var diffSum = 0L
                    var colorSum = 0L
                    var n = 0
                    for (py in y until y + block step 2) {
                        for (px in x until x + block step 2) {
                            val ca = a.getPixel(px, py)
                            val cb = b.getPixel(px, py)
                            diffSum += abs(Color.red(ca) - Color.red(cb)) +
                                abs(Color.green(ca) - Color.green(cb)) +
                                abs(Color.blue(ca) - Color.blue(cb))
                            colorSum += maxOf(Color.red(cb), Color.green(cb), Color.blue(cb)) -
                                minOf(Color.red(cb), Color.green(cb), Color.blue(cb))
                            n++
                        }
                    }
                    if (n > 0) {
                        val delta = diffSum.toDouble() / n / 3.0
                        val colorfulness = colorSum.toDouble() / n
                        val bx = x + block / 2
                        val by = y + block / 2
                        val distance = kotlin.math.hypot((bx - cx).toDouble(), (by - cy).toDouble())
                        if (delta >= 18.0 && colorfulness >= 15.0) {
                            val score = delta + colorfulness - distance * 0.4
                            if (score > bestScore) {
                                bestScore = score
                                best = LocalDiff(delta, bx, by)
                            }
                        }
                    }
                    x += block
                }
                y += block
            }
            best
        } finally {
            a.recycle()
            b.recycle()
        }
    }

    /**
     * Mean per-pixel channel spread in a small region. Inactive
     * toggle controls are usually near-gray; accent-coloured active controls
     * are saturated. Used as a cheap second factor before a vision model is
     * allowed to veto a toggle tap.
     */
    fun regionColorfulness(path: String, cx: Int, cy: Int, half: Int = 12): Double? {
        val bitmap = BitmapFactory.decodeFile(path) ?: return null
        return try {
            val left = (cx - half).coerceAtLeast(0)
            val top = (cy - half).coerceAtLeast(0)
            val right = (cx + half).coerceAtMost(bitmap.width)
            val bottom = (cy + half).coerceAtMost(bitmap.height)
            if (right - left < 2 || bottom - top < 2) return null
            var sum = 0L
            var n = 0
            for (y in top until bottom) {
                for (x in left until right) {
                    val c = bitmap.getPixel(x, y)
                    val r = Color.red(c)
                    val g = Color.green(c)
                    val b = Color.blue(c)
                    sum += maxOf(r, g, b) - minOf(r, g, b)
                    n++
                }
            }
            if (n == 0) null else sum.toDouble() / n
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Coarse, animation-tolerant fingerprint of a frame. Used only to avoid
     * paying for a screen-observation model call when the screenshot has not
     * meaningfully changed.
     */
    fun frameKey(path: String): String {
        val bitmap = BitmapFactory.decodeFile(path) ?: return path
        return try {
            val small = Bitmap.createScaledBitmap(bitmap, 32, 18, true)
            val out = StringBuilder(32 * 18)
            for (y in 0 until small.height) {
                for (x in 0 until small.width) {
                    val c = small.getPixel(x, y)
                    val luma = (Color.red(c) + Color.green(c) + Color.blue(c)) / 3
                    out.append((luma / 32).toString(16))
                }
            }
            if (small !== bitmap) small.recycle()
            out.toString()
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Mean absolute RGB difference around a raw point between two frames.
     * Used for deterministic local checks (e.g. a toggle colour change) without
     * bringing OCR/matching into the bootstrap hot loop.
     */
    fun regionDifference(pathA: String, pathB: String, cx: Int, cy: Int, half: Int = 60): Double? {
        val a = BitmapFactory.decodeFile(pathA) ?: return null
        val b = BitmapFactory.decodeFile(pathB) ?: return null
        return try {
            val width = minOf(a.width, b.width)
            val height = minOf(a.height, b.height)
            val left = (cx - half).coerceAtLeast(0)
            val top = (cy - half).coerceAtLeast(0)
            val right = (cx + half).coerceAtMost(width)
            val bottom = (cy + half).coerceAtMost(height)
            if (right - left < 20 || bottom - top < 20) return null
            val ca = Bitmap.createBitmap(a, left, top, right - left, bottom - top)
            val cb = Bitmap.createBitmap(b, left, top, right - left, bottom - top)
            try {
                var sum = 0L
                var n = 0
                for (y in 0 until ca.height step 2) {
                    for (x in 0 until ca.width step 2) {
                        val pa = ca.getPixel(x, y)
                        val pb = cb.getPixel(x, y)
                        sum += abs(Color.red(pa) - Color.red(pb)) +
                            abs(Color.green(pa) - Color.green(pb)) +
                            abs(Color.blue(pa) - Color.blue(pb))
                        n++
                    }
                }
                if (n == 0) null else sum.toDouble() / n / 3.0
            } finally {
                ca.recycle()
                cb.recycle()
            }
        } finally {
            a.recycle()
            b.recycle()
        }
    }

    /** Crop 220x220 raw pixels around a point and save it for a learned template. */
    fun cropTemplate(source: String, cx: Int, cy: Int, target: File, half: Int = 110): Boolean {
        val bitmap = BitmapFactory.decodeFile(source) ?: return false
        val left = (cx - half).coerceAtLeast(0)
        val top = (cy - half).coerceAtLeast(0)
        val right = (cx + half).coerceAtMost(bitmap.width)
        val bottom = (cy + half).coerceAtMost(bitmap.height)
        if (right - left < 40 || bottom - top < 40) {
            bitmap.recycle()
            return false
        }
        val crop = Bitmap.createBitmap(bitmap, left, top, right - left, bottom - top)
        target.parentFile?.mkdirs()
        val ok = target.outputStream().use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }
        crop.recycle()
        bitmap.recycle()
        return ok
    }
}
