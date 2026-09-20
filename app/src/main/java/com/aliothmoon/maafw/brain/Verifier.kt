package com.aliothmoon.maafw.brain

import android.graphics.BitmapFactory
import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

/**
 * Deterministic postcondition checks.
 *
 * Android supports the documented v0 primitives directly:
 *
 *  - `pixel`         mean colour around a device point
 *  - `element`       a named MaaFW element / an inline recognition
 *  - `screen_text`   MaaFW OCR must find text
 *  - `file_count`    a directory must grow by `delta_min`
 *
 * AI-authored native graphs sometimes carry a bare `{node, recognition,
 * expected, ...}` object instead of a `{type, ...}` wrapper. That shape is
 * accepted too and evaluated as a recognition postcondition, so the graph
 * authoring loop stays compatible with MaaFW's own field vocabulary.
 *
 * The caller supplies recognition/file evidence; this object stays free of
 * Android Context so the same parsing rules are easy to test.
 */
object Verifier {

    data class Outcome(
        val applicable: Boolean,
        val ok: Boolean,
        val evidenceJson: String,
    ) {
        val skipped: Boolean get() = !applicable
    }

    /**
     * Evidence sources for one verification attempt.
     *
     * [recognize] is expected to run one real MaaFW recognition on the
     * controller's cached frame and return `{hit, box, algorithm, detail}`.
     * [elementLocator] resolves a named element's native recognition params.
     */
    data class Evidence(
        val screenshotPath: String? = null,
        val fileCountBefore: Int? = null,
        val elementLocator: (String) -> JSONObject? = { null },
        val countFiles: (String) -> Int? = { path ->
            runCatching {
                val dir = File(path)
                if (!dir.isDirectory) null else dir.listFiles()?.count { it.isFile }
            }.getOrNull()
        },
        val recognize: suspend (String, JSONObject) -> JSONObject? = { _, _ -> null },
    )

    suspend fun verify(evidence: Evidence, postconditionJson: String): Outcome {
        val post = runCatching { JSONObject(postconditionJson.ifBlank { "{}" }) }
            .getOrDefault(JSONObject())
        val kind = post.optString("type").trim().lowercase()
        return when {
            kind == "none" -> Outcome(
                false,
                true,
                JSONObject().put("type", "none").put("skipped", true).toString(),
            )

            kind == "pixel" -> verifyPixel(evidence, post)

            kind == "file_count" -> verifyFileCount(evidence, post)

            kind == "element" -> verifyElement(evidence, post)

            kind == "screen_text" -> verifyScreenText(evidence, post)

            // MaaFW-native postcondition authored as a bare recognition map:
            // {"node":"VerifyClockHome","recognition":"OCR","expected":[...]}
            post.has("recognition") || post.has("expected") ||
                post.has("template") || post.has("lower") || post.has("upper") ->
                verifyRecognition(evidence, post)

            kind.isBlank() -> Outcome(
                false,
                true,
                JSONObject().put("type", "none").put("skipped", true).toString(),
            )

            else -> Outcome(
                false,
                true,
                JSONObject().put("type", kind).put("skipped", true)
                    .put("note", "postcondition type not wired on Android yet").toString(),
            )
        }
    }

    /** Capture the `file_count` baseline before a pipeline starts. */
    fun fileCountBaseline(postconditionJson: String): Int? {
        val post = runCatching { JSONObject(postconditionJson.ifBlank { "{}" }) }
            .getOrDefault(JSONObject())
        if (post.optString("type").lowercase() != "file_count") return null
        val path = post.optString("path").trim()
        if (path.isBlank()) return null
        return countFiles(path)
    }

    fun countFiles(path: String): Int? = runCatching {
        val dir = File(path)
        if (!dir.isDirectory) null else dir.listFiles()?.count { it.isFile }
    }.getOrNull()

