package com.hegg5577.i18n.locale

import com.hegg5577.i18n.editor.DefaultPatterns
import com.hegg5577.i18n.editor.KeyUsageScanner
import java.io.File

/**
 * 代码里 i18n key 的实际使用情况。
 *
 * 这是「缺失检测」的数据来源：光看语言包只能发现「某语言缺某 key」，
 * 但发现不了「代码里写了 `t('a.b')` 而语言包根本没有 `a.b`」——
 * 后者才是真正会导致界面显示原始 key 的问题。
 *
 * 扫描范围刻意保守：只遍历项目源码目录，跳过 node_modules /  dist / 构建产物，
 * 按配置的后缀过滤；大文件超过阈值就跳过，避免拖慢 IDE。
 */
object UsageScanner {

    /** 单文件扫描上限，与行内提示保持一致。 */
    const val MAX_FILE_LENGTH = KeyUsageScanner.MAX_FILE_LENGTH

    /** 永远跳过的目录名（小写比较）。 */
    private val SKIP_DIRS = setOf(
        "node_modules", "dist", "build", "out", ".git", ".idea",
        "coverage", ".next", ".nuxt", ".output", ".cache",
    )

    /** 单个 key 的使用明细。 */
    data class Usage(
        val key: String,
        /** 相对项目根的文件路径列表。 */
        val files: List<String>,
    ) {
        val fileCount: Int get() = files.size
    }

    /**
     * 扫描项目源码，找出所有被引用的 i18n key。
     *
     * @param projectRoot 项目根目录
     * @param sourceDirs 源码目录（相对项目根），为空时用[DEFAULT_SOURCE_DIRS]
     * @param extensions 参与扫描的文件后缀
     * @param customPatterns 用户配置的正则；为空时用内置规则
     * @return key -> 使用位置；按 key 字典序输出，便于稳定展示
     */
    fun scan(
        projectRoot: File,
        sourceDirs: List<String> = DEFAULT_SOURCE_DIRS,
        extensions: Set<String> = emptySet(),
        customPatterns: List<String> = emptyList(),
    ): Map<String, Usage> {
        if (!projectRoot.isDirectory) return emptyMap()

        // key -> key -> 出现该 key 的文件集合（同一文件可能多次引用）
        val found = HashMap<String, LinkedHashSet<String>>()
        val allowedExt = extensions.ifEmpty { TranslationIndex.DEFAULT_EXTENSIONS }

        for (dir in sourceDirs.ifEmpty { DEFAULT_SOURCE_DIRS }) {
            walkFiles(File(projectRoot, dir), allowedExt) { file, relativePath ->
                if (file.length() > MAX_FILE_LENGTH) return@walkFiles
                val text = runCatching { file.readText() }.getOrNull() ?: return@walkFiles
                for (usage in KeyUsageScanner.scan(text, customPatterns, DefaultPatterns.ALL)) {
                    found.getOrPut(usage.key) { LinkedHashSet() }.add(relativePath)
                }
            }
        }

        return found
            .map { (key, files) -> Usage(key, files.toList()) }
            .sortedBy { it.key }
            .associate { it.key to it }
    }

    /**
     * 对比语言包与代码使用情况。
     *
     * @return 报告对象，包含缺失（代码用了但没定义）与多余（定义了但没用到）
     */
    fun analyze(
        projectRoot: File,
        localesDir: String,
        pathMatcher: String,
        keyStyle: KeyStyle,
        sourceDirs: List<String> = DEFAULT_SOURCE_DIRS,
        extensions: Set<String> = emptySet(),
        customPatterns: List<String> = emptyList(),
    ): UsageReport {
        val usages = scan(projectRoot, sourceDirs, extensions, customPatterns)
        val repo = LocaleRepository(projectRoot, localesDir, pathMatcher, keyStyle)
        val defined = repo.scanLocales()
            .flatMap { it.keys }
            .toSortedSet()

        // 代码用了但语言包没定义 —— 这才是真正会导致界面显示原始 key 的问题
        val missing = usages.keys.filterNot { defined.contains(it) }.sorted()

        // 语言包定义了但代码里没用到，通常是历史遗留
        val unused = defined.filterNot { usages.containsKey(it) }

        return UsageReport(
            usages = usages,
            missing = missing,
            unused = unused,
            definedCount = defined.size,
            usedCount = usages.size,
        )
    }

    /**
     * 递归遍历，跳过构建产物与依赖目录。
     *
     * 刻意不用 `inline`：Kotlin 的 inline 函数不允许递归。
     */
    private fun walkFiles(
        dir: File,
        extensions: Set<String>,
        onFile: (File, String) -> Unit,
    ) {
        if (!dir.isDirectory) return
        val children = dir.listFiles() ?: return
        for (child in children) {
            val name = child.name
            when {
                child.isDirectory -> {
                    if (name.lowercase() !in SKIP_DIRS) walkFiles(child, extensions, onFile)
                }
                child.isFile -> {
                    if (child.extension.lowercase() in extensions) {
                        onFile(child, child.path)
                    }
                }
            }
        }
    }

}

/** 默认源码目录。 */
val DEFAULT_SOURCE_DIRS = listOf("src", "app", "pages", "components")

/**
 * key 使用情况报告。
 */
data class UsageReport(
    /** key -> 使用位置。 */
    val usages: Map<String, UsageScanner.Usage>,
    /** 代码里用了但语言包没定义。 */
    val missing: List<String>,
    /** 语言包定义了但代码里没用到。 */
    val unused: List<String>,
    /** 语言包定义的 key 总数。 */
    val definedCount: Int,
    /** 代码里引用到的 key 总数。 */
    val usedCount: Int,
) {
    /** 使用率：已使用 / 已定义。 */
    val usageRate: Double
        get() = if (definedCount == 0) 0.0 else usedCount.toDouble() / definedCount
}