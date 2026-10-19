package com.hegg5577.i18n.locale

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * 译文查询索引：把语言包预解析成 `key -> value` 的扁平映射，供编辑器高频查询。
 *
 * 缓存指纹包含「全部语言包路径、修改时间、大小 + 全部相关配置」，配置一改就自动重新解析 ——
 * 用户改完设置立刻生效，不需要手动清缓存。
 */
object TranslationIndex {

    /** 允许显示行内译文的文件后缀默认值。 */
    val DEFAULT_EXTENSIONS = setOf(
        "js", "jsx", "mjs", "cjs", "ts", "tsx", "vue", "html", "htm"
    )

    /** 首选显示语言。 */
    const val DEFAULT_LOCALE = "zh"

    /** 首选语言缺 key 时回退到源语言，行为与 i18n-ally 一致（未翻译时显示原文）。 */
    const val FALLBACK_LOCALE = "en"

    private data class Entry(val stamp: String, val values: Map<String, String>)

    private val cache = ConcurrentHashMap<String, Entry>()

    /** 可配置的后缀集合；为空时用 [DEFAULT_EXTENSIONS]。 */
    fun isSupported(fileName: String, extensions: Set<String> = emptySet()): Boolean {
        val allowed = extensions.ifEmpty { DEFAULT_EXTENSIONS }
        return fileName.substringAfterLast('.', "").lowercase() in allowed
    }

    /** 清空缓存（语言包被修改后可调用）。 */
    fun invalidate() = cache.clear()

    /**
     * 返回某项目的译文映射，key 为点号路径（如 `common.cancel`）。
     * 解析失败或语言包不存在时返回空表，调用方据此静默跳过提示。
     *
     * @param locale 行内提示使用的语言
     * @param fallbackLocale 首选语言缺 key 时回退到的语言；传空串表示不回退
     */
    fun values(
        projectRoot: String?,
        locale: String = DEFAULT_LOCALE,
        fallbackLocale: String = FALLBACK_LOCALE,
        localesDir: String = LocaleRepository.DEFAULT_LOCALES_DIR,
        pathMatcher: String = PathMatcher.DEFAULT_PATTERN,
        keyStyle: KeyStyle = KeyStyle.NESTED,
    ): Map<String, String> {
        if (projectRoot == null) return emptyMap()

        val cacheKey = "$projectRoot|$localesDir|$pathMatcher"
        val resolved = PathMatcher(pathMatcher).resolve(File(projectRoot, localesDir))
        val fingerprint = buildString {
            append(locale).append('|').append(fallbackLocale).append('|').append(keyStyle).append('|')
            for (l in listOf(locale, fallbackLocale).filter { it.isNotBlank() }) {
                append(l).append('|')
                for (file in resolved.filter { it.locale == l }.map { it.file }) {
                    append(file.path).append(':').append(file.lastModified())
                        .append(':').append(file.length()).append('|')
                }
            }
        }

        // 缓存键按「项目 + 路径配置」区分，指纹放在 Entry 里比对，
        // 避免文件每次改动都留下一条永不清理的记录
        cache[cacheKey]?.let { if (it.stamp == fingerprint) return it.values }

        val values = LinkedHashMap<String, String>()
        // 先放回退语言，再用首选语言覆盖：未翻译的 key 自动显示回退语言原文
        if (fallbackLocale.isNotBlank() && fallbackLocale != locale) {
            values.putAll(load(projectRoot, fallbackLocale, localesDir, pathMatcher, keyStyle))
        }
        values.putAll(load(projectRoot, locale, localesDir, pathMatcher, keyStyle))

        cache[cacheKey] = Entry(fingerprint, values)
        return values
    }

    /**
     * 单个语言包解析出的 key 数量，0 表示不存在或解析失败。
     * 仅供诊断展示用。
     */
    fun countFor(
        projectRoot: String,
        locale: String,
        localesDir: String = LocaleRepository.DEFAULT_LOCALES_DIR,
        pathMatcher: String = PathMatcher.DEFAULT_PATTERN,
        keyStyle: KeyStyle = KeyStyle.NESTED,
    ): Int = load(projectRoot, locale, localesDir, pathMatcher, keyStyle).size

    /** 某语言的语言包文件路径，遵循配置的路径模式。 */
    fun localeFile(
        projectRoot: String,
        locale: String,
        localesDir: String = LocaleRepository.DEFAULT_LOCALES_DIR,
        pathMatcher: String = PathMatcher.DEFAULT_PATTERN,
    ): File =
        PathMatcher(pathMatcher)
            .resolve(File(projectRoot, localesDir))
            .firstOrNull { it.locale == locale }
            ?.file
            ?: File(File(projectRoot, localesDir), "$locale/${LocaleRepository.DEFAULT_FILE_NAME}")

    private fun load(
        projectRoot: String,
        locale: String,
        localesDir: String,
        pathMatcher: String,
        keyStyle: KeyStyle,
    ): Map<String, String> {
        if (locale.isBlank()) return emptyMap()
        val root = File(projectRoot)
        val repo = LocaleRepository(
            projectRoot = root,
            localesDir = localesDir,
            pathMatcher = pathMatcher,
            keyStyle = keyStyle,
        )
        // 复用仓库的解析逻辑，保证面板与行内提示对 key 的理解完全一致
        return repo.valuesOf(locale)
    }

    /** 将嵌套 JSON 展平为点号 key 到字符串值的映射。 */
    private fun flatten(file: File): Map<String, String> {
        if (!file.isFile) return emptyMap()
        // 平台自带 gson；万一不可用也只会导致不显示提示，不会让 IDE 报错中断
        return runCatching {
            val root = JsonParser.parseString(file.readText()).asJsonObject
            val out = LinkedHashMap<String, String>()

            fun walk(obj: JsonObject, prefix: String) {
                for ((name, value) in obj.entrySet()) {
                    val key = if (prefix.isEmpty()) name else "$prefix.$name"
                    when {
                        value.isJsonObject -> walk(value.asJsonObject, key)
                        value.isJsonPrimitive -> out[key] = value.asString
                    }
                }
            }

            walk(root, "")
            out
        }.getOrDefault(emptyMap())
    }
}