    private fun verifyPixel(evidence: Evidence, post: JSONObject): Outcome {
        val point = post.optJSONArray("point")
        val rgb = post.optJSONArray("rgb")
        if (evidence.screenshotPath.isNullOrBlank() || point == null ||
            point.length() < 2 || rgb == null || rgb.length() < 3
        ) {
            return Outcome(
                true,
                false,
                JSONObject().put("type", "pixel").put("error", "screenshot/point/rgb missing").toString(),
            )
        }
        val bitmap = BitmapFactory.decodeFile(evidence.screenshotPath) ?: return Outcome(
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
            val evidenceJson = JSONObject()
                .put("type", "pixel")
                .put("point", point)
                .put("target_rgb", target.toList())
                .put("mean_rgb", mean.toList())
                .put("distance", distance)
                .put("tolerance", tolerance)
                .put("radius", radius)
                .put("hit", ok)
                .toString()
            Outcome(true, ok, evidenceJson)
        } finally {
            bitmap.recycle()
        }
    }

    private fun verifyFileCount(evidence: Evidence, post: JSONObject): Outcome {
        val path = post.optString("path").trim()
        if (path.isBlank()) {
            return Outcome(
                true,
                false,
                JSONObject().put("type", "file_count").put("error", "path is required").toString(),
            )
        }
        val deltaMin = post.optInt("delta_min", 1).coerceAtLeast(1)
        val before = evidence.fileCountBefore
            ?: if (post.has("before") && !post.isNull("before")) post.optInt("before") else null
        val after = evidence.countFiles(path)
        val ok = before != null && after != null && after - before >= deltaMin
        val evidenceJson = JSONObject()
            .put("type", "file_count")
            .put("path", path)
            .put("before", before ?: JSONObject.NULL)
            .put("after", after ?: JSONObject.NULL)
            .put("delta_min", deltaMin)
            .put("hit", ok)
        if (before == null) {
            evidenceJson.put(
                "error",
                "file_count baseline unavailable (capture before the pipeline starts)",
            )
        } else if (after == null) {
            evidenceJson.put("error", "directory is unreadable or does not exist")
        } else if (!ok) {
            evidenceJson.put("error", "directory count did not reach baseline + delta_min")
        }
        return Outcome(true, ok, evidenceJson.toString())
    }

    /**
     * `{"type":"element","name":"shutter"}` uses a row from `elements`;
     * an inline `recognition`/`expected`/`template` map is used as-is.
     */
    private suspend fun verifyElement(evidence: Evidence, post: JSONObject): Outcome {
        val name = post.optString("name").trim()
        val locator = name.takeIf { it.isNotBlank() }?.let { evidence.elementLocator(it) }
        val merged = when {
            locator == null -> post
            locator.has("recognition") || locator.has("expected") || locator.has("template") ||
                locator.has("lower") || locator.has("upper") -> merge(post, locator)
            else -> merge(post, legacyLocator(locator))
        }
        if (locator == null && !post.has("recognition") && !post.has("expected") &&
            !post.has("template") && !post.has("lower") && !post.has("upper")
        ) {
            return Outcome(
                true,
                false,
                JSONObject().put("type", "element").put("name", name)
                    .put("error", "element not found and no inline recognition params").toString(),
            )
        }
        return verifyRecognition(evidence, merged)
    }

    private suspend fun verifyScreenText(evidence: Evidence, post: JSONObject): Outcome {
        val text = post.optString("text").trim().ifBlank {
            post.optJSONArray("expected")?.let { arr ->
                (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeIf { it.isNotBlank() } }
                    .joinToString("|")
            }.orEmpty()
        }
        if (text.isBlank()) {
            return Outcome(
                true,
                false,
                JSONObject().put("type", "screen_text").put("error", "empty text").toString(),
            )
        }
        val recognition = JSONObject()
            .put("recognition", "OCR")
            .put("expected", JSONArray(listOf(text)))
        for (key in listOf("roi", "threshold", "replace", "only_rec")) {
            if (post.has(key)) recognition.put(key, post.get(key))
        }
        return verifyRecognition(evidence, recognition)
    }

