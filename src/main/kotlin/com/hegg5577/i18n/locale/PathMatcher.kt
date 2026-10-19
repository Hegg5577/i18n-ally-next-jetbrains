package com.hegg5577.i18n.locale

import java.io.File

class PathMatcher(
    val pattern: String,
    private val defaultFileName: String = LocaleRepository.DEFAULT_FILE_NAME,
) {
    private val segments: List<String> =
        pattern.trim().split('/').map { it.trim() }.filter { it.isNotEmpty() }

    val usesLocale: Boolean get() = pattern.contains(LOCALE_TOKEN)
    val usesNamespace: Boolean get() = pattern.contains(NAMESPACE_TOKEN)

    /** 按显式 token 匹配路径，保留 namespace；{file} 继续只选择一个默认文件。 */
    fun resolve(root: File): List<ResolvedLocale> {
        if (!root.isDirectory || segments.isEmpty()) return emptyList()
        var level = listOf(Level(null, root))

        for ((index, segment) in segments.withIndex()) {
            val next = when {
                segment == FILE_TOKEN -> level.mapNotNull { item ->
                    val exact = File(item.dir, defaultFileName)
                    val file = if (exact.isFile) exact else dataFiles(item.dir).firstOrNull()
                    file?.let { item.copy(dir = it) }
                }
                segment.contains(LOCALE_TOKEN) || segment.contains(NAMESPACE_TOKEN) -> {
                    val tokens = Regex("\\{locale}|\\{namespace}").findAll(segment).toList()
                    val expression = buildString {
                        var start = 0
                        for (token in tokens) {
                            append(Regex.escape(segment.substring(start, token.range.first)))
                            append(if (token.value == NAMESPACE_TOKEN) "(.+)" else "(.+?)")
                            start = token.range.last + 1
                        }
                        append(Regex.escape(segment.substring(start)))
                    }
                    val regex = Regex(expression, RegexOption.IGNORE_CASE)
                    level.flatMap { item ->
                        val candidates = if (index < segments.lastIndex || segment == LOCALE_TOKEN) {
                            childDirs(item.dir)
                        } else {
                            dataFiles(item.dir)
                        }
                        candidates.mapNotNull { file ->
                            val match = regex.matchEntire(file.name) ?: return@mapNotNull null
                            if (file.isFile && !usesNamespace && isAggregateFile(file.name)) {
                                return@mapNotNull null
                            }
                            var locale = item.locale
                            var namespace = item.namespace
                            for ((tokenIndex, token) in tokens.withIndex()) {
                                val value = match.groupValues[tokenIndex + 1]
                                if (token.value == LOCALE_TOKEN) locale = value
                                else namespace = if (file.isFile && segment == NAMESPACE_TOKEN) {
                                    file.nameWithoutExtension
                                } else value
                            }
                            Level(locale, file, namespace)
                        }
                    }
                }
                else -> level.mapNotNull { item ->
                    File(item.dir, segment).takeIf { it.exists() }?.let { item.copy(dir = it) }
                }
            }
            if (next.isEmpty()) return emptyList()
            level = next
        }

        return level
            .filter { it.dir.isFile }
            .map { (locale, file, namespace) ->
                ResolvedLocale(locale ?: localeFromFileName(file), file, namespace)
            }
            .distinctBy { it.locale to it.file.path }
            .sortedWith(compareBy({ it.locale }, { it.file.path }))
    }

    private data class Level(val locale: String?, val dir: File, val namespace: String? = null)

    /** 固定遍历顺序，保证默认文件回退与兼容首文件稳定。 */
    private fun childDirs(dir: File): List<File> =
        dir.listFiles { f: File -> f.isDirectory }?.sortedBy { it.name }.orEmpty()

    /** 仅匹配受支持的数据文件，并固定合并顺序。 */
    private fun dataFiles(dir: File): List<File> =
        dir.listFiles { f: File ->
            f.isFile && f.extension.lowercase() in DATA_EXTENSIONS
        }?.sortedBy { it.name }.orEmpty()

    private fun localeFromFileName(file: File): String {
        val base = file.name.substringBeforeLast('.', file.name)
        return base.substringAfterLast('.', base).ifBlank { base }
    }

    companion object {
        val DATA_EXTENSIONS = setOf("json")

        private val AGGREGATE_NAMES = setOf("index", "index.json", "i18n", "dictionaries")

        private fun isAggregateFile(fileName: String): Boolean =
            fileName.substringBeforeLast('.') in AGGREGATE_NAMES

        const val DEFAULT_PATTERN = "{locale}/{file}"
        const val LOCALE_TOKEN = "{locale}"
        const val NAMESPACE_TOKEN = "{namespace}"
        const val FILE_TOKEN = "{file}"
    }
}

/** namespace 仅由显式 {namespace} 提取，默认值兼容旧构造调用。 */
data class ResolvedLocale(val locale: String, val file: File, val namespace: String? = null)
