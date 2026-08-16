package com.misoup.app.splitting.llmrefine

import com.misoup.app.splitting.candidates.BoundingBox

/**
 * T02 视觉 LLM 校验建议的操作模型（纯逻辑，JVM 可测）。
 *
 * 操作类型（模块 06 文档 §实现路径 2）：
 * - [Merge]   合并：把 [indices] 指定的多个候选框并为一个（外接并集）
 * - [Split]   拆分：把 [index] 候选框按垂直中线拆成两框（子题跨栏等场景）
 * - [Delete]  删除：丢弃 [index] 候选框（误检题号）
 * - [Keep]    保留/边界微调：确认 [index] 框（或给定调整后的边界 [box]）
 */
sealed class RefineOp {
    data class Merge(val indices: List<Int>) : RefineOp() {
        override fun apply(boxes: List<BoundingBox>): List<BoundingBox> {
            val valid = indices.filter { it in boxes.indices }.distinct().sorted()
            if (valid.size < 2) return boxes
            val union = valid.map { boxes[it] }.reduce { acc, b -> acc.union(b) }
            val minIdx = valid.first()
            val out = boxes.filterIndexed { i, _ -> i !in valid.toSet() }.toMutableList()
            // 合并结果放回原最小下标位置，保持阅读顺序
            out.add(minIdx, union)
            return out
        }
    }

    data class Split(val index: Int) : RefineOp() {
        override fun apply(boxes: List<BoundingBox>): List<BoundingBox> {
            if (index !in boxes.indices) return boxes
            val b = boxes[index]
            val half = b.width / 2f
            if (half <= 0f) return boxes
            val left = BoundingBox(b.left, b.top, half, b.height)
            val right = BoundingBox(b.left + half, b.top, b.width - half, b.height)
            val out = boxes.toMutableList()
            out.removeAt(index)
            out.add(index, right)
            out.add(index, left)
            return out
        }
    }

    data class Delete(val index: Int) : RefineOp() {
        override fun apply(boxes: List<BoundingBox>): List<BoundingBox> =
            if (index in boxes.indices) boxes.filterIndexed { i, _ -> i != index } else boxes
    }

    data class Keep(val index: Int, val box: BoundingBox? = null) : RefineOp() {
        override fun apply(boxes: List<BoundingBox>): List<BoundingBox> {
            if (index !in boxes.indices) return boxes
            val b = box ?: return boxes
            return boxes.toMutableList().also { it[index] = b }
        }
    }

    /** 应用到候选框列表（按序执行；下标越界忽略不崩）。返回新列表 */
    abstract fun apply(boxes: List<BoundingBox>): List<BoundingBox>
}
