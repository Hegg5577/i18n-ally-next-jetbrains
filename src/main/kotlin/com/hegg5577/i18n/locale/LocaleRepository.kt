package com.hegg5577.i18n.locale

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

data class LocaleKeys(
    val locale: String,
    val file: File,
    val keys: Set<String>,
)

data class LocaleReport(
    val locales: List<LocaleKeys>,
    val missing: Map<String, List<String>>,
    val extra: Map<String, List<String>>,
) {
    val isEmpty: Boolean get() = locales.isEmpty()
    val isConsistent: Boolean get() = missing.isEmpty() && extra.isEmpty()
}

enum class KeyStyle {
    NESTED,
    FLAT,
    AUTO;

    companion object {
        fun from(name: String?): KeyStyle = when (name?.lowercase()) {
            "flat" -> FLAT
            "auto" -> AUTO
            else -> NESTED
        }
    }
}

class LocaleRepository(
    private val projectRoot: File,
    private val localesDir: String = DEFAULT_LOCALES_DIR,
    private val pathMatcher: String = PathMatcher.DEFAULT_PATTERN,
    val keyStyle: KeyStyle = KeyStyle.NESTED,
    private val fileName: String = DEFAULT_FILE_NAME,
) {
    companion object {
        const val DEFAULT_LOCALES_DIR = "src/i18n/locales"
        const val DEFAULT_FILE_NAME = "index.json"
    }

    private val matcher = PathMatcher(pathMatcher, fileName)

    /** 保留每个文件及其命名空间，供保存操作逐文件处理。 */
    fun resolvedFiles(): List<ResolvedLocale> = matcher.resolve(File(projectRoot, localesDir))

    /** 返回指定语言的全部文件，顺序与路径解析结果一致。 */
    fun filesFor(locale: String): List<File> =
        resolvedFiles().filter { it.locale == locale }.map { it.file }

    /** 按语言合并所有文件的 key，file 仅保留首个文件以兼容旧调用。 */
    fun scanLocales(): List<LocaleKeys> =
        resolvedFiles().groupBy { it.locale }.map { (locale, files) ->
            LocaleKeys(locale, files.first().file, files.flatMap { parseKeys(it.file) }.toSet())
        }

    fun buildReport(sourceLocale: String = "en"): LocaleReport {
        val all = scanLocales()
        val source = all.firstOrNull { it.locale == sourceLocale }
            ?: all.maxByOrNull { it.keys.size }
            ?: return LocaleReport(emptyList(), emptyMap(), emptyMap())

        val missing = LinkedHashMap<String, List<String>>()
        val extra = LinkedHashMap<String, List<String>>()

        for (item in all) {
            if (item.locale == source.locale) continue
            val miss = (source.keys - item.keys).sorted()
            val ext = (item.keys - source.keys).sorted()
            if (miss.isNotEmpty()) missing[item.locale] = miss
            if (ext.isNotEmpty()) extra[item.locale] = ext
        }
        return LocaleReport(all, missing, extra)
    }

    /** 合并指定语言的全部文件，重名 key 按稳定文件顺序覆盖。 */
    fun valuesOf(locale: String): Map<String, String> =
        LinkedHashMap<String, String>().apply {
            filesFor(locale).forEach { putAll(valuesOfFile(it)) }
        }

    /** 兼容旧的单文件调用；多文件写入应使用带 key 的重载。 */
    fun localeFile(locale: String): File? = filesFor(locale).firstOrNull()

    /**
     * 优先定位目标语言中 key 的唯一所属文件；新增 key 按源语言的命名空间定位。
     * 单文件布局仍允许新增 key，多文件布局缺少归属或存在歧义时拒绝猜测写入位置。
     */
    fun localeFile(locale: String, key: String, sourceLocale: String): File? {
        val files = resolvedFiles()
        val targets = files.filter { it.locale == locale }
        val owners = targets.filter { key in valuesOfFile(it.file) }
        check(owners.size <= 1) {
            "语言「$locale」的 key「$key」存在于多个文件：${owners.joinToString { it.file.path }}"
        }
        owners.singleOrNull()?.let { return it.file }

        val sources = files.filter { it.locale == sourceLocale }
        if (!matcher.usesNamespace && targets.size <= 1 && sources.size <= 1) {
            return targets.singleOrNull()?.file
        }

        val sourceOwners = sources.filter { key in valuesOfFile(it.file) }
        check(sourceOwners.size == 1) {
            "无法定位语言「$locale」的 key「$key」：源语言「$sourceLocale」中该 key 的所属文件不存在或不唯一"
        }
        val namespace = sourceOwners.single().namespace
        check(namespace != null) {
            "无法定位 key「$key」：多文件布局需要显式配置 {namespace}"
        }
        val matches = targets.filter { it.namespace == namespace }
        check(matches.size == 1) {
            "无法定位语言「$locale」的 key「$key」：namespace「$namespace」对应文件不存在或不唯一"
        }
        return matches.single().file
    }

    /** 兼容旧 API，每个语言仅返回首个文件；完整遍历请使用 resolvedFiles。 */
    fun localeFiles(): Map<String, File?> =
        resolvedFiles().groupBy { it.locale }.mapValues { (_, files) -> files.first().file }

    /** AUTO 最后覆盖顶层完整 key，避免嵌套展开结果受 JSON 属性顺序影响。 */
    private fun flattenValues(obj: JsonObject, prefix: String = ""): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        if (keyStyle == KeyStyle.FLAT) {
            for ((name, value) in obj.entrySet()) {
                if (value.isJsonPrimitive) result[name] = value.asString
            }
            return result
        }
        for ((name, value) in obj.entrySet()) {
            val key = if (prefix.isEmpty()) name else "$prefix.$name"
            when {
                value.isJsonObject -> result += flattenValues(value.asJsonObject, key)
                value.isJsonPrimitive -> result[key] = value.asString
            }
        }
        if (keyStyle == KeyStyle.AUTO && prefix.isEmpty()) {
            for ((name, value) in obj.entrySet()) {
                if (value.isJsonPrimitive) result[name] = value.asString
            }
        }
        return result
    }

    /** 复用值解析以保证扫描与查询对 key 的理解一致。 */
    private fun parseKeys(file: File): Set<String> = valuesOfFile(file).keys

    /** 读取单个文件，供保存调用方判断 key 的实际归属。 */
    fun valuesOfFile(file: File): Map<String, String> = runCatching {
        flattenValues(JsonParser.parseString(file.readText()).asJsonObject)
    }.getOrDefault(emptyMap())
}
