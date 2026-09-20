package com.aliothmoon.maafw.brain

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Native MaaFW graph contract.
 *
 * `pipelines.definition_json` / `pipeline_versions.definition_json` are a map:
 *
 *   {
 *     "NodeA": {"recognition":"OCR","expected":["..."],"action":"Click","target":true,"next":["NodeB"]},
 *     "NodeB": {"recognition":"DirectHit","action":"DoNothing"}
 *   }
 *
 * and `.entry` names the first node. This is MaaFW's own pipeline JSON shape;
 * there is no app-specific step wrapper in the database.
 *
 * The conversion helpers here are only used while *authoring* a run
 * trajectory or performing a one-time legacy migration. The runtime compiler
 * never interprets app-specific formats.
 */
object PipelineGraph {

    private val keyCodes = mapOf(
        "back" to 4, "home" to 3, "enter" to 66, "delete" to 67, "menu" to 82,
        "power" to 26, "volume_up" to 24, "volume_down" to 25, "tab" to 61, "escape" to 111,
    )

    data class Native(val graph: JSONObject, val entry: String)

    /** Validate a native graph and return a human-readable error, or null. */
    fun validate(graph: JSONObject): String? {
        if (graph.length() == 0) return "graph is empty"
        for (name in graph.keys()) {
            val node = graph.optJSONObject(name) ?: return "node '$name' is not an object"
            val recognition = node.opt("recognition")
            if (recognition != null && recognition !is String) {
                return "node '$name'.recognition must be a string (flat MaaFW field), not ${recognition.javaClass.simpleName}"
            }
            val action = node.opt("action")
            if (action != null && action !is String) {
                return "node '$name'.action must be a string (flat MaaFW field), not ${action.javaClass.simpleName}"
            }
            for (key in listOf("next", "on_error", "interrupt")) {
                val value = node.opt(key) ?: continue
                when (value) {
                    is String -> if (value.isBlank()) return "node '$name'.$key is blank"
                    is JSONArray -> {
                        for (i in 0 until value.length()) {
                            if (value.optString(i).isBlank()) return "node '$name'.$key[$i] is blank"
                        }
                    }
                    else -> return "node '$name'.$key must be a string or string array"
                }
            }
        }
        return null
    }

    fun deriveEntry(graph: JSONObject): String {
        val names = graph.keys().asSequence().toList()
        if (names.isEmpty()) return ""
        val referenced = mutableSetOf<String>()
        for (name in names) {
            val node = graph.optJSONObject(name) ?: continue
            for (key in listOf("next", "on_error", "interrupt")) {
                when (val value = node.opt(key)) {
                    is JSONArray -> for (i in 0 until value.length()) {
                        value.optString(i).takeIf { it.isNotBlank() }?.let { referenced += it }
                    }
                    is String -> value.takeIf { it.isNotBlank() }?.let { referenced += it }
                }
            }
        }
        return names.firstOrNull { it !in referenced } ?: names.first()
    }

    /**
     * Authoring conversion: turn the existing bootstrap action-step list into
     * one native graph with a `next` chain. Not used at read/execute time.
     */
    fun fromActionSteps(steps: JSONArray, elements: JSONObject): Native? {
        if (steps.length() == 0) return null
        val graph = JSONObject()
        val names = mutableListOf<String>()
        for (i in 0 until steps.length()) {
            val step = steps.optJSONObject(i) ?: continue
            val name = step.optString("name").takeIf { it.isNotBlank() } ?: "brain_step_$i"
            val node = actionStepToNode(step, elements) ?: continue
            graph.put(name, node)
            names += name
        }
        if (graph.length() == 0) return null
        for (i in 0 until names.size - 1) {
            val node = graph.optJSONObject(names[i]) ?: continue
            node.put("next", JSONArray(listOf(names[i + 1])))
        }
        return Native(graph, names.first())
    }

    private fun actionStepToNode(step: JSONObject, elements: JSONObject): JSONObject? {
        val action = step.optString("action")
        val delayMs = (step.optDouble("delay", 0.0) * 1000).toLong()
        val node = JSONObject()
        node.put("recognition", "DirectHit")
        when (action) {
            "launch" -> {
                val pkg = step.optString("package")
                if (pkg.isBlank()) return null
                node.put("action", "StartApp").put("package", pkg)
            }
            "tap", "click" -> {
                val element = step.optString("element")
                val locator = element.takeIf { it.isNotBlank() }?.let { elements.optJSONObject(it) }
                when {
                    locator?.optString("type") == "template" -> {
                        node.put("recognition", "TemplateMatch")
                        val template = locator.optString("template").ifBlank {
                            File(locator.optString("path")).name
                        }
                        node.put("template", JSONArray(listOf(template)))
                        node.put("threshold", locator.optDouble("threshold", 0.75))
                        node.put("action", "Click").put("target", true)
                    }
                    step.has("point") -> {
                        node.put("action", "Click").put("target", step.optJSONArray("point"))
                    }
                    else -> return null
                }
            }
            "text" -> node.put("action", "InputText").put("input_text", step.optString("value"))
            "key" -> {
                val raw = step.opt("value")?.toString().orEmpty()
                val code = keyCodes[raw.lowercase()] ?: raw.toIntOrNull() ?: return null
                node.put("action", "ClickKey").put("key", JSONArray(listOf(code)))
            }
            "swipe" -> {
                val from = step.optJSONArray("from") ?: return null
                val to = step.optJSONArray("to") ?: return null
                node.put("action", "Swipe")
                node.put("begin", from)
                node.put("end", JSONArray(listOf(to)))
                node.put("duration", JSONArray(listOf(step.optInt("duration", 400))))
            }
            "wait" -> {
                node.put("action", "DoNothing")
                node.put("post_delay", (step.optDouble("seconds", 1.5) * 1000).toLong())
                return node
            }
            else -> return null
        }
        if (delayMs > 0) node.put("post_delay", delayMs)
        return node
    }

