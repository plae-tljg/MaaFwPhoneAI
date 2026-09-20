package com.aliothmoon.maafw.brain

import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.aliothmoon.maafw.privileged.PermissionGateway
import com.aliothmoon.maafw.privileged.PrivilegedServicePort
import com.aliothmoon.maafw.privileged.WatchdogState
import com.aliothmoon.maafw.runner.PreviewPort
import com.aliothmoon.maafw.runner.RunnerPort
import com.aliothmoon.maafw.runner.isBusy
import com.aliothmoon.maafw.session.PreviewTouchAction
import com.aliothmoon.maafw.settings.AppSettingsManager
import com.aliothmoon.maafw.theme.MaaFwTheme
import com.aliothmoon.maafw.ui.components.MaaButton
import com.aliothmoon.maafw.ui.components.MaaOutlinedButton
import com.aliothmoon.maafw.ui.tasks.FullscreenPreview
import com.aliothmoon.maafw.ui.tasks.LivePreview
import com.aliothmoon.maafw.ui.tasks.rememberMovablePreview
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.koin.core.context.GlobalContext
import java.io.File

class AssistantActivity : ComponentActivity() {

    private lateinit var db: BrainDb
    private lateinit var brain: BrainRunner
    private lateinit var deepseek: DeepSeekClient
    private lateinit var previewPort: PreviewPort
    private lateinit var runnerPort: RunnerPort
    private lateinit var agent: AgentRunner
    private var pendingGoal by mutableStateOf<String?>(null)
    private var pendingMaintain by mutableStateOf(false)

    /** Queue work outlives individual Composables; Activity destruction cancels it. */
    private val brainScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var queueJob: Job? = null

    @Volatile
    private var queuePaused = false

    @Volatile
    private var queueCancelled = false

    fun startMissionQueue(missionId: Long) {
        if (queueJob?.isActive == true) return
        queuePaused = false
        queueCancelled = false
        runCatching { db.clearMissionSchedule(missionId) }
        queueJob = brainScope.launch {
            runCatching {
                db.enqueueMission(missionId)
                brain.runMissionQueue(
                    missionId = missionId,
                    isPaused = { queuePaused },
                    isCancelled = { queueCancelled },
                )
            }
        }
    }

    fun pauseMissionQueue() {
        queuePaused = true
    }

    fun resumeMissionQueue() {
        queuePaused = false
    }

    fun cancelMissionQueue() {
        queueCancelled = true
        brainScope.launch { runCatching { runnerPort.stop() } }
    }

    fun scheduleMissionQueue(missionId: Long, delayMs: Long) {
        if (delayMs <= 0) return
        queueCancelled = false
        brainScope.launch {
            delay(delayMs)
            if (!queueCancelled) startMissionQueue(missionId)
        }
    }

    /**
     * In-app schedule recovery: the existing Android Schedule stack owns
     * project runs; mission queues are rescheduled when the Assistant opens
     * and run while its process stays alive.
     */
    private fun restoreMissionSchedules() {
        brainScope.launch {
            runCatching {
                val now = System.currentTimeMillis()
                for (row in db.scheduledMissions()) {
                    val schedule = runCatching { JSONObject(row.optString("schedule_json")) }.getOrNull()
                        ?: continue
                    val runAt = schedule.optLong("run_at", 0L)
                    if (runAt <= 0L) continue
                    val delayMs = runAt - now
                    if (delayMs <= 0L) startMissionQueue(row.getLong("id"))
                    else scheduleMissionQueue(row.getLong("id"), delayMs)
                }
            }
        }
    }

