package com.misoup.app.settings.compat

/**
 * 白名单引导 ROM（模块 10 T09，纯逻辑）
 *
 * 前台服务/后台限制：vivo(OriginOS) / 小米(澎湃OS) / 华为(鸿蒙) 省电白名单引导页。
 * 检测 [Build.MANUFACTURER]（UI 层注入字符串，本对象保持纯逻辑可测）。
 */
enum class RomKind(val displayName: String) {
    VIVO("vivo"),
    XIAOMI("小米"),
    HUAWEI("华为"),
    OTHER("其他"),
}

object RomCompat {

    /**
     * 厂商字符串 → ROM 分类（大小写不敏感、子串匹配）。
     * - vivo / iQOO → [RomKind.VIVO]（iQOO 同属 vivo 系，验收标准指定 iQOO 真机）
     * - xiaomi / redmi / poco → [RomKind.XIAOMI]
     * - huawei / honor → [RomKind.HUAWEI]（荣耀脱胎华为，后台限制同源）
     * - 其余 / 空 → [RomKind.OTHER]
     */
    fun detect(manufacturer: String): RomKind {
        val m = manufacturer.trim().lowercase()
        return when {
            m.contains("vivo") || m.contains("iqoo") -> RomKind.VIVO
            m.contains("xiaomi") || m.contains("redmi") || m.contains("poco") -> RomKind.XIAOMI
            m.contains("huawei") || m.contains("honor") -> RomKind.HUAWEI
            else -> RomKind.OTHER
        }
    }

    /** 是否需要白名单引导（非白名单 ROM → false，不展示引导卡片） */
    fun needsWhitelistGuide(kind: RomKind): Boolean = kind != RomKind.OTHER

    /**
     * 白名单引导文案（设置页「关于/复习提醒」展示）；OTHER → null。
     * 文案指向各 ROM 的实际后台白名单路径（V2 复习提醒依赖）。
     */
    fun guideText(kind: RomKind): String? = when (kind) {
        RomKind.VIVO ->
            "vivo/OriginOS 后台限制较严：请到「设置 → 电池 → 后台耗电管理」将错题汤加入" +
                "白名单并允许「自启动」，否则复习提醒可能被系统清理。"
        RomKind.XIAOMI ->
            "小米/澎湃OS：请到「设置 → 应用设置 → 应用管理 → 错题汤 → 省电策略」选择「无限制」" +
                "并开启「自启动」，否则复习提醒可能延迟或丢失。"
        RomKind.HUAWEI ->
            "华为/鸿蒙：请到「设置 → 应用 → 应用启动管理」关闭「自动管理」后手动允许" +
                "「自启动 / 关联启动 / 后台活动」，否则复习提醒可能被系统清理。"
        RomKind.OTHER -> null
    }
}
