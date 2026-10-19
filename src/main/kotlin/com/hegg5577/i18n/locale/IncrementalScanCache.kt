package com.hegg5577.i18n.locale

import com.hegg5577.i18n.settings.I18nContext
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileEvent
import com.intellij.openapi.vfs.VirtualFileListener
import java.io.File

/**
 * 增量扫描缓存。
 *
 * 跟踪文件修改时间，只重新扫描改动过的文件。
 * 通过 VirtualFileListener 监听文件系统变化，实现热更新。
 *
 * **性能提升**：从全量扫描 O(n) 降低到增量扫描 O(Δ)。
 */
class IncrementalScanCache(
    private val project: Project,
) : VirtualFileListener {

    /** 文件路径 -> 上次扫描时间。 */
    private val scanTimestamps = mutableMapOf<String, Long>()

    /** 缓存的键值对使用情况。 */
    private var cachedUsages: Map<String, UsageScanner.Usage>? = null

    /** 缓存的缺失键列表。 */
    private var cachedMissing: List<String>? = null

    /** 缓存的多余键列表。 */
    private var cachedUnused: List<String>? = null

    /** 上次完整扫描的时间戳。 */
    private var lastFullScanTime: Long = 0

    /**
     * 执行增量或全量扫描。
     *
     * 如果没有缓存或距上次完整扫描超过 60 秒，执行全量扫描；
     * 否则只扫描改动过的文件。
     */
    fun scanUsages(
        projectRoot: File,
        sourceDirs: List<String> = DEFAULT_SOURCE_DIRS,
        extensions: Set<String> = emptySet(),
        customPatterns: List<String> = emptyList(),
    ): Map<String, UsageScanner.Usage> {
        val now = System.currentTimeMillis()

        // 距上次完整扫描超过 60 秒，或没有缓存 —— 执行全量扫描
        if (cachedUsages == null || (now - lastFullScanTime > 60_000)) {
            return fullScan(projectRoot, sourceDirs, extensions, customPatterns, now)
        }

        // 否则执行增量扫描
        return incrementalScan(projectRoot, sourceDirs, extensions, customPatterns, now)
    }

    /**
     * 执行完整扫描，更新所有缓存。
     */
    private fun fullScan(
        projectRoot: File,
        sourceDirs: List<String>,
        extensions: Set<String>,
        customPatterns: List<String>,
        now: Long,
    ): Map<String, UsageScanner.Usage> {
        scanTimestamps.clear()
        val result = UsageScanner.scan(projectRoot, sourceDirs, extensions, customPatterns)

        // 记录扫描时间
        for ((key, usage) in result) {
            for (file in usage.files) {
                val f = File(projectRoot, file)
                if (f.exists()) {
                    scanTimestamps[file] = f.lastModified()
                }
            }
        }

        cachedUsages = result
        lastFullScanTime = now
        invalidateReports()
        return result
    }

    /**
     * 执行增量扫描，只检查改动过的文件。
     */
    private fun incrementalScan(
        projectRoot: File,
        sourceDirs: List<String>,
        extensions: Set<String>,
        customPatterns: List<String>,
        now: Long,
    ): Map<String, UsageScanner.Usage> {
        val modified = mutableListOf<String>()
        val current = cachedUsages?.toMutableMap() ?: mutableMapOf()
        val allowedExt = extensions.ifEmpty { TranslationIndex.DEFAULT_EXTENSIONS }

        // 检查所有源码文件
        for (dir in sourceDirs.ifEmpty { DEFAULT_SOURCE_DIRS }) {
            val sourceDir = File(projectRoot, dir)
            findModifiedFiles(sourceDir, allowedExt, modified) { file, relPath ->
                val lastMod = scanTimestamps[relPath] ?: 0L
                file.lastModified() > lastMod
            }
        }

        // 如果没有改动，直接返回缓存
        if (modified.isEmpty()) {
            return cachedUsages ?: emptyMap()
        }

        // 移除改动文件对应的旧键
        for (file in modified) {
            current.forEach { (_, usage) ->
                usage.files.forEach { f ->
                    if (f == file) {
                        current.remove(usage.key)
                    }
                }
            }
        }

        // 重新扫描改动的文件
        for (file in modified) {
            val f = File(projectRoot, file)
            if (f.isFile && f.length() <= UsageScanner.MAX_FILE_LENGTH) {
                val text = runCatching { f.readText() }.getOrNull() ?: continue
                val usages = com.hegg5577.i18n.editor.KeyUsageScanner.scan(
                    text,
                    customPatterns,
                    com.hegg5577.i18n.editor.DefaultPatterns.ALL
                )

                for (usage in usages) {
                    current.getOrPut(usage.key) { UsageScanner.Usage(usage.key, emptyList()) }
                        .let { existing ->
                            val files = existing.files.toMutableList()
                            if (!files.contains(file)) files.add(file)
                            current[usage.key] = UsageScanner.Usage(usage.key, files)
                        }
                }

                scanTimestamps[file] = f.lastModified()
            }
        }

        cachedUsages = current
        invalidateReports()
        return current
    }

    /**
     * 查找改动过的文件。
     */
    private fun findModifiedFiles(
        dir: File,
        extensions: Set<String>,
        result: MutableList<String>,
        predicate: (File, String) -> Boolean,
    ) {
        if (!dir.isDirectory) return
        val children = dir.listFiles() ?: return

        for (child in children) {
            val name = child.name.lowercase()
            if (name in UsageScanner::class.java.getDeclaredField("SKIP_DIRS").let {
                it.isAccessible = true
                (it.get(null) as Set<*>).map { it.toString().lowercase() }
            }) {
                continue
            }

            when {
                child.isDirectory -> findModifiedFiles(child, extensions, result, predicate)
                child.isFile && child.extension.lowercase() in extensions -> {
                    val relPath = child.path
                    if (predicate(child, relPath)) {
                        result.add(relPath)
                    }
                }
            }
        }
    }

    /**
     * 分析键的缺失与多余。
     */
    fun analyzeUsages(
        projectRoot: File,
        localesDir: String,
        pathMatcher: String,
        keyStyle: KeyStyle,
        sourceDirs: List<String> = DEFAULT_SOURCE_DIRS,
        extensions: Set<String> = emptySet(),
        customPatterns: List<String> = emptyList(),
    ): UsageReport {
        // 检查缓存是否有效
        if (cachedMissing != null && cachedUnused != null) {
            val usages = scanUsages(projectRoot, sourceDirs, extensions, customPatterns)
            return UsageReport(
                usages = usages,
                missing = cachedMissing!!,
                unused = cachedUnused!!,
                definedCount = 0,
                usedCount = usages.size,
            )
        }

        // 否则执行完整分析
        val usages = scanUsages(projectRoot, sourceDirs, extensions, customPatterns)
        val repo = LocaleRepository(projectRoot, localesDir, pathMatcher, keyStyle)
        val defined = repo.scanLocales()
            .flatMap { it.keys }
            .toSortedSet()

        val missing = usages.keys.filterNot { defined.contains(it) }.sorted()
        val unused = defined.filterNot { usages.containsKey(it) }

        cachedMissing = missing
        cachedUnused = unused

        return UsageReport(
            usages = usages,
            missing = missing,
            unused = unused,
            definedCount = defined.size,
            usedCount = usages.size,
        )
    }

    /**
     * 让缓存的报告失效，下次需要重新计算。
     */
    fun invalidateReports() {
        cachedMissing = null
        cachedUnused = null
    }

    /**
     * 清空所有缓存。
     */
    fun clear() {
        scanTimestamps.clear()
        cachedUsages = null
        cachedMissing = null
        cachedUnused = null
        lastFullScanTime = 0
    }

    // VirtualFileListener 实现

    override fun beforePropertyChange(event: com.intellij.openapi.vfs.VirtualFilePropertyEvent) {}

    override fun beforeContentsChange(event: com.intellij.openapi.vfs.VirtualFileEvent) {}

    override fun contentsChanged(event: com.intellij.openapi.vfs.VirtualFileEvent) {
        invalidateReports()
    }

    override fun fileCreated(event: com.intellij.openapi.vfs.VirtualFileEvent) {
        invalidateReports()
    }

    override fun fileDeleted(event: com.intellij.openapi.vfs.VirtualFileEvent) {
        invalidateReports()
    }

    override fun fileMoved(event: com.intellij.openapi.vfs.VirtualFileMoveEvent) {
        invalidateReports()
    }

    override fun fileCopied(event: com.intellij.openapi.vfs.VirtualFileCopyEvent) {
        invalidateReports()
    }

    override fun propertyChanged(event: com.intellij.openapi.vfs.VirtualFilePropertyEvent) {}
}
