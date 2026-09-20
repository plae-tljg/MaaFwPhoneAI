package com.aliothmoon.maafw.brain

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/**
 * Postcondition parsing contract. These tests use a fake recognition source,
 * so they run without a device and without Android's stubbed BitmapFactory.
 */
class VerifierTest {

    @Test
    fun bareMaaFwRecognitionMapBecomesApplicable() = runBlocking {
        var usedType = ""
        var usedParams: JSONObject? = null
        val evidence = Verifier.Evidence(
            recognize = { type, params ->
                usedType = type
                usedParams = params
                JSONObject()
                    .put("hit", true)
                    .put("box", JSONArray(listOf(1, 2, 3, 4)))
                    .put("algorithm", "OCR")
                    .put("detail", """{"text":"闹钟"}""")
            },
        )
        val outcome = Verifier.verify(
            evidence,
            """{"node":"VerifyClockHome","recognition":"OCR",
                "expected":["闹钟","时钟"],"roi":[0,0,1280,720],"required_hit":true}""",
        )
        assertTrue(outcome.applicable)
        assertTrue(outcome.ok)
        assertTrue(outcome.evidenceJson.contains("闹钟"))
        assertTrue(usedType == "OCR")
        assertTrue(usedParams?.optJSONArray("expected")?.optString(0) == "闹钟")
    }

    @Test
    fun screenTextUsesOcrExpected() = runBlocking {
        var params: JSONObject? = null
        val evidence = Verifier.Evidence(
            recognize = { _, value ->
                params = value
                JSONObject().put("hit", true)
            },
        )
        val outcome = Verifier.verify(
            evidence,
            """{"type":"screen_text","text":"闹钟","roi":[0,0,1080,2400]}""",
        )
        assertTrue(outcome.applicable && outcome.ok)
        assertTrue(params?.optJSONArray("expected")?.optString(0) == "闹钟")
        assertTrue(params?.optJSONArray("roi") != null)
    }

    @Test
    fun unsupportedPostconditionIsSkippedNotFalsePassed() = runBlocking {
        val outcome = Verifier.verify(Verifier.Evidence(), """{"type":"OCRHit","node":"X"}""")
        assertFalse(outcome.applicable)
        assertTrue(outcome.skipped)
    }

    @Test
    fun fileCountUsesPreRunBaseline() = runBlocking {
        val dir = Files.createTempDirectory("maa-verifier").toFile()
        dir.resolve("one.txt").writeText("1")
        dir.resolve("two.txt").writeText("2")
        val outcome = Verifier.verify(
            Verifier.Evidence(fileCountBefore = 1),
            """{"type":"file_count","path":"${dir.absolutePath}","delta_min":1}""",
        )
        assertTrue(outcome.applicable)
        assertTrue(outcome.ok)
        assertTrue(outcome.evidenceJson.contains("\"after\":2"))
        dir.deleteRecursively()
        Unit
    }
}
