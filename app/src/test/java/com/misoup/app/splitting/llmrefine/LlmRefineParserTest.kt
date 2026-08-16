package com.misoup.app.splitting.llmrefine

import com.misoup.app.splitting.candidates.BoundingBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmRefineParserTest {

    @Test
    fun `parses merge split delete keep operations`() {
        val json = """
            {"operations": [
              {"op": "merge", "indices": [0, 2]},
              {"op": "split", "index": 1},
              {"op": "delete", "index": 3},
              {"op": "keep", "index": 0, "box": {"x": 1, "y": 2, "w": 100, "h": 50}}
            ]}
        """.trimIndent()
        val ops = LlmRefineParser.parse(json).getOrThrow()
        assertEquals(4, ops.size)
        assertTrue(ops[0] is RefineOp.Merge)
        assertEquals(listOf(0, 2), (ops[0] as RefineOp.Merge).indices)
        assertTrue(ops[1] is RefineOp.Split)
        assertEquals(1, (ops[1] as RefineOp.Split).index)
        assertTrue(ops[2] is RefineOp.Delete)
        assertEquals(3, (ops[2] as RefineOp.Delete).index)
        assertTrue(ops[3] is RefineOp.Keep)
        val keep = ops[3] as RefineOp.Keep
        assertEquals(0, keep.index)
        assertEquals(BoundingBox(1f, 2f, 100f, 50f), keep.box)
    }

    @Test
    fun `tolerates json code fence`() {
        val json = """
            ```json
            {"operations": [{"op": "delete", "index": 0}]}
            ```
        """.trimIndent()
        val ops = LlmRefineParser.parse(json).getOrThrow()
        assertEquals(listOf(RefineOp.Delete(0)), ops)
    }

    @Test
    fun `tolerates surrounding chatter text`() {
        val json = "好的，以下是建议：{\"operations\": [{\"op\": \"split\", \"index\": 1}]} 完毕"
        val ops = LlmRefineParser.parse(json).getOrThrow()
        assertEquals(listOf(RefineOp.Split(1)), ops)
    }

    @Test
    fun `unknown op and unknown fields are ignored`() {
        val json = """
            {"operations": [
              {"op": "frobnicate", "index": 9},
              {"op": "delete", "index": 2, "confidence": 0.9, "reason": "x"}
            ]}
        """.trimIndent()
        val ops = LlmRefineParser.parse(json).getOrThrow()
        assertEquals(listOf(RefineOp.Delete(2)), ops)
    }

    @Test
    fun `bad json returns failure`() {
        val result = LlmRefineParser.parse("这不是 JSON {{{")
        assertTrue(result.isFailure)
    }

    @Test
    fun `empty string returns failure`() {
        assertTrue(LlmRefineParser.parse("").isFailure)
        assertTrue(LlmRefineParser.parse("   ").isFailure)
    }

    @Test
    fun `missing operations array returns failure`() {
        assertTrue(LlmRefineParser.parse("{\"suggestions\": []}").isFailure)
    }

    @Test
    fun `empty operations array yields empty list`() {
        val ops = LlmRefineParser.parse("{\"operations\": []}").getOrThrow()
        assertTrue(ops.isEmpty())
    }

    @Test
    fun `missing index on delete is skipped tolerantly`() {
        // 单条 op 缺字段 → 该 op 忽略（容错解析，LLM 部分坏 op 不影响其余）
        val ops = LlmRefineParser.parse("{\"operations\": [{\"op\": \"delete\"}]}").getOrThrow()
        assertTrue(ops.isEmpty())
    }

    @Test
    fun `indices may be empty list on merge`() {
        val ops = LlmRefineParser.parse("{\"operations\": [{\"op\": \"merge\", \"indices\": []}]}").getOrThrow()
        assertEquals(listOf(RefineOp.Merge(emptyList())), ops)
    }

    @Test
    fun `keep without box keeps original bounds`() {
        val ops = LlmRefineParser.parse("{\"operations\": [{\"op\": \"keep\", \"index\": 0}]}").getOrThrow()
        val keep = ops.single() as RefineOp.Keep
        assertEquals(0, keep.index)
        assertEquals(null, keep.box)
    }
}
