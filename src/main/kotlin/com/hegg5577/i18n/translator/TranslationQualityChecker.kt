package com.hegg5577.i18n.translator

/**
 * 翻译质量检查器。
 *
 * 验证翻译中的参数一致性、占位符完整性，以及其他常见问题。
 * 例如检测原文中的 `{0}` 是否在翻译中保留。
 */
object TranslationQualityChecker {

    /** 质量检查的结果。 */
    data class CheckResult(
        val key: String,
        val language: String,
        val source: String,
        val translation: String,
        val issues: List<QualityIssue>,
    ) {
        val isValid: Boolean get() = issues.isEmpty()
    }

    /** 单个质量问题。 */
    data class QualityIssue(
        val type: IssueType,
        val message: String,
        val severity: IssueSeverity = IssueSeverity.WARNING,
    )

    enum class IssueType {
        /** 参数数量不匹配。 */
        PARAMETER_COUNT_MISMATCH,
        /** 参数类型不一致。 */
        PARAMETER_TYPE_MISMATCH,
        /** 缺少必要的占位符。 */
        MISSING_PLACEHOLDER,
        /** 包含多余的占位符。 */
        EXTRA_PLACEHOLDER,
        /** 翻译过短（可能被截断）。 */
        SUSPICIOUSLY_SHORT,
        /** 翻译过长（可能超出界面限制）。 */
        SUSPICIOUSLY_LONG,
        /** HTML 标签不匹配。 */
        HTML_TAG_MISMATCH,
        /** 其他问题。 */
        OTHER,
    }

    enum class IssueSeverity {
        INFO, WARNING, ERROR
    }

    /**
     * 检查翻译质量。
     */
    fun check(
        key: String,
        language: String,
        source: String,
        translation: String,
    ): CheckResult {
        val issues = mutableListOf<QualityIssue>()

        // 检查参数占位符
        val sourceParams = extractParameters(source)
        val translationParams = extractParameters(translation)

        if (sourceParams.size != translationParams.size) {
            issues.add(
                QualityIssue(
                    IssueType.PARAMETER_COUNT_MISMATCH,
                    "参数数量不匹配: 原文 ${sourceParams.size} 个，翻译 ${translationParams.size} 个",
                    IssueSeverity.ERROR,
                )
            )
        }

        // 检查缺失的占位符
        for (param in sourceParams) {
            if (!translationParams.contains(param)) {
                issues.add(
                    QualityIssue(
                        IssueType.MISSING_PLACEHOLDER,
                        "缺少参数占位符: $param",
                        IssueSeverity.ERROR,
                    )
                )
            }
        }

        // 检查多余的占位符
        for (param in translationParams) {
            if (!sourceParams.contains(param)) {
                issues.add(
                    QualityIssue(
                        IssueType.EXTRA_PLACEHOLDER,
                        "多余的参数占位符: $param",
                        IssueSeverity.WARNING,
                    )
                )
            }
        }

        // 检查 HTML 标签
        val sourceTags = extractHtmlTags(source)
        val translationTags = extractHtmlTags(translation)

        if (sourceTags != translationTags) {
            issues.add(
                QualityIssue(
                    IssueType.HTML_TAG_MISMATCH,
                    "HTML 标签不匹配: 原文 $sourceTags，翻译 $translationTags",
                    IssueSeverity.WARNING,
                )
            )
        }

        // 检查翻译长度
        val sourceLen = source.length
        val transLen = translation.length

        if (transLen < sourceLen * 0.3 && transLen < 5) {
            issues.add(
                QualityIssue(
                    IssueType.SUSPICIOUSLY_SHORT,
                    "翻译过短（${transLen} 字符），可能被截断",
                    IssueSeverity.WARNING,
                )
            )
        }

        if (transLen > sourceLen * 2.5 && transLen > 100) {
            issues.add(
                QualityIssue(
                    IssueType.SUSPICIOUSLY_LONG,
                    "翻译过长（${transLen} 字符），可能超出界面限制",
                    IssueSeverity.INFO,
                )
            )
        }

        return CheckResult(key, language, source, translation, issues)
    }

    /**
     * 提取文本中的参数占位符。
     *
     * 支持格式：
     * - `{0}`, `{1}` —— 位置参数
     * - `{name}` —— 命名参数
     * - `$1`, `$2` —— 美元符号参数
     * - `%d`, `%s`, `%s` —— 格式化参数
     */
    private fun extractParameters(text: String): Set<String> {
        val params = mutableSetOf<String>()

        // 匹配 {0}, {1}, {name} 等
        val braceRegex = Regex("""\{([^}]+)\}""")
        braceRegex.findAll(text).forEach { match ->
            params.add("{${match.groupValues[1]}}")
        }

        // 匹配 $1, $2 等
        val dollarRegex = Regex("""\$(\d+)""")
        dollarRegex.findAll(text).forEach { match ->
            params.add("$${match.groupValues[1]}")
        }

        // 匹配 %s, %d, %f 等
        val percentRegex = Regex("""%[sdfx]""")
        var index = 0
        percentRegex.findAll(text).forEach {
            params.add("%${index++}")
        }

        return params
    }

    /**
     * 提取 HTML 标签。
     */
    private fun extractHtmlTags(text: String): Set<String> {
        val tags = mutableSetOf<String>()
        val tagRegex = Regex("""</?(\w+)[^>]*>""", RegexOption.IGNORE_CASE)

        tagRegex.findAll(text).forEach { match ->
            tags.add(match.groupValues[1].lowercase())
        }

        return tags
    }

    /**
     * 批量检查翻译。
     */
    fun checkBatch(
        translations: Map<String, Map<String, String>>,
        sources: Map<String, String> = emptyMap(),
    ): List<CheckResult> {
        val results = mutableListOf<CheckResult>()

        for ((key, langMap) in translations) {
            val source = sources[key] ?: continue

            for ((language, translation) in langMap) {
                val result = check(key, language, source, translation)
                if (!result.isValid) {
                    results.add(result)
                }
            }
        }

        return results.sortedWith(
            compareBy<TranslationQualityChecker.CheckResult> { it.issues.maxByOrNull { issue -> issue.severity.ordinal }?.severity?.ordinal ?: -1 }
                .thenBy { it.key }
                .thenBy { it.language }
        )
    }
}
