package com.hegg5577.i18n.ui

import com.intellij.openapi.components.Service
import com.intellij.codeInsight.hints.declarative.impl.DeclarativeInlayHintsPassFactory
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentFactory
import com.hegg5577.i18n.locale.LocaleKeys
import com.hegg5577.i18n.settings.I18nContext

/**
 * 侧边栏「i18n」面板的数据服务。
 *
 * 抽成 project 级 Service 是为了让 UI 与数据加载解耦：
 * 刷新动作只管调 [refresh]，UI 订阅回调重绘，符合平台生命周期。
 */
@Service(Service.Level.PROJECT)
class I18nPanelService(private val project: Project) {

    data class Snapshot(
        val rows: List<TranslationRow>,
        val sourceLocale: String,
        val displayLocales: List<String>,
        /** locale -> (已翻译数, 总数)，用于进度概览。 */
        val progress: Map<String, Pair<Int, Int>>,
        /** 代码里用到但语言包没定义的 key。 */
        val missingKeys: List<MissingKey> = emptyList(),
        /** 语言包定义了但代码里没用到。 */
        val unusedKeys: List<String> = emptyList(),
        /** 语言包定义的 key 总数 / 代码引用到的 key 总数。 */
        val definedCount: Int = 0,
        val usedCount: Int = 0,
        /** 配置或路径有问题时的提示信息，为空表示一切正常。 */
        val warning: String = "",
    )

    /** 一个缺失的 key 及其引用位置。 */
    data class MissingKey(
        val key: String,
        /** 引用该 key 的文件（相对路径）。 */
        val files: List<String>,
    )

    private val listeners = mutableListOf<(Snapshot) -> Unit>()
    private var cached: Snapshot? = null

    /** 订阅数据变化，返回的句柄用于取消订阅。 */
    fun subscribe(listener: (Snapshot) -> Unit): () -> Unit {
        listeners += listener
        return { listeners -= listener }
    }

    /** 重新扫描语言包并通知订阅者。 */
    fun refresh() {
        com.hegg5577.i18n.locale.TranslationIndex.invalidate()
        val snapshot = load()
        cached = snapshot
        listeners.toList().forEach { it(snapshot) }
        // 语言包和设置的变化不会修改源码，需主动让行内提示重新计算。
        EditorFactory.getInstance().allEditors.filter { it.project == project }.forEach {
            DeclarativeInlayHintsPassFactory.scheduleRecompute(it, project)
        }
    }

    /** 取当前快照，未加载过则现场构建。 */
    fun snapshot(): Snapshot = cached ?: load().also { cached = it }

    /**
     * 挑选基准语言。
     *
     * 优先级：配置指定 → key 数量最多的语言 → 列表首个。
     * 刻意**不简单取字母序首个** —— 目录名排序把 `ar`（阿拉伯语）排在最前，
     * 一旦配置的源语言写错，整张表会以阿拉伯语为原文显示，
     * 看起来"全是译文但 key 对不上"。用 key 数量兜底更稳。
     */
    private fun pickSource(configured: String, locales: List<LocaleKeys>): String? {
        locales.firstOrNull { it.locale == configured }?.let { return it.locale }
        return locales.maxByOrNull { it.keys.size }?.locale ?: locales.firstOrNull()?.locale
    }

