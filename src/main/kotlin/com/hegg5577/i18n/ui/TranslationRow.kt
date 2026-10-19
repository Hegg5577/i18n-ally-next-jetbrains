package com.hegg5577.i18n.ui

/**
 * 一条 key 在各语言下的翻译情况，是对照表的一行。
 *
 * @param key 点号路径形式的 key，如 `common.cancel`
 * @param sourceText 源语言当前原文，作为比对基准
 * @param values locale -> 译文；语言包里没有该 key 时 map 里不会有这个 locale
 * @param usageCount 代码中引用该 key 的文件数，0 表示未统计
 */
data class TranslationRow(
    val key: String,
    val sourceText: String,
    val values: Map<String, String>,
    val usageCount: Int = 0,
) {
    /**
     * 该行在某语言下的展示状态。
     *
     * 只看译文是否存在，不比对译文与原文是否一致 —— 见[TranslationStatus.of]的说明。
     */
    fun status(locale: String): TranslationStatus = TranslationStatus.of(values[locale])
}