package com.aliothmoon.maafw.brain

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Reads the packaged Everything-Maa skill family index and loads only the
 * family that matches the current goal. This is deliberately small: the
 * Android bootstrap agent should not load all skills at once.
 */
object SkillLoader {

    private const val TAG = "BrainSkills"
    private const val INDEX_ASSET = "maa-skills/index.json"
    private const val MAX_GUIDANCE_CHARS = 8_000
    private const val MAX_PER_SKILL_CHARS = 2_200

    data class Loaded(
        val family: String,
        val skills: List<String>,
        val guidance: String,
        val source: String = INDEX_ASSET,
    ) {
        fun summary(): String {
            val list = if (skills.isEmpty()) "(none)" else skills.joinToString(", ")
            return "skills loaded: family=$family -> [$list] from $source" +
                if (guidance.isBlank()) " (no guidance text)" else " (${guidance.length} chars)"
        }
    }

    fun load(context: Context, goal: String): Loaded {
        val index = runCatching { JSONObject(readAsset(context, INDEX_ASSET)) }.getOrNull()
            ?: return Loaded("none", emptyList(), "", INDEX_ASSET)
        val families = index.optJSONArray("families") ?: JSONArray()
        val wanted = chooseFamily(goal)
        var chosen: JSONObject? = null
        for (i in 0 until families.length()) {
            val family = families.optJSONObject(i) ?: continue
            if (family.optString("id") == wanted) {
                chosen = family
                break
            }
        }
        if (chosen == null && families.length() > 0) chosen = families.optJSONObject(0)
        if (chosen == null) return Loaded("none", emptyList(), "", INDEX_ASSET)

        val familyId = chosen.optString("id", "unknown")
        val skillsArr = chosen.optJSONArray("skills") ?: JSONArray()
        val skills = (0 until skillsArr.length())
            .mapNotNull { skillsArr.optString(it).takeIf(String::isNotBlank) }

        val guidance = StringBuilder()
        guidance.append("Authoring skill family: ").append(familyId).append('\n')
        guidance.append("Load rule: use only these loaded skills for this task; do not assume other families.\n")
        for (skill in skills) {
            val text = runCatching { readAsset(context, "maa-skills/$skill/SKILL.md") }.getOrNull() ?: continue
            val cleaned = stripFrontMatter(text)
            guidance.append("\n--- skill: ").append(skill).append(" ---\n")
            guidance.append(cleaned.take(MAX_PER_SKILL_CHARS))
            guidance.append('\n')
            if (guidance.length >= MAX_GUIDANCE_CHARS) break
        }
        val result = guidance.toString().take(MAX_GUIDANCE_CHARS)
        Log.i(TAG, "loaded family=$familyId skills=${skills.joinToString(",")} chars=${result.length}")
        return Loaded(familyId, skills, result, INDEX_ASSET)
    }

    private fun chooseFamily(goal: String): String {
        val g = goal.lowercase()
        return when {
            listOf("debug", "diagnose", "failure", "failed", "error", "crash", "修复", "调试", "失败")
                .any { it in g } -> "diagnosis"
            listOf("test", "verify", "benchmark", "assert", "测试", "校验", "验证")
                .any { it in g } -> "pipeline-testing"
            listOf("build", "create", "design", "workflow", "state machine", "编写", "生成", "设计")
                .any { it in g } -> "workflow-design"
            else -> "pipeline-authoring"
        }
    }

    private fun readAsset(context: Context, path: String): String =
        context.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun stripFrontMatter(text: String): String {
        if (!text.startsWith("---")) return text
        val end = text.indexOf("\n---", startIndex = 3)
        return if (end >= 0) text.substring(end + 4).trimStart() else text
    }
}