    /** 聚合每种语言的全部文件，并去重展示语言列表。 */
    private fun load(): Snapshot {
        val context = I18nContext.of(project)
        if (!context.isValid) {
            return Snapshot(
                emptyList(), context.sourceLocale, context.displayLocales, emptyMap(),
                warning = "无法定位项目根目录",
            )
        }

        val root = context.projectRoot!!
        val repo = context.repository() ?: return Snapshot(
            emptyList(), context.sourceLocale, context.displayLocales, emptyMap(),
            warning = "无法定位项目根目录",
        )

        val all = repo.scanLocales()
        if (all.isEmpty()) {
            val warning = "未找到语言包。当前配置：路径 ${context.localesPath} + 模式 ${context.pathMatcher}"
            return Snapshot(
                emptyList(), context.sourceLocale, context.displayLocales, emptyMap(),
                warning = warning,
            )
        }

        val sourceLocale = pickSource(context.sourceLocale, all) ?: all.first().locale

        // 配置里指定的语言排前面，其余按字母序，减少横向滚动
        val preferred = context.displayLocales.filter { it != sourceLocale && all.any { l -> l.locale == it } }
        val rest = all.map { it.locale }
            .filter { it != sourceLocale && !preferred.contains(it) }
            .sorted()
        val displayLocales = (preferred + rest).distinct()

        // 取所有语言的 key 并集作为行集合：任何语言里出现过的 key 都要列出来，
        // 否则「某语言多出一个 key」这类问题在表里根本看不见
        val allKeys = LinkedHashSet<String>()
        all.forEach { allKeys += it.keys }

        val sourceKeys = all.firstOrNull { it.locale == sourceLocale }?.keys.orEmpty()
        // 源语言里的 key 排在前面，其余按字母序，避免刷新时行序跳动
        val sortedKeys = allKeys.sortedWith(compareBy({ if (sourceKeys.contains(it)) 0 else 1 }, { it }))

        val valueCache = HashMap<String, Map<String, String>>()
        for (item in all) {
            valueCache[item.locale] = repo.valuesOf(item.locale)
        }

        val rows = sortedKeys.map { key ->
            val values = HashMap<String, String>()
            for (item in all) {
                valueCache[item.locale]?.get(key)?.let { values[item.locale] = it }
            }
            TranslationRow(
                key = key,
                sourceText = values[sourceLocale].orEmpty(),
                values = values,
            )
        }

        val total = allKeys.size.coerceAtLeast(1)
        val progress = all.associate { item ->
            val done = allKeys.count { key -> !valueCache[item.locale]?.get(key).isNullOrBlank() }
            item.locale to (done to total)
        }

        // 扫描代码里实际用到的 key，与语言包比对找出「缺失」与「多余」
        val usage = runCatching {
            com.hegg5577.i18n.locale.UsageScanner.analyze(
                projectRoot = root,
                localesDir = context.localesPath,
                pathMatcher = context.pathMatcher,
                keyStyle = context.keyStyle,
                sourceDirs = context.sourceDirs.ifEmpty { com.hegg5577.i18n.locale.DEFAULT_SOURCE_DIRS },
                extensions = context.supportedExtensions,
                customPatterns = context.usageMatchRegex,
            )
        }.getOrNull()

        // 源语言没配对时给出提示，避免用户对着"整表都是原文"的界面一头雾水
        val warning = if (all.none { it.locale == context.sourceLocale }) {
            "配置指定的源语言「${context.sourceLocale}」不存在，当前改用「${Flags.displayName(sourceLocale)}」作为基准。"
        } else {
            ""
        }

        return Snapshot(
            rows = rows,
            sourceLocale = sourceLocale,
            displayLocales = displayLocales,
            progress = progress,
            missingKeys = usage?.missing?.map { key ->
                MissingKey(key, usage.usages[key]?.files.orEmpty())
            }.orEmpty(),
            unusedKeys = usage?.unused.orEmpty(),
            definedCount = usage?.definedCount ?: 0,
            usedCount = usage?.usedCount ?: 0,
            warning = warning,
        )
    }
}

/**
 * 在侧边栏注册 i18n 面板。
 *
 * 结构对齐原版：多语言对照表为主体，语言列表随项目实际语言包动态变化。
 */
class I18nToolWindowFactory : ToolWindowFactory {

