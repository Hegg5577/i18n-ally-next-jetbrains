package com.hegg5577.i18n.editor

/**
 * `t('key')` 用法扫描：在文件文本里找出所有待提示的位置。
 *
 * 收集器（[I18nInlayCollector]）与诊断动作共用同一套判断，
 * 避免"诊断说应该显示、实际却不显示"这种两套逻辑不一致的情况。
 *
 * 沿用 i18n-ally-next 的做法：正则匹配文本而非解析 PSI，
 * 好处是 js / jsx / ts / tsx / vue / html 可以共用同一套逻辑。
 *
 * **匹配规则可配置**：既可以用内置框架集合，也可以由用户在设置里直接写正则。
 * 用户自定义的规则优先 —— 项目里用什么写法只有用户知道，让用户自己填比猜更准。
 */
object KeyUsageScanner {

    /** 一次`t('key')` 的出现。 */
    data class Usage(
        /** 点号路径形式的 key，如 `common.cancel`。 */
        val key: String,
        /** 提示插入位置（整个调用结束之后）。 */
        val offset: Int,
        /** 供诊断展示的原文片段。 */
        val raw: String,
        /** 命中的规则来源，诊断时用于说明「为什么这条被识别」。 */
        val source: String = "",
    )

    /** 超过此长度的文件不做扫描，保护编辑流畅度。 */
    const val MAX_FILE_LENGTH = 400_000

    /** 单文件最多产出的提示数量。 */
    const val MAX_HINTS_PER_FILE = 300

    /** 寻找调用右括号时最多向前扫描的字符数。 */
    private const val MAX_CALL_SCAN = 2_000

    /** key 的默认字符集，用于替换用户配置里的 `{key}` 占位符。 */
    const val DEFAULT_KEY_PATTERN = """[\w.\-]+"""

    /**
     * 兜底正则：只认裸 `t('key')`。
     *
     * 存在的意义是当用户没配任何规则、框架推断也失败时，诊断仍能给出有效信息，
     * 而不是"什么都没扫到"。
     */
    private val FALLBACK_REGEX = Regex("""(?:\bi18n\s*\.\s*)?\bt\s*\(\s*['"]($DEFAULT_KEY_PATTERN)['"]""")

    fun isTooLarge(textLength: Int): Boolean = textLength > MAX_FILE_LENGTH

    /**
     * 扫描文本。
     *
     * @param customPatterns 用户在设置里配置的正则，可含 `{key}` 占位符。
     *        非空时**只**用这些规则（用户既然指定了，就不该再混入内置规则干扰结果）。
     * @param builtinPatterns 内置规则，仅在用户未配置时使用。
     *        默认取 [DefaultPatterns.ALL] —— 不设成空列表，否则调用方一忘传参就只剩兜底正则，
     *        `$t('a')`、`i18n.t('a')` 这些写法全部识别不到。
     */
    fun scan(
        text: String,
        customPatterns: List<String> = emptyList(),
        builtinPatterns: List<Regex> = DefaultPatterns.ALL,
    ): Sequence<Usage> {
        if (isTooLarge(text.length)) return emptySequence()

        val rules = compileRules(customPatterns, builtinPatterns)
        return scanWith(text, rules)
    }

    /** 规则来源标记，用于诊断展示。 */
    data class Rule(val regex: Regex, val source: String)

    /**
     * 编译规则列表。
     *
     * 用户配置的 `{key}` 占位符会被替换成真实的 key 字符集，
     * 并强制转成命名捕获组 —— 因为不同写法里组号会变（有的写法前面还有引号组），
     * 按组号取 key 不可靠，命名组才稳定。
     */
    fun compileRules(
        customPatterns: List<String>,
        builtinPatterns: List<Regex> = DefaultPatterns.ALL,
    ): List<Rule> {
        if (customPatterns.isNotEmpty()) {
            return customPatterns.mapNotNull { raw ->
                val regex = buildRegex(raw)
                regex?.let { Rule(it, "自定义规则") }
            }.ifEmpty {
                listOf(Rule(FALLBACK_REGEX, "兜底规则"))
            }
        }

        if (builtinPatterns.isNotEmpty()) {
            return builtinPatterns.map { Rule(it, "内置规则") }
        }
        return listOf(Rule(FALLBACK_REGEX, "兜底规则"))
    }

    /**
     * 把用户写的模式编译成正则。
     *
     * `(?<key>...)` 若已存在则原样使用；只有 `{key}` 占位符时替换为命名捕获组。
     * 两种写法都支持，对应 i18n-ally 的 `({key})` 与本插件的命名组习惯。
     */
    private fun buildRegex(pattern: String): Regex? = runCatching {
        val normalized = pattern.trim()
        when {
            normalized.contains("{key}") ->
                Regex(normalized.replace("{key}", "(?<key>$DEFAULT_KEY_PATTERN)"))
            // 用户自己写了命名捕获组，直接用
            normalized.contains("(?<key>") -> Regex(normalized)
            // 写了普通捕获组：给第一组补上名字，靠替换成命名组形式
            else -> Regex(normalized.replaceFirst(Regex("""\((?!\?)([^)]*)\)"""), "(?<key>$1)"))
        }
    }.getOrNull()

    /**
     * 用编译好的规则扫描。
     *
     * 多条规则会命中同一处调用（例如 `i18next.t('a')` 同时被裸 `t` 与
     * `i18next.t` 两种写法匹配），这里按「偏移量 + key」去重，保证一个调用只产出一条提示。
     */
    fun scanWith(text: String, rules: List<Rule>): Sequence<Usage> = sequence {
        val seen = HashSet<Int>()
        var produced = 0

        outer@ for (rule in rules) {
            for (match in rule.regex.findAll(text)) {
                if (produced >= MAX_HINTS_PER_FILE) break@outer

                val key = match.groups["key"]?.value
                    ?: match.groupValues.drop(1).firstOrNull { it.isNotBlank() }
                    ?: continue
                val cleanKey = key.trim().trim('"', '\'')
                // 过长的捕获多半是误匹配（例如匹配到了整个字符串字面量）
                if (cleanKey.isBlank() || cleanKey.length > 200) continue

                val offset = hintOffset(text, match.range.last + 1)
                if (!seen.add(offset)) continue

                yield(Usage(cleanKey, offset, match.value, rule.source))
                produced++
            }
        }
    }

    /**
     * 提示插入位置：定位到整个 `t(...)` 调用的右括号之后，
     * 使带参数的调用也能渲染成 `t('a.b', {...}) 译文`。
     *
     * 从匹配结束之后开始按括号配对向前扫描（跳过字符串，避免被字符串里的括号干扰）。
     * 扫描有长度上限，遇到不配对或超长时退回「紧贴匹配末尾」的位置，保证总有结果。
     */
    fun hintOffset(text: String, from: Int): Int {
        var depth = 1 // 起点位于调用括号内部
        var quote: Char? = null
        var i = from
        val limit = minOf(text.length, from + MAX_CALL_SCAN)

        while (i < limit) {
            val c = text[i]
            when {
                quote != null -> when {
                    c == '\\' -> i++ // 跳过转义字符
                    c == quote -> quote = null
                }

                c == '\'' || c == '"' || c == '`' -> quote = c
                c == '(' -> depth++
                c == ')' -> if (--depth == 0) return i + 1
            }
            i++
        }
        return from
    }
}