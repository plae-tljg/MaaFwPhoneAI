package com.aliothmoon.maafw.brain

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

/**
 * App-process SQLite over schema.sql (assets/brain/schema.sql).
 *
 * M1 uses the same tables as the PC prototype; the Android fork later swaps
 * this for Room without changing the schema.
 */
class BrainDb(context: Context) : SQLiteOpenHelper(context.applicationContext, DB_NAME, null, DB_VERSION) {

    private val assets = context.applicationContext.assets
    private val appContext = context.applicationContext

    override fun onCreate(db: SQLiteDatabase) {
        val script = assets.open("brain/schema.sql").bufferedReader().use { it.readText() }
        script.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("--") }
            .joinToString("\n")
            .split(';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .forEach { db.execSQL(it) }
        seed(db)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        // mission_items / pipeline_version_runs declare ON DELETE CASCADE and
        // are only useful if SQLite enforces them.
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v3 fixes legacy nested action/recognition maps that the v2 migration
        // copied verbatim. Migration is still the only place this conversion
        // happens; the runtime compiler remains native-only.
        if (oldVersion < 3) migrateToNativeGraph(db)
        if (oldVersion < 4) createMissionQueue(db)
    }

    private fun createMissionQueue(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS mission_queue (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "mission_id INTEGER NOT NULL REFERENCES missions(id) ON DELETE CASCADE," +
                "mission_item_id INTEGER NOT NULL REFERENCES mission_items(id) ON DELETE CASCADE," +
                "position INTEGER NOT NULL DEFAULT 0," +
                "state TEXT NOT NULL DEFAULT 'queued'," +
                "run_id INTEGER REFERENCES runs(id)," +
                "error TEXT NOT NULL DEFAULT ''," +
                "created_at TEXT NOT NULL DEFAULT (datetime('now'))," +
                "updated_at TEXT NOT NULL DEFAULT (datetime('now'))" +
                ")",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS idx_mission_queue_mission ON mission_queue(mission_id, position)",
        )
    }

