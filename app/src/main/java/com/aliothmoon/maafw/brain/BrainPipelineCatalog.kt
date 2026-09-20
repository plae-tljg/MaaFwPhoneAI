package com.aliothmoon.maafw.brain

import android.content.Context
import com.aliothmoon.maafw.MaaDispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.json.JSONObject

/**
 * Live brain pipelines projected for MaaFwApp's normal task/config flow.
 *
 * A pipeline that the user approved in Review is not just an Assistant row:
 * it must be addable to the original Tasks tab config and runnable there as a
 * normal MaaFW task. This catalog keeps that projection fresh.
 */
data class BrainPipeline(
    val id: Long,
    val name: String,
    val goal: String,
    val entry: String,
    val graph: JsonObject,
)

data class BrainPipelineSnapshot(
    /** Increments on every refresh so a new template bundle also reaches the repository. */
    val version: Long = 0L,
    val pipelines: List<BrainPipeline> = emptyList(),
    /** Relative paths from PI root; appended to the active MaaFwApp resource. */
    val resourcePaths: List<String> = emptyList(),
)

class BrainPipelineCatalog(
    private val context: Context,
    private val db: BrainDb,
) {
    private val _snapshot = MutableStateFlow(BrainPipelineSnapshot())
    val snapshot: StateFlow<BrainPipelineSnapshot> = _snapshot.asStateFlow()

    private val refreshMutex = Mutex()

    /** Re-read `pipelines WHERE status='live'`; safe to call from ON_RESUME and after approval. */
    suspend fun refresh() = refreshMutex.withLock {
        val loaded = withContext(MaaDispatchers.IO) { load() }
        _snapshot.value = loaded
    }

    private fun load(): BrainPipelineSnapshot {
        val rows = runCatching {
            db.rows(
                "SELECT id,name,goal,entry,definition_json FROM pipelines " +
                    "WHERE status='live' ORDER BY id DESC",
            )
        }.getOrDefault(emptyList())

        val parsed = rows.mapNotNull { row ->
            val definition = runCatching { JSONObject(row.optString("definition_json")) }.getOrNull()
                ?: return@mapNotNull null
            if (PipelineGraph.validate(definition) != null) return@mapNotNull null
            val entry = row.optString("entry").takeIf { it.isNotBlank() && definition.has(it) }
                ?: PipelineGraph.deriveEntry(definition)
            if (entry.isBlank()) return@mapNotNull null
            val graph = runCatching {
                Json.parseToJsonElement(definition.toString()) as? JsonObject
            }.getOrNull() ?: return@mapNotNull null
            BrainPipeline(
                id = row.getLong("id"),
                name = row.optString("name"),
                goal = row.optString("goal"),
                entry = entry,
                graph = graph,
            )
        }

        return BrainPipelineSnapshot(
            version = _snapshot.value.version + 1,
            pipelines = parsed,
            resourcePaths = BrainResources.resourcePaths(context, db),
        )
    }
}
