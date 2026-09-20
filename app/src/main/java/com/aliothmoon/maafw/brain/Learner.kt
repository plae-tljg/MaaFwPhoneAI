package com.aliothmoon.maafw.brain

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Turns a verified AI trajectory into a Review proposal, and publishes it. */
object Learner {

    fun propose(
        db: BrainDb,
        context: Context,
        runId: Long,
        goal: String,
        appId: Long?,
        trajectory: List<JSONObject>,
        proposalKind: String = "pipeline_new",
        targetPipelineId: Long? = null,
        evidenceRunId: Long? = null,
        postcondition: JSONObject = JSONObject(),
    ): Long? {
        val steps = JSONArray()
        val elements = JSONObject()
        var templateVersion: Int? = null
        var index = 0
        for (entry in trajectory) {
            val step = JSONObject()
            when (entry.optString("action")) {
                "launch" -> {
                    val pkg = entry.optString("package")
                    if (pkg.isBlank()) continue
                    step.put("action", "launch").put("package", pkg)
                }
                "tap" -> {
                    val source = entry.optString("templateSource")
                    val point = entry.optJSONArray("templatePoint") ?: entry.optJSONArray("point")
                    if (!source.isBlank() && point != null && point.length() >= 2) {
                        val version: Int
                        if (templateVersion == null) {
                            version = BrainResources.nextVersion(db)
                            templateVersion = version
                        } else {
                            version = templateVersion
                        }
                        val name = "brain_${runId}_$index"
                        val ok = BrainResources.saveTemplate(
                            context, version, "$name.png", source,
                            point.optInt(0), point.optInt(1),
                        )
                        if (ok) {
                            elements.put(
                                name,
                                JSONObject()
                                    .put("type", "template")
                                    .put("template", "$name.png")
                                    .put("threshold", 0.75),
                            )
                            step.put("action", "tap").put("element", name)
                        } else if (entry.optJSONArray("point") != null) {
                            step.put("action", "tap").put("point", entry.optJSONArray("point"))
                        } else {
                            continue
                        }
                    } else if (point != null) {
                        step.put("action", "tap").put("point", point)
                    } else {
                        continue
                    }
                }
                "text" -> step.put("action", "text").put("value", entry.optString("value"))
                "key" -> step.put("action", "key").put("value", entry.optString("value"))
                "swipe" -> {
                    val from = entry.optJSONArray("from") ?: continue
                    val to = entry.optJSONArray("to") ?: continue
                    step.put("action", "swipe").put("from", from).put("to", to)
                        .put("duration", entry.optInt("duration", 400))
                }
                "wait" -> step.put("action", "wait").put("seconds", entry.optDouble("seconds", 1.5))
                else -> continue
            }
            if (step.optString("action") != "wait") step.put("delay", 2.0)
            steps.put(step)
            index++
        }
        if (steps.length() == 0) return null
        val native = PipelineGraph.fromActionSteps(steps, elements) ?: return null
        val fallbackName = slug(goal)
        val aliasesJson = JSONArray(listOf(goal)).toString()
        val pipelineId = targetPipelineId
            ?: db.ensurePipeline(goal, appId, fallbackName, source = "bootstrap", aliasesJson = aliasesJson)
        val pipelineRow = db.rows("SELECT name,aliases,app_id FROM pipelines WHERE id=?", arrayOf<Any?>(pipelineId))
            .firstOrNull()
        val effectiveAppId = appId
            ?: pipelineRow?.opt("app_id")?.takeIf { it != JSONObject.NULL }?.toString()?.toLongOrNull()
        val name = pipelineRow?.optString("name")?.takeIf { it.isNotBlank() } ?: fallbackName
        val versionId = db.insertPipelineVersion(
            pipelineId = pipelineId,
            definitionJson = native.graph.toString(),
            entry = native.entry,
            postconditionJson = postcondition.toString(),
            source = "bootstrap",
            sourceRunId = runId,
            evidenceJson = JSONObject().put("vision_verified", true).toString(),
        )
        if (evidenceRunId != null) {
            db.linkVersionRun(versionId, evidenceRunId, "evidence")
        }
        val pipeline = JSONObject()
            .put("id", pipelineId)
            .put("version_id", versionId)
            .put("name", name)
            .put("goal", goal)
            .put("aliases", JSONArray(listOf(goal)))
            .put("app_id", effectiveAppId ?: JSONObject.NULL)
            .put("entry", native.entry)
            .put("graph", native.graph)
            .put("postcondition", postcondition)
        val candidate = JSONObject()
            .put("pipeline", pipeline)
            .put("elements", elements)
        val proposalId = db.insertProposal(
            proposalKind, candidate.toString(), runId,
            targetPipelineId = pipelineId,
            targetVersionId = versionId,
        )
        val card = if (proposalKind == "pipeline_fix") {
            "Update existing pipeline? proposal #$proposalId (${steps.length()} steps, " +
                "${elements.length()} template pattern(s), replay evidence)"
        } else {
            "Save as pipeline? proposal #$proposalId (${steps.length()} steps, " +
                "${elements.length()} template pattern(s), vision-verified)"
        }
        db.addMessage(
            runId, "assistant", "card", card,
            JSONObject().put("proposal_id", proposalId).toString(),
        )
        return proposalId
    }

