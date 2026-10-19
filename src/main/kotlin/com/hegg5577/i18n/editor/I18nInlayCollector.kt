package com.hegg5577.i18n.editor

import com.intellij.codeInsight.hints.declarative.HintFormat
import com.intellij.codeInsight.hints.declarative.InlayPayload
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.codeInsight.hints.declarative.InlineInlayPosition
import com.intellij.codeInsight.hints.declarative.OwnBypassCollector
import com.intellij.psi.PsiFile
import com.hegg5577.i18n.locale.TranslationIndex
import com.hegg5577.i18n.settings.I18nContext

/**
 * 在 `t('key')` 之后内联显示对应译文。
 *
 * 只负责把 [KeyUsageScanner] 的扫描结果翻译成 IDE 的 inlay 渲染；
 * 命中判定统一放在 [KeyUsageScanner]，与诊断动作共用，避免两套逻辑不一致。
 *
 * 全部参数（语言包路径、提示语言、匹配规则、后缀）都从 [I18nContext] 读取，
 * 用户在设置里改完立即生效。
 */
class I18nInlayCollector : OwnBypassCollector {

    override fun collectHintsForFile(file: PsiFile, sink: InlayTreeSink) {
        val context = I18nContext.of(file.project)
        if (!context.inlineAnnotationEnabled) return
        if (!TranslationIndex.isSupported(file.name, context.supportedExtensions)) return
        if (!context.isValid) return

        val annotationLocale = context.effectiveAnnotationLocale
        val fallback = if (context.fallbackToSource) context.sourceLocale else ""
        val values = TranslationIndex.values(
            projectRoot = context.projectRoot?.path,
            locale = annotationLocale,
            fallbackLocale = fallback,
            localesDir = context.localesPath,
            pathMatcher = context.pathMatcher,
            keyStyle = context.keyStyle,
        )
        if (values.isEmpty()) return

        val text = file.text
        if (KeyUsageScanner.isTooLarge(text.length)) return

        // 用户配了自定义正则就只用它，避免内置规则混进来产生误命中
        val builtin = if (context.usageMatchRegex.isEmpty()) DefaultPatterns.ALL else emptyList()

        for (usage in KeyUsageScanner.scan(text, context.usageMatchRegex, builtin)) {
            // 只给语言包里确实存在的 key 显示，天然过滤掉同名但非 i18n 的调用
            val translated = values[usage.key] ?: continue

            sink.addPresentation(
                position = InlineInlayPosition(usage.offset, true, 0),
                payloads = emptyList<InlayPayload>(),
                tooltip = null,
                hintFormat = HINT_FORMAT,
            ) {
                text(" $translated")
            }
        }
    }

    companion object {
        const val PROVIDER_ID = "i18n.translation.hints"

        /** 默认格式（带背景色），与平台参数提示观感一致，便于一眼看到。 */
        private val HINT_FORMAT = HintFormat.Companion.default
    }
}

/**
 * 内置匹配规则集合。
 *
 * 仅在用户未配置自定义规则时启用。刻意做成「多条并集」而不是「按框架二选一」：
 * 同一项目里常混用 `t('a')` 与 `$t('b')`，并集命中的召回率更高，
 * 而误命中的条目会被「key 必须存在于语言包」这道过滤挡掉。
 */
object DefaultPatterns {
    val ALL: List<Regex> = listOf(
        // t('key')  —— 裸函数调用
        Regex("""\bt\s*\(\s*['"](?<key>[\w.\-]+)['"]"""),
        // i18n.t('key') / i18next.t('key') / I18n.t('key')
        Regex("""\b(?:i18n|i18next|I18n)\s*\.\s*t\s*\(\s*['"](?<key>[\w.\-]+)['"]"""),
        // $t('key') / $tc('key') —— Vue 组件内写法
        Regex("""\$(?:t|tc)\s*\(\s*['"](?<key>[\w.\-]+)['"]"""),
        // this.$t('key') —— Options API 写法
        Regex("""this\s*\.\s*\$(?:t|tc)\s*\(\s*['"](?<key>[\w.\-]+)['"]"""),
        // translate('key') / transl('key') / gettext('key') —— i18n-js 等
        Regex("""\b(?:translate|transl|gettext)\s*\(\s*['"](?<key>[\w.\-]+)['"]"""),
        // translate('key', {...}) —— 带命名空间参数的写法
        Regex("""\b(?:translate|transl)\s*\(\s*['"](?<key>[\w.\-]+)['"]\s*,"""),
    )
}