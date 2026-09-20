package com.aliothmoon.maafw.brain

import com.aliothmoon.maafw.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** DeepSeek chat client for the AI fallback loop (native tool calling). */
class DeepSeekClient(private val db: BrainDb) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(240, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    fun apiKey(): String = db.setting("deepseek_api_key", BuildConfig.DEEPSEEK_API_KEY)
    fun model(): String = db.setting("deepseek_model", BuildConfig.DEEPSEEK_MODEL)
    fun baseUrl(): String = db.setting("deepseek_base", BuildConfig.DEEPSEEK_BASE).trimEnd('/')
    fun configured(): Boolean = apiKey().isNotBlank()

    fun chat(messages: JSONArray, maxTokens: Int = 4000, jsonMode: Boolean = false,
             tools: JSONArray? = null): JSONObject {
        val payload = JSONObject()
            .put("model", model())
            .put("messages", messages)
            .put("max_tokens", maxTokens)
        if (jsonMode) payload.put("response_format", JSONObject().put("type", "json_object"))
        if (tools != null) payload.put("tools", tools)
        val request = Request.Builder()
            .url(baseUrl() + "/chat/completions")
            .header("Authorization", "Bearer ${apiKey()}")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("DeepSeek HTTP ${response.code}: ${body.take(300)}")
            }
            return JSONObject(body)
        }
    }

    /** One vision-guided action for the current goal. */
    fun nextAction(
        goal: String,
        imagePath: String,
        history: List<String>,
        knownApps: List<String>,
        skillGuidance: String = "",
        observation: String = "",
    ): JSONObject? {
        val image = ImageTools.encode(imagePath) ?: return null
        val system = """
            You control an Android phone step by step to reach a user goal.
            Call the phone_action tool with exactly ONE action per turn.
            Rules:
            - To open an app, prefer {"action":"launch","package":"..."} using the known apps list.
              The app catalog is populated from the device's launchable apps; if the goal
              names an app, find its label in the known apps list and launch the package directly.
              Do not navigate through HOME or the app drawer on the virtual display.
            - Coordinates (point/from/to) use the provided image scale ${image.visionWidth}x${image.visionHeight}.
            - To tap an element whose position is unclear, use {"action":"locate","description":"the like thumbs-up button"}
              instead of guessing coordinates; the locator will return the exact center.
            - Prefer point taps for elements you can see precisely. Use "text" to type into a focused field,
              "key" for enter/back/home.
            - For a search goal, tap the search field first, then call {"action":"text","value":"<the query>"}.
              After typing, press {"action":"key","value":"enter"} or tap the search button. Never tap the
              search button repeatedly and never assume a previous query in the box is the requested one.
            - InputText replaces the focused field content (select-all + delete + inject), so one focus tap
              followed by one text action is enough. A visible on-screen keyboard is not required.
            - If you tapped a text field on the previous turn, your current turn MUST be a text action for
              that field (or a key action to search); do not tap the field again.
            - In background/virtual-display mode, do NOT use key "home" to normalize. HOME is global and the
              virtual display can go black. To start a task, launch the target app package instead. If the
              goal says "go home, then open App X", treat it as "launch X" and do not press HOME.
            - If the screen is black after HOME, do not press HOME again; launch the next target app package.
            - The screenshot may look black for video/camera surfaces; do not assume failure from that alone.
            - After one decisive action that achieves the goal, call done and include a summary.
            - Before calling done on a state/toggle goal (like/follow/enabled), look at the latest screenshot and
              confirm the desired final state is visibly true (e.g. the like icon is filled pink, not gray).
              Toggle state can change after a short animation; wait briefly and re-check before deciding.
            - For like/follow/点赞/关注: if it is already active, call done without tapping; otherwise tap it
              exactly once, wait, re-check, and then call done. NEVER tap the same toggle a second time;
              toggles flip back on the second tap.
            - Never tap ads, never buy or send messages unless the goal says so.
            - Do not repeat an action that just failed or produced no visible change. After two
              attempts, change strategy (different element, scroll, wait, or back) or call fail.
            - A screen observation (visible elements, roles, points) may be provided in the user
              turn. Treat it as ground truth for the current screen.
            - Do not use locate for an element/control that is absent from the observation.
              If the observation has no control for the next step, navigate instead: key back,
              tap a visible bottom-navigation item, scroll, or launch a package. Never locate
              the same missing element repeatedly.
            - Prefer points from the observation for visible text/buttons/navigation; use locate
              only for icons or controls the observation did not identify.
            - If an "OCR recognition" JSON is present, it comes from MaaFramework's real OCR
              (`detail` contains text plus boxes in raw screenshot coordinates). Use its text/boxes
              as additional evidence for tapping the right control and for state checks.
            - To query MaaFramework directly, use {"action":"recognize","reco_type":"OCR",
              "reco_param":{...}} (also TemplateMatch / ColorMatch). The result is appended to the
              action history; use it before deciding a tap or a postcondition.
            - To benchmark a recognition (hit rate/latency before choosing thresholds), use
              {"action":"benchmark","reco_type":"OCR","reco_param":{...},"times":5}.
            - After you have enough recognition evidence, you may author a native MaaFW graph and
              call {"action":"propose_pipeline","pipeline":{"NodeA":{...},"NodeB":{...}},
              "entry":"NodeA","postcondition":{...}}. It becomes a pending Review proposal; do not
              use it for live execution until Test replay/approve.
            - The "postcondition" must be a deterministic local check with an explicit type.
              Use exactly one of these shapes (extra fields are allowed):
                {"type":"pixel","point":[x,y],"rgb":[r,g,b],"tolerance":60,"radius":10}
                {"type":"element","recognition":"OCR","expected":["text|other"],"roi":[0,0,w,h],"threshold":0.3}
                {"type":"screen_text","text":"text that must be visible"}
                {"type":"file_count","path":"/sdcard/DCIM/Camera","delta_min":1}
              Never pass a bare node/recognition object as the whole postcondition; it must contain
              "type" so Test replay can decide success without the vision model.
            - Native MaaFW node fields are FLAT, not nested type/param objects. Example:
              {"Open":{"recognition":"OCR","expected":["闹钟|时钟"],"action":"DoNothing",
              "next":["Verify"]},"Verify":{"recognition":"OCR","expected":[...],
              "action":"DoNothing"}}. Use direct field names package/input_text/key/target/
              template/threshold/roi/next/on_error. If you write {"action":{"type":"StartApp",
              "param":{"package":"..."}}}, flatten it to {"action":"StartApp","package":"..."}.
            - If you need a user decision or missing information (ambiguous target, irreversible action,
              credentials, or a choice between options), call
              {"action":"ask","question":"...","options":["Yes","No"],"allow_free_text":true}.
              The run pauses in needs_input and resumes with the user's answer. Ask once, then continue.
            - If the goal cannot be done, call fail with a reason.
        """.trimIndent() + "\nKnown apps: " + (knownApps.joinToString("; ").ifBlank { "(none)" }) +
            if (skillGuidance.isBlank()) "" else "\n\n" + skillGuidance
        val userText = buildString {
            append("Goal: ").append(goal).append('\n')
            append("Image size: ").append(image.visionWidth).append('x').append(image.visionHeight).append('\n')
            if (observation.isNotBlank()) {
                append("Screen observation: ").append(observation.take(4000)).append('\n')
            }
            if (history.isNotEmpty()) append("Recent actions:\n").append(history.takeLast(8).joinToString("\n"))
        }
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", userText))
            .put(
                JSONObject().put("type", "image_url")
                    .put("image_url", JSONObject().put("url", image.dataUrl))
            )
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
            .put(JSONObject().put("role", "user").put("content", content))
        val data = chat(messages, maxTokens = 6000, tools = tools())
        var result = actionFrom(data)
        if (result == null) {
            // Retry once: reasoning models occasionally answer with empty content
            // in tool mode; JSON mode (or a plain repair turn) recovers it.
            val repair = JSONArray()
            for (i in 0 until messages.length()) repair.put(messages.get(i))
            repair.put(
                JSONObject().put("role", "user").put(
                    "content",
                    "Your previous reply had no valid phone_action. Reply with exactly one JSON " +
                        "object like {\"action\":\"tap\",\"point\":[x,y],\"thought\":\"...\"}.",
                )
            )
            result = runCatching { chat(repair, maxTokens = 6000, jsonMode = true) }
                .getOrNull()?.let { actionFrom(it) }
        }
        return result
    }

    /**
     * Generic screen observation for the bootstrap planner: visible text,
     * roles, approximate points, and whether an add/action control exists.
     * This is app-agnostic; it gives the action model the recognition layer
     * the raw screenshot alone lacks.
     */
    fun observeScreen(imagePath: String): JSONObject? {
        val image = ImageTools.encode(imagePath) ?: return null
        val prompt = """
            You are a screen observer for Android automation. Look at the screenshot and return JSON only:
            {
              "screen": "one-line summary of the current screen",
              "elements": [
                {"text":"visible text or icon description","role":"button|nav|input|text|icon|other","point":[x,y],"clickable":true|false}
              ],
              "has_add_control": true|false,
              "notes": "anything important for the next planner step"
            }
            Coordinates use the exact image pixel size (${image.visionWidth}x${image.visionHeight}).
            List every visible bottom navigation item and action button. If you cannot see an element,
            do not invent it. Report whether the current screen has an add/create/floating-action control.
        """.trimIndent()
        val messages = JSONArray().put(
            JSONObject().put("role", "user").put(
                "content",
                JSONArray()
                    .put(JSONObject().put("type", "text").put("text", prompt))
                    .put(
                        JSONObject().put("type", "image_url")
                            .put("image_url", JSONObject().put("url", image.dataUrl))
                    )
            )
        )
        return runCatching {
            val data = chat(messages, maxTokens = 3000, jsonMode = true)
            val text = data.optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content").orEmpty()
            if (text.isBlank()) null else extractJson(text)
        }.getOrNull()
    }

    private fun actionFrom(data: JSONObject): JSONObject? {
        val message = data.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            ?: return null
        val toolCalls = message.optJSONArray("tool_calls")
        if (toolCalls != null && toolCalls.length() > 0) {
            val args = toolCalls.optJSONObject(0)?.optJSONObject("function")?.optString("arguments")
            if (!args.isNullOrBlank()) {
                runCatching { JSONObject(args) }.getOrNull()?.let { return it }
            }
        }
        val content = message.optString("content").ifBlank { message.optString("reasoning_content") }
        return runCatching { extractJson(content) }.getOrNull()
    }

    /** Locate one UI element by description; returns image-scale point. */
    fun locate(imagePath: String, description: String): Pair<Int, Int>? {
        val image = ImageTools.encode(imagePath) ?: return null
        val messages = JSONArray().put(
            JSONObject().put("role", "user").put(
                "content",
                JSONArray()
                    .put(
                        JSONObject().put("type", "text").put(
                            "text",
                            "Find this Android UI element: $description. Return JSON " +
                                "{\"found\": true|false, \"point\": [x, y]} where x,y use the " +
                                "${image.visionWidth}x${image.visionHeight} image scale.",
                        )
                    )
                    .put(
                        JSONObject().put("type", "image_url")
                            .put("image_url", JSONObject().put("url", image.dataUrl))
                    )
            )
        )
        return runCatching {
            val data = chat(messages, maxTokens = 1500, jsonMode = true)
            val text = data.optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content").orEmpty()
            val json = extractJson(text)
            if (!json.optBoolean("found")) return null
            val point = json.optJSONArray("point") ?: return null
            point.optInt(0) to point.optInt(1)
        }.getOrNull()
    }

    /**
     * Focused pre-tap state check for a toggle-like control. The full
     * screenshot is often too small for the model to judge an icon state, so
     * crop around the located point and ask only that question.
     */
    fun inspectToggle(imagePath: String, rawX: Int, rawY: Int, description: String): Boolean? {
        val image = ImageTools.encodeCrop(imagePath, rawX, rawY) ?: return null
        val prompt = "This is a zoomed crop around an Android UI control: $description. " +
            "Is the control currently active/on/liked/filled? " +
            "Return JSON {\"active\": true|false, \"state\": \"short description\"}."
        val messages = JSONArray().put(
            JSONObject().put("role", "user").put(
                "content",
                JSONArray()
                    .put(JSONObject().put("type", "text").put("text", prompt))
                    .put(
                        JSONObject().put("type", "image_url")
                            .put("image_url", JSONObject().put("url", image.dataUrl))
                    )
            )
        )
        return runCatching {
            val data = chat(messages, maxTokens = 1200, jsonMode = true)
            val text = data.optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content").orEmpty()
            if (text.isBlank()) return@runCatching null
            extractJson(text).optBoolean("active", false)
        }.getOrNull()
    }

    /** Independent, strict vision verification before a pipeline is proposed. */
    fun verify(goal: String, imagePath: String): Pair<Boolean, String> {
        val image = ImageTools.encode(imagePath) ?: return false to "no screenshot"
        val messages = JSONArray().put(
            JSONObject().put("role", "user").put(
                "content",
                JSONArray()
                    .put(
                        JSONObject().put("type", "text").put(
                            "text",
                            "You are a strict verifier for an Android automation. " +
                                "Goal: $goal\n" +
                                "Look at the FINAL screenshot only. Do not assume an action succeeded " +
                                "just because it was attempted. For state/toggle goals (like, follow, " +
                                "enabled, liked, sent), inspect the actual final state (icon fill/color/state " +
                                "text) and quote it as evidence.\n" +
                                "Return JSON {\"success\": true|false, \"state\": \"what the screen shows\", " +
                                "\"evidence\": \"the exact visible evidence\"}.",
                        )
                    )
                    .put(
                        JSONObject().put("type", "image_url")
                            .put("image_url", JSONObject().put("url", image.dataUrl))
                    )
            )
        )
        var lastError = "verifier returned no content"
        repeat(2) { attempt ->
            val data = runCatching {
                chat(messages, maxTokens = 3000, jsonMode = attempt == 0)
            }.getOrElse {
                lastError = it.message ?: "verify request failed"
                return@repeat
            }
            val text = data.optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content").orEmpty()
            if (text.isNotBlank()) {
                val json = runCatching { extractJson(text) }.getOrNull()
                if (json != null) {
                    val ok = json.optBoolean("success")
                    val reason = json.optString("evidence").ifBlank { json.optString("state") }
                    return ok to reason
                }
                lastError = "verifier returned non-JSON: ${text.take(120)}"
            }
        }
        return false to lastError
    }

    private fun extractJson(text: String): JSONObject {
        val trimmed = text.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        return runCatching { JSONObject(trimmed) }.getOrElse {
            val start = trimmed.indexOf('{')
            val end = trimmed.lastIndexOf('}')
            if (start >= 0 && end > start) JSONObject(trimmed.substring(start, end + 1))
            else throw it
        }
    }

    private fun tools(): JSONArray {
        val properties = JSONObject()
            .put("thought", JSONObject().put("type", "string"))
            .put(
                "action",
                JSONObject().put("type", "string")
                    .put("enum", JSONArray(listOf("launch", "locate", "tap", "text", "key", "swipe", "wait", "recognize", "benchmark", "propose_pipeline", "ask", "done", "fail")))
            )
            .put("package", JSONObject().put("type", "string"))
            .put("description", JSONObject().put("type", "string"))
            .put("point", JSONObject().put("type", "array").put("items", JSONObject().put("type", "integer")))
            .put("from", JSONObject().put("type", "array").put("items", JSONObject().put("type", "integer")))
            .put("to", JSONObject().put("type", "array").put("items", JSONObject().put("type", "integer")))
            .put("duration", JSONObject().put("type", "integer"))
            .put("value", JSONObject().put("type", "string"))
            .put("seconds", JSONObject().put("type", "number"))
            .put("reco_type", JSONObject().put("type", "string"))
            .put("reco_param", JSONObject().put("type", "object").put("additionalProperties", true))
            .put("times", JSONObject().put("type", "integer"))
            .put("entry", JSONObject().put("type", "string"))
            .put("pipeline", JSONObject().put("type", "object").put("additionalProperties", true))
            .put(
                "postcondition",
                JSONObject().put("type", "object").put("additionalProperties", true)
                    .put(
                        "description",
                        "Deterministic postcondition with explicit type: " +
                            "pixel | element | screen_text | file_count",
                    ),
            )
            .put("question", JSONObject().put("type", "string"))
            .put(
                "options",
                JSONObject().put("type", "array")
                    .put("items", JSONObject().put("type", "string")),
            )
            .put("allow_free_text", JSONObject().put("type", "boolean"))
            .put("summary", JSONObject().put("type", "string"))
            .put("reason", JSONObject().put("type", "string"))
        val parameters = JSONObject()
            .put("type", "object")
            .put("properties", properties)
            .put("required", JSONArray(listOf("action")))
        val tool = JSONObject()
            .put("type", "function")
            .put(
                "function",
                JSONObject().put("name", "phone_action")
                    .put("description", "Choose exactly one next phone action.")
                    .put("parameters", parameters)
            )
        return JSONArray(listOf(tool))
    }
}