    private suspend fun verifyRecognition(evidence: Evidence, post: JSONObject): Outcome {
        val recoType = post.optString("recognition").trim().ifBlank { inferRecognitionType(post) }
        val params = recognitionParams(post, recoType)
        if (params.length() == 0) {
            return Outcome(
                true,
                false,
                JSONObject().put("type", "recognition").put("recognition", recoType)
                    .put("error", "no usable recognition parameters").toString(),
            )
        }
        val detail = runCatching { evidence.recognize(recoType, params) }.getOrNull()
            ?: return Outcome(
                true,
                false,
                JSONObject().put("type", "recognition")
                    .put("recognition", recoType)
                    .put("params", params)
                    .put("error", "recognition unavailable or returned no result")
                    .toString(),
            )
        val hit = detail.optBoolean("hit")
        val requiredHit = if (post.has("required_hit")) post.optBoolean("required_hit", true) else true
        val ok = if (requiredHit) hit else !hit
        val result = JSONObject()
            .put("type", post.optString("type").ifBlank { "recognition" })
            .put("node", post.optString("node"))
            .put("recognition", recoType)
            .put("params", params)
            .put("hit", hit)
            .put("required_hit", requiredHit)
            .put("ok", ok)
            .put("box", detail.opt("box") ?: JSONObject.NULL)
            .put("algorithm", detail.optString("algorithm"))
            .put("detail", detail.optString("detail").take(2000))
        return Outcome(true, ok, result.toString())
    }

    private fun inferRecognitionType(post: JSONObject): String = when {
        post.optString("recognition").isNotBlank() -> post.optString("recognition")
        post.has("template") || post.has("templates") -> "TemplateMatch"
        post.has("lower") || post.has("upper") -> "ColorMatch"
        else -> "OCR"
    }

    private fun recognitionParams(post: JSONObject, recoType: String): JSONObject {
        val allowed = when (recoType.lowercase()) {
            "templatematch" -> listOf(
                "template", "templates", "threshold", "roi", "roi_offset", "order_by",
                "index", "method", "green_mask",
            )
            "colormatch" -> listOf(
                "lower", "upper", "method", "count", "connected", "roi", "roi_offset",
            )
            else -> listOf(
                "expected", "threshold", "roi", "roi_offset", "replace", "only_rec",
            )
        }
        val out = JSONObject()
        for (key in allowed) {
            if (!post.has(key) || post.isNull(key)) continue
            out.put(key, post.get(key))
        }
        if (recoType.equals("OCR", ignoreCase = true) && !out.has("expected")) {
            val text = post.optString("text").trim().ifBlank { post.optString("value").trim() }
            if (text.isNotBlank()) out.put("expected", JSONArray(listOf(text)))
        }
        if (recoType.equals("OCR", ignoreCase = true)) {
            val expected = out.opt("expected")
            if (expected is String && expected.isNotBlank()) {
                out.put("expected", JSONArray(listOf(expected)))
            }
        }
        if (recoType.equals("TemplateMatch", ignoreCase = true)) {
            for (key in listOf("template", "templates")) {
                val value = out.opt(key)
                if (value is String && value.isNotBlank()) out.put(key, JSONArray(listOf(value)))
            }
        }
        return out
    }

    /**
     * Convert a pre-native `elements.locator_json` row
     * `{"type":"template","template":"x.png"}` / `{"type":"ocr","value":"x"}`
     * into a native recognition map.
     */
    private fun legacyLocator(locator: JSONObject): JSONObject {
        val kind = locator.optString("type").lowercase()
        val out = JSONObject()
        when (kind) {
            "template" -> {
                out.put("recognition", "TemplateMatch")
                val template = locator.optString("template")
                    .ifBlank { locator.optString("path") }
                if (template.isNotBlank()) out.put("template", JSONArray(listOf(File(template).name)))
                out.put("threshold", locator.optDouble("threshold", 0.7))
            }
            "color" -> {
                out.put("recognition", "ColorMatch")
                for (key in listOf("lower", "upper", "method", "count", "roi")) {
                    if (locator.has(key)) out.put(key, locator.get(key))
                }
            }
            else -> {
                out.put("recognition", "OCR")
                val value = locator.optString("value")
                if (value.isNotBlank()) {
                    val contains = locator.optBoolean("contains", true)
                    val expected = if (contains) value else "^${Regex.escape(value)}$"
                    out.put("expected", JSONArray(listOf(expected)))
                }
                if (locator.has("threshold")) out.put("threshold", locator.get("threshold"))
                if (locator.has("roi")) out.put("roi", locator.get("roi"))
            }
        }
        return out
    }

    private fun merge(post: JSONObject, locator: JSONObject): JSONObject {
        val out = JSONObject(locator.toString())
        for (key in post.keys()) {
            if (key == "name" || key == "type") continue
            out.put(key, post.get(key))
        }
        return out
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
