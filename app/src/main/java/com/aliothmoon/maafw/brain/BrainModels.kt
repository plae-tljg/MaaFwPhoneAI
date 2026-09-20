package com.aliothmoon.maafw.brain

import org.json.JSONArray

data class PipelineRow(
    val id: Long,
    val appId: Long?,
    val name: String,
    val goal: String,
    val aliases: List<String>,
    val definitionJson: String,
    val autonomy: String,
    val entry: String = "",
    val postconditionJson: String = "{}",
)

fun parseAliases(raw: String?): List<String> = try {
    val arr = JSONArray(raw ?: "[]")
    (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeIf { it.isNotBlank() } }
} catch (_: Exception) {
    emptyList()
}