    /**
     * One-time migration from the old mixed storage (linear step arrays /
     * {pipeline,node} arrays) to the native MaaFW graph contract. The runtime
     * compiler and future writers only ever see native graphs.
     */
    private fun migrateToNativeGraph(db: SQLiteDatabase) {
        val elementsByApp = mutableMapOf<Long, JSONObject>()
        db.rawQuery("SELECT app_id,name,locator_json FROM elements", null).use { c ->
            while (c.moveToNext()) {
                if (c.isNull(0)) continue
                val appId = c.getLong(0)
                val map = elementsByApp.getOrPut(appId) { JSONObject() }
                val locator = runCatching { JSONObject(c.getString(2) ?: "{}") }.getOrDefault(JSONObject())
                map.put(c.getString(1) ?: "", locator)
            }
        }

        fun migrateDefinitionTable(selectSql: String, table: String) {
            val updates = mutableListOf<Triple<Long, String, String>>()
            db.rawQuery(selectSql, null).use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val appId = if (c.isNull(1)) null else c.getLong(1)
                    val current = c.getString(2) ?: continue
                    val native = PipelineGraph.migrateDefinition(
                        current,
                        appId?.let { elementsByApp[it] } ?: JSONObject(),
                    ) ?: continue
                    updates += Triple(id, native.graph.toString(), native.entry)
                }
            }
            for ((id, definition, entry) in updates) {
                db.execSQL(
                    "UPDATE $table SET definition_json=?, entry=? WHERE id=?",
                    arrayOf<Any?>(definition, entry, id),
                )
            }
        }

        migrateDefinitionTable(
            "SELECT id,app_id,definition_json FROM pipelines",
            "pipelines",
        )
        migrateDefinitionTable(
            "SELECT v.id,p.app_id,v.definition_json FROM pipeline_versions v " +
                "LEFT JOIN pipelines p ON p.id=v.pipeline_id",
            "pipeline_versions",
        )

        val proposalUpdates = mutableListOf<Pair<Long, String>>()
        db.rawQuery("SELECT id,candidate_json FROM proposals", null).use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val raw = c.getString(1) ?: continue
                val candidate = runCatching { JSONObject(raw) }.getOrNull() ?: continue
                val pipeline = candidate.optJSONObject("pipeline") ?: continue
                val currentGraph = pipeline.optJSONObject("graph")
                if (currentGraph != null && PipelineGraph.validate(currentGraph) == null) {
                    if (pipeline.optString("entry").isBlank()) {
                        pipeline.put("entry", PipelineGraph.deriveEntry(currentGraph))
                    }
                    proposalUpdates += id to candidate.toString()
                    continue
                }
                val rawDefinition = pipeline.optJSONObject("graph")?.toString()
                    ?: pipeline.optJSONArray("steps")?.toString()
                    ?: continue
                val native = PipelineGraph.migrateDefinition(
                    rawDefinition,
                    candidate.optJSONObject("elements") ?: JSONObject(),
                ) ?: continue
                pipeline.remove("steps")
                pipeline.put("graph", native.graph)
                pipeline.put("entry", native.entry)
                proposalUpdates += id to candidate.toString()
            }
        }
        for ((id, json) in proposalUpdates) {
            db.execSQL("UPDATE proposals SET candidate_json=? WHERE id=?", arrayOf<Any?>(json, id))
        }
    }

    /** Idempotent knowledge hints and defaults, also applied to existing databases. */
    fun seedHints() {
        writableDatabase.execSQL(
            "INSERT OR IGNORE INTO settings(key,value) VALUES('agent_max_steps','300')",
        )
        writableDatabase.execSQL(
            "INSERT OR IGNORE INTO settings(key,value) VALUES('agent_repeat_limit','5')",
        )
        writableDatabase.execSQL(
            "INSERT OR REPLACE INTO settings(key,value) VALUES(?,?)",
            arrayOf(
                "hint:tv.danmaku.bili",
                "Video page: the like button is the leftmost thumbs-up icon in the bottom action bar; " +
                    "pink/red means liked, gray means not. Use {\"action\":\"locate\",\"description\":" +
                    "\"the like thumbs-up button in the bottom action bar\"} to tap it precisely. Tap once, " +
                    "wait, verify it is pink before done; never tap it twice or repeat the same point.",
            ),
        )
    }

    private fun seed(db: SQLiteDatabase) {
        val count = db.rawQuery("SELECT COUNT(*) FROM pipelines", null).use {
            it.moveToFirst(); it.getInt(0)
        }
        if (count > 0) return
        db.execSQL(
            "INSERT INTO apps(package_name,name,aliases) VALUES(?,?,?)",
            arrayOf<Any?>("com.android.settings", "设置", JSONArray(listOf("settings", "Settings")).toString()),
        )
        db.execSQL(
            "INSERT INTO apps(package_name,name,aliases) VALUES(?,?,?)",
            arrayOf<Any?>("tv.danmaku.bili", "哔哩哔哩", JSONArray(listOf("bilibili", "B站", "b站")).toString()),
        )
        val apps = queryRows(db, "SELECT id,package_name FROM apps")
        val settingsId = apps.first { it.getString("package_name") == "com.android.settings" }.getLong("id")
        val graph = JSONObject().put(
            "open_settings_launch",
            JSONObject()
                .put("recognition", "DirectHit")
                .put("action", "StartApp")
                .put("package", "com.android.settings")
                .put("post_delay", 2000),
        )
        db.execSQL(
            "INSERT INTO pipelines(app_id,name,goal,aliases,definition_json,entry,status,autonomy) " +
                "VALUES(?,?,?,?,?,?,'live','auto')",
            arrayOf<Any?>(
                settingsId,
                "open_settings",
                "open settings",
                JSONArray(listOf("open settings", "打开设置", "设置")).toString(),
                graph.toString(),
                "open_settings_launch",
            ),
        )
        // Leave deepseek_model unset: DeepSeekClient picks deepseek-chat for
        // api.deepseek.com and the packaged gateway default otherwise.
    }

    fun rows(sql: String, args: Array<out Any?> = emptyArray()): List<JSONObject> =
        queryRows(readableDatabase, sql, args)

    fun exec(sql: String, args: Array<out Any?> = emptyArray()) {
        writableDatabase.execSQL(sql, args)
    }

    fun setting(key: String, default: String = ""): String =
        rows("SELECT value FROM settings WHERE key=?", arrayOf(key))
            .firstOrNull()?.optString("value") ?: default

    fun setSetting(key: String, value: String) {
        exec(
            "INSERT INTO settings(key,value) VALUES(?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value",
            arrayOf(key, value),
        )
    }

    fun startRun(goal: String, path: String = "pipeline"): Long {
        val values = ContentValues().apply {
            put("goal", goal)
            put("path", path)
            put("state", "running")
            put("engine", "maa")
            put("source", "assistant")
        }
        val id = writableDatabase.insert("runs", null, values)
        writableDatabase.insert("messages", null, ContentValues().apply {
            put("run_id", id)
            put("role", "user")
            put("kind", "goal")
            put("content", goal)
            put("source", "assistant")
        })
        return id
    }

    fun finishRun(runId: Long, success: Boolean, error: String = "", durationMs: Long = 0,
                  verified: Boolean? = null, verifyJson: String = "{}",
                  stateOverride: String? = null, aiCost: Int = 0) {
        val state = stateOverride ?: if (success) "done" else "failed"
        exec(
            "UPDATE runs SET success=?, error=?, duration_ms=?, state=?, ai_cost=?, verified=?, verify_json=?, " +
                "finished_at=datetime('now') WHERE id=?",
            arrayOf<Any?>(
                if (success) 1 else 0, error, durationMs,
                state, aiCost.coerceAtLeast(0),
                if (verified == true) 1 else 0, verifyJson, runId,
            ),
        )
        writableDatabase.insert("messages", null, ContentValues().apply {
            put("run_id", runId)
            put("role", "assistant")
            put("kind", "result")
            put("content", when {
                success -> "Done"
                state == "cancelled" -> "Cancelled: $error"
                else -> "Failed: $error"
            })
            put("source", "assistant")
        })
    }

    fun cancelRun(runId: Long, reason: String = "cancelled by user") {
        exec(
            "UPDATE runs SET success=0, state='cancelled', error=?, finished_at=datetime('now') WHERE id=?",
            arrayOf<Any?>(reason, runId),
        )
        writableDatabase.insert("messages", null, ContentValues().apply {
            put("run_id", runId)
            put("role", "assistant")
            put("kind", "result")
            put("content", "Cancelled: $reason")
            put("source", "assistant")
        })
    }

    fun recentRuns(limit: Int = 20): List<JSONObject> =
        rows("SELECT id,goal,path,success,error,state FROM runs ORDER BY id DESC LIMIT ?", arrayOf<Any?>(limit))

    private fun queryRows(
        db: SQLiteDatabase,
        sql: String,
        args: Array<out Any?> = emptyArray(),
    ): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        val bind = args.map { it?.toString() ?: "" }.toTypedArray()
        db.rawQuery(sql, bind).use { c ->
            val columns = c.columnNames
            while (c.moveToNext()) {
                val obj = JSONObject()
                for (i in columns.indices) {
                    when (c.getType(i)) {
                        Cursor.FIELD_TYPE_NULL -> obj.put(columns[i], JSONObject.NULL)
                        Cursor.FIELD_TYPE_INTEGER -> obj.put(columns[i], c.getLong(i))
                        Cursor.FIELD_TYPE_FLOAT -> obj.put(columns[i], c.getDouble(i))
                        else -> obj.put(columns[i], c.getString(i))
                    }
                }
                out += obj
            }
        }
        return out
    }


    /**
     * Index launchable apps from PackageManager into the `apps` registry.
     * This is the MaaFwApp/MAA-Meow approach: resolve a package and launch it
     * directly with StartApp instead of driving the home screen/app drawer.
     */
    fun syncInstalledApps(): Int {
        val pm = appContext.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolves = runCatching { pm.queryIntentActivities(intent, 0) }.getOrDefault(emptyList())
        val seen = mutableSetOf<String>()
        var added = 0
        for (resolve in resolves) {
            val pkg = resolve.activityInfo?.packageName ?: continue
            if (!seen.add(pkg)) continue
            val label = runCatching { resolve.loadLabel(pm).toString() }.getOrDefault(pkg)
                .ifBlank { pkg }
            val existing = rows(
                "SELECT id,name FROM apps WHERE package_name=?",
                arrayOf<Any?>(pkg),
            ).firstOrNull()
            if (existing == null) {
                runCatching {
                    writableDatabase.insertOrThrow("apps", null, ContentValues().apply {
                        put("package_name", pkg)
                        put("name", label)
                        put("aliases", JSONArray(listOf(label)).toString())
                        put("active", 1)
                    })
                    added++
                }
            } else if (existing.optString("name").isBlank()) {
                exec("UPDATE apps SET name=? WHERE package_name=?", arrayOf<Any?>(label, pkg))
            }
        }
        return added
    }

    fun ensureApp(packageName: String, name: String = ""): Long {
        rows("SELECT id FROM apps WHERE package_name=?", arrayOf<Any?>(packageName)).firstOrNull()?.let {
            return it.getLong("id")
        }
        writableDatabase.insert("apps", null, ContentValues().apply {
            put("package_name", packageName)
            put("name", name.ifBlank { packageName })
            put("aliases", "[]")
        })
        return rows("SELECT id FROM apps WHERE package_name=?", arrayOf<Any?>(packageName))
            .first().getLong("id")
    }

    fun addMessage(
        runId: Long?,
        role: String,
        kind: String,
        content: String,
        payloadJson: String = "{}",
        state: String = "sent",
    ): Long = writableDatabase.insert("messages", null, ContentValues().apply {
        put("run_id", runId)
        put("role", role)
        put("kind", kind)
        put("content", content)
        put("payload_json", payloadJson)
        put("state", state)
        put("source", "assistant")
    })

    fun setRunState(runId: Long, state: String) {
        exec("UPDATE runs SET state=? WHERE id=?", arrayOf<Any?>(state, runId))
    }

    /** Mark an agent question answered/expired/dismissed and persist the answer. */
    fun updateMessageState(messageId: Long, state: String, answer: String = "") {
        val payload = rows(
            "SELECT payload_json FROM messages WHERE id=?",
            arrayOf<Any?>(messageId),
        ).firstOrNull()?.optString("payload_json")
            ?.takeIf { it.isNotBlank() && it != "null" }
            ?.let { raw -> runCatching { JSONObject(raw) }.getOrNull() }
            ?: JSONObject()
        if (answer.isNotBlank()) payload.put("answer", answer)
        exec(
            "UPDATE messages SET state=?, answered_at=datetime('now'), payload_json=? WHERE id=?",
            arrayOf<Any?>(state, payload.toString(), messageId),
        )
    }

    /** Startup/reconnect support: the newest unresolved agent question for a run. */
    fun pendingQuestionForRun(runId: Long): JSONObject? =
        rows(
            "SELECT id,content,payload_json FROM messages " +
                "WHERE run_id=? AND kind='question' AND state='pending' ORDER BY id DESC LIMIT 1",
            arrayOf<Any?>(runId),
        ).firstOrNull()

    fun setRunSteps(runId: Long, stepsJson: String) {
        exec("UPDATE runs SET steps_json=? WHERE id=?", arrayOf<Any?>(stepsJson, runId))
    }

    fun setRunProgress(runId: Long, progressJson: String) {
        exec("UPDATE runs SET progress_json=? WHERE id=?", arrayOf<Any?>(progressJson, runId))
    }

    /**
     * Recovery query for the Assistant after process/Activity death: the
     * newest question that still has a live `needs_input` run behind it.
     */
    fun latestPendingQuestion(): JSONObject? =
        rows(
            "SELECT r.id run_id, r.goal, r.progress_json, " +
                "m.id message_id, m.content question, m.payload_json " +
                "FROM runs r JOIN messages m ON m.run_id=r.id " +
                "WHERE r.state='needs_input' AND m.kind='question' AND m.state='pending' " +
                "ORDER BY m.id DESC LIMIT 1",
        ).firstOrNull()

    fun insertProposal(
        kind: String,
        candidateJson: String,
        sourceRunId: Long?,
        targetPipelineId: Long? = null,
        targetVersionId: Long? = null,
    ): Long = writableDatabase.insert("proposals", null, ContentValues().apply {
        put("kind", kind)
        put("candidate_json", candidateJson)
        put("source_run_id", sourceRunId)
        if (targetPipelineId != null) put("target_pipeline_id", targetPipelineId)
        if (targetVersionId != null) put("target_version_id", targetVersionId)
        put("status", "pending")
    })

    // ---- pipelines / versions -------------------------------------------------

    fun ensurePipeline(
        goal: String,
        appId: Long?,
        name: String,
        source: String = "bootstrap",
        aliasesJson: String = "[]",
    ): Long {
        val existing = rows(
            "SELECT id FROM pipelines WHERE goal=? AND COALESCE(app_id,-1)=CAST(? AS INTEGER) " +
                "AND status IN ('draft','live') " +
                "ORDER BY CASE status WHEN 'live' THEN 0 ELSE 1 END, id LIMIT 1",
            arrayOf<Any?>(goal, appId ?: -1L),
        ).firstOrNull()
        if (existing != null) return existing.getLong("id")
        return writableDatabase.insert("pipelines", null, ContentValues().apply {
            if (appId != null) put("app_id", appId)
            put("name", name.ifBlank { "pipeline" })
            put("goal", goal)
            put("aliases", aliasesJson)
            put("definition_json", "{}")
            put("status", "draft")
            put("source", source)
        })
    }

    fun insertPipelineVersion(
        pipelineId: Long,
        definitionJson: String,
        entry: String = "",
        postconditionJson: String = "{}",
        source: String = "ai",
        sourceRunId: Long? = null,
        evidenceJson: String = "{}",
    ): Long {
        val next = rows(
            "SELECT COALESCE(MAX(version),0)+1 AS next_version FROM pipeline_versions WHERE pipeline_id=?",
            arrayOf<Any?>(pipelineId),
        ).first().getLong("next_version")
        val versionId = writableDatabase.insert("pipeline_versions", null, ContentValues().apply {
            put("pipeline_id", pipelineId)
            put("version", next)
            put("entry", entry)
            put("definition_json", definitionJson)
            put("postcondition_json", postconditionJson)
            put("status", "candidate")
            put("source", source)
            if (sourceRunId != null) put("source_run_id", sourceRunId)
            put("evidence_json", evidenceJson)
        })
        if (sourceRunId != null) {
            writableDatabase.insertWithOnConflict(
                "pipeline_version_runs", null,
                ContentValues().apply {
                    put("pipeline_version_id", versionId)
                    put("run_id", sourceRunId)
                    put("role", "source")
                },
                SQLiteDatabase.CONFLICT_IGNORE,
            )
        }
        return versionId
    }

    /** Link a run that is evidence for a candidate version (source|replay|evidence). */
    fun linkVersionRun(versionId: Long, runId: Long, role: String = "evidence") {
        writableDatabase.insertWithOnConflict(
            "pipeline_version_runs", null,
            ContentValues().apply {
                put("pipeline_version_id", versionId)
                put("run_id", runId)
                put("role", role)
            },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
    }

    fun updateVersionCandidate(
        versionId: Long,
        definitionJson: String,
        entry: String,
        postconditionJson: String,
    ) {
        exec(
            "UPDATE pipeline_versions SET definition_json=?, entry=?, postcondition_json=? " +
                "WHERE id=? AND status='candidate'",
            arrayOf<Any?>(definitionJson, entry, postconditionJson, versionId),
        )
    }

    fun approvePipelineVersion(versionId: Long): String {
        val version = rows("SELECT * FROM pipeline_versions WHERE id=?", arrayOf<Any?>(versionId))
            .firstOrNull() ?: return "version $versionId not found"
        if (version.optString("status") != "candidate") {
            return "version $versionId is ${version.optString("status")}, not candidate"
        }
        val pipelineId = version.getLong("pipeline_id")
        exec(
            "UPDATE pipeline_versions SET status='superseded', reviewed_at=datetime('now') " +
                "WHERE pipeline_id=? AND status='approved'",
            arrayOf<Any?>(pipelineId),
        )
        exec(
            "UPDATE pipeline_versions SET status='approved', reviewed_at=datetime('now') WHERE id=?",
            arrayOf<Any?>(versionId),
        )
        exec(
            "UPDATE pipelines SET entry=?, definition_json=?, postcondition_json=?, " +
                "status='live', updated_at=datetime('now') WHERE id=?",
            arrayOf<Any?>(
                version.optString("entry"),
                version.optString("definition_json", "{}"),
                version.optString("postcondition_json", "{}"),
                pipelineId,
            ),
        )
        return "pipeline version $versionId approved"
    }

    fun rejectPipelineVersion(versionId: Long): String {
        exec(
            "UPDATE pipeline_versions SET status='rejected', reviewed_at=datetime('now') " +
                "WHERE id=? AND status='candidate'",
            arrayOf<Any?>(versionId),
        )
        return "pipeline version $versionId rejected"
    }

    fun candidateVersions(limit: Int = 50): List<JSONObject> =
        rows(
            "SELECT v.*, p.name AS pipeline_name, p.goal AS pipeline_goal " +
                "FROM pipeline_versions v JOIN pipelines p ON p.id=v.pipeline_id " +
                "WHERE v.status='candidate' ORDER BY v.id DESC LIMIT ?",
            arrayOf<Any?>(limit),
        )

    /**
     * Human-facing pipeline library: every non-archived pipeline plus its
     * current approved version and newest run evidence. Rendering these rows
     * is what makes an approved pipeline discoverable and runnable again;
     * without it the only path was creating a mission first.
     */
    fun pipelineLibrary(limit: Int = 100): List<JSONObject> =
        rows(
            "SELECT p.id, p.app_id, p.name, p.goal, p.status, p.source, p.autonomy, " +
                "p.entry, p.postcondition_json, p.success, p.fail, p.last_run_at, " +
                "a.name AS app_name, a.package_name AS app_package, " +
                "v.id AS version_id, v.version AS version, v.status AS version_status, " +
                "r.id AS last_run_id, r.state AS last_run_state, " +
                "r.success AS last_run_success, r.verified AS last_run_verified, " +
                "r.error AS last_run_error " +
                "FROM pipelines p " +
                "LEFT JOIN apps a ON a.id=p.app_id " +
                "LEFT JOIN pipeline_versions v ON v.id=(" +
                "  SELECT v2.id FROM pipeline_versions v2 WHERE v2.pipeline_id=p.id " +
                "  ORDER BY CASE v2.status WHEN 'approved' THEN 0 ELSE 1 END, v2.version DESC LIMIT 1" +
                ") " +
                "LEFT JOIN runs r ON r.id=(" +
                "  SELECT r2.id FROM runs r2 WHERE r2.pipeline_id=p.id ORDER BY r2.id DESC LIMIT 1" +
                ") " +
                "WHERE p.status IN ('live', 'draft', 'broken') " +
                "ORDER BY CASE p.status WHEN 'live' THEN 0 WHEN 'draft' THEN 1 ELSE 2 END, " +
                "p.updated_at DESC, p.id DESC LIMIT ?",
            arrayOf<Any?>(limit),
        )

    // ---- missions -------------------------------------------------------------

    fun createMission(name: String, description: String = ""): Long =
        writableDatabase.insert("missions", null, ContentValues().apply {
            put("name", name)
            put("description", description)
        })

    fun addMissionItem(
        missionId: Long,
        pipelineId: Long?,
        goal: String,
        overridesJson: String = "{}",
        position: Long? = null,
        pipelineVersionId: Long? = null,
    ): Long {
        val pos = position ?: rows(
            "SELECT COALESCE(MAX(position),0)+1 AS next_position FROM mission_items WHERE mission_id=?",
            arrayOf<Any?>(missionId),
        ).first().getLong("next_position")
        return writableDatabase.insert("mission_items", null, ContentValues().apply {
            put("mission_id", missionId)
            put("position", pos)
            if (pipelineId != null) put("pipeline_id", pipelineId)
            if (pipelineVersionId != null) put("pipeline_version_id", pipelineVersionId)
            put("goal", goal)
            put("overrides_json", overridesJson)
        })
    }

    fun missions(): List<JSONObject> =
        rows(
            "SELECT m.*, " +
                "(SELECT COUNT(*) FROM mission_items mi WHERE mi.mission_id=m.id) item_count " +
                "FROM missions m ORDER BY m.enabled DESC, m.id DESC",
        )

    fun missionItems(missionId: Long): List<JSONObject> =
        rows(
            "SELECT mi.*, p.name pipeline_name, p.status pipeline_status, " +
                "r.id last_run_id, r.state last_run_state, r.success last_run_success, " +
                "r.verified last_run_verified, r.error last_run_error " +
                "FROM mission_items mi " +
                "LEFT JOIN pipelines p ON p.id=mi.pipeline_id " +
                "LEFT JOIN runs r ON r.id=(" +
                "  SELECT r2.id FROM runs r2 WHERE r2.mission_item_id=mi.id ORDER BY r2.id DESC LIMIT 1" +
                ") " +
                "WHERE mi.mission_id=? ORDER BY mi.position, mi.id",
            arrayOf<Any?>(missionId),
        )

    /** Replace the persisted queue for a mission with its currently enabled items. */
    fun enqueueMission(missionId: Long): Int {
        writableDatabase.beginTransaction()
        try {
            exec("DELETE FROM mission_queue WHERE mission_id=?", arrayOf<Any?>(missionId))
            val items = missionItems(missionId).filter { it.optInt("enabled", 1) == 1 }
            var position = 0
            for (item in items) {
                writableDatabase.insert("mission_queue", null, ContentValues().apply {
                    put("mission_id", missionId)
                    put("mission_item_id", item.optLong("id"))
                    put("position", position++)
                    put("state", "queued")
                })
            }
            writableDatabase.setTransactionSuccessful()
            return items.size
        } finally {
            writableDatabase.endTransaction()
        }
    }

    fun missionQueue(missionId: Long): List<JSONObject> =
        rows(
            "SELECT q.*, mi.goal, mi.pipeline_id, p.name pipeline_name " +
                "FROM mission_queue q " +
                "LEFT JOIN mission_items mi ON mi.id=q.mission_item_id " +
                "LEFT JOIN pipelines p ON p.id=mi.pipeline_id " +
                "WHERE q.mission_id=? ORDER BY q.position, q.id",
            arrayOf<Any?>(missionId),
        )

    fun setMissionQueueState(queueId: Long, state: String, runId: Long? = null, error: String = "") {
        exec(
            "UPDATE mission_queue SET state=?, run_id=COALESCE(?, run_id), error=?, " +
                "updated_at=datetime('now') WHERE id=?",
            arrayOf<Any?>(state, runId, error, queueId),
        )
    }

    fun clearMissionQueue(missionId: Long) {
        exec("DELETE FROM mission_queue WHERE mission_id=?", arrayOf<Any?>(missionId))
    }

    fun setMissionSchedule(missionId: Long, scheduleJson: String) {
        exec(
            "UPDATE missions SET schedule_json=?, updated_at=datetime('now') WHERE id=?",
            arrayOf<Any?>(scheduleJson, missionId),
        )
    }

    fun clearMissionSchedule(missionId: Long) {
        setMissionSchedule(missionId, "{}")
    }

    fun scheduledMissions(): List<JSONObject> =
        rows("SELECT id,name,schedule_json FROM missions WHERE schedule_json NOT IN ('', '{}')")

    fun deleteMission(missionId: Long) {
        exec("DELETE FROM missions WHERE id=?", arrayOf<Any?>(missionId))
    }

    fun deleteMissionItem(itemId: Long) {
        exec("DELETE FROM mission_items WHERE id=?", arrayOf<Any?>(itemId))
    }

    fun setMissionItemEnabled(itemId: Long, enabled: Boolean) {
        exec(
            "UPDATE mission_items SET enabled=?, updated_at=datetime('now') WHERE id=?",
            arrayOf<Any?>(if (enabled) 1 else 0, itemId),
        )
    }

    fun latestPendingProposal(): JSONObject? =
        rows("SELECT * FROM proposals WHERE status='pending' ORDER BY id DESC LIMIT 1").firstOrNull()

    fun updateProposalStatus(id: Long, status: String) {
        exec("UPDATE proposals SET status=?, reviewed_at=datetime('now') WHERE id=?", arrayOf<Any?>(status, id))
    }

    fun insertElement(appId: Long, name: String, locatorJson: String) {
        writableDatabase.insertWithOnConflict(
            "elements", null,
            ContentValues().apply {
                put("app_id", appId)
                put("name", name)
                put("locator_json", locatorJson)
                put("status", "live")
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun insertPipeline(
        appId: Long?, name: String, goal: String, aliasesJson: String,
        definitionJson: String, postconditionJson: String, entry: String = "",
    ): Long = writableDatabase.insert("pipelines", null, ContentValues().apply {
        put("app_id", appId)
        put("name", name)
        put("goal", goal)
        put("aliases", aliasesJson)
        put("definition_json", definitionJson)
        put("postcondition_json", postconditionJson)
        put("entry", entry)
        put("status", "live")
        put("autonomy", "confirm")
    })

    fun recentProposals(limit: Int = 10): List<JSONObject> =
        rows("SELECT id,kind,status,substr(candidate_json,1,120) candidate FROM proposals ORDER BY id DESC LIMIT ?", arrayOf<Any?>(limit))

    companion object {
        private const val DB_NAME = "brain.db"
        private const val DB_VERSION = 4
    }
}
