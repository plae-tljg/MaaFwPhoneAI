package com.aliothmoon.maafw.brain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.json.JSONObject

/**
 * DB rows -> MaaFW pipeline graph.
 *
 * The DB contract is now MaaFW-native only: `definition_json` is a node map
 * `{NodeName: {recognition/action/next/...}}` and `.entry` names the first
 * node. This compiler does not interpret app-specific step arrays; legacy
 * rows are converted once by the DB migration in [BrainDb].
 */
object Compiler {

    data class CompiledGraph(
        val entry: String,
        val nodes: Map<String, JsonObject>,
        val labels: Map<String, String>,
    )

    fun compile(db: BrainDb, pipeline: PipelineRow): CompiledGraph {
        val graph = runCatching { JSONObject(pipeline.definitionJson) }.getOrNull()
            ?: return CompiledGraph("", emptyMap(), emptyMap())
        if (PipelineGraph.validate(graph) != null) {
            return CompiledGraph("", emptyMap(), emptyMap())
        }
        val names = graph.keys().asSequence().toList()
        if (names.isEmpty()) return CompiledGraph("", emptyMap(), emptyMap())
        val entry = pipeline.entry.takeIf { it.isNotBlank() && graph.has(it) }
            ?: PipelineGraph.deriveEntry(graph)
        val nodes = mutableMapOf<String, JsonObject>()
        for (name in names) {
            val node = graph.optJSONObject(name) ?: continue
            val parsed = runCatching { Json.parseToJsonElement(node.toString()).jsonObject }.getOrNull() ?: continue
            nodes[name] = parsed
        }
        return CompiledGraph(entry, nodes, names.associateWith { it })
    }
}
