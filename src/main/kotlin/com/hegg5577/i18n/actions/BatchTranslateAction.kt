package com.hegg5577.i18n.actions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.hegg5577.i18n.locale.LocaleRepository
import com.hegg5577.i18n.locale.TranslationSaver
import com.hegg5577.i18n.translator.PlaceholderProtector
import com.hegg5577.i18n.translator.TranslationEngine
import com.hegg5577.i18n.translator.TranslationEngineManager
import com.hegg5577.i18n.translator.TranslationException
import com.hegg5577.i18n.ui.I18nPanelService
import java.io.File
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 批量翻译：把配置的源语言里已有、目标语言里缺失的 key 自动补齐。
 *
 * 设计上刻意保守，因为这一步会**改写磁盘上的语言包**：
 * - 默认只补缺失 key，**绝不覆盖已存在的人工译文**；
 * - 先出预览让用户确认，再真正联网；
 * - 单条失败不影响其它条目，且不会写入半成品；
 * - 通过 IDE 的 Document 写入，**Ctrl+Z 可整体撤销**。
 */
class BatchTranslateAction : AnAction() {

    /** 待翻译的最小单元：同一语言下，相同原文的多条 key 合并成一次请求。 */
    internal class TranslateUnit(
        val locale: String,
        val sourceText: String,
        val keys: List<String>,
    )

    /** 使用配置的引擎生成预览，并将同一引擎交给后台任务。 */
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val root = project.basePath?.let(::File)

        if (root == null || !root.isDirectory) {
            notify(project, "无法定位项目根目录", NotificationType.WARNING)
            return
        }

        val context = com.hegg5577.i18n.settings.I18nContext.of(project)
        val repository = context.repository() ?: return
        val report = repository.buildReport(context.sourceLocale)
        if (report.isEmpty) {
            notify(
                project,
                "未找到语言包。当前配置：路径 ${context.localesPath} + 模式 ${context.pathMatcher}",
                NotificationType.WARNING,
            )
            return
        }

        val sourceValues = repository.valuesOf(context.sourceLocale)
        val units = buildUnits(report.missing, sourceValues)

        if (units.isEmpty()) {
            Messages.showInfoMessage(
                project,
                "所有语言的 key 都已齐全，没有需要翻译的条目。",
                TITLE,
            )
            return
        }

        val engine = TranslationEngineManager.getInstance(project).getActiveEngine()
        val answer = Messages.showYesNoDialog(
            project,
            buildPreview(units, context.sourceLocale, engine),
            TITLE,
            "开始翻译",
            "取消",
            Messages.getQuestionIcon(),
        )
        if (answer != Messages.YES) return

