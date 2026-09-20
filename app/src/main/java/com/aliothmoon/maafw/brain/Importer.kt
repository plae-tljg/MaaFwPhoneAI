package com.aliothmoon.maafw.brain

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Import a standard MaaFramework pipeline JSON (MaaMCP / Everything-Maa /
 * hand-written) as a Review proposal. Nodes are stored as native pipeline
 * fragments, so ROI, recognition method, order_by/index, actions and other
 * protocol fields survive 1:1 and replay through the compiler unchanged.
 */
object Importer {

    fun importPipeline(
        db: BrainDb,
        context: Context,
        pipelineJson: String,
        goal: String,
        appId: Long? = null,
    ): Long {
        val root = runCatching { JSONObject(pipelineJson) }.getOrElse {
            throw IllegalArgumentException("pipeline is not a JSON object: ${it.message}")
        }
        if (root.length() == 0) throw IllegalArgumentException("pipeline is empty")
        // Plain MaaFW pipeline maps are the canonical import. A thin wrapper
        // {pipeline:{...}, entry:"...", postcondition:{...}} is also accepted
        // so an authoring tool can carry a deterministic check without
        // polluting every node map with framework-external keys.
        val wrapped = root.optJSONObject("pipeline") != null &&
            (root.has("postcondition") || root.has("entry"))
        val pipeline = if (wrapped) root.optJSONObject("pipeline")!! else root
        val postcondition = if (wrapped) root.optJSONObject("postcondition") ?: JSONObject() else JSONObject()
        PipelineGraph.validate(pipeline)?.let {
            throw IllegalArgumentException("pipeline is not MaaFW-native: $it")
        }
        val entry = root.optString("entry").takeIf { it.isNotBlank() && pipeline.has(it) }
            ?: PipelineGraph.deriveEntry(pipeline)
        if (entry.isBlank()) throw IllegalArgumentException("pipeline has no entry node")

        val name = "imported_" + (goal.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(40).ifBlank { "pipeline" })
        val aliasesJson = JSONArray(listOf(goal)).toString()
        val pipelineId = db.ensurePipeline(goal, appId, name, source = "maamcp", aliasesJson = aliasesJson)
        val pipelineCandidate = JSONObject()
            .put("id", pipelineId)
            .put("name", name)
            .put("goal", goal)
            .put("aliases", JSONArray(listOf(goal)))
            .put("app_id", appId ?: JSONObject.NULL)
            .put("entry", entry)
            .put("graph", pipeline)
            .put("postcondition", postcondition)
        val copied = copyTemplates(db, context, pipelineCandidate)
        val versionId = db.insertPipelineVersion(
            pipelineId = pipelineId,
            definitionJson = pipeline.toString(),
            entry = entry,
            postconditionJson = postcondition.toString(),
            source = "maamcp",
            evidenceJson = JSONObject().put("imported", true).put("templates", copied).toString(),
        )
        pipelineCandidate.put("version_id", versionId)
        val candidate = JSONObject()
            .put("pipeline", pipelineCandidate)
            .put("elements", JSONObject())
            .put("templates", copied)
        val id = db.insertProposal(
            "pipeline_new",
            candidate.toString(),
            sourceRunId = null,
            targetPipelineId = pipelineId,
            targetVersionId = versionId,
        )
        db.addMessage(
            null, "assistant", "card",
            "Imported pipeline proposal #$id ($goal, pipeline=$pipelineId version=$versionId, " +
                "${copied.length()} template file(s))",
        )
        return id
    }

    /** Copy template images referenced by the pipeline into a versioned bundle. */
    private fun copyTemplates(db: BrainDb, context: Context, pipeline: JSONObject): JSONArray {
        val imports = File(context.getExternalFilesDir("brain"), "imports")
        val refs = mutableSetOf<String>()
        fun collect(node: JSONObject) {
            for (key in listOf("template", "templates")) {
                when (val value = node.opt(key)) {
                    is String -> refs.add(value)
                    is JSONArray -> for (i in 0 until value.length()) value.optString(i).takeIf { it.isNotBlank() }?.let { refs.add(it) }
                }
            }
            for (childKey in node.keys()) {
                node.optJSONObject(childKey)?.let { collect(it) }
            }
        }
        collect(pipeline)
        if (refs.isEmpty()) return JSONArray()

        val version = BrainResources.nextVersion(db)
        val imageDir = File(BrainResources.bundleDir(context, version), "image").apply { mkdirs() }
        val copied = JSONArray()
        for (ref in refs) {
            val candidates = listOf(
                File(imports, ref),
                File(imports, File(ref).name),
                File(imports, "image/${File(ref).name}"),
            )
            val source = candidates.firstOrNull { it.isFile } ?: continue
            val target = File(imageDir, File(ref).name)
            target.parentFile?.mkdirs()
            source.copyTo(target, overwrite = true)
            copied.put(File(ref).name)
        }
        return copied
    }
}
