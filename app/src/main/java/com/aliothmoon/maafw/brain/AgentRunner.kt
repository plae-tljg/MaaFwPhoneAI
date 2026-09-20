package com.aliothmoon.maafw.brain

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * M3 AI fallback: screenshot -> DeepSeek vision -> one native action -> repeat.
 * On success the trajectory is independently vision-verified, recorded in
 * runs.steps_json and filed as a pipeline_new proposal for human Review.
 */
class AgentRunner(
    private val context: Context,
    private val db: BrainDb,
    private val tools: AgentTools,
    private val deepseek: DeepSeekClient,
) {

    /** A live `ask` action waiting for the user in the Assistant UI. */
    data class PendingQuestion(
        val runId: Long,
        val messageId: Long,
        val question: String,
        val options: List<String>,
        val allowFreeText: Boolean,
    )

    private val _pendingQuestion = MutableStateFlow<PendingQuestion?>(null)
    val pendingQuestion: StateFlow<PendingQuestion?> = _pendingQuestion.asStateFlow()

    private val answerLock = Any()

    @Volatile
    private var answerDeferred: CompletableDeferred<String>? = null

    fun configured(): Boolean = deepseek.configured()

    @Volatile
    private var cancelRequested = false

    fun requestCancel() {
        cancelRequested = true
        synchronized(answerLock) {
            answerDeferred?.complete(ANSWER_CANCEL)
            answerDeferred = null
        }
        _pendingQuestion.value = null
    }

    /** Called by the Assistant modal. The run loop resumes on its own coroutine. */
    fun submitAnswer(answer: String) {
        val value = answer.trim()
        if (value.isEmpty()) return
        synchronized(answerLock) {
            answerDeferred?.complete(value)
        }
    }

    suspend fun stopRunner(): Boolean = tools.stop()

    suspend fun run(
        goal: String,
        maxSteps: Int = 300,
        proposalKind: String = "pipeline_new",
        targetPipelineId: Long? = null,
        evidenceRunId: Long? = null,
        pipelineContext: String = "",
        targetPackage: String = "",
        targetAppId: Long? = null,
    ): BrainRunner.GoalResult {
        cancelRequested = false
        synchronized(answerLock) { answerDeferred = null }
        _pendingQuestion.value = null
        deepseek.drainUsageTokens()
        if (!configured()) {
            return BrainRunner.GoalResult("no_key", "No DeepSeek API key configured.")
        }
        val startedAt = System.currentTimeMillis()
        db.seedHints()
        val runId = db.startRun(goal, path = "bootstrap")
        val loadedSkills = SkillLoader.load(context, goal)
        Log.i("BrainSkills", loadedSkills.summary())
        db.addMessage(runId, "assistant", "status", loadedSkills.summary())
        if (pipelineContext.isNotBlank()) {
            db.addMessage(
                runId, "assistant", "status",
                "maintenance mode: replay failed; adapting pipeline #${targetPipelineId ?: "?"} from evidence #${evidenceRunId ?: "?"}",
            )
        }
        val shotDir = File(context.getExternalFilesDir("brain"), "shots").apply { mkdirs() }
        val addedApps = runCatching { db.syncInstalledApps() }.getOrDefault(0)
        if (addedApps > 0) {
            db.addMessage(
                runId, "assistant", "status",
                "app catalog synced: $addedApps launchable app(s) indexed",
            )
        }
        val trajectory = mutableListOf<JSONObject>()
        val history = mutableListOf<String>()
        val knownApps = db.rows("SELECT name,package_name FROM apps WHERE active=1")
            .map { "${it.optString("name")} -> ${it.optString("package_name")}" } +
            db.rows("SELECT key,value FROM settings WHERE key LIKE 'hint:%'")
                .map { "${it.optString("key")}: ${it.optString("value")}" }
        var appId: Long? = targetAppId
        val searchQuery = extractSearchQuery(goal)
        var textTyped = false
        var homeTaps = 0
        val repeatPoints = mutableListOf<Pair<Int, Int>>()
        val toggleGoal = Regex("(?i)(like|follow|点赞|关注|收藏|toggle|enable|disable|开启|关闭)")
            .containsMatchIn(goal)
        var toggleTapPoint: Pair<Int, Int>? = null
        var toggleBeforeShot: String? = null
        var lastFinalShot: String? = null
        var authoredProposalId: Long? = null
        var success = false
        var error = ""
        var steps = 0
        val configuredMaxSteps = db.setting("agent_max_steps", "300")
            .toIntOrNull()?.coerceIn(5, 1000) ?: maxSteps
        val earlyExitLimit = db.setting("agent_repeat_limit", "5")
            .toIntOrNull()?.coerceIn(2, 10) ?: 5
        var lastActionSignature = ""
        var repeatedActionStreak = 0
        var noProgressStreak = 0
        var lastObservedFrame = ""
        var lastObservation: JSONObject? = null
        var lastRecognitionObservation = ""
        db.addMessage(
            runId, "assistant", "status",
            "bootstrap budget: max_steps=$configuredMaxSteps, early_exit_streak=$earlyExitLimit",
        )
        if (pipelineContext.isNotBlank() && targetPackage.isNotBlank()) {
            val launchAction = JSONObject().put("action", "launch").put("package", targetPackage)
            val launchResult = runCatching {
                tools.execute(launchAction, 1.0, "brain_maintenance_launch")
            }.getOrElse { "launch failed: ${it.message}" }
            trajectory.add(
                JSONObject()
                    .put("action", "launch")
                    .put("package", targetPackage)
                    .put("result", launchResult),
            )
            db.setRunSteps(runId, JSONArray(trajectory).toString())
            history.add("launch -> $launchResult")
            appId = db.ensureApp(targetPackage)
            db.addMessage(
                runId, "assistant", "status",
                "maintenance state reset: launch $targetPackage -> $launchResult",
            )
            delay(2500)
        }

        for (step in 0 until configuredMaxSteps) {
            steps = step + 1
            if (cancelRequested) {
                error = "cancelled by user"
                break
            }
            val shot = File(shotDir, "run${runId}_turn$step.png")
            if (!tools.captureScreenshot(shot.absolutePath, "brain_capture_$step")) {
                error = "screenshot capture failed"
                break
            }
            val image = ImageTools.encode(shot.absolutePath)
            if (image == null) {
                error = "screenshot decode failed"
                break
            }
            val frameKey = ImageTools.frameKey(shot.absolutePath)
            if (frameKey != lastObservedFrame) {
                lastObservedFrame = frameKey
                lastObservation = runCatching { deepseek.observeScreen(shot.absolutePath) }.getOrNull()
                lastObservation?.optString("screen")?.take(240)?.takeIf { it.isNotBlank() }?.let { summary ->
                    db.addMessage(runId, "assistant", "status", "screen: $summary")
                }
                lastRecognitionObservation = runCatching {
                    tools.recognition("OCR", "{}")?.toString().orEmpty()
                }.getOrNull().orEmpty()
                if (lastRecognitionObservation.isNotBlank()) {
                    db.addMessage(
                        runId, "assistant", "status",
                        "native OCR recognition: ${lastRecognitionObservation.length} chars",
                    )
                }
            }
            val runtimeGuidance = buildString {
                append(loadedSkills.guidance)
                if (pipelineContext.isNotBlank()) {
                    append("\n\nExisting pipeline under maintenance (its UI may have changed):\n")
                    append(pipelineContext.take(12_000))
                    append("\nAdapt to the current screen rather than following it blindly; ")
                    append("the successful trajectory from this run will become a new candidate version.")
                }
            }
            val modelResult = runCatching {
                deepseek.nextAction(
                    goal = goal,
                    imagePath = shot.absolutePath,
                    history = history,
                    knownApps = knownApps,
                    skillGuidance = runtimeGuidance,
                    observation = buildString {
                        lastObservation?.let { append(it.toString()) }
                        if (lastRecognitionObservation.isNotBlank()) {
                            append("\nOCR recognition: ")
                            append(lastRecognitionObservation.take(5000))
                        }
                    },
                )
            }
            val action = modelResult.getOrNull()
            if (action == null) {
                noProgressStreak++
                val detail = modelResult.exceptionOrNull()?.message?.takeIf { it.isNotBlank() }
                    ?: "model returned no usable action"
                trajectory.add(
                    JSONObject()
                        .put("action", "none")
                        .put("result", "empty model response")
                        .put("detail", detail)
                        .put("screenshot", shot.absolutePath),
                )
                db.setRunSteps(runId, JSONArray(trajectory).toString())
                history.add("none -> empty model response ($detail)")
                db.addMessage(
                    runId, "assistant", "status",
                    "empty model response ($noProgressStreak/$earlyExitLimit): $detail",
                )
                if (noProgressStreak >= earlyExitLimit) {
                    error = "early exit: model returned no usable action $noProgressStreak time(s) ($detail)"
                    break
                }
                delay(1000)
                continue
            }
            if (action.optString("action") == "key" &&
                action.optString("value").equals("home", ignoreCase = true)
            ) {
                homeTaps++
                if (homeTaps >= 3) {
                    error = "HOME on the virtual display is not rendering; launch the target app package instead"
                    break
                }
            }
            when (action.optString("action")) {
                "done" -> {
                    success = true
                    break
                }
                "fail" -> {
                    error = action.optString("reason", "agent failed")
                    break
                }
                "ask" -> {
                    val question = action.optString("question").trim()
                        .ifBlank { "I need your input to continue with this goal." }
                    val optionList = mutableListOf<String>()
                    action.optJSONArray("options")?.let { arr ->
                        for (i in 0 until arr.length()) {
                            arr.optString(i).trim().takeIf { it.isNotBlank() }?.let { optionList += it }
                        }
                    }
                    val allowFreeText = if (action.has("allow_free_text")) {
                        action.optBoolean("allow_free_text", true)
                    } else {
                        optionList.isEmpty()
                    }
                    val questionMessageId = db.addMessage(
                        runId,
                        "assistant",
                        "question",
                        question,
                        JSONObject()
                            .put("options", JSONArray(optionList))
                            .put("allow_free_text", allowFreeText)
                            .toString(),
                    )
                    db.setRunState(runId, "needs_input")
                    db.addMessage(runId, "assistant", "status", "waiting for user input: $question")
                    val deferred = CompletableDeferred<String>()
                    synchronized(answerLock) { answerDeferred = deferred }
                    _pendingQuestion.value = PendingQuestion(
                        runId = runId,
                        messageId = questionMessageId,
                        question = question,
                        options = optionList,
                        allowFreeText = allowFreeText,
                    )
                    val answer = withTimeoutOrNull(QUESTION_TIMEOUT_MS) {
                        if (cancelRequested) ANSWER_CANCEL else deferred.await()
                    } ?: ANSWER_TIMEOUT
                    synchronized(answerLock) {
                        if (answerDeferred === deferred) answerDeferred = null
                    }
                    _pendingQuestion.value = null
                    db.setRunState(runId, "running")
                    when {
                        cancelRequested || answer == ANSWER_CANCEL -> {
                            db.updateMessageState(questionMessageId, "dismissed")
                            error = "cancelled by user"
                            success = false
                            break
                        }
                        answer == ANSWER_TIMEOUT -> {
                            db.updateMessageState(questionMessageId, "expired")
                            error = "timed out waiting for user input"
                            success = false
                            break
                        }
                    }
                    db.updateMessageState(questionMessageId, "answered", answer)
                    db.addMessage(
                        runId,
                        "user",
                        "answer",
                        answer,
                        JSONObject().put("question_message_id", questionMessageId).toString(),
                    )
                    trajectory.add(
                        JSONObject()
                            .put("action", "answer")
                            .put("value", answer)
                            .put("question", question)
                            .put("question_message_id", questionMessageId),
                    )
                    db.setRunSteps(runId, JSONArray(trajectory).toString())
                    history.add("user answer -> $answer")
                    db.addMessage(runId, "assistant", "status", "user answered: $answer")
                    delay(300)
                    continue
                }
                "propose_pipeline" -> {
                    val graph = action.optJSONObject("pipeline") ?: action.optJSONObject("graph")
                    if (graph == null || graph.length() == 0) {
                        error = "propose_pipeline was called without a graph object"
                        break
                    }
                    val validation = PipelineGraph.validate(graph)
                    if (validation != null) {
                        db.addMessage(
                            runId, "assistant", "status",
                            "propose_pipeline invalid: $validation; use flat MaaFW fields and retry",
                        )
                        history.add("propose_pipeline -> invalid: $validation")
                        delay(800)
                        continue
                    }
                    val entry = action.optString("entry")
                    val post = action.optJSONObject("postcondition") ?: JSONObject()
                    authoredProposalId = Learner.proposeNativeGraph(
                        db = db,
                        context = context,
                        runId = runId,
                        goal = goal,
                        appId = appId,
                        graph = graph,
                        entry = entry,
                        postcondition = post,
                        proposalKind = if (proposalKind == "pipeline_fix") "pipeline_fix" else "pipeline_new",
                        targetPipelineId = targetPipelineId,
                        evidenceRunId = evidenceRunId,
                    )
                    if (authoredProposalId == null) {
                        error = "propose_pipeline produced an empty/invalid graph"
                    } else {
                        success = true
                        trajectory.add(
                            JSONObject()
                                .put("action", "propose_pipeline")
                                .put("nodes", graph.length())
                                .put("entry", entry)
                                .put("proposal_id", authoredProposalId)
                                .put("screenshot", shot.absolutePath),
                        )
                        db.setRunSteps(runId, JSONArray(trajectory).toString())
                    }
                    break
                }
            }
            val kind = action.optString("action")
            val actionContext = listOf(
                action.optString("description"),
                action.optString("thought"),
                action.optString("value"),
            ).joinToString(" ")
            val isToggleAction = toggleGoal &&
                Regex("(?i)(like|follow|点赞|关注|收藏|thumbs|heart)").containsMatchIn(actionContext)
            var result: String
            var rawPointForEntry: JSONArray? = null
            if (kind == "locate") {
                val description = action.optString("description")
                if (isToggleAction && toggleTapPoint != null) {
                    // Never toggle twice: the first tap may already have changed the
                    // state. Finish and let the final verifier decide.
                    success = true
                    result = "toggle already tapped once; verifying state instead of tapping again"
                } else {
                    val located = runCatching {
                        deepseek.locate(shot.absolutePath, description)
                    }.getOrNull()
                    if (located == null) {
                        result = "locate failed"
                    } else {
                        val rawX = (located.first * image.scaleFactor).toInt()
                        val rawY = (located.second * image.scaleFactor).toInt()
                        rawPointForEntry = JSONArray(listOf(rawX, rawY))
                        if (isToggleAction) {
                            val alreadyActive = isToggleActive(
                                shot.absolutePath, rawX, rawY,
                                actionContext.ifBlank { description },
                            )
                            if (alreadyActive) {
                                result = "toggle already active; not tapping ($rawX,$rawY)"
                                success = true
                            } else {
                                val ok = tools.tapRaw(rawX, rawY, "brain_locate_${runId}_$step")
                                if (ok) {
                                    val changedPoint = toggleChangedAfterTap(
                                        shotDir, runId, step, shot.absolutePath, rawX, rawY,
                                    )
                                    result = if (changedPoint != null) {
                                        "located and tapped ($rawX,$rawY; toggle state changed at " +
                                            "${changedPoint.first},${changedPoint.second})"
                                    } else {
                                        "located and tapped ($rawX,$rawY) but toggle state did not visibly change"
                                    }
                                    if (changedPoint != null) {
                                        toggleTapPoint = changedPoint
                                        toggleBeforeShot = shot.absolutePath
                                    }
                                } else {
                                    result = "locate tap failed"
                                }
                            }
                        } else {
                            val ok = tools.tapRaw(rawX, rawY, "brain_locate_${runId}_$step")
                            result = if (ok) "located and tapped ($rawX,$rawY)" else "locate tap failed"
                        }
                    }
                }
            } else if (kind == "tap" && isToggleAction) {
                val rawTap = rawPoint(action, image.scaleFactor, "point")
                val repeatToggle = rawTap != null && toggleTapPoint?.let { previous ->
                    abs(previous.first - rawTap.optInt(0)) <= 80 &&
                        abs(previous.second - rawTap.optInt(1)) <= 80
                } == true
                if (repeatToggle) {
                    success = true
                    result = "toggle already tapped once; verifying state instead of tapping again"
                    rawPointForEntry = rawTap
                } else if (rawTap != null) {
                    val alreadyActive = isToggleActive(
                        shot.absolutePath, rawTap.optInt(0), rawTap.optInt(1), actionContext,
                    )
                    if (alreadyActive) {
                        success = true
                        result = "toggle already active; not tapping (${rawTap.optInt(0)},${rawTap.optInt(1)})"
                        rawPointForEntry = rawTap
                    } else {
                        result = runCatching {
                            tools.execute(action, image.scaleFactor, "brain_action_${runId}_$step")
                        }.getOrElse {
                            error = "action error: ${it.message}"
                            "error: ${it.message}"
                        }
                        if (result.startsWith("tapped")) {
                            val changedPoint = toggleChangedAfterTap(
                                shotDir, runId, step, shot.absolutePath,
                                rawTap.optInt(0), rawTap.optInt(1),
                            )
                            if (changedPoint != null) {
                                toggleTapPoint = changedPoint
                                toggleBeforeShot = shot.absolutePath
                            } else {
                                result += " (no visible toggle change)"
                            }
                        }
                    }
                } else {
                    result = runCatching {
                        tools.execute(action, image.scaleFactor, "brain_action_${runId}_$step")
                    }.getOrElse {
                        error = "action error: ${it.message}"
                        "error: ${it.message}"
                    }
                }
            } else {
                result = runCatching {
                    tools.execute(action, image.scaleFactor, "brain_action_${runId}_$step")
                }.getOrElse {
                    error = "action error: ${it.message}"
                    "error: ${it.message}"
                }
                if (kind == "text" && result.startsWith("typed")) textTyped = true
            }
            if (kind == "tap" && rawPointForEntry == null) {
                rawPointForEntry = rawPoint(action, image.scaleFactor, "point")
            }
            // Early-exit guard: a complex goal gets a large step budget, but
            // repeated identical actions / empty or failed results should not
            // burn hundreds of expensive model turns.
            val signature = actionSignature(action)
            repeatedActionStreak =
                if (signature.isNotBlank() && signature == lastActionSignature) repeatedActionStreak + 1 else 1
            lastActionSignature = signature
            if (resultLooksNoProgress(result)) {
                noProgressStreak++
            } else {
                noProgressStreak = 0
                if (error.startsWith("action error:")) error = ""
            }
            var autoRecovered = false
            rawPointForEntry?.let { point ->
                val x = point.optInt(0)
                val y = point.optInt(1)
                repeatPoints.add(x to y)
                val recent = repeatPoints.takeLast(3)
                if (recent.size == 3 && recent.all { abs(it.first - x) <= 30 && abs(it.second - y) <= 30 }) {
                    if (searchQuery != null && !textTyped) {
                        // The model repeatedly tapped a field without typing. For a
                        // search goal, use the query from the goal once; this is a
                        // generic bootstrap recovery, not an app-specific shortcut.
                        val autoText = JSONObject().put("action", "text").put("value", searchQuery)
                        val autoResult = runCatching {
                            tools.execute(autoText, image.scaleFactor, "brain_auto_text_${runId}_$step")
                        }.getOrElse { "auto text error: ${it.message}" }
                        textTyped = autoResult.startsWith("typed")
                        repeatPoints.clear()
                        trajectory.add(
                            JSONObject()
                                .put("action", "text")
                                .put("result", autoResult)
                                .put("value", searchQuery)
                                .put("auto_recovery", true)
                                .put("screenshot", shot.absolutePath),
                        )
                        db.setRunSteps(runId, JSONArray(trajectory).toString())
                        history.add("text -> $autoResult (auto recovery)")
                        db.addMessage(
                            runId, "assistant", "status",
                            "auto recovery: repeated field taps -> text '$searchQuery'",
                        )
                        autoRecovered = true
                    } else {
                        error = "repeat loop detected: tapped ($x,$y) repeatedly without progress"
                        return@let
                    }
                }
            }
            if (error.startsWith("repeat loop")) break
            if (autoRecovered) {
                delay(1200)
                continue
            }
            val entry = JSONObject().put("action", if (kind == "locate") "tap" else kind).put("result", result)
            when (kind) {
                "launch" -> {
                    val pkg = action.optString("package")
                    entry.put("package", pkg)
                    if (pkg.isNotBlank()) appId = db.ensureApp(pkg)
                }
                "tap" -> rawPoint(action, image.scaleFactor, "point")?.let { entry.put("point", it) }
                "locate" -> rawPointForEntry?.let {
                    entry.put("point", it)
                    entry.put("located", true)
                }
                "swipe" -> {
                    rawPoint(action, image.scaleFactor, "from")?.let { entry.put("from", it) }
                    rawPoint(action, image.scaleFactor, "to")?.let { entry.put("to", it) }
                    entry.put("duration", action.optInt("duration", 400))
                }
                "wait" -> entry.put("seconds", action.optDouble("seconds", 1.5))
                else -> entry.put("value", action.optString("value"))
            }
            if ((kind == "tap" || kind == "locate") && rawPointForEntry != null) {
                entry.put("templateSource", shot.absolutePath)
                entry.put("templatePoint", rawPointForEntry)
            }
            entry.put("screenshot", shot.absolutePath)
            lastObservation?.optString("screen")?.take(240)?.takeIf { it.isNotBlank() }?.let {
                entry.put("screen", it)
            }
            trajectory.add(entry)
            db.setRunSteps(runId, JSONArray(trajectory).toString())
            history.add("${action.optString("action")} -> $result")
            val thought = action.optString("thought").take(300)
            val statusText = "AI step: ${action.optString("action")} -> $result" +
                if (thought.isBlank()) "" else " | thought: $thought"
            db.addMessage(runId, "assistant", "status", statusText)
            if (!success) {
                if (noProgressStreak >= earlyExitLimit) {
                    error = "early exit: $noProgressStreak consecutive action(s) returned no usable result " +
                        "(last result: $result)"
                    break
                }
                if (repeatedActionStreak >= earlyExitLimit) {
                    error = "early exit: repeated action '$kind' $repeatedActionStreak times without progress " +
                        "(${signature.take(160)})"
                    break
                }
            }
            delay(1200)
            if (success) break
        }

        if (success && authoredProposalId == null) {
            // First-time AI is necessarily model-verified (there is no
            // consolidated pipeline yet). UI state changes can lag or animate,
            // so capture fresh screenshots and retry the verifier instead of
            // judging one frame.
            delay(700)
            var verified = false
            var verifyReason = ""
            for (attempt in 0 until 3) {
                val finalShot = File(
                    shotDir,
                    if (attempt == 0) "run${runId}_final.png" else "run${runId}_final_try$attempt.png",
                )
                if (!tools.captureScreenshot(finalShot.absolutePath, "brain_capture_final_$attempt")) {
                    verifyReason = "final screenshot failed"
                    continue
                }
                lastFinalShot = finalShot.absolutePath
                // Deterministic local toggle check first: compare the region around
                // the first toggle tap before/after. A colour/icon change is stronger
                // evidence than one LLM verdict (ADR-025).
                val toggleBefore = toggleBeforeShot
                val togglePoint = toggleTapPoint
                if (toggleBefore != null && togglePoint != null) {
                    val beforeColor = runCatching {
                        ImageTools.regionColorfulness(
                            toggleBefore, togglePoint.first, togglePoint.second, half = 12,
                        )
                    }.getOrNull() ?: 0.0
                    val afterColor = runCatching {
                        ImageTools.regionColorfulness(
                            finalShot.absolutePath, togglePoint.first, togglePoint.second, half = 12,
                        )
                    }.getOrNull() ?: 0.0
                    if (afterColor - beforeColor >= 15.0 || afterColor >= 40.0) {
                        verified = true
                        verifyReason = "local toggle colourfulness delta " +
                            "${"%.1f".format(afterColor - beforeColor)} (after ${"%.1f".format(afterColor)})"
                        break
                    }
                }
                val (ok, reason) = runCatching { deepseek.verify(goal, finalShot.absolutePath) }
                    .getOrElse { false to (it.message ?: "verify failed") }
                verifyReason = reason
                if (ok) {
                    verified = true
                    break
                }
                delay(1000)
            }
            if (!verified) {
                success = false
                error = "vision verify failed after retries: $verifyReason"
            }
        }

        if (cancelRequested) {
            success = false
            if (error.isBlank()) error = "cancelled by user"
        }
        if (success && authoredProposalId == null && toggleGoal && toggleTapPoint == null) {
            db.addMessage(
                runId, "assistant", "status",
                "warning: toggle goal verified without a recorded toggle tap (screen may already have been in the goal state)",
            )
        }
        val learnerPostcondition = if (success && authoredProposalId == null && toggleGoal && toggleTapPoint != null) {
            val point = toggleTapPoint
            val mean = Verifier.meanColor(lastFinalShot ?: "", point.first, point.second, 10)
            if (mean != null) {
                JSONObject()
                    .put("type", "pixel")
                    .put("point", JSONArray(listOf(point.first, point.second)))
                    .put("rgb", JSONArray(listOf(mean[0], mean[1], mean[2])))
                    .put("tolerance", 60)
                    .put("radius", 10)
                    .put("timeout", 6)
            } else {
                JSONObject()
            }
        } else {
            JSONObject()
        }
        val duration = System.currentTimeMillis() - startedAt
        db.setRunSteps(runId, JSONArray(trajectory).toString())
        val verifyJson = if (authoredProposalId != null) {
            JSONObject()
                .put("authored", true)
                .put("proposal_id", authoredProposalId)
                .put("vision_verified", false)
                .put("note", "AI-authored graph; Review Test replay is required")
                .toString()
        } else {
            JSONObject()
                .put("vision_verified", success)
                .put("postcondition", learnerPostcondition)
                .put("error", error)
                .toString()
        }
        db.finishRun(
            runId, success, error.ifBlank { if (success) "" else "max steps reached" }, duration,
            verified = success && authoredProposalId == null,
            verifyJson = verifyJson,
            stateOverride = if (cancelRequested) "cancelled" else null,
            aiCost = deepseek.drainUsageTokens(),
        )
        val proposalId = if (success && authoredProposalId == null) {
            Learner.propose(
                db = db,
                context = context,
                runId = runId,
                goal = goal,
                appId = appId,
                trajectory = trajectory,
                proposalKind = proposalKind,
                targetPipelineId = targetPipelineId,
                evidenceRunId = evidenceRunId,
                postcondition = learnerPostcondition,
            )
        } else {
            null
        }
        val message = when {
            authoredProposalId != null ->
                "AI authored native graph proposal #$authoredProposalId; test replay and Review before approval"
            success -> if (proposalKind == "pipeline_fix") {
                "AI adapted '$goal' in $steps step(s); pipeline fix proposal #$proposalId ready to review"
            } else {
                "AI solved '$goal' in $steps step(s)" + (proposalId?.let { "; proposal #$it ready to review" } ?: "")
            }
            else -> "AI fallback failed: ${error.ifBlank { "unknown error" }}"
        }
        db.addMessage(runId, "assistant", if (success) "result" else "result", message)
        return BrainRunner.GoalResult(
            if (authoredProposalId != null) "proposed" else if (success) "done" else "failed",
            message,
        )
    }

    private fun extractSearchQuery(goal: String): String? {
        val patterns = listOf(
            Regex("(?i)search\\s+for\\s+(.+?)(?:,\\s*|;|$)"),
            Regex("(?i)search\\s*[\"“]?(.+?)[\"”]?(?:[,，;；]|$)"),
            Regex("搜索(?:一下)?[：:\\s]*[\"“]?(.+?)[\"”]?(?:[,，;；]|$)"),
        )
        for (pattern in patterns) {
            val value = pattern.find(goal)?.groupValues?.getOrNull(1)?.trim()
            if (!value.isNullOrBlank()) return value
        }
        return null
    }

    /**
     * A vision model alone must not veto a toggle tap: on gray/empty UI it
     * can hallucinate an active control. Require the model to say "active"
     * *and* the local crop to have some colour/saturation.
     */
    private fun isToggleActive(screenshotPath: String, x: Int, y: Int, description: String): Boolean {
        val modelSaysActive = runCatching {
            deepseek.inspectToggle(screenshotPath, x, y, description)
        }.getOrNull() == true
        if (!modelSaysActive) return false
        val colorfulness = ImageTools.regionColorfulness(screenshotPath, x, y, half = 12) ?: return false
        return colorfulness >= 12.0
    }

    /**
     * A toggle tap only counts as "already toggled" if the tapped region
     * actually changed in a fresh frame. A missed click must not suppress a
     * later correct tap.
     */
    private suspend fun toggleChangedAfterTap(
        shotDir: File,
        runId: Long,
        step: Int,
        beforePath: String,
        x: Int,
        y: Int,
    ): Pair<Int, Int>? {
        delay(900)
        val after = File(shotDir, "run${runId}_toggle$step.png")
        if (!tools.captureScreenshot(after.absolutePath, "brain_toggle_check_${runId}_$step")) return null
        val block = runCatching {
            ImageTools.mostColorfulBlock(after.absolutePath, x, y, searchRadius = 60, block = 24)
        }.getOrNull() ?: return null
        val beforeColor = runCatching {
            ImageTools.regionColorfulness(beforePath, block.x, block.y, half = 12)
        }.getOrNull() ?: 0.0
        val afterColor = runCatching {
            ImageTools.regionColorfulness(after.absolutePath, block.x, block.y, half = 12)
        }.getOrNull() ?: 0.0
        if (afterColor - beforeColor < 15.0 && afterColor < 30.0) return null
        return block.x to block.y
    }

    /** Target-sensitive identity of an action for the repeat guard. */
    private fun actionSignature(action: JSONObject): String {
        val kind = action.optString("action")
        val detail = when (kind) {
            "launch" -> action.optString("package")
            "tap" -> action.opt("point")?.toString().orEmpty()
            "locate" -> action.optString("description")
            "text" -> action.optString("value")
            "key" -> action.optString("value")
            "swipe" -> "${action.opt("from")}->${action.opt("to")}"
            else -> action.optString("value")
        }
        return if (detail.isBlank()) kind else "$kind|$detail"
    }

    /** A blank/engine-level failure result should count as no progress. */
    private fun resultLooksNoProgress(result: String): Boolean {
        if (result.isBlank()) return true
        val value = result.lowercase()
        return value.startsWith("unknown") ||
            value.contains("failed") ||
            value.startsWith("error:") ||
            value.startsWith("tap skipped") ||
            value.startsWith("swipe skipped")
    }

    private fun rawPoint(action: JSONObject, scale: Double, key: String): JSONArray? {
        val arr = action.optJSONArray(key) ?: return null
        if (arr.length() < 2) return null
        return JSONArray(
            listOf(
                (arr.optDouble(0) * scale).toInt(),
                (arr.optDouble(1) * scale).toInt(),
            )
        )
    }

    private companion object {
        const val QUESTION_TIMEOUT_MS = 10 * 60 * 1000L
        const val ANSWER_CANCEL = "__cancel__"
        const val ANSWER_TIMEOUT = "__timeout__"
    }
}