    override fun onDestroy() {
        queueJob?.cancel()
        brainScope.cancel()
        super.onDestroy()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = BrainDb(this)
        runnerPort = GlobalContext.get().get<RunnerPort>()
        val servicePort = GlobalContext.get().get<PrivilegedServicePort>()
        val permissionGateway = GlobalContext.get().get<PermissionGateway>()
        previewPort = GlobalContext.get().get<PreviewPort>()
        deepseek = DeepSeekClient(db)
        agent = AgentRunner(this, db, AgentTools(this, db, runnerPort, servicePort), deepseek)
        brain = BrainRunner(this, db, runnerPort, servicePort) { goal -> agent.run(goal) }
        pendingGoal = intent?.getStringExtra(EXTRA_GOAL)
        pendingMaintain = intent?.getBooleanExtra(EXTRA_MAINTAIN, false) ?: false
        restoreMissionSchedules()
        setContent {
            MaaFwTheme {
                AssistantApp(
                    db = db,
                    brain = brain,
                    agent = agent,
                    deepseek = deepseek,
                    previewPort = previewPort,
                    runnerPort = runnerPort,
                    permissionGateway = permissionGateway,
                    pendingGoal = pendingGoal,
                    pendingMaintain = pendingMaintain,
                    onGoalConsumed = { pendingGoal = null; pendingMaintain = false },
                    onStop = {
                        agent.requestCancel()
                        runnerPort.stop()
                    },
                    onStartQueue = { missionId -> startMissionQueue(missionId) },
                    onPauseQueue = { pauseMissionQueue() },
                    onResumeQueue = { resumeMissionQueue() },
                    onCancelQueue = { cancelMissionQueue() },
                    onScheduleQueue = { missionId, delayMs ->
                        scheduleMissionQueue(missionId, delayMs)
                    },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingGoal = intent.getStringExtra(EXTRA_GOAL)
        pendingMaintain = intent.getBooleanExtra(EXTRA_MAINTAIN, false)
    }

    companion object {
        const val EXTRA_GOAL = "goal"
        const val EXTRA_MAINTAIN = "maintain"
    }
}

private enum class AssistantTab(val label: String) {
    ASSISTANT("Run"),
    LOGS("Chat"),
    RUNS("Runs"),
    MISSIONS("Missions"),
    REVIEW("Review"),
    DATA("Data"),
    SETTINGS("Settings"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AssistantApp(
    db: BrainDb,
    brain: BrainRunner,
    agent: AgentRunner,
    deepseek: DeepSeekClient,
    previewPort: PreviewPort,
    runnerPort: RunnerPort,
    permissionGateway: PermissionGateway,
    pendingGoal: String?,
    pendingMaintain: Boolean,
    onGoalConsumed: () -> Unit,
    onStop: suspend () -> Unit,
    onStartQueue: (Long) -> Unit,
    onPauseQueue: () -> Unit,
    onResumeQueue: () -> Unit,
    onCancelQueue: () -> Unit,
    onScheduleQueue: (Long, Long) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val settings = remember { GlobalContext.get().get<AppSettingsManager>() }
    val resolution by settings.resolutionPreference.collectAsState()
    val pendingQuestion by agent.pendingQuestion.collectAsState()
    val previewMarkers by previewPort.markers.collectAsState()
    val runnerState by runnerPort.state.collectAsState()
    val watchdogState by permissionGateway.watchdogState.collectAsState()
    val runnerBusy = runnerState.phase.isBusy
    // Preview frames can outlive a finished runner: MaaFwApp keeps the
    // virtual display (and AppWatchdog) alive until the next start/stop.
    // Calling that state "Not running" over a live picture is contradictory.
    val previewActive = runnerBusy || watchdogState == WatchdogState.WATCHING
    var previewSurfaceReady by remember { mutableStateOf(false) }
    var previewFullscreen by rememberSaveable { mutableStateOf(false) }
    // Reuse MaaFwApp's movable preview wiring: it owns the Surface dedup/detach
    // contract and its touch-marker overlay. The raw MaaPreviewSurface used
    // before this could stay black after the SurfaceView was recreated.
    val previewContent = rememberMovablePreview(
        resolution = resolution.resolution,
        markers = { previewMarkers },
        onSurfaceCreated = { previewSurfaceReady = true },
        onSurfaceAvailable = { surface -> previewPort.attachSurface(surface) },
        onSurfaceDestroyed = {
            previewSurfaceReady = false
            previewPort.detachSurface()
        },
    )
    var tab by remember { mutableStateOf(AssistantTab.ASSISTANT) }
    var goal by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Ready.") }
    var busy by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    var runs by remember { mutableStateOf(emptyList<JSONObject>()) }
    var proposals by remember { mutableStateOf(emptyList<JSONObject>()) }
    var messages by remember { mutableStateOf(emptyList<JSONObject>()) }
    var missions by remember { mutableStateOf(emptyList<JSONObject>()) }
    var missionItems by remember { mutableStateOf(emptyList<JSONObject>()) }
    var missionQueue by remember { mutableStateOf(emptyList<JSONObject>()) }
    var livePipelines by remember { mutableStateOf(emptyList<JSONObject>()) }
    var selectedMissionId by remember { mutableLongStateOf(0L) }
    var chatRunId by remember { mutableLongStateOf(0L) }
    var recoveredQuestion by remember { mutableStateOf<JSONObject?>(null) }
    var dataTable by remember { mutableStateOf("runs") }
    var tableRows by remember { mutableStateOf(emptyList<JSONObject>()) }
    var latestShot by remember { mutableStateOf<String?>(null) }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) {
            status = "import cancelled"
        } else {
            scope.launch {
                val text = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    }.getOrNull()
                }
                if (text.isNullOrBlank()) {
                    status = "import failed: empty or unreadable JSON"
                } else {
                    val name = goal.ifBlank { uri.lastPathSegment?.substringAfterLast('/') ?: "imported" }
                    runCatching {
                        withContext(Dispatchers.IO) {
                            Importer.importPipeline(db, context, text, name)
                        }
                    }.onSuccess {
                        status = "imported proposal #$it; review it in Review"
                    }.onFailure {
                        status = "import failed: ${it.message}"
                    }
                }
            }
        }
    }

    fun refresh() {
        scope.launch {
            val missionId = selectedMissionId
            val requestedChatRunId = chatRunId
            val loaded = withContext(Dispatchers.IO) {
                val runRows = db.rows(
                    "SELECT id,goal,state,success,verified,ai_cost,error,duration_ms " +
                        "FROM runs ORDER BY id DESC LIMIT 30",
                )
                val effectiveChatRunId = requestedChatRunId.takeIf { it > 0 }
                    ?: runRows.firstOrNull()?.getLong("id")
                    ?: 0L
                val messageRows = if (effectiveChatRunId > 0) {
                    db.rows(
                        "SELECT id,run_id,role,kind,content,state,payload_json " +
                            "FROM messages WHERE run_id=? ORDER BY id ASC LIMIT 200",
                        arrayOf<Any?>(effectiveChatRunId),
                    )
                } else {
                    emptyList()
                }
                listOf<Any?>(
                    runRows,
                    db.rows(
                        "SELECT p.id,p.kind,p.status,p.source_run_id,p.target_pipeline_id,p.target_version_id," +
                            "p.candidate_json," +
                            "(SELECT group_concat(r.id || ':' || pvr.role || ':' || r.success || ':' || r.verified, ' | ') " +
                            " FROM pipeline_version_runs pvr JOIN runs r ON r.id=pvr.run_id " +
                            " WHERE pvr.pipeline_version_id=p.target_version_id) evidence " +
                            "FROM proposals p ORDER BY p.id DESC LIMIT 30",
                    ),
                    messageRows,
                    db.missions(),
                    db.rows("SELECT id,name,goal FROM pipelines WHERE status='live' ORDER BY name"),
                    if (missionId > 0) db.missionItems(missionId) else emptyList(),
                    db.latestPendingQuestion(),
                    if (missionId > 0) db.missionQueue(missionId) else emptyList(),
                    effectiveChatRunId,
                )
            }
            runs = loaded[0] as List<JSONObject>
            proposals = loaded[1] as List<JSONObject>
            messages = loaded[2] as List<JSONObject>
            missions = loaded[3] as List<JSONObject>
            livePipelines = loaded[4] as List<JSONObject>
            missionItems = loaded[5] as List<JSONObject>
            recoveredQuestion = loaded[6] as JSONObject?
            missionQueue = loaded[7] as List<JSONObject>
            chatRunId = loaded[8] as Long
            val base = context.getExternalFilesDir("brain")
            val shots = if (base != null) File(base, "shots") else null
            latestShot = shots?.listFiles()?.filter { it.name.endsWith(".png") }
                ?.maxByOrNull { it.lastModified() }?.absolutePath
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            refresh()
            delay(1200)
        }
    }

    val startGoal: (String) -> Unit = { text ->
        if (text.isNotBlank() && !busy) {
            busy = true
            status = "Running: $text …"
            scope.launch {
                val result = withContext(Dispatchers.IO) { brain.runGoal(text) }
                status = "[${result.status}] ${result.message}"
                busy = false
                tick++
            }
        }
    }

    /**
     * Replay the current live pipeline first. If it fails, run the AI with the
     * old pipeline as context and file a pipeline_fix candidate. This is the
     * UI-change maintenance loop; Review still has to approve the new version.
     */
    val maintainGoal: (String) -> Unit = { text ->
        if (text.isNotBlank() && !busy) {
            busy = true
            status = "Replaying live pipeline for: $text …"
            scope.launch {
                val replay = withContext(Dispatchers.IO) { brain.runGoal(text, mode = "replay") }
                when (replay.status) {
                    "done" -> status = "Replay OK: ${replay.message}"
                    "no_pipeline" -> status = replay.message
                    "cancelled" -> status = "Replay cancelled"
                    else -> {
                        val pipelineId = replay.pipelineId
                        if (pipelineId == null) {
                            status = "Replay failed: ${replay.message}"
                        } else {
                            val contextJson = withContext(Dispatchers.IO) {
                                db.rows(
                                    "SELECT p.name,p.goal,p.aliases,p.definition_json,p.postcondition_json," +
                                        "p.app_id,a.package_name " +
                                        "FROM pipelines p LEFT JOIN apps a ON a.id=p.app_id WHERE p.id=?",
                                    arrayOf<Any?>(pipelineId),
                                ).firstOrNull()?.let { row ->
                                    JSONObject()
                                        .put("id", pipelineId)
                                        .put("name", row.optString("name"))
                                        .put("goal", row.optString("goal"))
                                        .put("definition", row.optString("definition_json"))
                                        .put("postcondition", row.optString("postcondition_json"))
                                        .put("app_id", row.opt("app_id"))
                                        .put("package", row.optString("package_name"))
                                        .put("replay_error", replay.message)
                                        .toString()
                                }
                            }
                            status = "Replay failed; AI is adapting the pipeline and will file a fix proposal …"
                            val context = contextJson?.let { JSONObject(it) }
                            val result = withContext(Dispatchers.IO) {
                                agent.run(
                                    goal = text,
                                    proposalKind = "pipeline_fix",
                                    targetPipelineId = pipelineId,
                                    evidenceRunId = replay.runId,
                                    pipelineContext = contextJson.orEmpty(),
                                    targetPackage = context?.optString("package").orEmpty(),
                                    targetAppId = context?.opt("app_id")
                                        ?.takeIf { it != JSONObject.NULL }
                                        ?.toString()?.toLongOrNull(),
                                )
                            }
                            status = "[${result.status}] ${result.message}"
                        }
                    }
                }
                busy = false
                tick++
            }
        }
    }

    val runMissionItem: (Long) -> Unit = { itemId ->
        if (!busy) {
            busy = true
            status = "Running mission item #$itemId …"
            scope.launch {
                val result = withContext(Dispatchers.IO) { brain.runMissionItem(itemId) }
                status = "[${result.status}] ${result.message}"
                busy = false
                tick++
            }
        }
    }

    LaunchedEffect(pendingGoal, pendingMaintain) {
        pendingGoal?.takeIf { it.isNotBlank() }?.let {
            goal = it
            val maintain = pendingMaintain
            onGoalConsumed()
            if (maintain) maintainGoal(it) else startGoal(it)
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Maa-phone Assistant") }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            ScrollableTabRow(selectedTabIndex = tab.ordinal) {
                AssistantTab.entries.forEach { item ->
                    Tab(selected = tab == item, onClick = { tab = item }, text = { Text(item.label) })
                }
            }
            when (tab) {
                AssistantTab.ASSISTANT -> AssistantTabContent(
                    db = db,
                    resolution = resolution,
                    previewContent = previewContent,
                    previewFullscreen = previewFullscreen,
                    previewActive = previewActive,
                    watchdogState = watchdogState,
                    previewSurfaceReady = previewSurfaceReady,
                    onEnterFullscreen = { previewFullscreen = true },
                    goal = goal,
                    onGoalChange = { goal = it },
                    status = status,
                    busy = busy,
                    latestShot = latestShot,
                    onRun = { startGoal(goal) },
                    onMaintain = { maintainGoal(goal) },
                    onStop = { scope.launch { onStop() } },
                    onImport = { importLauncher.launch("application/json") },
                    onStatus = { status = it; tick++ },
                )
                AssistantTab.LOGS -> ChatTab(
                    runs = runs,
                    messages = messages,
                    selectedRunId = chatRunId,
                    onSelectRun = { runId -> chatRunId = runId; tick++ },
                )
                AssistantTab.RUNS -> RunsTab(runs)
                AssistantTab.MISSIONS -> MissionsTab(
                    db = db,
                    missions = missions,
                    missionItems = missionItems,
                    missionQueue = missionQueue,
                    livePipelines = livePipelines,
                    selectedMissionId = selectedMissionId,
                    busy = busy,
                    onSelectMission = { missionId ->
                        selectedMissionId = missionId
                        tick++
                    },
                    onChanged = { status = it; tick++ },
                    onRunItem = runMissionItem,
                    onStartQueue = onStartQueue,
                    onPauseQueue = onPauseQueue,
                    onResumeQueue = onResumeQueue,
                    onCancelQueue = onCancelQueue,
                    onScheduleQueue = onScheduleQueue,
                )
                AssistantTab.REVIEW -> ReviewTab(
                    db = db,
                    proposals = proposals,
                    busy = busy,
                    onTest = { proposalId ->
                        if (!busy) {
                            busy = true
                            status = "Testing candidate #$proposalId …"
                            scope.launch {
                                val result = withContext(Dispatchers.IO) { brain.testProposal(proposalId) }
                                status = "[${result.status}] ${result.message}"
                                busy = false
                                tick++
                            }
                        }
                    },
                    onChanged = { status = it; tick++ },
                )
                AssistantTab.DATA -> DataTab(
                    db = db,
                    table = dataTable,
                    onTable = { dataTable = it; tick++ },
                    rows = tableRows,
                    onRefresh = {
                        scope.launch {
                            tableRows = withContext(Dispatchers.IO) {
                                db.rows("SELECT * FROM $dataTable ORDER BY id DESC LIMIT 50")
                            }
                        }
                    },
                )
                AssistantTab.SETTINGS -> SettingsTab(db, deepseek) { status = it }
            }
        }
    }

