package com.hegg5577.i18n.translator

/** 翻译失败：网络不可达、被限流、响应格式异常等都归一到这一类。 */
class TranslationException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * 翻译引擎抽象。
 *
 * 刻意保持极小：一次接受多条文本。因为批量场景下请求数是主要开销
 * （13 语言 × 上千 key），单次一条会被限流拖垮。
 */
interface TranslationEngine {

    /** 稳定标识，用于未来的配置持久化。 */
    val id: String

    val displayName: String

    /**
     * 批量翻译，返回与 [texts] 等长顺序一致的结果。
     *
     * @param targetLocale i18next 语言代码（如 `zh`、`pt-BR`）
     * @param sourceLocale 源语言代码
     * @throws TranslationException 任一批次失败即抛出，由调用方决定重试或中止
     */
    @Throws(TranslationException::class)
    fun translate(
        texts: List<String>,
        targetLocale: String,
        sourceLocale: String = "en",
    ): List<String>
}
