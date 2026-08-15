package com.misoup.app

import com.misoup.app.data.model.ErrorReason
import com.misoup.app.data.model.FsrsCardState
import com.misoup.app.data.model.MisoupMeta
import com.misoup.app.data.model.QuestionType
import com.misoup.app.data.model.Subject
import com.misoup.app.data.store.MetaJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T05 数据模型：meta.json 序列化往返 / 默认值 / 未知字段容错 / 枚举解析
 */
class MetaJsonTest {

    @Test
    fun `序列化后解析往返一致`() {
        val meta = makeMeta(
            id = "uuid-1",
            subject = "物理",
            questionType = "实验题",
            errorReasons = listOf("计算失误", "审题错误"),
            tags = listOf("力学", "月考"),
            knowledgePoints = listOf("牛顿第二定律"),
            note = "注意单位",
            source = "期中",
            createdAt = "2026-08-16T14:05:23+08:00",
            updatedAt = "2026-08-16T15:00:00+08:00"
        )
        val json = MetaJson.serialize(meta)
        val parsed = MetaJson.parse(json).getOrThrow()

        assertEquals(meta, parsed)
        assertEquals("uuid-1", parsed.id)
        assertEquals(2, parsed.errorReasons.size)
        assertEquals(2, parsed.tags.size)
        assertEquals(1, parsed.schemaVersion)
    }

    @Test
    fun `最小字段 JSON 解析出默认值`() {
        val json = """
            {
              "id": "uuid-min",
              "subject": "数学",
              "questionType": "选择题",
              "errorReasons": [],
              "tags": [],
              "knowledgePoints": [],
              "createdAt": "2026-08-16T10:00:00+08:00",
              "updatedAt": "2026-08-16T10:00:00+08:00",
              "images": {"original": "原图.webp"},
              "ocr": {},
              "fsrs": {}
            }
        """.trimIndent()
        val meta = MetaJson.parse(json).getOrThrow()

        assertEquals("uuid-min", meta.id)
        assertEquals("", meta.note)                                    // 默认空串
        assertEquals("", meta.source)
        assertEquals("文本.md", meta.textFile)                         // 默认文本文件名
        assertTrue(meta.reviews.isEmpty())                             // 默认空列表
        assertEquals(1, meta.schemaVersion)                            // 默认版本 1
        assertEquals("pending", meta.ocr.status)                       // 默认识别状态
        assertEquals(0, meta.fsrs.state)                               // 默认新卡
        assertEquals("原图.webp", meta.images.original)
    }

    @Test
    fun `未知字段被忽略不崩溃`() {
        val json = """
            {
              "id": "uuid-unknown",
              "subject": "数学",
              "questionType": "选择题",
              "errorReasons": [],
              "tags": [],
              "knowledgePoints": [],
              "createdAt": "2026-08-16T10:00:00+08:00",
              "updatedAt": "2026-08-16T10:00:00+08:00",
              "images": {"original": "原图.webp"},
              "ocr": {},
              "fsrs": {},
              "futureField": {"a": 1},
              "anotherUnknown": "x"
            }
        """.trimIndent()
        val meta = MetaJson.parse(json).getOrThrow()
        assertEquals("uuid-unknown", meta.id)
    }

    @Test
    fun `损坏 JSON 解析返回失败`() {
        assertTrue(MetaJson.parse("{not valid json").isFailure)
        assertTrue(MetaJson.parse("").isFailure)
        assertTrue(MetaJson.parse("null").isFailure)
        assertTrue(MetaJson.parse("[]").isFailure)
        // 缺必填字段（id）也失败
        assertTrue(
            MetaJson.parse(
                """{"subject":"数学","questionType":"选择题","errorReasons":[],"tags":[],"knowledgePoints":[],"createdAt":"t","updatedAt":"t","images":{"original":"a"},"ocr":{},"fsrs":{}}"""
            ).isFailure
        )
    }