    if (pendingQuestion != null) {
        AgentQuestionDialog(
            question = pendingQuestion!!,
            onAnswer = { answer -> agent.submitAnswer(answer) },
            onCancel = { agent.requestCancel() },
        )
    } else {
        recoveredQuestion?.let { recovered ->
            val payload = runCatching { JSONObject(recovered.optString("payload_json")) }
                .getOrDefault(JSONObject())
            val options = mutableListOf<String>()
            payload.optJSONArray("options")?.let { arr ->
                for (i in 0 until arr.length()) {
                    arr.optString(i).trim().takeIf { it.isNotBlank() }?.let { options += it }
                }
            }
            val pending = AgentRunner.PendingQuestion(
                runId = recovered.optLong("run_id"),
                messageId = recovered.optLong("message_id"),
                question = recovered.optString("question").ifBlank { "A previous run needs input." },
                options = options,
                allowFreeText = if (payload.has("allow_free_text")) {
                    payload.optBoolean("allow_free_text", true)
                } else {
                    options.isEmpty()
                },
            )
            AgentQuestionDialog(
                question = pending,
                onAnswer = { answer ->
                    busy = true
                    status = "Resuming run #${pending.runId} …"
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            agent.resume(pending.runId, pending.messageId, answer)
                        }
                        status = "[${result.status}] ${result.message}"
                        recoveredQuestion = null
                        busy = false
                        tick++
                    }
                },
                onCancel = {
                    scope.launch {
                        withContext(Dispatchers.IO) { agent.cancelPendingRun(pending.runId) }
                        recoveredQuestion = null
                        tick++
                    }
                },
            )
        }
    }

    if (previewFullscreen) {
        FullscreenPreview(
            resolution = resolution.resolution,
            onExit = { previewFullscreen = false },
            onTouch = { x, y, action, contact ->
                when (action) {
                    PreviewTouchAction.Down -> previewPort.touchDown(x, y, contact)
                    PreviewTouchAction.Move -> previewPort.touchMove(x, y, contact)
                    PreviewTouchAction.Up -> previewPort.touchUp(x, y, contact)
                }
            },
            content = previewContent,
        )
    }
}

