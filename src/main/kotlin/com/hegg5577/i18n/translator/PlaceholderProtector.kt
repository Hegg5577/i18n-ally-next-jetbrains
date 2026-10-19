package com.hegg5577.i18n.translator

/**
 * 占位符保护：翻译前把「不能被翻译引擎改写」的片段替换成占位 token，翻译后还原。
 *
 * 为什么必须做：语言包里有 39 个 key 带 `{variable}` 这类插值占位符，还有整段 HTML 富文本
 * （`<h2><ul><li>…</li></ul></h2>`）。翻译引擎会把 `{size}` 翻成 `{尺寸}`、把标签的
 * 属性值改写甚至吞掉标签，线上直接报错。
 *
 * **token 必须用 `__P0__` 这类 ASCII 形式，绝不能用 U+E000/U+E001 私有区字符 —— 这是实测踩到的坑**。
 * 私有区字符会被翻译引擎当成某种序列做「重编号」：实测把 U+E000+序号+U+E001 送进去，
 * 返回里 U+E001 被改写成了 U+E002（序号自增），有的甚至被整段删掉，导致还原必然失败。
 * 换成 `__P0__` 后，在 zh / ja / ru / ar / de / tr 六种语言下均完整保留，
 * 包括 `<h2>…</h2><ul><li>…</li></ul>` 这种整段 HTML 富文本也一样无损。
 *
 * 还原后如果**还有 token 残留**，说明译文走向不对，
 * 返回 null 让调用方丢弃该条 —— 宁可缺一条翻译，也不要写入被破坏的内容。
 */
object PlaceholderProtector {

    private val PATTERNS = listOf(
        // 顺序有讲究：{{count}} 必须先于 {size}，否则 { 的处理会先把内层吃掉，外侧两个花括号残留
        Regex("""\{\{[^}]+\}\}"""),                        // i18next 插值 {{count}}
        Regex("""\{[a-zA-Z_][\w.]*\}"""),                  // 插值 {size} {user.name}
        Regex("""<[^>]+>"""),                              // HTML 标签 <span> </li> <br/>
        Regex("""&(?:[a-zA-Z][a-zA-Z0-9]+|#\d+);"""),      // HTML 实体 &nbsp; &#39;
        Regex("""%\([^)]+\)[sd]|%[sd@]"""),                // printf 风格 %s %(name)s
    )

    private const val TOKEN_PREFIX = "__P"
    private const val TOKEN_SUFFIX = "__"

    /** 用于检测「翻译后仍有 token 没被还原」的残留扫描。 */
    private val RESIDUAL_TOKEN = Regex("""__P\d+__""")

    class Protected internal constructor(
        /** 替换后可直接送去翻译的文本。 */
        val text: String,
        private val tokens: Map<String, String>,
    ) {
        /** 该文本里是否本来就没有需要保护的片段。 */
        val isEmpty: Boolean get() = tokens.isEmpty()

        /**
         * 还原译文。若仍有 token 残留（被翻译引擎改坏或丢弃）返回 null，
         * 调用方应当放弃这一条而不是写入半成品。
         */
        fun restore(translated: String): String? {
            if (tokens.isEmpty()) return translated

            var out = translated
            for ((token, original) in tokens) {
                out = out.replace(token, original)
            }
            // 仍有 token 残留 => 翻译引擎把它删了或改了，宁可丢这一条
            return if (RESIDUAL_TOKEN.containsMatchIn(out)) null else out
        }
    }

    fun protect(text: String): Protected {
        val tokens = LinkedHashMap<String, String>()
        var out = text

        for (pattern in PATTERNS) {
            out = pattern.replace(out) { match ->
                val token = token(tokens.size)
                tokens[token] = match.value
                token
            }
        }
        return Protected(out, tokens)
    }

    private fun token(index: Int): String = "$TOKEN_PREFIX$index$TOKEN_SUFFIX"
}
