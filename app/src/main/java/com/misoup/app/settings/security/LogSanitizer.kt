package com.misoup.app.settings.security

/**
 * 日志脱敏（模块 10 T08，纯逻辑）
 *
 * 日志铁律（module/10「安全实现」）：不打印 Key / 密码；识别失败日志含路径不含内容。
 * 本对象提供统一脱敏：
 * - [sanitize]：按模式剥离常见敏感字段（Authorization/Bearer、apiKey、password、token…）
 * - [maskSecrets]：按已知密钥值整串打码（如 URL 查询参数中的 token、动态密钥）
 *
 * ⚠️ 脱敏是防御性兜底：业务代码仍应避免把密钥拼进日志；
 * 正则只覆盖常见形态，未知形态密钥不保证 100% 命中。
 */
object LogSanitizer {

    const val REDACTED = "***"

    /** Authorization 头 / Bearer / Basic 凭据：`Authorization: Bearer xxxx` / `"authorization":"Bearer x"` */
    private val AUTH_HEADER = Regex(
        """(?i)(authorization\s*[:=]\s*(?:bearer|basic)\s+)[A-Za-z0-9._~+/=-]+"""
    )

    /**
     * 常见敏感键值：`apiKey=xxx` / `"api_key": "xxx"` / `password: xxx` / `token=xxx`。
     * 不含 authorization（由 [AUTH_HEADER] 专管，避免把 "Bearer" 当值二次打码）。
     */
    private val SENSITIVE_KEY_VALUE = Regex(
        """(?i)(["']?(?:api[_-]?key|apikey|password|passwd|pwd|secret|token|access[_-]?token|refresh[_-]?token)["']?\s*[:=]\s*["']?)[^"'\s,&;{}]+"""
    )

    /**
     * 按模式脱敏一段文本（日志行/异常消息）。
     * 例：`Authorization: Bearer sk-abc123` → `Authorization: Bearer ***`；
     * `"apiKey":"sk-abc123"` → `"apiKey":"***"`。
     */
    fun sanitize(text: String): String {
        var out = AUTH_HEADER.replace(text) { m -> m.groupValues[1] + REDACTED }
        out = SENSITIVE_KEY_VALUE.replace(out) { m -> m.groupValues[1] + REDACTED }
        return out
    }

    /**
     * 按已知密钥值整串打码：text 中出现的任何 secrets 成员（长度 ≥4）替换为 ***。
     * 用于 URL 查询参数、动态 token 等正则覆盖不到的形态。
     * 短串（<4 字符）忽略，避免把常见单词误伤。
     */
    fun maskSecrets(text: String, secrets: Collection<String>): String {
        var out = text
        for (s in secrets) {
            if (s.length >= 4) out = out.replace(s, REDACTED)
        }
        return out
    }
}
