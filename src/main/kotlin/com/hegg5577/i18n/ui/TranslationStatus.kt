package com.hegg5577.i18n.ui

import java.util.Locale as JavaLocale

/**
 * 单条译文的状态。
 *
 * - [TRANSLATED] 已翻译且非空
 * - [EMPTY] key 存在但值为空串或纯空白
 * - [MISSING] 语言包里没有这个 key
 */
enum class TranslationStatus(val label: String) {
    TRANSLATED("已翻译"),
    EMPTY("待填写"),
    MISSING("缺失");

    companion object {
        /**
         * 判定一条译文的展示状态。
         *
         * @param target 目标语言的译文，`null` 表示 key 不存在
         *
         * 刻意只判定「有没有译文」，不判定「译文对不对」。
         *
         * 踩过的坑：曾按「译文≠源文本且占位符集合一致 → 已过期」来判定，
         * 结果正常的机器翻译全部被标成⚠ —— 因为译文和英文原文本来就不一样，
         * 而两边都没有占位符时空集相等，被误判为"同一句原文的旧译文"。
         * 上万个已翻译条目变成一片警告，比不给提示更糟。
         *
         * 真正的过期检测需要源文本的历史快照才能做，没有历史数据就不该显示过期。
         * 等后续记录了源文本指纹，再把这个状态加回来。
         */
        fun of(target: String?): TranslationStatus = when {
            target == null -> MISSING
            target.isBlank() -> EMPTY
            else -> TRANSLATED
        }
    }
}

/**
 * 状态对应的行内标记符号。
 *
 * 用 Unicode 符号而非 [javax.swing.Icon]：单元格宽度有限，符号能随文本一起
 * 被复制粘贴，且不依赖平台图标资源在不同主题下的存在性。
 *
 * 只用三个基础符号（✓ ○ ✗），都是 BMP 内字符，任何字体都有，不会渲染成豆腐块。
 */
val TranslationStatus.marker: String
    get() = when (this) {
        TranslationStatus.TRANSLATED -> "✓"
        TranslationStatus.EMPTY -> "○"
        TranslationStatus.MISSING -> "✗"
    }

/**
 * 语言代码的显示名。
 *
 * 只用 JDK 自带的 [JavaLocale] 做本地化，不引入国旗 emoji。
 *
 * 曾经用 emoji 国旗做列头，实测在 Windows 上全部渲染成豆腐块 □
 * （系统缺彩色 emoji 字体），反而不如文字。文字标签在任何平台都稳定，
 * 且「日本語 (ja)」比国旗表意更明确 —— 荷兰语和佛兰芒语都该配荷兰国旗，
 * 光看旗子分不出来。
 */
object Flags {

    /**
     * 语言代码的中文显示名，用于面板表头。
     *
     * 输出形如「日本語 (ja)」。JDK 认不出的语言代码原样返回，
     * 显示原始代码本身比显示一个错误的翻译更可靠。
     */
    fun displayName(locale: String): String {
        if (locale.isBlank()) return "(未设置)"

        val normalized = locale.replace('_', '-')
        val javaLocale = runCatching {
            JavaLocale.forLanguageTag(normalized)
        }.getOrNull()

        // 认不出的语言 tag，getDisplayLanguage 会回显输入本身
        val name = javaLocale?.getDisplayLanguage(JavaLocale.SIMPLIFIED_CHINESE)
        return if (name.isNullOrBlank() || name == normalized || name == locale) {
            locale
        } else {
            "$name ($locale)"
        }
    }
}