        runTranslation(project, repository, units, context.sourceLocale, engine)
    }

    /** 把「各语言缺失 key」压平成去重后的翻译单元。 */
    private fun buildUnits(
        missing: Map<String, List<String>>,
        sourceValues: Map<String, String>,
    ): List<TranslateUnit> {
        val units = ArrayList<TranslateUnit>()

        for ((locale, keys) in missing) {
            val byText = LinkedHashMap<String, MutableList<String>>()
            for (key in keys) {
                val text = sourceValues[key]?.takeIf { it.isNotBlank() } ?: continue
                byText.getOrPut(text) { ArrayList() } += key
            }
            for ((text, groupedKeys) in byText) {
                units += TranslateUnit(locale, text, groupedKeys)
            }
        }
        return units
    }

    /** 展示缺失项及本次实际使用的引擎，让预览与请求保持一致。 */
    internal fun buildPreview(units: List<TranslateUnit>, sourceLocale: String, engine: TranslationEngine): String {
        val perLocale = LinkedHashMap<String, Int>()
        var totalKeys = 0
        for (unit in units) {
            perLocale[unit.locale] = (perLocale[unit.locale] ?: 0) + unit.keys.size
            totalKeys += unit.keys.size
        }

        return buildString {
            appendLine("本操作将把 $sourceLocale 的原文翻译成下列语言并写入语言包：")
            appendLine()
            for ((locale, count) in perLocale) {
                appendLine("  $locale　$count 条")
            }
            appendLine()
            appendLine("共 $totalKeys 条缺失 key，去重合并为 ${units.size} 次翻译请求。")
            appendLine("只补全缺失项，不会改动任何已有译文。")
            appendLine()
            appendLine("预览：")
            for (unit in units.take(3)) {
                appendLine("  ${unit.keys.first()}")
                appendLine("    $sourceLocale：${unit.sourceText}")
            }
            if (units.size > 3) appendLine("  … 其余 ${units.size - 3} 条省略")
            appendLine()
            appendLine("翻译引擎：${engine.displayName}")
        }
    }

    /** 并发调用本次选定的引擎，汇总翻译与保存失败并刷新面板。 */
    private fun runTranslation(
        project: Project,
        repository: LocaleRepository,
        units: List<TranslateUnit>,
        sourceLocale: String,
        engine: TranslationEngine,
    ) {
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "i18n: 批量翻译语言包", true) {
                /** 后台翻译结束后统一保存，避免逐条请求直接改写语言包。 */
                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = false
                    indicator.fraction = 0.0

                    val byLocale = ConcurrentHashMap<String, ConcurrentHashMap<String, String>>()
                    val failures = Collections.synchronizedList(ArrayList<String>())

                    val executor: ExecutorService = Executors.newFixedThreadPool(CONCURRENCY)
                    try {
                        val futures = LinkedHashMap<TranslateUnit, java.util.concurrent.Future<String>>()
                        for (unit in units) {
                            futures[unit] = executor.submit<String> { translateUnit(unit, sourceLocale, engine) }
                        }

                        var done = 0
                        for ((unit, future) in futures) {
                            indicator.checkCanceled()
                            done++
                            indicator.fraction = done.toDouble() / futures.size
                            indicator.text = "翻译 ${unit.locale}（$done/${futures.size}）"

                            try {
                                val translated = future.get()
                                val bucket = byLocale.getOrPut(unit.locale) { ConcurrentHashMap() }
                                for (key in unit.keys) bucket[key] = translated
                            } catch (e: Exception) {
                                val reason = e.cause?.message ?: e.message ?: "未知原因"
                                failures += "${unit.locale} · ${unit.keys.first()}：$reason"
                            }
                        }
                    } finally {
                        executor.shutdownNow()
                    }

                    if (indicator.isCanceled) throw ProcessCanceledException()

                    indicator.text = "正在写入语言包…"
                    indicator.isIndeterminate = true

                    val result = writeBack(project, repository, sourceLocale, byLocale)
                    failures += result.failed.map { (locale, reason) -> "$locale 保存失败：$reason" }

                    ApplicationManager.getApplication().invokeAndWait {
                        project.getService(I18nPanelService::class.java).refresh()
                        notifyResult(project, result.succeeded, failures)
                    }
                }
            }
        )
    }

    /**
     * 单个翻译单元：套占位符保护 → 调引擎 → 还原。
     * 还原失败（占位符被翻译引擎改动或吞掉）时抛异常，该条被丢弃而不是写入坏内容。
     */
    internal fun translateUnit(unit: TranslateUnit, sourceLocale: String, engine: TranslationEngine): String {
        val protected = PlaceholderProtector.protect(unit.sourceText)
        val raw = engine.translate(listOf(protected.text), unit.locale, sourceLocale).singleOrNull()
            ?: throw TranslationException("翻译引擎未返回 ${unit.locale} 的单条译文")
        return protected.restore(raw)
            ?: throw TranslationException("占位符在翻译后丢失，为保证质量已丢弃该条")
    }

    /** 保存前重新检查缺失项，避免覆盖翻译等待期间补入的人工译文。 */
    private fun writeBack(
        project: Project,
        repository: LocaleRepository,
        sourceLocale: String,
        byLocale: Map<String, Map<String, String>>,
    ): TranslationSaver.Result {
        val edits = missingEdits(repository, byLocale)
        return TranslationSaver.save(project, repository, sourceLocale, edits, onlyMissing = true)
    }

    /** 已存在的 key 即使值为空也保留，批量操作只补真正缺失的项。 */
    internal fun missingEdits(
        repository: LocaleRepository,
        byLocale: Map<String, Map<String, String>>,
    ): Map<String, Map<String, String>> = byLocale.mapValues { (locale, values) ->
        val existing = repository.valuesOf(locale)
        values.filterKeys { it !in existing }
    }.filterValues { it.isNotEmpty() }

    private fun notifyResult(project: Project, written: List<String>, failures: List<String>) {
        val message = buildString {
            if (written.isNotEmpty()) {
                append("已写入 ${written.size} 个语言包（${written.joinToString("、")}），可用 Ctrl+Z 撤销。")
            } else {
                append("没有任何语言包被修改。")
            }
            if (failures.isNotEmpty()) {
                append(" 有 ${failures.size} 条翻译失败：${failures.take(3).joinToString("；")}")
                if (failures.size > 3) append(" …")
            }
        }

        val type = if (written.isEmpty() || failures.isNotEmpty()) NotificationType.WARNING else NotificationType.INFORMATION
        notify(project, message, type)
    }

    private fun notify(project: Project, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("i18n.helper.notifications")
            .createNotification(content, type)
            .notify(project)
    }

    private companion object {
        const val TITLE = "i18n: 批量翻译"
        const val CONCURRENCY = 4
    }
}
