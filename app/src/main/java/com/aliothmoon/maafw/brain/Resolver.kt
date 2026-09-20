package com.aliothmoon.maafw.brain

import org.json.JSONObject

/** Deterministic-first goal resolution (M1 subset of resolver.py). */
object Resolver {

    fun normalize(text: String): String = text.lowercase().replace(Regex("\\s+"), "")

    fun score(goal: String, alias: String): Int {
        val g = normalize(goal)
        val a = normalize(alias)
        if (g.isEmpty() || a.isEmpty()) return 0
        if (g == a) return 100
        if (g.contains(a) || a.contains(g)) return 70
        val common = g.toSet().intersect(a.toSet()).size
        val denom = maxOf(g.toSet().size, a.toSet().size, 1)
        return (common.toDouble() / denom * 50).toInt()
    }

    fun resolve(db: BrainDb, goal: String, threshold: Int = 65): Pair<PipelineRow, Int>? {
        var best: PipelineRow? = null
        var bestScore = 0
        for (row in db.rows("SELECT * FROM pipelines WHERE status='live'")) {
            val aliases = parseAliases(row.optString("aliases")) + listOf(row.optString("goal"))
            for (alias in aliases) {
                val value = score(goal, alias)
                if (value > bestScore) {
                    bestScore = value
                    best = PipelineRow(
                        id = row.getLong("id"),
                        appId = row.optLong("app_id").takeIf { !row.isNull("app_id") },
                        name = row.optString("name"),
                        goal = row.optString("goal"),
                        aliases = parseAliases(row.optString("aliases")),
                        definitionJson = row.optString("definition_json", "[]"),
                        autonomy = row.optString("autonomy", "confirm"),
                        entry = row.optString("entry", ""),
                        postconditionJson = row.optString("postcondition_json", "{}"),
                    )
                }
            }
        }
        if (best == null || bestScore < threshold) return null
        return best to bestScore
    }
}

private fun JSONObject.optLongOrNull(key: String): Long? = if (has(key) && !isNull(key)) optLong(key) else null
