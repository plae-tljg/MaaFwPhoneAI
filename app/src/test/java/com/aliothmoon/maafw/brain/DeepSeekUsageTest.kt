package com.aliothmoon.maafw.brain

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class DeepSeekUsageTest {

    @Test
    fun totalTokensWinsWhenProviderSendsIt() {
        val data = JSONObject()
            .put("usage", JSONObject().put("prompt_tokens", 10).put("completion_tokens", 5).put("total_tokens", 42))
        assertEquals(42, usageTokensOf(data))
    }

    @Test
    fun promptAndCompletionAreSummedForOpenAiCompatibleGateways() {
        val data = JSONObject()
            .put("usage", JSONObject().put("prompt_tokens", 10).put("completion_tokens", 5))
        assertEquals(15, usageTokensOf(data))
    }

    @Test
    fun missingUsageIsZero() {
        assertEquals(0, usageTokensOf(JSONObject()))
        assertEquals(0, usageTokensOf(JSONObject().put("choices", org.json.JSONArray())))
    }
}