    companion object {
        const val TOOL_WINDOW_ID = "i18n-helper"
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val service = project.getService(I18nPanelService::class.java)

        // 概览面板
        val overviewPanel = OverviewPanel()

        // 翻译对照表
        val tablePanel = TranslationTablePanel("en", emptyList())
        tablePanel.onEditKey = { key -> openKeyEditor(project, key, service) }

        // 缺失检测
        val missingPanel = MissingKeysPanel()

        // 添加三个 tab
        val manager = ContentFactory.getInstance()
        val overviewContent = manager.createContent(overviewPanel, "概览", false)
        toolWindow.contentManager.addContent(overviewContent)

        val tableContent = manager.createContent(tablePanel, "翻译对照", false)
        toolWindow.contentManager.addContent(tableContent)

        val missingContent = manager.createContent(missingPanel, "检测", false)
        toolWindow.contentManager.addContent(missingContent)

        // 数据订阅
        val unsubscribe = service.subscribe { snapshot ->
            // ToolWindow 已关闭时不再重绘
            if (toolWindow.isDisposed) return@subscribe

            // 更新概览面板
            overviewPanel.setProgress(snapshot.progress)
            overviewPanel.setStats(
                definedCount = snapshot.definedCount,
                usedCount = snapshot.usedCount,
                missingCount = snapshot.missingKeys.size,
                unusedCount = snapshot.unusedKeys.size,
            )
            overviewPanel.setActions(
                onRefresh = { service.refresh() },
                onDiagnose = { showDiagnoseDialog(project) },
                onSettings = { project.showSettingsEditor() },
            )

            // 更新翻译对照表
            tablePanel.setLocales(snapshot.sourceLocale, snapshot.displayLocales)
            tablePanel.setRows(snapshot.rows)
            tablePanel.setStatus(snapshot.warning)

            // 更新缺失检测面板
            missingPanel.setData(
                missing = snapshot.missingKeys.map { it.key to it.files },
                unused = snapshot.unusedKeys,
                definedCount = snapshot.definedCount,
                usedCount = snapshot.usedCount,
            )
            missingPanel.onEditKey = { key -> openKeyEditor(project, key, service) }
        }

        Disposer.register(toolWindow.disposable) { unsubscribe() }
        service.refresh()
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}

/**
 * 打开 key 编辑弹窗。
 *
 * 从面板服务读当前数据而不是重新扫盘：用户刚刷新过，数据是最新的，
 * 也避免每次双击都重新遍历项目。
 */
internal fun openKeyEditor(project: Project, key: String, service: I18nPanelService) {
    val context = I18nContext.of(project)
    val repo = context.repository() ?: return
    val snapshot = service.snapshot()

    val locales = (listOf(snapshot.sourceLocale) + snapshot.displayLocales).distinct()
    // 缺失的 key 用 null 表示「语言包里根本没有」，弹窗据此提示"保存后创建"
    val values: Map<String, String?> = locales.associateWith { repo.valuesOf(it)[key] }

    val localeFiles = try {
        locales.mapNotNull { locale ->
            repo.localeFile(locale, key, snapshot.sourceLocale)?.let { locale to it }
        }.toMap()
    } catch (e: IllegalStateException) {
        com.intellij.openapi.ui.Messages.showErrorDialog(project, e.message.orEmpty(), "无法定位语言包文件")
        return
    }

    KeyEditorDialog(
        project = project,
        key = key,
        sourceLocale = snapshot.sourceLocale,
        values = values,
        locales = locales,
        localeFiles = localeFiles,
        onSaved = {
            // 保存后立即刷新，让用户看到状态变化
            service.refresh()
        },
    ).show()
}

/**
 * 显示诊断对话框。
 */
private fun showDiagnoseDialog(project: Project) {
    val context = I18nContext.of(project)
    if (!context.isValid) {
        com.intellij.openapi.ui.Messages.showErrorDialog(
            project,
            "无法定位项目根目录",
            "诊断失败",
        )
        return
    }

    val repo = context.repository()
    if (repo == null) {
        com.intellij.openapi.ui.Messages.showErrorDialog(
            project,
            "无法初始化语言包仓库",
            "诊断失败",
        )
        return
    }

    val report = repo.buildReport(context.sourceLocale)
    val sections = mutableListOf<Pair<String, List<DiagnosticItem>>>()

    // 检查语言包
    sections.add(
        "语言包检查" to listOf(
            DiagnosticItem(
                ok = report.locales.isNotEmpty(),
                title = if (report.locales.isEmpty()) "未找到任何语言包" else "找到 ${report.locales.size} 个语言",
                detail = report.locales.joinToString(", ") { it.locale },
            ),
        )
    )

    // 检查一致性
    if (report.locales.isNotEmpty()) {
        val consistencyItems = mutableListOf<DiagnosticItem>()
        val baseLocale = report.locales.first().locale
        for (locale in report.locales) {
            if (locale.locale == baseLocale) continue
            val missing = report.missing[locale.locale]?.size ?: 0
            val extra = report.extra[locale.locale]?.size ?: 0
            consistencyItems.add(
                DiagnosticItem(
                    ok = missing == 0 && extra == 0,
                    title = "${Flags.displayName(locale.locale)}",
                    detail = when {
                        missing == 0 && extra == 0 -> "完全一致"
                        missing > 0 && extra == 0 -> "缺失 $missing 个 key"
                        missing == 0 && extra > 0 -> "多余 $extra 个 key"
                        else -> "缺失 $missing 个，多余 $extra 个"
                    },
                )
            )
        }
        if (consistencyItems.isNotEmpty()) {
            sections.add("语言包一致性" to consistencyItems)
        }
    }

    val conclusionOk = report.isConsistent && report.locales.isNotEmpty()
    val conclusion = when {
        report.locales.isEmpty() -> "未找到语言包文件，请检查配置"
        report.isConsistent -> "所有语言包完全一致，没有问题"
        else -> "发现 ${report.missing.size + report.extra.size} 处不一致，需要修复"
    }

    DiagnosticDialog(
        project = project,
        fileName = "i18n 诊断",
        reportText = buildString {
            append("i18n 诊断报告\n")
            append("时间：${java.time.LocalDateTime.now()}\n\n")
            for ((section, items) in sections) {
                append("【$section】\n")
                for (item in items) {
                    append("  ${if (item.ok) "✓" else "✗"} ${item.title}\n")
                    if (item.detail.isNotBlank()) append("     ${item.detail}\n")
                }
            }
            append("\n【结论】$conclusion\n")
        },
        sections = sections,
        conclusionOk = conclusionOk,
        conclusion = conclusion,
    ).show()
}

/**
 * 打开设置对话框。
 */
private fun Project.showSettingsEditor() {
    com.intellij.openapi.options.ShowSettingsUtil.getInstance()
        .showSettingsDialog(this, com.hegg5577.i18n.settings.I18nConfigurable::class.java)
}

/** 便捷入口：主动打开侧边栏面板。 */
fun Project.showI18nToolWindow() {
    ToolWindowManager.getInstance(this)
        .getToolWindow(I18nToolWindowFactory.TOOL_WINDOW_ID)
        ?.activate(null, true)
}
