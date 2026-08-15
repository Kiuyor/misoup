package com.misoup.app.data.model

/**
 * 科目预设（定稿）：语文/数学/英语/物理/化学/生物/政治/历史/地理 + 自定义
 *
 * meta.json 中 subject 以字符串存储；解析时未知值回退 CUSTOM（自定义），不崩。
 */
enum class Subject(val displayName: String) {
    CHINESE("语文"),
    MATH("数学"),
    ENGLISH("英语"),
    PHYSICS("物理"),
    CHEMISTRY("化学"),
    BIOLOGY("生物"),
    POLITICS("政治"),
    HISTORY("历史"),
    GEOGRAPHY("地理"),
    /** 自定义科目（不在预设列表内） */
    CUSTOM("自定义");

    companion object {
        /** 预设科目展示名列表（用于筛选栏/新建选择器） */
        val presetNames: List<String> = entries
            .filter { it != CUSTOM }
            .map { it.displayName }

        /** 字符串 → 枚举；未知字符串归为 CUSTOM（不抛异常） */
        fun fromString(name: String?): Subject =
            entries.firstOrNull { it.displayName == name } ?: CUSTOM
    }
}

/**
 * 题型预设（定稿）：选择题/填空题/解答题/实验题/计算题/其他
 */
enum class QuestionType(val displayName: String) {
    CHOICE("选择题"),
    FILL_BLANK("填空题"),
    SOLUTION("解答题"),
    EXPERIMENT("实验题"),
    CALCULATION("计算题"),
    OTHER("其他");

    companion object {
        val presetNames: List<String> = entries.map { it.displayName }

        /** 字符串 → 枚举；未知字符串归为 OTHER（不抛异常） */
        fun fromString(name: String?): QuestionType =
            entries.firstOrNull { it.displayName == name } ?: OTHER
    }
}

/**
 * 错因预设（定稿）：概念不清/计算失误/审题错误/思路错误/粗心大意/其他
 */
enum class ErrorReason(val displayName: String) {
    CONCEPT_UNCLEAR("概念不清"),
    CALCULATION_ERROR("计算失误"),
    MISREAD_QUESTION("审题错误"),
    WRONG_APPROACH("思路错误"),
    CARELESS("粗心大意"),
    OTHER("其他");

    companion object {
        val presetNames: List<String> = entries.map { it.displayName }

        /** 字符串 → 枚举；未知字符串归为 OTHER（不抛异常） */
        fun fromString(name: String?): ErrorReason =
            entries.firstOrNull { it.displayName == name } ?: OTHER
    }
}

/**
 * FSRS 卡片状态（定稿）：0=新卡 1=学习中 2=已掌握 3=重学
 * 与 meta.fsrs.state 的 Int 值一一对应
 */
enum class FsrsCardState(val code: Int, val displayName: String) {
    NEW(0, "新卡"),
    LEARNING(1, "学习中"),
    MASTERED(2, "已掌握"),
    RELEARNING(3, "重学");

    companion object {
        /** Int → 枚举；未知值归为 NEW（不抛异常） */
        fun fromCode(code: Int): FsrsCardState =
            entries.firstOrNull { it.code == code } ?: NEW
    }
}