@Composable
private fun AgentQuestionDialog(
    question: AgentRunner.PendingQuestion,
    onAnswer: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var freeText by remember(question.messageId) { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Agent needs input") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(question.question, style = MaterialTheme.typography.bodyMedium)
                question.options.forEach { option ->
                    MaaOutlinedButton(
                        onClick = { onAnswer(option) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(option) }
                }
                if (question.allowFreeText) {
                    OutlinedTextField(
                        value = freeText,
                        onValueChange = { freeText = it },
                        label = { Text("Type your answer") },
                        modifier = Modifier.fillMaxWidth(),
                        maxLines = 4,
                    )
                } else if (question.options.isEmpty()) {
                    Text(
                        "No options were provided; type a reply is impossible without free text.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        },
        confirmButton = {
            if (question.allowFreeText) {
                MaaButton(
                    onClick = {
                        val answer = freeText.trim()
                        if (answer.isNotEmpty()) onAnswer(answer)
                    },
                    enabled = freeText.isNotBlank(),
                ) { Text("Send") }
            } else {
                MaaButton(onClick = onCancel) { Text("Cancel") }
            }
        },
        dismissButton = {
            if (question.allowFreeText) {
                MaaOutlinedButton(onClick = onCancel) { Text("Cancel") }
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AssistantTabContent(
    db: BrainDb,
    resolution: com.aliothmoon.maafw.runner.ResolutionPreference,
    previewContent: @Composable () -> Unit,
    previewFullscreen: Boolean,
    previewActive: Boolean,
    watchdogState: WatchdogState,
    previewSurfaceReady: Boolean,
    onEnterFullscreen: () -> Unit,
    goal: String,
    onGoalChange: (String) -> Unit,
    status: String,
    busy: Boolean,
    latestShot: String?,
    onRun: () -> Unit,
    onMaintain: () -> Unit,
    onStop: () -> Unit,
    onImport: () -> Unit,
    onStatus: (String) -> Unit,
) {
    val context = LocalContext.current
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp)
    ) {
        LivePreview(
            resolution = resolution.resolution,
            surfaceReady = previewSurfaceReady,
            running = previewActive,
            watchdogState = watchdogState,
            content = previewContent.takeUnless { previewFullscreen },
            onEnterFullscreen = onEnterFullscreen,
            showStatus = true,
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
        )
        latestShot?.let { path ->
            val bitmap = remember(path) { BitmapFactory.decodeFile(path) }
            bitmap?.let {
                Spacer(Modifier.height(6.dp))
                Card(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = "latest AI screenshot",
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = goal,
            onValueChange = onGoalChange,
            label = { Text("Goal") },
            modifier = Modifier.fillMaxWidth(),
            maxLines = 3,
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MaaButton(onClick = onRun, enabled = !busy) {
                Text(if (busy) "Running…" else "Run", maxLines = 1)
            }
            MaaOutlinedButton(onClick = onMaintain, enabled = !busy) {
                Text("Replay + improve", maxLines = 1)
            }
            MaaOutlinedButton(onClick = onStop, enabled = busy) {
                Text("Stop", maxLines = 1)
            }
            MaaOutlinedButton(onClick = onImport) {
                Text("Import pipeline", maxLines = 1)
            }
        }
        Text(status, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 10.dp))
        Text(
            "Tip: type the goal, press Run. On a resolver miss the AI fallback runs. " +
                "Replay + improve first runs the existing live pipeline; if it fails, AI adapts " +
                "the current UI and files a pipeline-fix candidate for Review. Import picks a " +
                "MaaFW pipeline.json from device storage.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ChatTab(
    runs: List<JSONObject>,
    messages: List<JSONObject>,
    selectedRunId: Long,
    onSelectRun: (Long) -> Unit,
) {
    val selectedRun = runs.firstOrNull { it.optLong("id") == selectedRunId }
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            runs.take(12).forEach { run ->
                val id = run.optLong("id")
                if (id == selectedRunId) {
                    MaaButton(onClick = { onSelectRun(id) }) { Text("#$id", maxLines = 1) }
                } else {
                    MaaOutlinedButton(onClick = { onSelectRun(id) }) { Text("#$id", maxLines = 1) }
                }
            }
        }
        selectedRun?.let { run ->
            Text(
                "Run #${run.optLong("id")} · ${run.optString("state")} · ${run.optString("goal")}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp),
                maxLines = 2,
            )
        }
        LogsTab(messages, Modifier.weight(1f))
    }
}

@Composable
private fun LogsTab(
    messages: List<JSONObject>,
    modifier: Modifier = Modifier.fillMaxSize(),
) {
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }
    LazyColumn(
        state = listState,
        modifier = modifier.padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(messages) { message -> ChatMessage(message) }
    }
}

@Composable
private fun ChatMessage(message: JSONObject) {
    val role = message.optString("role").ifBlank { "assistant" }
    val kind = message.optString("kind")
    val state = message.optString("state")
    val isUser = role == "user"
    val isQuestion = kind == "question"
    val isError = message.optString("content").startsWith("Failed") ||
        (kind == "result" &&
            message.optString("content").contains("failed", ignoreCase = true) &&
            !message.optString("content").startsWith("Cancelled"))
    val background = when {
        isError -> MaterialTheme.colorScheme.errorContainer
        isQuestion -> MaterialTheme.colorScheme.tertiaryContainer
        isUser -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val payload = runCatching { JSONObject(message.optString("payload_json")) }.getOrNull()
    val options = mutableListOf<String>()
    payload?.optJSONArray("options")?.let { arr ->
        for (i in 0 until arr.length()) {
            arr.optString(i).takeIf { it.isNotBlank() }?.let { options += it }
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(0.88f),
            colors = CardDefaults.cardColors(containerColor = background),
        ) {
            Column(Modifier.padding(10.dp)) {
                Text(
                    "#${message.optLong("id")} · run=${message.optLong("run_id")} · " +
                        "$role/$kind" + if (state.isNotBlank()) " · $state" else "",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                )
                Text(message.optString("content"), style = MaterialTheme.typography.bodyMedium)
                if (isQuestion && options.isNotEmpty()) {
                    Text(
                        "options: " + options.joinToString(" | "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (isQuestion && state == "pending") {
                    Text(
                        "waiting for the answer modal",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
        }
    }
}

@Composable
private fun RunsTab(runs: List<JSONObject>) {
    LazyColumn(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(runs) { run ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp)) {
                    Text(
                        "#${run.optLong("id")} · ${run.optString("state")} · " +
                            (if (run.optInt("success") == 1) "ok" else "failed") +
                            " · verified=${run.optInt("verified") == 1}" +
                            " · cost=${run.optLong("ai_cost")}tok · ${run.optLong("duration_ms")}ms",
                        fontWeight = FontWeight.Bold,
                    )
                    Text(run.optString("goal"), style = MaterialTheme.typography.bodyMedium)
                    if (run.optString("error").isNotBlank()) {
                        Text(run.optString("error"), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
private fun MissionsTab(
    db: BrainDb,
    missions: List<JSONObject>,
    missionItems: List<JSONObject>,
    missionQueue: List<JSONObject>,
    livePipelines: List<JSONObject>,
    selectedMissionId: Long,
    busy: Boolean,
    onSelectMission: (Long) -> Unit,
    onChanged: (String) -> Unit,
    onRunItem: (Long) -> Unit,
    onStartQueue: (Long) -> Unit,
    onPauseQueue: () -> Unit,
    onResumeQueue: () -> Unit,
    onCancelQueue: () -> Unit,
    onScheduleQueue: (Long, Long) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var newMissionName by remember { mutableStateOf("") }
    var newItemGoal by remember { mutableStateOf("") }
    var scheduleMinutes by remember { mutableStateOf("10") }
    var queueStatus by remember { mutableStateOf("") }
    val selected = missions.firstOrNull { it.optLong("id") == selectedMissionId }

    LazyColumn(
        Modifier.fillMaxSize().padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (selectedMissionId <= 0 || selected == null) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Missions", style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold)
                        Text(
                            "Create a mission, add pipeline/AI items, then run each item into the " +
                                "normal runs table with mission provenance.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        OutlinedTextField(
                            value = newMissionName,
                            onValueChange = { newMissionName = it },
                            label = { Text("Mission name") },
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 2,
                        )
                        MaaButton(
                            onClick = {
                                val name = newMissionName.trim()
                                if (name.isNotBlank()) {
                                    scope.launch {
                                        withContext(Dispatchers.IO) { db.createMission(name) }
                                        newMissionName = ""
                                        onChanged("mission created")
                                    }
                                }
                            },
                            enabled = !busy && newMissionName.isNotBlank(),
                        ) { Text("Create mission") }
                    }
                }
            }
            if (missions.isEmpty()) {
                item { Text("No missions yet.", style = MaterialTheme.typography.bodySmall) }
            } else {
                items(missions) { mission ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "#${mission.optLong("id")} ${mission.optString("name")}",
                                fontWeight = FontWeight.Bold,
                            )
                            if (mission.optString("description").isNotBlank()) {
                                Text(mission.optString("description"), style = MaterialTheme.typography.bodySmall)
                            }
                            Text(
                                "items=${mission.optLong("item_count")} enabled=${mission.optInt("enabled") == 1}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                MaaButton(onClick = { onSelectMission(mission.optLong("id")) }) { Text("Open") }
                                MaaOutlinedButton(onClick = {
                                    scope.launch {
                                        withContext(Dispatchers.IO) { db.deleteMission(mission.optLong("id")) }
                                        onChanged("mission deleted")
                                    }
                                }) { Text("Delete") }
                            }
                        }
                    }
                }
            }
        } else {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "Mission #${selected.optLong("id")} · ${selected.optString("name")}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        MaaOutlinedButton(onClick = { onSelectMission(0L) }) { Text("Back to missions") }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Queue", fontWeight = FontWeight.Bold)
                        Text(
                            "Run all enabled items sequentially. Pause takes effect between items; " +
                                "Cancel stops the current MaaFW run and cancels the rest of the queue.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            MaaButton(
                                onClick = {
                                    queueStatus = "queue started"
                                    onStartQueue(selectedMissionId)
                                },
                                enabled = !busy,
                            ) { Text("Run all") }
                            MaaOutlinedButton(onClick = {
                                queueStatus = "queue paused (between items)"
                                onPauseQueue()
                            }) { Text("Pause") }
                            MaaOutlinedButton(onClick = {
                                queueStatus = "queue resumed"
                                onResumeQueue()
                            }) { Text("Resume") }
                            MaaOutlinedButton(onClick = {
                                queueStatus = "queue cancelling"
                                onCancelQueue()
                            }) { Text("Cancel") }
                            MaaOutlinedButton(onClick = {
                                scope.launch {
                                    withContext(Dispatchers.IO) { db.clearMissionQueue(selectedMissionId) }
                                    queueStatus = "queue cleared"
                                    onChanged("mission queue cleared")
                                }
                            }) { Text("Clear queue") }
                        }
                        OutlinedTextField(
                            value = scheduleMinutes,
                            onValueChange = { value -> scheduleMinutes = value.filter(Char::isDigit).take(4) },
                            label = { Text("Schedule run-all in minutes") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        MaaButton(
                            onClick = {
                                val minutes = scheduleMinutes.toLongOrNull() ?: 0L
                                if (minutes > 0) {
                                    val delayMs = minutes * 60_000L
                                    queueStatus = "scheduled in $minutes min"
                                    scope.launch {
                                        withContext(Dispatchers.IO) {
                                            db.setMissionSchedule(
                                                selectedMissionId,
                                                JSONObject()
                                                    .put("run_at", System.currentTimeMillis() + delayMs)
                                                    .put("status", "scheduled")
                                                    .toString(),
                                            )
                                        }
                                        onScheduleQueue(selectedMissionId, delayMs)
                                    }
                                }
                            },
                            enabled = !busy && (scheduleMinutes.toLongOrNull() ?: 0L) > 0,
                        ) { Text("Schedule run all") }
                        if (queueStatus.isNotBlank()) {
                            Text(
                                queueStatus,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        if (missionQueue.isEmpty()) {
                            Text(
                                "No persisted queue yet.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            missionQueue.forEach { q ->
                                Text(
                                    "pos=${q.optLong("position")} #${q.optLong("id")} " +
                                        "${q.optString("state")} " +
                                        (q.optString("pipeline_name").ifBlank { q.optString("goal") }) +
                                        if (q.optString("error").isNotBlank()) " error=${q.optString("error").take(80)}" else "",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = when (q.optString("state")) {
                                        "failed", "cancelled" -> MaterialTheme.colorScheme.error
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                            }
                        }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Add item", fontWeight = FontWeight.Bold)
                        OutlinedTextField(
                            value = newItemGoal,
                            onValueChange = { newItemGoal = it },
                            label = { Text("Goal (used by AI fallback or matching pipeline)") },
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 3,
                        )
                        MaaButton(
                            onClick = {
                                val goal = newItemGoal.trim()
                                if (goal.isNotBlank()) {
                                    scope.launch {
                                        withContext(Dispatchers.IO) {
                                            db.addMissionItem(selectedMissionId, null, goal)
                                        }
                                        newItemGoal = ""
                                        onChanged("mission item added (AI goal)")
                                    }
                                }
                            },
                            enabled = !busy && newItemGoal.isNotBlank(),
                        ) { Text("Add goal item") }
                        Text(
                            if (livePipelines.isEmpty()) {
                                "No live pipelines yet; goal items will use AI fallback when run."
                            } else {
                                "Or pin a live pipeline:"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        livePipelines.forEach { pipeline ->
                            MaaOutlinedButton(
                                onClick = {
                                    scope.launch {
                                        withContext(Dispatchers.IO) {
                                            db.addMissionItem(
                                                selectedMissionId,
                                                pipeline.optLong("id"),
                                                pipeline.optString("goal"),
                                            )
                                        }
                                        onChanged("mission item added: ${pipeline.optString("name")}")
                                    }
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("+ ${pipeline.optString("name")}") }
                        }
                    }
                }
            }
            if (missionItems.isEmpty()) {
                item { Text("No items in this mission yet.", style = MaterialTheme.typography.bodySmall) }
            } else {
                items(missionItems) { item ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                "#${item.optLong("id")} · pos=${item.optLong("position")} · " +
                                    if (item.optInt("enabled") == 1) "enabled" else "disabled",
                                fontWeight = FontWeight.Bold,
                            )
                            Text(
                                item.optString("pipeline_name").ifBlank { item.optString("goal") },
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            if (item.isNull("last_run_id")) {
                                Text(
                                    "last run: none",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            } else {
                                Text(
                                    "last run #${item.optLong("last_run_id")} · " +
                                        "${item.optString("last_run_state")} · " +
                                        "success=${item.optInt("last_run_success") == 1} · " +
                                        "verified=${item.optInt("last_run_verified") == 1}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (item.optString("last_run_error").isNotBlank()) {
                                    Text(
                                        item.optString("last_run_error"),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                MaaButton(
                                    onClick = { onRunItem(item.optLong("id")) },
                                    enabled = !busy && item.optInt("enabled") == 1,
                                ) { Text("Run") }
                                MaaOutlinedButton(onClick = {
                                    val nowEnabled = item.optInt("enabled") == 1
                                    scope.launch {
                                        withContext(Dispatchers.IO) {
                                            db.setMissionItemEnabled(item.optLong("id"), !nowEnabled)
                                        }
                                        onChanged(if (nowEnabled) "mission item disabled" else "mission item enabled")
                                    }
                                }) { Text(if (item.optInt("enabled") == 1) "Disable" else "Enable") }
                                MaaOutlinedButton(onClick = {
                                    scope.launch {
                                        withContext(Dispatchers.IO) { db.deleteMissionItem(item.optLong("id")) }
                                        onChanged("mission item deleted")
                                    }
                                }) { Text("Delete") }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReviewTab(
    db: BrainDb,
    proposals: List<JSONObject>,
    busy: Boolean,
    onTest: (Long) -> Unit,
    onChanged: (String) -> Unit,
) {
    val scope = rememberCoroutineScope()
    LazyColumn(Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(proposals) { proposal ->
            val candidate = remember(proposal.optString("candidate_json")) {
                runCatching { JSONObject(proposal.optString("candidate_json")) }.getOrNull()
            }
            val pipeline = candidate?.optJSONObject("pipeline")
            val graph = pipeline?.optJSONObject("graph")
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(10.dp)) {
                    Text(
                        "#${proposal.optLong("id")} [${proposal.optString("kind")}] ${proposal.optString("status")}",
                        fontWeight = FontWeight.Bold,
                    )
                    val versionId = proposal.optLong("target_version_id")
                    val pipelineId = proposal.optLong("target_pipeline_id")
                    if (versionId > 0 || pipelineId > 0) {
                        Text(
                            "pipeline=$pipelineId version=$versionId",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    pipeline?.optString("goal")?.takeIf { it.isNotBlank() }?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (graph != null) {
                        Text(
                            "entry=${pipeline?.optString("entry").orEmpty()} · ${graph.length()} node(s)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val nodes = graph.keys().asSequence().take(8).map { name ->
                            val node = graph.optJSONObject(name)
                            val recognition = node?.optString("recognition").orEmpty()
                            val action = node?.optString("action").orEmpty()
                            "$name: ${recognition.ifBlank { "DirectHit" }}/${action.ifBlank { "DoNothing" }}"
                        }.toList()
                        Text(
                            nodes.joinToString("\n"),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    val postcondition = pipeline?.optJSONObject("postcondition")
                    Text(
                        "postcondition: " + (postcondition?.toString() ?: "{}"),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    val evidence = proposal.opt("evidence")
                        ?.takeIf { it != JSONObject.NULL }
                        ?.toString()
                        ?.takeIf { it.isNotBlank() && it != "null" }
                    Text(
                        if (evidence == null) "replay evidence: none yet" else "runs (id:role:success:verified): $evidence",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (proposal.optString("status") == "pending") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            MaaOutlinedButton(
                                onClick = { onTest(proposal.optLong("id")) },
                                enabled = !busy,
                            ) { Text("Test replay") }
                            MaaButton(onClick = {
                                scope.launch {
                                    onChanged(withContext(Dispatchers.IO) { Learner.approve(db, proposal.optLong("id")) })
                                }
                            }) { Text("Approve") }
                            MaaOutlinedButton(onClick = {
                                scope.launch {
                                    onChanged(withContext(Dispatchers.IO) { Learner.reject(db, proposal.optLong("id")) })
                                }
                            }) { Text("Reject") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DataTab(
    db: BrainDb,
    table: String,
    onTable: (String) -> Unit,
    rows: List<JSONObject>,
    onRefresh: () -> Unit,
) {
    LaunchedEffect(table) { onRefresh() }
    val tables = listOf("runs", "messages", "pipelines", "pipeline_versions", "pipeline_version_runs", "missions", "mission_items", "elements", "proposals", "settings", "apps", "policies")
    Column(Modifier.fillMaxSize().padding(10.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            tables.forEach { item ->
                if (item == table) MaaButton(onClick = { onTable(item); onRefresh() }) { Text(item) }
                else MaaOutlinedButton(onClick = { onTable(item); onRefresh() }) { Text(item) }
            }
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(rows) { row ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(8.dp)) {
                        row.keys().asSequence().forEach { key ->
                            Text(
                                "$key = ${row.opt(key)?.toString()?.replace('\n', ' ') ?: ""}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsTab(db: BrainDb, deepseek: DeepSeekClient, onStatus: (String) -> Unit) {
    val scope = rememberCoroutineScope()
    var apiKey by remember { mutableStateOf(db.setting("deepseek_api_key", "")) }
    var baseUrl by remember { mutableStateOf(db.setting("deepseek_base", deepseek.baseUrl())) }
    var model by remember { mutableStateOf(db.setting("deepseek_model", deepseek.model())) }
    var maxSteps by remember { mutableStateOf(db.setting("agent_max_steps", "300")) }
    var repeatLimit by remember { mutableStateOf(db.setting("agent_repeat_limit", "5")) }
    var testStatus by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("AI provider (OpenAI-compatible)", style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text("API key") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = { Text("Base URL") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = model,
            onValueChange = { model = it },
            label = { Text("Model") },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Official api.deepseek.com uses deepseek-chat or deepseek-reasoner. " +
                "A custom OpenAI-compatible gateway can keep deepseek-v4-flash.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = maxSteps,
            onValueChange = { maxSteps = it.filter(Char::isDigit).take(4) },
            label = { Text("Max AI steps (5-1000)") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = repeatLimit,
            onValueChange = { repeatLimit = it.filter(Char::isDigit).take(2) },
            label = { Text("Early-exit repeat limit (2-10)") },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Larger budget for complex goals. Early exit stops a run after this many " +
                "consecutive empty/failed results or repeated identical actions.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MaaButton(onClick = {
                db.setSetting("deepseek_api_key", apiKey.trim())
                db.setSetting("deepseek_base", baseUrl.trim().trimEnd('/'))
                db.setSetting("deepseek_model", model.trim())
                db.setSetting("agent_max_steps", (maxSteps.toIntOrNull()?.coerceIn(5, 1000) ?: 300).toString())
                db.setSetting("agent_repeat_limit", (repeatLimit.toIntOrNull()?.coerceIn(2, 10) ?: 5).toString())
                onStatus("AI settings saved")
                testStatus = "saved"
            }) { Text("Save") }
            MaaOutlinedButton(onClick = {
                testStatus = "testing…"
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            val messages = org.json.JSONArray().put(
                                JSONObject().put("role", "user").put("content", "ping")
                            )
                            deepseek.chat(messages, maxTokens = 16)
                        }.fold(
                            onSuccess = { "OK: endpoint reachable" },
                            onFailure = { "FAILED: ${it.message}" },
                        )
                    }
                    testStatus = result
                }
            }) { Text("Test") }
        }
        if (testStatus.isNotBlank()) {
            Text(testStatus, style = MaterialTheme.typography.bodySmall)
        }
        HorizontalDivider()
        Text(
            "Key/model are embedded at build time from .env as defaults; values here override them. " +
                "Base URL must expose OpenAI-compatible /chat/completions.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
