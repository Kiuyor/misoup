package com.misoup.app.data.store

import android.content.Context
import com.misoup.app.data.model.MisoupConfigConst
import com.misoup.app.data.model.Subject
import java.io.File

/**
 * 全局配置常量与库根管理
 *
 * - 库根默认：context.getExternalFilesDir(null)/library（免权限，DESIGN §14 定稿倾向）
 * - SAF 选目录迁移接口预留：LibraryRoot.set()（模块 10 设置页接入）
 * - 枚举常量见 data/model/Enums.kt；文件命名常量见 MisoupConfigConst
 */
object MisoupConfig {

    /** 默认库根（应用专属外部目录，卸载即清，免存储权限） */
    fun defaultLibraryRoot(context: Context): File =
        File(context.getExternalFilesDir(null), MisoupConfigConst.DEFAULT_LIBRARY_DIR_NAME)

    /** 库根下科目目录 */
    fun subjectDir(root: File, subject: String): File = File(root, subject)

    /** 错题目录（科目/自动命名目录） */
    fun entryDir(root: File, subject: String, dirName: String): File =
        File(subjectDir(root, subject), dirName)

    /** 预设科目列表（含自定义入口由 UI 控制） */
    val presetSubjects: List<String> = Subject.presetNames

    /** 预设题型列表 */
    val presetQuestionTypes: List<String> = com.misoup.app.data.model.QuestionType.presetNames

    /** 预设错因列表 */
    val presetErrorReasons: List<String> = com.misoup.app.data.model.ErrorReason.presetNames
}

/**
 * 库根（可迁移）持有者
 *
 * 预留 SAF 迁移接口：设置页选择新目录后调用 [set]，后续所有读写走新库根；
 * 未设置时回退 [MisoupConfig.defaultLibraryRoot]。线程安全（volatile 读）。
 */
object LibraryRoot {
    @Volatile
    private var custom: File? = null

    /** 当前库根；未自定义时用默认路径 */
    fun get(context: Context): File = custom ?: MisoupConfig.defaultLibraryRoot(context)

    /** 迁移库根（SAF 选目录后调用；调用方负责复制数据/重建索引） */
    fun set(root: File?) {
        custom = root
    }

    /** 是否已被用户自定义（用于设置页展示"默认/自定义"状态） */
    fun isCustomized(): Boolean = custom != null

    /** 测试/重置用 */
    fun reset() {
        custom = null
    }
}
