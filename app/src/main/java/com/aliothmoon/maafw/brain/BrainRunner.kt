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
import com.aliothmoon.maafw.runner.isBusy
import com.aliothmoon.maafw.runner.RuntimeTask
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** M1/M3: goal -> DB resolver -> compiler -> MaaFwApp runner, AI fallback hook. */
class BrainRunner(
    private val context: Context,
    private val db: BrainDb,
    private val runner: RunnerPort,
    private val servicePort: PrivilegedServicePort? = null,
    private val aiFallback: (suspend (String) -> GoalResult)? = null,
) {

    data class GoalResult(
        val status: String,
        val message: String,
        val runId: Long? = null,
        val pipelineId: Long? = null,
    )

    /**
     * @param mode "auto" = normal resolver + AI fallback on a miss;
     *             "replay" = only run an existing live pipeline, never fall back.
     */
    suspend fun runGoal(
        goal: String,
        mode: String = "auto",
        missionItemId: Long? = null,
    ): GoalResult {
        val replayOnly = mode == "replay"
        val resolved = Resolver.resolve(db, goal)
        if (resolved == null) {
            if (!replayOnly && aiFallback != null) return aiFallback(goal)
            val startedAt = System.currentTimeMillis()
            val runId = db.startRun(goal)
            val message = if (replayOnly) {
                "No live pipeline for '$goal'. Run it once with AI first and approve the proposal."
            } else {
                "No live pipeline for '$goal' and no AI fallback configured."
            }
            db.finishRun(runId, false, message, System.currentTimeMillis() - startedAt)
            return GoalResult(if (replayOnly) "no_pipeline" else "no_skill", message, runId = runId)
        }
        return executePipeline(goal, resolved.first, resolved.second, missionItemId)
    }

    /**
     * Authoritative pipeline runner used by resolver hits and mission items.
     * It owns exactly one `runs` row and keeps the mission/pipeline/version
     * provenance columns in sync before any MaaFW task starts.
     */
    private suspend fun executePipeline(
        goal: String,
        pipeline: PipelineRow,
        score: Int,
        missionItemId: Long? = null,
        pipelineVersionId: Long? = null,
    ): GoalResult {
        val startedAt = System.currentTimeMillis()
        val runId = db.startRun(goal, path = "pipeline")
        db.exec(
            "UPDATE runs SET pipeline_id=?, pipeline_version_id=?, mission_item_id=? WHERE id=?",
            arrayOf<Any?>(pipeline.id, pipelineVersionId, missionItemId, runId),
        )
        val fileCountBefore = Verifier.fileCountBaseline(pipeline.postconditionJson)
        val compiled = Compiler.compile(db, pipeline)
        if (compiled.nodes.isEmpty()) {
            val message = "Pipeline '${pipeline.name}' has no runnable steps."
            db.finishRun(runId, false, message, System.currentTimeMillis() - startedAt)
            return GoalResult("compile_failed", message, runId = runId, pipelineId = pipeline.id)
        }

        val plan = planFor(compiled, pipeline.name)

        return try {
            when (val accepted = runner.start(plan)) {
                is RunnerCommandResult.Rejected -> {
                    val message = "Runner rejected: ${accepted.reason}"
                    db.finishRun(runId, false, message, System.currentTimeMillis() - startedAt)
                    GoalResult("rejected", message, runId = runId, pipelineId = pipeline.id)
                }
                RunnerCommandResult.Accepted -> {
                    val state = withTimeout(180_000) {
                        runner.state.first { !it.phase.isBusy && it.latestResult != null }
                    }
                    val result = state.latestResult
                    var success = result is ExecutionResult.Completed
                    val taskDetail = result?.taskResults
                        ?.filter { !it.success }
                        ?.joinToString("; ") { "${it.taskName}: ${it.message ?: "failed"}" }
                        .orEmpty()
                    var message = when (result) {
                        is ExecutionResult.Completed ->
                            "Pipeline '${pipeline.name}' (score $score) completed: ${compiled.nodes.size} node(s)."
                        is ExecutionResult.CompletedWithFailures ->
                            "Pipeline '${pipeline.name}' completed with failures${if (taskDetail.isNotBlank()) ": $taskDetail" else "."}"
                        is ExecutionResult.Cancelled -> "Pipeline '${pipeline.name}' cancelled."
                        is ExecutionResult.Failed ->
                            "Pipeline '${pipeline.name}' failed: ${result.reason}${if (taskDetail.isNotBlank()) " ($taskDetail)" else ""}"
                        null -> "Pipeline '${pipeline.name}' finished without a result."
                    }
                    var outcome = Verifier.Outcome(false, true, "{}")
                    if (success) {
                        outcome = verifyPostcondition(
                            runId = runId,
                            postconditionJson = pipeline.postconditionJson,
                            appId = pipeline.appId,
                            fileCountBefore = fileCountBefore,
                        )
                        if (outcome.applicable && !outcome.ok) {
                            success = false
                            message += " Postcondition failed."
                        } else if (outcome.applicable) {
                            message += " Postcondition passed."
                        }
                    }
                    db.finishRun(
                        runId, success, if (success) "" else message, System.currentTimeMillis() - startedAt,
                        verified = outcome.ok && outcome.applicable,
                        verifyJson = outcome.evidenceJson,
                        stateOverride = if (result is ExecutionResult.Cancelled) "cancelled" else null,
                    )
                    GoalResult(
                        if (result is ExecutionResult.Cancelled) "cancelled" else if (success) "done" else "failed",
                        message,
                        runId = runId,
                        pipelineId = pipeline.id,
                    )
                }
            }
        } catch (t: Throwable) {
            val message = "Brain run error: ${t.message ?: t.javaClass.simpleName}"
            db.finishRun(runId, false, message, System.currentTimeMillis() - startedAt)
            GoalResult("error", message, runId = runId, pipelineId = pipeline.id)
        }
    }

    /**
     * Run one mission item. A pipeline item executes the pinned version when
     * present, otherwise the pipeline's current live definition. A goal-only
     * item falls back to the same resolver/AI path as a normal goal run.
     */
    suspend fun runMissionItem(itemId: Long): GoalResult {
        val item = db.rows(
            "SELECT * FROM mission_items WHERE id=?",
            arrayOf<Any?>(itemId),
        ).firstOrNull() ?: return GoalResult("not_found", "Mission item #$itemId not found.")
        if (item.optInt("enabled", 1) == 0) {
            return GoalResult("disabled", "Mission item #$itemId is disabled.")
        }
        val pipelineId = item.opt("pipeline_id")
            ?.takeIf { it != JSONObject.NULL }?.toString()?.toLongOrNull()
        val versionId = item.opt("pipeline_version_id")
            ?.takeIf { it != JSONObject.NULL }?.toString()?.toLongOrNull()
        val goal = item.optString("goal").trim()
        if (pipelineId == null) {
            if (goal.isBlank()) return GoalResult("bad_item", "Mission item #$itemId has no goal.")
            val result = if (aiFallback != null) aiFallback.invoke(goal) else runGoal(goal)
            result.runId?.let { runId -> linkMissionRun(runId, itemId) }
            return result
        }
        val pipeline = loadPipelineForRun(pipelineId, versionId)
        if (pipeline == null) {
            val startedAt = System.currentTimeMillis()
            val runGoal = goal.ifBlank { "mission item #$itemId" }
            val runId = db.startRun(runGoal, path = "pipeline")
            linkMissionRun(runId, itemId)
            val message = "Mission item #$itemId references missing pipeline #$pipelineId" +
                if (versionId != null) " version #$versionId" else ""
            db.finishRun(runId, false, message, System.currentTimeMillis() - startedAt)
            return GoalResult("pipeline_missing", message, runId = runId, pipelineId = pipelineId)
        }
        return executePipeline(
            goal = goal.ifBlank { pipeline.goal },
            pipeline = pipeline,
            score = 100,
            missionItemId = itemId,
            pipelineVersionId = versionId,
        )
    }

    private fun loadPipelineForRun(pipelineId: Long, versionId: Long?): PipelineRow? {
        val row = db.rows("SELECT * FROM pipelines WHERE id=?", arrayOf<Any?>(pipelineId)).firstOrNull()
            ?: return null
        val definitionJson: String
        val entry: String
        val postconditionJson: String
        if (versionId != null) {
            val version = db.rows(
                "SELECT definition_json,entry,postcondition_json FROM pipeline_versions " +
                    "WHERE id=? AND pipeline_id=?",
                arrayOf<Any?>(versionId, pipelineId),
            ).firstOrNull() ?: return null
            definitionJson = version.optString("definition_json", "{}")
            entry = version.optString("entry", "")
            postconditionJson = version.optString("postcondition_json", "{}")
        } else {
            definitionJson = row.optString("definition_json", "{}")
            entry = row.optString("entry", "")
            postconditionJson = row.optString("postcondition_json", "{}")
        }
        return PipelineRow(
            id = pipelineId,
            appId = row.opt("app_id")?.takeIf { it != JSONObject.NULL }?.toString()?.toLongOrNull(),
            name = row.optString("name").ifBlank { "pipeline_$pipelineId" },
            goal = row.optString("goal"),
            aliases = parseAliases(row.optString("aliases")),
            definitionJson = definitionJson,
            autonomy = row.optString("autonomy", "confirm"),
            entry = entry,
            postconditionJson = postconditionJson,
        )
    }

    private fun linkMissionRun(runId: Long, missionItemId: Long) {
        db.exec(
            "UPDATE runs SET mission_item_id=? WHERE id=?",
            arrayOf<Any?>(missionItemId, runId),
        )
    }

    private fun planFor(compiled: Compiler.CompiledGraph, pipelineName: String): RunPlan = RunPlan(
        projectName = "maa-phone-brain",
        projectVersion = "0.1",
        controller = ControllerDefinition(),
        // Relative to the installed PI root; every plan must load at least one bundle
        // before pipeline overrides can be applied.
        resource = ResourceDefinition(name = "brain", paths = BrainResources.resourcePaths(context, db)),
        runConfigurationId = RunConfigurationId("brain-${UUID.randomUUID()}"),
        tasks = listOf(
            RuntimeTask(
                taskName = pipelineName.ifBlank { "brain_pipeline" },
                entry = compiled.entry,
                // Whole graph in one override object: MaaFW walks next/on_error
                // itself instead of us submitting one isolated task per node.
                pipelineOverrides = listOf(JsonObject(compiled.nodes)),
                label = pipelineName.ifBlank { "pipeline" },
            ),
        ),
    )

    /** Capture a fresh post-run frame through the privileged controller. */
    private suspend fun captureVerificationShot(runId: Long, attempt: Int): String? {
        val service = servicePort ?: return null
        val file = Verifier.screenshotFor(context, runId, attempt)
        val tools = AgentTools(context, db, runner, service)
        return if (tools.captureScreenshot(file.absolutePath, "brain_verify_${runId}_$attempt")) {
            file.absolutePath
        } else {
            null
        }
    }

    /**
     * Postconditions are evaluated against fresh runtime evidence, with retries
     * for short animations. `element` / `screen_text` use MaaFW's recognition
     * bridge, `file_count` uses the baseline captured before the run, and
     * `pixel` samples a fresh screenshot. Unknown types are reported as
     * skipped instead of false-passing.
     */
    private suspend fun verifyPostcondition(
        runId: Long,
        postconditionJson: String,
        appId: Long?,
        fileCountBefore: Int?,
    ): Verifier.Outcome {
        var last = Verifier.Outcome(
            true,
            false,
            JSONObject().put("error", "postcondition not evaluated").toString(),
        )
        for (attempt in 0 until 3) {
            val shot = captureVerificationShot(runId, attempt)
            val evidence = Verifier.Evidence(
                screenshotPath = shot,
                fileCountBefore = fileCountBefore,
                elementLocator = { name -> elementLocator(appId, name) },
                recognize = { type, params -> recognitionDirect(type, params) },
            )
            val outcome = Verifier.verify(evidence, postconditionJson)
            if (outcome.skipped || outcome.ok) return outcome
            last = outcome
            delay(700)
        }
        return last
    }

    private fun elementLocator(appId: Long?, name: String): JSONObject? {
        if (appId == null || name.isBlank()) return null
        return db.rows(
            "SELECT locator_json FROM elements WHERE app_id=? AND name=? AND status='live'",
            arrayOf<Any?>(appId, name),
        ).firstOrNull()?.optString("locator_json")
            ?.takeIf { it.isNotBlank() }
            ?.let { raw -> runCatching { JSONObject(raw) }.getOrNull() }
    }

    /**
     * Run one MaaFW recognition on the controller's cached frame. The caller
     * has just executed a Screencap node, so the cache is the same frame the
     * pixel verifier would sample.
     */
    private suspend fun recognitionDirect(type: String, params: JSONObject): JSONObject? {
        val service = servicePort ?: return null
        val raw = runCatching {
            service.useService { it.recognitionDirect(type, params.toString()) }
        }.getOrNull() ?: return null
        return runCatching { JSONObject(raw) }.getOrNull()
    }

    /**
     * Replay a pending proposal without promoting it. This is the Review
     * "Test replay" path: candidate definition + candidate template elements
     * are compiled with the same compiler the live pipeline uses, and a
     * candidate pixel postcondition is evaluated when present.
     */
    suspend fun testProposal(proposalId: Long): GoalResult {
        val row = db.rows("SELECT * FROM proposals WHERE id=?", arrayOf<Any?>(proposalId)).firstOrNull()
            ?: return GoalResult("not_found", "Proposal #$proposalId not found.")
        if (row.optString("status") != "pending") {
            return GoalResult("not_pending", "Proposal #$proposalId is ${row.optString("status")}.")
        }
        val candidate = runCatching { JSONObject(row.optString("candidate_json")) }.getOrNull()
            ?: return GoalResult("bad_candidate", "Proposal #$proposalId has invalid candidate JSON.")
        val pipeline = candidate.optJSONObject("pipeline")
            ?: return GoalResult("bad_candidate", "Proposal #$proposalId has no pipeline.")
        val candidateElements = candidate.optJSONObject("elements") ?: JSONObject()
        // The candidate wrapper is {pipeline:{graph:{...}}, elements:{...}};
        // migrate the graph/step definition, not the wrapper object.
        val rawDefinition = pipeline.optJSONObject("graph")?.toString()
            ?: pipeline.optJSONArray("steps")?.toString()
            ?: pipeline.toString()
        val native = PipelineGraph.migrateDefinition(rawDefinition, candidateElements)
            ?: return GoalResult("bad_candidate", "Proposal #$proposalId has no native graph.")
        val definitionJson = native.graph.toString()
        val pipelineId = row.opt("target_pipeline_id")?.takeIf { it != JSONObject.NULL }?.toString()?.toLongOrNull()
            ?: pipeline.opt("id")?.takeIf { it != JSONObject.NULL }?.toString()?.toLongOrNull()
        val versionId = row.opt("target_version_id")?.takeIf { it != JSONObject.NULL }?.toString()?.toLongOrNull()
            ?: pipeline.opt("version_id")?.takeIf { it != JSONObject.NULL }?.toString()?.toLongOrNull()
        val appId = pipeline.opt("app_id")?.takeIf { it != JSONObject.NULL }?.toString()?.toLongOrNull()
        val aliases = mutableListOf<String>()
        pipeline.optJSONArray("aliases")?.let { arr ->
            for (i in 0 until arr.length()) arr.optString(i).takeIf { it.isNotBlank() }?.let { aliases += it }
        }
        val candidateGoal = pipeline.optString("goal").ifBlank { "proposal $proposalId" }
        val postconditionJson = pipeline.optJSONObject("postcondition")?.toString() ?: "{}"
        val fileCountBefore = Verifier.fileCountBaseline(postconditionJson)
        val pipelineRow = PipelineRow(
            id = pipelineId ?: 0L,
            appId = appId,
            name = pipeline.optString("name").ifBlank { "candidate_$proposalId" },
            goal = candidateGoal,
            aliases = aliases,
            definitionJson = definitionJson,
            autonomy = "confirm",
            entry = native.entry,
            postconditionJson = postconditionJson,
        )
        val compiled = Compiler.compile(db, pipelineRow)
        if (compiled.nodes.isEmpty()) {
            return GoalResult("compile_failed", "Candidate #$proposalId has no runnable steps.")
        }

        val startedAt = System.currentTimeMillis()
        val runId = db.startRun(candidateGoal, path = "pipeline")
        if (pipelineId != null) {
            db.exec(
                "UPDATE runs SET pipeline_id=?, pipeline_version_id=? WHERE id=?",
                arrayOf<Any?>(pipelineId, versionId, runId),
            )
        }
        if (versionId != null) db.linkVersionRun(versionId, runId, "replay")

        return try {
            when (val accepted = runner.start(planFor(compiled, pipelineRow.name))) {
                is RunnerCommandResult.Rejected -> {
                    val message = "Candidate #$proposalId rejected by runner: ${accepted.reason}"
                    db.finishRun(runId, false, message, System.currentTimeMillis() - startedAt)
                    GoalResult("rejected", message, runId = runId, pipelineId = pipelineId)
                }
                RunnerCommandResult.Accepted -> {
                    val state = withTimeout(180_000) {
                        runner.state.first { !it.phase.isBusy && it.latestResult != null }
                    }
                    val result = state.latestResult
                    var success = result is ExecutionResult.Completed
                    val taskDetail = result?.taskResults
                        ?.filter { !it.success }
                        ?.joinToString("; ") { "${it.taskName}: ${it.message ?: "failed"}" }
                        .orEmpty()
                    var message = when (result) {
                        is ExecutionResult.Completed ->
                            "Candidate #$proposalId replay completed: ${compiled.nodes.size} node(s)."
                        is ExecutionResult.CompletedWithFailures ->
                            "Candidate #$proposalId replay completed with failures${if (taskDetail.isNotBlank()) ": $taskDetail" else "."}"
                        is ExecutionResult.Cancelled -> "Candidate #$proposalId replay cancelled."
                        is ExecutionResult.Failed ->
                            "Candidate #$proposalId replay failed: ${result.reason}${if (taskDetail.isNotBlank()) " ($taskDetail)" else ""}"
                        null -> "Candidate #$proposalId replay finished without a result."
                    }
                    var outcome = Verifier.Outcome(false, true, "{}")
                    if (success) {
                        outcome = verifyPostcondition(
                            runId = runId,
                            postconditionJson = postconditionJson,
                            appId = appId,
                            fileCountBefore = fileCountBefore,
                        )
                        when {
                            outcome.applicable && outcome.ok -> message += " Postcondition passed."
                            outcome.applicable -> {
                                success = false
                                message += " Postcondition failed."
                            }
                            else -> message += " No deterministic postcondition; human review required."
                        }
                    }
                    val verifyJson = JSONObject(outcome.evidenceJson)
                        .put("candidate_replay", success)
                        .put("deterministic_verified", outcome.ok && outcome.applicable)
                        .toString()
                    db.finishRun(
                        runId, success, if (success) "" else message, System.currentTimeMillis() - startedAt,
                        verified = outcome.ok && outcome.applicable,
                        verifyJson = verifyJson,
                        stateOverride = if (result is ExecutionResult.Cancelled) "cancelled" else null,
                    )
                    GoalResult(
                        if (result is ExecutionResult.Cancelled) "cancelled" else if (success) "done" else "failed",
                        message,
                        runId = runId,
                        pipelineId = pipelineId,
                    )
                }
            }
        } catch (t: Throwable) {
            val message = "Candidate #$proposalId replay error: ${t.message ?: t.javaClass.simpleName}"
            db.finishRun(runId, false, message, System.currentTimeMillis() - startedAt, verified = false)
            GoalResult("error", message, runId = runId, pipelineId = pipelineId)
        }
    }
}
