package com.hegg5577.i18n.actions

import com.intellij.codeInsight.hints.declarative.InlayHintsProviderFactory
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.psi.PsiFile
import com.hegg5577.i18n.editor.I18nInlayCollector
import com.hegg5577.i18n.editor.I18nInlayProviderFactory
import com.hegg5577.i18n.editor.KeyUsageScanner
import com.hegg5577.i18n.locale.TranslationIndex
import com.hegg5577.i18n.settings.I18nContext
import com.hegg5577.i18n.ui.DiagnosticDialog
import com.hegg5577.i18n.ui.DiagnosticItem

/**
 * 诊断当前文件的行内译文提示链路。
 *
 * 存在的意义：提示没显示时，问题可能出在任意一环（文件类型、语言 ID、扩展点注册、
 * 语言包路径、key 不存在、Inlay Hints 未勾选）。与其猜，不如一次性把状态全列出来。
 *
 * 判定逻辑全部复用 [KeyUsageScanner] 与 [TranslationIndex]，与真正生效的收集器同源，
 * 保证"诊断说会显示"和"实际会显示"是同一套判断。
 */
class DiagnoseCurrentFileAction : AnAction() {

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project
        if (project == null) {
            Messages.showWarningDialog("没有打开的项目。", DIALOG_TITLE)
            return
        }

        val psiFile = event.getData(CommonDataKeys.PSI_FILE)
        if (psiFile == null) {
            Messages.showWarningDialog("请先把光标放到编辑器里的某个文件中再执行诊断。", DIALOG_TITLE)
            return
        }

        // PSI 读取必须在读锁内进行；computeBlocking 是 compute 的非废弃替代
        val file = ReadAction.computeBlocking<PsiFile, RuntimeException> { psiFile }
        val text = ReadAction.computeBlocking<String, RuntimeException> { file.text }
        val report = diagnose(project, file, text)