    @Test
    fun `科目枚举解析-预设与自定义回退`() {
        assertEquals(Subject.MATH, Subject.fromString("数学"))
        assertEquals(Subject.GEOGRAPHY, Subject.fromString("地理"))
        assertEquals(Subject.CUSTOM, Subject.fromString("计算机"))     // 未知 → 自定义，不崩
        assertEquals(Subject.CUSTOM, Subject.fromString(null))
        assertEquals(Subject.CUSTOM, Subject.fromString(""))
        // 预设列表含 9 科且不含"自定义"
        assertEquals(9, Subject.presetNames.size)
        assertFalse(Subject.presetNames.contains("自定义"))
        assertTrue(Subject.presetNames.contains("语文"))
    }

    @Test
    fun `题型枚举解析-未知回退其他`() {
        assertEquals(QuestionType.CHOICE, QuestionType.fromString("选择题"))
        assertEquals(QuestionType.CALCULATION, QuestionType.fromString("计算题"))
        assertEquals(QuestionType.OTHER, QuestionType.fromString("作文题"))
        assertEquals(6, QuestionType.presetNames.size)
    }

    @Test
    fun `错因枚举解析-未知回退其他`() {
        assertEquals(ErrorReason.CONCEPT_UNCLEAR, ErrorReason.fromString("概念不清"))
        assertEquals(ErrorReason.CARELESS, ErrorReason.fromString("粗心大意"))
        assertEquals(ErrorReason.OTHER, ErrorReason.fromString("手滑"))
        assertEquals(6, ErrorReason.presetNames.size)
    }

    @Test
    fun `FSRS 状态枚举-码值与回退`() {
        assertEquals(FsrsCardState.NEW, FsrsCardState.fromCode(0))
        assertEquals(FsrsCardState.LEARNING, FsrsCardState.fromCode(1))
        assertEquals(FsrsCardState.MASTERED, FsrsCardState.fromCode(2))
        assertEquals(FsrsCardState.RELEARNING, FsrsCardState.fromCode(3))
        assertEquals(FsrsCardState.NEW, FsrsCardState.fromCode(-1))    // 未知回退新卡
        assertEquals(FsrsCardState.NEW, FsrsCardState.fromCode(99))
    }

    @Test
    fun `序列化输出包含关键字段`() {
        val json = MetaJson.serialize(makeMeta(id = "uuid-out", subject = "英语"))
        assertTrue(json.contains("\"id\":\"uuid-out\""))
        assertTrue(json.contains("\"subject\":\"英语\""))
        assertTrue(json.contains("\"schemaVersion\":1"))
        assertTrue(json.contains("\"textFile\":\"文本.md\""))
    }

    @Test
    fun `枚举以字符串形式存储`() {
        // 契约：JSON 存字符串（subject/questionType/errorReasons），非数字编码
        val json = MetaJson.serialize(makeMeta(subject = "化学", questionType = "实验题"))
        assertTrue(json.contains("\"subject\":\"化学\""))
        assertTrue(json.contains("\"questionType\":\"实验题\""))
        assertTrue(json.contains("\"errorReasons\":[\"概念不清\"]"))
        // 解析回字符串可直接映射枚举
        val parsed = MetaJson.parse(json).getOrThrow()
        assertNotNull(Subject.fromString(parsed.subject))
    }

    @Test
    fun `reviews 与 fsrs 完整字段往返`() {
        val meta = makeMeta().copy(
            fsrs = com.misoup.app.data.model.FsrsState(
                state = 2, due = "2026-08-18T00:00:00+08:00",
                stability = 2.5, difficulty = 5.0, reps = 3, lapses = 1,
                lastReview = "2026-08-16T10:00:00+08:00"
            ),
            reviews = listOf(
                com.misoup.app.data.model.Review(date = "2026-08-16T10:00:00+08:00", rating = 2, stateAfter = 2, intervalDays = 3.0)
            )
        )
        val parsed = MetaJson.parse(MetaJson.serialize(meta)).getOrThrow()
        assertEquals(meta, parsed)
        assertEquals(2.5, parsed.fsrs.stability, 1e-9)
        assertEquals(1, parsed.reviews.size)
        assertEquals(3.0, parsed.reviews[0].intervalDays, 1e-9)
    }
}