    /**
     * One-time migration from the old mixed forms:
     *  - an action-step array
     *  - an array of {name, pipeline/node} fragments
     *  - an already-native graph object
     */
    fun migrateDefinition(definitionJson: String, elements: JSONObject = JSONObject()): Native? {
        val trimmed = definitionJson.trim()
        if (trimmed.isEmpty()) return null
        runCatching { JSONObject(trimmed) }.getOrNull()?.let { obj ->
            flattenLegacyGraph(obj)?.let { return it }
        }
        val array = runCatching { JSONArray(trimmed) }.getOrNull() ?: return null
        if (array.length() == 0) return null
        val graph = JSONObject()
        val names = mutableListOf<String>()
        var migratedFragments = false
        val actionSteps = JSONArray()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val fragment = item.optJSONObject("pipeline") ?: item.optJSONObject("node")
            if (fragment != null) {
                migratedFragments = true
                val name = item.optString("name").takeIf { it.isNotBlank() } ?: "brain_node_$i"
                graph.put(name, flattenLegacyNode(fragment))
                names += name
            } else {
                actionSteps.put(item)
            }
        }
        if (migratedFragments) {
            if (graph.length() == 0) return null
            if (validate(graph) != null) return null
            return Native(graph, deriveEntry(graph))
        }
        return fromActionSteps(actionSteps, elements)
    }

    /**
     * One-time repair for graphs written before the flat-native contract.
     * Earlier proposals used MaaFW fields nested as
     * `{"action":{"type":"StartApp","param":{"package":"..."}}}`; the runtime
     * compiler deliberately does not normalize at read time, so those rows are
     * flattened here during the DB migration instead.
     */
    private fun flattenLegacyGraph(graph: JSONObject): Native? {
        if (graph.length() == 0) return null
        val out = JSONObject()
        for (name in graph.keys()) {
            val node = graph.optJSONObject(name) ?: return null
            out.put(name, flattenLegacyNode(node))
        }
        if (validate(out) != null) return null
        return Native(out, deriveEntry(out))
    }

    private fun flattenLegacyNode(node: JSONObject): JSONObject {
        val out = JSONObject(node.toString())

        val nestedRecognition = out.opt("recognition")
        if (nestedRecognition is JSONObject) {
            val type = nestedRecognition.optString("type").trim()
            out.put("recognition", recognitionName(type.ifBlank { "OCR" }))
            copyParams(out, nestedRecognition.optJSONObject("param"))
        }

        val nestedAction = out.opt("action")
        if (nestedAction is JSONObject) {
            val type = nestedAction.optString("type").trim()
                .ifBlank { nestedAction.optString("action").trim() }
            out.put("action", actionName(type.ifBlank { "DoNothing" }))
            copyParams(out, nestedAction.optJSONObject("param"))
        }

        return out
    }

    private fun copyParams(target: JSONObject, params: JSONObject?) {
        if (params == null) return
        for (key in params.keys()) {
            if (key == "type" || key == "param") continue
            target.put(key, params.get(key))
        }
    }

    private fun recognitionName(raw: String): String = when (raw.lowercase()) {
        "ocr" -> "OCR"
        "template", "templatematch" -> "TemplateMatch"
        "color", "colormatch" -> "ColorMatch"
        "directhit", "direct_hit" -> "DirectHit"
        "featurematch", "feature_match" -> "FeatureMatch"
        "neuralnetworkclassify", "neural_network_classify" -> "NeuralNetworkClassify"
        "neuralnetworkdetect", "neural_network_detect" -> "NeuralNetworkDetect"
        "custom" -> "Custom"
        else -> raw
    }

    private fun actionName(raw: String): String = when (raw.lowercase()) {
        "donothing", "do_nothing" -> "DoNothing"
        "click" -> "Click"
        "startapp", "start_app" -> "StartApp"
        "stopapp", "stop_app" -> "StopApp"
        "inputtext", "input_text" -> "InputText"
        "clickkey", "click_key" -> "ClickKey"
        "swipe" -> "Swipe"
        "screencap" -> "Screencap"
        "stoptask", "stop_task" -> "StopTask"
        "custom" -> "Custom"
        else -> raw
    }
}
