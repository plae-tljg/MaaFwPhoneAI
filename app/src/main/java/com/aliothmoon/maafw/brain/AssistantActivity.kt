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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.aliothmoon.maafw.privileged.PrivilegedServicePort
import com.aliothmoon.maafw.runner.PreviewPort
import com.aliothmoon.maafw.runner.RunnerPort
import com.aliothmoon.maafw.settings.AppSettingsManager
import com.aliothmoon.maafw.theme.MaaFwTheme
import com.aliothmoon.maafw.ui.components.MaaButton
import com.aliothmoon.maafw.ui.components.MaaOutlinedButton
import com.aliothmoon.maafw.ui.components.MaaPreviewSurface
import kotlinx.coroutines.Dispatchers
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = BrainDb(this)
        runnerPort = GlobalContext.get().get<RunnerPort>()
        val servicePort = GlobalContext.get().get<PrivilegedServicePort>()
        previewPort = GlobalContext.get().get<PreviewPort>()
        deepseek = DeepSeekClient(db)
        agent = AgentRunner(this, db, AgentTools(this, db, runnerPort, servicePort), deepseek)
        brain = BrainRunner(this, db, runnerPort, servicePort) { goal -> agent.run(goal) }
        pendingGoal = intent?.getStringExtra(EXTRA_GOAL)
        pendingMaintain = intent?.getBooleanExtra(EXTRA_MAINTAIN, false) ?: false
        setContent {
            MaaFwTheme {
                AssistantApp(
                    db = db,
                    brain = brain,
                    agent = agent,
                    deepseek = deepseek,
                    previewPort = previewPort,
                    pendingGoal = pendingGoal,
                    pendingMaintain = pendingMaintain,
                    onGoalConsumed = { pendingGoal = null; pendingMaintain = false },
                    onStop = {
                        agent.requestCancel()
                        runnerPort.stop()
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
    LOGS("Logs"),
    RUNS("Runs"),
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
    pendingGoal: String?,
    pendingMaintain: Boolean,
    onGoalConsumed: () -> Unit,
    onStop: suspend () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val settings = remember { GlobalContext.get().get<AppSettingsManager>() }
    val resolution by settings.resolutionPreference.collectAsState()
    var tab by remember { mutableStateOf(AssistantTab.ASSISTANT) }
    var goal by remember { mutableStateOf("") }
    var status by remember { mutableStateOf("Ready.") }
    var busy by remember { mutableStateOf(false) }
    var tick by remember { mutableIntStateOf(0) }
    var runs by remember { mutableStateOf(emptyList<JSONObject>()) }
    var proposals by remember { mutableStateOf(emptyList<JSONObject>()) }
    var messages by remember { mutableStateOf(emptyList<JSONObject>()) }
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
            val loaded = withContext(Dispatchers.IO) {
                Triple(
                    db.rows("SELECT id,goal,state,success,verified,error,duration_ms FROM runs ORDER BY id DESC LIMIT 30"),
                    db.rows(
                        "SELECT p.id,p.kind,p.status,p.source_run_id,p.target_pipeline_id,p.target_version_id," +
                            "p.candidate_json," +
                            "(SELECT group_concat(r.id || ':' || pvr.role || ':' || r.success || ':' || r.verified, ' | ') " +
                            " FROM pipeline_version_runs pvr JOIN runs r ON r.id=pvr.run_id " +
                            " WHERE pvr.pipeline_version_id=p.target_version_id) evidence " +
                            "FROM proposals p ORDER BY p.id DESC LIMIT 30",
                    ),
                    db.rows("SELECT id,run_id,role,kind,substr(content,1,200) content FROM messages ORDER BY id DESC LIMIT 80"),
                )
            }
            runs = loaded.first
            proposals = loaded.second
            messages = loaded.third.reversed()
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
                    previewPort = previewPort,
                    resolution = resolution,
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
                AssistantTab.LOGS -> LogsTab(messages)
                AssistantTab.RUNS -> RunsTab(runs)
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
}

@Composable
private fun AssistantTabContent(
    db: BrainDb,
    previewPort: PreviewPort,
    resolution: com.aliothmoon.maafw.runner.ResolutionPreference,
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
        Card(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
            MaaPreviewSurface(
                resolution = resolution.resolution,
                onSurfaceCreated = {},
                onSurfaceAvailable = { surface -> previewPort.attachSurface(surface) },
                onSurfaceDestroyed = { previewPort.detachSurface() },
                modifier = Modifier.fillMaxSize(),
            )
        }
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
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            MaaButton(onClick = onRun, enabled = !busy) { Text(if (busy) "Running…" else "Run") }
            MaaOutlinedButton(onClick = onMaintain, enabled = !busy) { Text("Replay + improve") }
            MaaOutlinedButton(onClick = onStop, enabled = busy) { Text("Stop") }
            MaaOutlinedButton(onClick = onImport) { Text("Import pipeline") }
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
private fun LogsTab(messages: List<JSONObject>) {
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(messages) { message ->
            val kind = message.optString("kind")
            val isError = message.optString("content").startsWith("Failed") ||
                kind == "result" && message.optString("content").contains("failed", ignoreCase = true)
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (isError) MaterialTheme.colorScheme.errorContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(Modifier.padding(10.dp)) {
                    Text(
                        "#${message.optLong("id")} · run=${message.optLong("run_id")} · " +
                            "${message.optString("role")}/${kind}",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(message.optString("content"), style = MaterialTheme.typography.bodySmall)
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
                            " · verified=${run.optInt("verified") == 1} · ${run.optLong("duration_ms")}ms",
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
