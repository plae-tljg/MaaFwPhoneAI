package com.aliothmoon.maafw.brain

import android.content.Context
import com.aliothmoon.maafw.domain.ControllerDefinition
import com.aliothmoon.maafw.domain.ResourceDefinition
import com.aliothmoon.maafw.domain.RunConfigurationId
import com.aliothmoon.maafw.privileged.PrivilegedServicePort
import com.aliothmoon.maafw.runner.ExecutionResult
import com.aliothmoon.maafw.runner.RunPlan
import com.aliothmoon.maafw.runner.RunnerCommandResult
import com.aliothmoon.maafw.runner.RunnerPort
import com.aliothmoon.maafw.runner.RuntimeTask
import com.aliothmoon.maafw.runner.isBusy
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * One-node MaaFW run-plan helpers for the AI fallback loop.
 *
 * We deliberately use the existing RunnerPort (same contract as compiled
 * pipelines) instead of adding AIDL methods: each action/screenshot is one
 * node task; MaaFramework does the native work in the privileged process.
 */
class AgentTools(
    private val context: Context,
    private val db: BrainDb,
    private val runner: RunnerPort,
    private val servicePort: PrivilegedServicePort,
) {

    private val keyCodes = mapOf("back" to 4, "home" to 3, "enter" to 66, "delete" to 67)

    private fun plan(name: String, node: JSONObject): RunPlan = RunPlan(
        projectName = "maa-phone-brain",
        projectVersion = "0.1",
        controller = ControllerDefinition(),
        resource = ResourceDefinition(name = "brain", paths = BrainResources.resourcePaths(context, db)),
        runConfigurationId = RunConfigurationId("brain-${UUID.randomUUID()}"),
        tasks = listOf(
            RuntimeTask(
                taskName = name,
                entry = name,
                pipelineOverrides = listOf(
                    JsonObject(mapOf(name to Json.parseToJsonElement(node.toString()).jsonObject))
                ),
                label = name,
            )
        ),
    )

    suspend fun tapRaw(x: Int, y: Int, taskName: String = "brain_locate_tap"): Boolean {
        val node = JSONObject()
            .put("recognition", "DirectHit")
            .put("action", "Click")
            .put("target", JSONArray(listOf(x, y)))
        return await(plan(taskName, node)) is ExecutionResult.Completed
    }

    suspend fun stop(): Boolean = when (runner.stop()) {
        RunnerCommandResult.Accepted -> true
        is RunnerCommandResult.Rejected -> false
    }

    suspend fun await(plan: RunPlan): ExecutionResult? {
        return when (runner.start(plan)) {
            RunnerCommandResult.Accepted -> withTimeout(120_000) {
                runner.state.first { !it.phase.isBusy && it.latestResult != null }
            }.latestResult
            is RunnerCommandResult.Rejected -> null
        }
    }

    /** Refresh the controller frame, then save it as a file both sides can read. */
    suspend fun captureScreenshot(path: String, taskName: String = "brain_capture"): Boolean {
        val node = JSONObject()
            .put("recognition", "DirectHit")
            .put("action", "Screencap")
        val result = await(plan(taskName, node)) ?: return false
        if (result !is ExecutionResult.Completed) return false
        return servicePort.useService { service -> service.saveCachedImage(path) }
    }

    /**
     * Run a real MaaFramework recognition on a fresh controller frame and
     * return the recognition detail JSON. This is the bridge used by the AI
     * fallback for OCR/TemplateMatch/ColorMatch observations.
     */
    suspend fun recognition(recoType: String = "OCR", recoParamJson: String = "{}"): JSONObject? {
        val node = JSONObject()
            .put("recognition", "DirectHit")
            .put("action", "Screencap")
        val frameResult = await(plan("brain_recognition_frame_${UUID.randomUUID()}", node)) ?: return null
        if (frameResult !is ExecutionResult.Completed) return null
        val raw = runCatching {
            servicePort.useService { service -> service.recognitionDirect(recoType, recoParamJson) }
        }.getOrNull() ?: return null
        return runCatching { JSONObject(raw) }.getOrNull()
    }

    /** Execute one model action. Returns a human-readable result. */
    suspend fun execute(action: JSONObject, scale: Double, taskName: String = "brain_action"): String {
        return when (val kind = action.optString("action")) {
            "launch" -> {
                val node = JSONObject()
                    .put("recognition", "DirectHit")
                    .put("action", "StartApp")
                    .put("package", action.optString("package"))
                val result = await(plan(taskName, node))
                if (result is ExecutionResult.Completed) "launched ${action.optString("package")}"
                else "launch failed"
            }
            "tap" -> {
                val raw = pointToRaw(action.opt("point"), scale)
                    ?: return "tap skipped: no point"
                val node = JSONObject()
                    .put("recognition", "DirectHit")
                    .put("action", "Click")
                    .put("target", JSONArray(listOf(raw.first, raw.second)))
                val result = await(plan(taskName, node))
                if (result is ExecutionResult.Completed) "tapped (${raw.first},${raw.second})"
                else "tap failed"
            }
            "text" -> {
                val node = JSONObject()
                    .put("recognition", "DirectHit")
                    .put("action", "InputText")
                    .put("input_text", action.optString("value"))
                val result = await(plan(taskName, node))
                if (result is ExecutionResult.Completed) "typed ${action.optString("value")}"
                else "text failed"
            }
            "key" -> {
                val code = keyCodes[action.optString("value").lowercase()] ?: return "unknown key"
                val node = JSONObject()
                    .put("recognition", "DirectHit")
                    .put("action", "ClickKey")
                    .put("key", JSONArray(listOf(code)))
                val result = await(plan(taskName, node))
                if (result is ExecutionResult.Completed) "key ${action.optString("value")}"
                else "key failed"
            }
            "swipe" -> {
                val from = pointToRaw(action.opt("from"), scale) ?: return "swipe skipped"
                val to = pointToRaw(action.opt("to"), scale) ?: return "swipe skipped"
                val node = JSONObject()
                    .put("recognition", "DirectHit")
                    .put("action", "Swipe")
                    .put("begin", JSONArray(listOf(from.first, from.second)))
                    .put("end", JSONArray(listOf(JSONArray(listOf(to.first, to.second)))))
                    .put("duration", JSONArray(listOf(action.optInt("duration", 400))))
                val result = await(plan(taskName, node))
                if (result is ExecutionResult.Completed) "swiped"
                else "swipe failed"
            }
            "wait" -> {
                delay((action.optDouble("seconds", 1.5) * 1000).toLong())
                "waited"
            }
            "recognize" -> {
                val recoType = action.optString("reco_type").ifBlank { "OCR" }
                val recoParam = action.opt("reco_param")?.toString().orEmpty().ifBlank { "{}" }
                val detail = recognition(recoType, recoParam)
                if (detail != null) {
                    "recognized $recoType: ${detail.toString().take(1200)}"
                } else {
                    "recognize failed: $recoType"
                }
            }
            "benchmark" -> {
                val recoType = action.optString("reco_type").ifBlank { "OCR" }
                val recoParam = action.opt("reco_param")?.toString().orEmpty().ifBlank { "{}" }
                val times = action.optInt("times", 3).coerceIn(1, 20)
                var hits = 0
                val started = System.currentTimeMillis()
                val samples = mutableListOf<String>()
                repeat(times) {
                    val detail = recognition(recoType, recoParam)
                    if (detail?.optBoolean("hit") == true) hits++
                    detail?.optString("detail")?.take(200)?.takeIf { it.isNotBlank() }?.let { samples += it }
                }
                val elapsed = System.currentTimeMillis() - started
                val avg = if (times > 0) elapsed / times else 0
                "benchmark $recoType x$times: hits=$hits/$times, avg=${avg}ms, " +
                    "sample=${samples.firstOrNull() ?: "none"}"
            }
            else -> "unknown action: $kind"
        }
    }

    private fun pointToRaw(value: Any?, scale: Double): Pair<Int, Int>? {
        val point = when (value) {
            is JSONArray -> value
            is List<*> -> JSONArray(value)
            else -> return null
        }
        if (point.length() < 2) return null
        return Pair(
            (point.optDouble(0) * scale).toInt(),
            (point.optDouble(1) * scale).toInt(),
        )
    }
}