    /**
     * Store a native MaaFW graph authored by the AI (or by the PC bench) as a
     * pending Review proposal. The graph is an object map:
     * { "NodeA": {"recognition": "...", "action": "...", "next": ["NodeB"]} }.
     */
    fun proposeNativeGraph(
        db: BrainDb,
        context: Context,
        runId: Long,
        goal: String,
        appId: Long?,
        graph: JSONObject,
        entry: String = "",
        postcondition: JSONObject = JSONObject(),
        proposalKind: String = "pipeline_new",
        targetPipelineId: Long? = null,
        evidenceRunId: Long? = null,
    ): Long? {
        if (graph.length() == 0 || PipelineGraph.validate(graph) != null) return null
        val effectiveEntry = entry.takeIf { it.isNotBlank() && graph.has(it) } ?: PipelineGraph.deriveEntry(graph)
        val fallbackName = slug(goal)
        val aliasesJson = JSONArray(listOf(goal)).toString()
        val pipelineId = targetPipelineId
            ?: db.ensurePipeline(goal, appId, fallbackName, source = "bootstrap", aliasesJson = aliasesJson)
        val pipelineRow = db.rows("SELECT name,app_id FROM pipelines WHERE id=?", arrayOf<Any?>(pipelineId))
            .firstOrNull()
        val effectiveAppId = appId
            ?: pipelineRow?.opt("app_id")?.takeIf { it != JSONObject.NULL }?.toString()?.toLongOrNull()
        val name = pipelineRow?.optString("name")?.takeIf { it.isNotBlank() } ?: fallbackName
        val versionId = db.insertPipelineVersion(
            pipelineId = pipelineId,
            definitionJson = graph.toString(),
            entry = effectiveEntry,
            postconditionJson = postcondition.toString(),
            source = "bootstrap",
            sourceRunId = runId,
            evidenceJson = JSONObject().put("ai_authored_graph", true).toString(),
        )
        if (evidenceRunId != null) db.linkVersionRun(versionId, evidenceRunId, "evidence")
        val pipeline = JSONObject()
            .put("id", pipelineId)
            .put("version_id", versionId)
            .put("name", name)
            .put("goal", goal)
            .put("aliases", JSONArray(listOf(goal)))
            .put("app_id", effectiveAppId ?: JSONObject.NULL)
            .put("entry", effectiveEntry)
            .put("graph", graph)
            .put("postcondition", postcondition)
        val candidate = JSONObject()
            .put("pipeline", pipeline)
            .put("elements", JSONObject())
        val proposalId = db.insertProposal(
            proposalKind, candidate.toString(), runId,
            targetPipelineId = pipelineId,
            targetVersionId = versionId,
        )
        db.addMessage(
            runId, "assistant", "card",
            "AI-authored native graph proposal #$proposalId (${graph.length()} node(s), " +
                "entry=$effectiveEntry); test replay in Review before approval",
            JSONObject().put("proposal_id", proposalId).toString(),
        )
        return proposalId
    }