        DiagnosticDialog(
            project = project,
            fileName = file.name,
            reportText = report.text,
            sections = report.sections,
            conclusionOk = report.conclusionOk,
            conclusion = report.conclusion,
        ).show()
    }

    /** 诊断产物：既有可复制的纯文本，也有用于面板渲染的结构化数据。 */
    private data class Diagnosis(
        val text: String,
        val sections: List<Pair<String, List<DiagnosticItem>>>,
        val conclusionOk: Boolean,
        val conclusion: String,
    )

    /** 与收集器读取同一份配置，避免自定义语言或路径时给出错误结论。 */
    private fun diagnose(project: Project, file: PsiFile, text: String): Diagnosis {
        val context = I18nContext.of(project)
        val projectRoot = project.basePath
        val languageId = file.language.id
        val locale = context.effectiveAnnotationLocale
        val fallback = if (context.fallbackToSource) context.sourceLocale else ""
        val values = TranslationIndex.values(
            projectRoot = projectRoot,
            locale = locale,
            fallbackLocale = fallback,
            localesDir = context.localesPath,
            pathMatcher = context.pathMatcher,
            keyStyle = context.keyStyle,
        )

        val hits = ArrayList<KeyUsageScanner.Usage>()
        val resolved = ArrayList<Pair<KeyUsageScanner.Usage, String>>()
        val missed = LinkedHashSet<String>()
        for (usage in KeyUsageScanner.scan(text, context.usageMatchRegex)) {
            hits += usage
            val translated = values[usage.key]
            if (translated == null) missed += usage.key else resolved += usage to translated
        }

        val tooLarge = KeyUsageScanner.isTooLarge(text.length)
        val extSupported = TranslationIndex.isSupported(file.name, context.supportedExtensions)
        val langSupported = I18nInlayProviderFactory.supports(languageId)
        val providerVisible = providerRegistered(file)

        val willShow = context.isValid && context.inlineAnnotationEnabled &&
            extSupported && langSupported && providerVisible && !tooLarge

        val sections = buildList {
            add("当前文件" to listOf(
                DiagnosticItem(true, file.name, "后缀 ${file.name.substringAfterLast('.', "无")} · 语言 ID $languageId · ${fmt(text.length)} 字符"),
            ))

            add("加载判定" to listOf(
                DiagnosticItem(context.inlineAnnotationEnabled, "已启用行内译文提示", "提示语言：$locale", if (context.inlineAnnotationEnabled) "" else "请在 i18n tool plus 设置中启用"),
                DiagnosticItem(extSupported, "后缀在支持列表内", context.supportedExtensions.ifEmpty { TranslationIndex.DEFAULT_EXTENSIONS }.joinToString(" / "), if (extSupported) "" else "该文件不会被扫描"),
                DiagnosticItem(langSupported, "语言 ID 已绑定 Provider", languageId, if (langSupported) "" else "需补进 I18nInlayProviderFactory.LANGUAGE_IDS"),
                DiagnosticItem(providerVisible, "扩展点已生效", I18nInlayCollector.PROVIDER_ID, if (providerVisible) "" else "检查 plugin.xml 的 declarativeInlayProviderFactory"),
                DiagnosticItem(!tooLarge, "文件大小在上限内", "上限 ${fmt(KeyUsageScanner.MAX_FILE_LENGTH)} 字符", if (!tooLarge) "" else "文件过大，已跳过"),
            ))

            add("项目与语言包" to buildList {
                add(DiagnosticItem(context.isValid, "项目根定位", projectRoot ?: "无法获取", if (context.isValid) "" else "检查项目根目录是否存在"))
                if (projectRoot != null) {
                    for (language in listOf(locale, fallback).filter { it.isNotBlank() }.distinct()) {
                        val count = TranslationIndex.countFor(projectRoot, language, context.localesPath, context.pathMatcher, context.keyStyle)
                        add(DiagnosticItem(count > 0, "$language 语言包可用", "$language · ${fmt(count)} 个 key", if (count > 0) "" else "语言包未找到或解析失败"))
                    }
                    add(DiagnosticItem(values.isNotEmpty(), "可用于提示的 key", "${fmt(values.size)} 个", if (values.isNotEmpty()) "" else "索引为空，行内提示不会出现"))
                }
            })

            add("扫描结果" to listOf(
                DiagnosticItem(true, "t('...') 命中", "${fmt(hits.size)} 处", ""),
                DiagnosticItem(resolved.isNotEmpty(), "能解析出译文", "${fmt(resolved.size)} 处", ""),
                DiagnosticItem(missed.isEmpty(), "未解析的 key", "${fmt(missed.size)} 处", if (missed.isEmpty()) "" else missed.take(5).joinToString(", ")),
            ))

            if (resolved.isNotEmpty()) {
                add("将显示的提示（最多预览 10 条）" to
                    resolved.take(10).map { (usage, translated) ->
                        DiagnosticItem(true, usage.key, translated)
                    })
            }
        }

        val conclusionOk = willShow && resolved.isNotEmpty()
        val conclusion = if (conclusionOk) {
            "该文件预计显示 ${resolved.size} 条译文提示。若编辑器里仍看不到，检查 Settings → Editor → Inlay Hints → $languageId 是否勾选了本插件。"
        } else {
            "该文件当前不会显示译文提示，请对照上面标红的项处理。"
        }

        return Diagnosis(renderPlainText(file, sections, conclusionOk, conclusion), sections, conclusionOk, conclusion)
    }

    /** 纯文本版本，供「复制报告」按钮与外部粘贴使用。 */
    private fun renderPlainText(
        file: PsiFile,
        sections: List<Pair<String, List<DiagnosticItem>>>,
        conclusionOk: Boolean,
        conclusion: String,
    ): String = buildString {
        appendLine("当前文件：${file.name}")
        for ((title, items) in sections) {
            if (title == "当前文件") continue
            appendLine()
            appendLine("── $title ──")
            for (item in items) {
                appendLine("  ${if (item.ok) "[OK]" else "[!!]"} ${item.title}")
                if (item.detail.isNotBlank()) appendLine("      ${item.detail}")
                if (item.hint.isNotBlank()) appendLine("      → ${item.hint}")
            }
        }
        appendLine()
        appendLine("── 结论 ──")
        appendLine("  ${if (conclusionOk) "[OK]" else "[!!]"} $conclusion")
    }

    /**
     * 通过平台的聚合入口查询，确认我们的 Provider 真的被扩展点注册进去了。
     * 这一步能区分「收集器代码有问题」和「plugin.xml 没生效」。
     */
    private fun providerRegistered(file: PsiFile): Boolean {
        val infos = runCatching {
            InlayHintsProviderFactory.getProvidersForLanguage(file.language)
        }.getOrNull().orEmpty()
        return infos.any { it.providerId == I18nInlayCollector.PROVIDER_ID }
    }

    private fun fmt(n: Int): String = String.format("%,d", n)

    private companion object {
        const val DIALOG_TITLE = "i18n 诊断"
    }
}
