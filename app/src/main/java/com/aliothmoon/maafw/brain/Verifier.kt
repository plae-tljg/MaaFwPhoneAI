package com.aliothmoon.maafw.brain

import android.graphics.BitmapFactory
import android.graphics.Color
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

/**
 * Deterministic postcondition checks.
 *
 * Android currently wires the `pixel` primitive (the toggle/state primitive)
 * using the run's final screenshot. `element` / `screen_text` still need
 * MaaFramework recognition details and `file_count` needs a scoped storage
 * policy, so they report "not applicable" instead of a false pass.
 */
object Verifier {

    data class Outcome(
        val applicable: Boolean,
        val ok: Boolean,
        val evidenceJson: String,
    ) {
        val skipped: Boolean get() = !applicable
    }

    fun verify(screenshotPath: String?, postconditionJson: String): Outcome {
        val post = runCatching { JSONObject(postconditionJson.ifBlank { "{}" }) }.getOrDefault(JSONObject())
        return when (val kind = post.optString("type")) {
            "", "none" -> Outcome(false, true, JSONObject().put("type", "none").put("skipped", true).toString())
            "pixel" -> verifyPixel(screenshotPath, post)
            else -> Outcome(
                false,
                true,
                JSONObject().put("type", kind).put("skipped", true)
                    .put("note", "postcondition type not wired on Android yet").toString(),
            )
        }
    }

    private fun verifyPixel(screenshotPath: String?, post: JSONObject): Outcome {
        val point = post.optJSONArray("point")
        val rgb = post.optJSONArray("rgb")
        if (screenshotPath.isNullOrBlank() || point == null || point.length() < 2 || rgb == null || rgb.length() < 3) {
            return Outcome(
                true,
                false,
                JSONObject().put("type", "pixel").put("error", "screenshot/point/rgb missing").toString(),
            )
        }
        val bitmap = BitmapFactory.decodeFile(screenshotPath) ?: return Outcome(
            true,
            false,
            JSONObject().put("type", "pixel").put("error", "screenshot decode failed").toString(),
        )
        return try {
            val x = point.optInt(0)
            val y = point.optInt(1)
            val radius = post.optInt("radius", 8).coerceIn(1, 200)
            val tolerance = post.optDouble("tolerance", 70.0)
            val target = intArrayOf(rgb.optInt(0), rgb.optInt(1), rgb.optInt(2))
            val left = (x - radius).coerceAtLeast(0)
            val top = (y - radius).coerceAtLeast(0)
            val right = (x + radius).coerceAtMost(bitmap.width)
            val bottom = (y + radius).coerceAtMost(bitmap.height)
            if (right - left < 2 || bottom - top < 2) {
                return Outcome(
                    true,
                    false,
                    JSONObject().put("type", "pixel").put("error", "pixel box outside screenshot").toString(),
                )
            }
            var r = 0L
            var g = 0L
            var b = 0L
            var n = 0
            for (py in top until bottom) {
                for (px in left until right) {
                    val color = bitmap.getPixel(px, py)
                    r += Color.red(color)
                    g += Color.green(color)
                    b += Color.blue(color)
                    n++
                }
            }
            val mean = intArrayOf((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
            val distance = maxOf(
                abs(mean[0] - target[0]),
                abs(mean[1] - target[1]),
                abs(mean[2] - target[2]),
            )
            val ok = distance <= tolerance
            val evidence = JSONObject()
                .put("type", "pixel")
                .put("point", point)
                .put("target_rgb", target.toList())
                .put("mean_rgb", mean.toList())
                .put("distance", distance)
                .put("tolerance", tolerance)
                .put("radius", radius)
                .put("hit", ok)
                .toString()
            Outcome(true, ok, evidence)
        } finally {
            bitmap.recycle()
        }
    }

    fun meanColor(screenshotPath: String?, cx: Int, cy: Int, radius: Int = 10): IntArray? {
        val path = screenshotPath ?: return null
        val bitmap = BitmapFactory.decodeFile(path) ?: return null
        return try {
            val left = (cx - radius).coerceAtLeast(0)
            val top = (cy - radius).coerceAtLeast(0)
            val right = (cx + radius).coerceAtMost(bitmap.width)
            val bottom = (cy + radius).coerceAtMost(bitmap.height)
            if (right - left < 2 || bottom - top < 2) return null
            var r = 0L
            var g = 0L
            var b = 0L
            var n = 0
            for (py in top until bottom) {
                for (px in left until right) {
                    val color = bitmap.getPixel(px, py)
                    r += Color.red(color)
                    g += Color.green(color)
                    b += Color.blue(color)
                    n++
                }
            }
            intArrayOf((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
        } finally {
            bitmap.recycle()
        }
    }

    fun screenshotFor(context: android.content.Context, runId: Long, attempt: Int): File =
        File(context.getExternalFilesDir("brain"), "verify/run${runId}_$attempt.png").apply {
            parentFile?.mkdirs()
        }
}