    fun approve(db: BrainDb, proposalId: Long): String {
        val row = db.rows("SELECT * FROM proposals WHERE id=?", arrayOf<Any?>(proposalId)).firstOrNull()
            ?: return "proposal $proposalId not found"
        if (row.optString("status") != "pending") return "proposal $proposalId is ${row.optString("status")}"
        val candidate = runCatching { JSONObject(row.optString("candidate_json")) }.getOrNull()
            ?: return "invalid candidate JSON"
        val pipeline = candidate.optJSONObject("pipeline") ?: return "candidate has no pipeline"
        var appId = pipeline.opt("app_id")?.takeIf { it != JSONObject.NULL }?.toString()?.toLongOrNull()
        if (appId == null) {
            val targetPipelineId = row.opt("target_pipeline_id")?.takeIf { it != JSONObject.NULL }
                ?.toString()?.toLongOrNull()
                ?: pipeline.opt("id")?.takeIf { it != JSONObject.NULL }?.toString()?.toLongOrNull()
            appId = targetPipelineId?.let { id ->
                db.rows("SELECT app_id FROM pipelines WHERE id=?", arrayOf<Any?>(id)).firstOrNull()
                    ?.opt("app_id")?.takeIf { it != JSONObject.NULL }?.toString()?.toLongOrNull()
            }
        }
        val elements = candidate.optJSONObject("elements")
        if (elements != null && appId != null) {
            for (name in elements.keys()) {
                db.insertElement(appId, name, elements.optJSONObject(name)?.toString() ?: "{}")
            }
        }
        val versionId = row.opt("target_version_id")?.takeIf { it != JSONObject.NULL }
            ?.toString()?.toLongOrNull()
            ?: pipeline.opt("version_id")?.takeIf { it != JSONObject.NULL }?.toString()?.toLongOrNull()
        val rawDefinition = pipeline.optJSONObject("graph")?.toString()
            ?: pipeline.optJSONArray("steps")?.toString()
            ?: "{}"
        val native = PipelineGraph.migrateDefinition(rawDefinition, candidate.optJSONObject("elements") ?: JSONObject())
            ?: return "candidate has no native MaaFW graph"
        PipelineGraph.validate(native.graph)?.let { return "candidate graph invalid: $it" }
        val definitionJson = native.graph.toString()
        val entry = native.entry
        if (versionId != null) {
            db.updateVersionCandidate(
                versionId = versionId,
                definitionJson = definitionJson,
                entry = entry,
                postconditionJson = pipeline.optJSONObject("postcondition")?.toString() ?: "{}",
            )
            db.approvePipelineVersion(versionId)
        } else {
            db.insertPipeline(
                appId = appId,
                name = pipeline.optString("name", slug(pipeline.optString("goal"))),
                goal = pipeline.optString("goal"),
                aliasesJson = pipeline.optJSONArray("aliases")?.toString() ?: "[]",
                definitionJson = definitionJson,
                postconditionJson = pipeline.optJSONObject("postcondition")?.toString() ?: "{}",
                entry = entry,
            )
        }
        db.updateProposalStatus(proposalId, "approved")
        db.addMessage(null, "assistant", "status", "Proposal #$proposalId approved and live")
        return "proposal #$proposalId approved"
    }

    fun reject(db: BrainDb, proposalId: Long): String {
        db.updateProposalStatus(proposalId, "rejected")
        db.addMessage(null, "assistant", "status", "Proposal #$proposalId rejected")
        return "proposal #$proposalId rejected"
    }

    private fun slug(goal: String): String {
        val ascii = goal.lowercase().map { if (it.isLetterOrDigit()) it else '_' }
            .joinToString("").trim('_').take(32)
        return "brain_" + (ascii.ifBlank { "pipeline" })
    }
}
