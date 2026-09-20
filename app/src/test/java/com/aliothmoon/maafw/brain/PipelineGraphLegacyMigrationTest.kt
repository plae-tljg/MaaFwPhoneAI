package com.aliothmoon.maafw.brain

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Contract test for the one-time legacy graph repair. The runtime compiler is
 * native-only, so nested `{action:{type,param}}` rows from old proposals must
 * be flattened by the DB migration, not at execution time.
 */
class PipelineGraphLegacyMigrationTest {

    @Test
    fun flattensNestedActionAndRecognition() {
        val legacy = JSONObject()
            .put(
                "OpenClockEntry",
                JSONObject()
                    .put("action", JSONObject().put("type", "DoNothing"))
                    .put("next", JSONArray(listOf("OpenClockLaunch"))),
            )
            .put(
                "OpenClockLaunch",
                JSONObject()
                    .put(
                        "action",
                        JSONObject()
                            .put("type", "StartApp")
                            .put("param", JSONObject().put("package", "com.android.deskclock")),
                    )
                    .put("next", JSONArray(listOf("OpenClockVerify"))),
            )
            .put(
                "OpenClockVerify",
                JSONObject().put(
                    "recognition",
                    JSONObject()
                        .put("type", "OCR")
                        .put(
                            "param",
                            JSONObject()
                                .put("expected", JSONArray(listOf("闹钟", "Clock")))
                                .put("roi", JSONArray(listOf(0, 0, 1280, 720))),
                        ),
                ),
            )

        val native = PipelineGraph.migrateDefinition(legacy.toString())
        assertNotNull(native)
        val graph = native!!.graph
        assertEquals("DoNothing", graph.getJSONObject("OpenClockEntry").getString("action"))
        assertEquals(
            "StartApp",
            graph.getJSONObject("OpenClockLaunch").getString("action"),
        )
        assertEquals(
            "com.android.deskclock",
            graph.getJSONObject("OpenClockLaunch").getString("package"),
        )
        assertEquals(
            "OCR",
            graph.getJSONObject("OpenClockVerify").getString("recognition"),
        )
        assertEquals(
            "闹钟",
            graph.getJSONObject("OpenClockVerify").getJSONArray("expected").getString(0),
        )
        assertNull(PipelineGraph.validate(graph))
    }

    @Test
    fun keepsAlreadyNativeGraphUnchanged() {
        val native = JSONObject()
            .put(
                "Launch",
                JSONObject()
                    .put("recognition", "DirectHit")
                    .put("action", "StartApp")
                    .put("package", "com.android.deskclock")
                    .put("next", JSONArray(listOf("Verify"))),
            )
            .put(
                "Verify",
                JSONObject()
                    .put("recognition", "OCR")
                    .put("expected", JSONArray(listOf("闹钟")))
                    .put("action", "DoNothing"),
            )

        val migrated = PipelineGraph.migrateDefinition(native.toString())
        assertNotNull(migrated)
        assertEquals("Launch", migrated!!.entry)
        assertEquals("StartApp", migrated.graph.getJSONObject("Launch").getString("action"))
        assertEquals(
            "DirectHit",
            migrated.graph.getJSONObject("Launch").getString("recognition"),
        )
    }
}
