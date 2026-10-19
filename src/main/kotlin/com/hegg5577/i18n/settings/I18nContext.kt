package com.hegg5577.i18n.settings

import com.intellij.openapi.project.Project
import com.hegg5577.i18n.locale.KeyStyle
import com.hegg5577.i18n.locale.LocaleRepository
import com.hegg5577.i18n.locale.PathMatcher
import java.io.File

/**
 * 项目 i18n 配置的读取入口。
 *
 * 存在的意义是避免各处重复写「从 Settings 里取 X项再传给 Y」：
 * UI、扫描、批量翻译、行内提示都从这里拿同一份快照，保证行为一致。
 * 用户改完设置，下一次读取立即生效。
 */
data class I18nContext(
    val projectRoot: File?,
    val localesPath: String,
    val pathMatcher: String,
    val sourceLocale: String,
    val displayLocales: List<String>,
    val keyStyle: KeyStyle,
    val usageMatchRegex: List<String>,
    val supportedExtensions: Set<String>,
    val inlineAnnotationEnabled: Boolean,
    val annotationLocale: String,
    val fallbackToSource: Boolean,
    val overrideExisting: Boolean,
    val sourceDirs: List<String>,
    val activeTranslationEngine: String,
    val deeplApiKey: String,
    val deeplIsFreeApi: Boolean,
    val openaiApiKey: String,
    val openaiModel: String,
    val openaiBaseUrl: String,
) {
    /** 行内提示实际使用的语言：优先配置项，其次首选显示语言，最后源语言。 */
    val effectiveAnnotationLocale: String
        get() = annotationLocale.ifBlank {
            displayLocales.firstOrNull() ?: sourceLocale
        }

    /** 是否为可用的上下文（项目根存在）。 */
    val isValid: Boolean get() = projectRoot != null && projectRoot.isDirectory

    /**
     * 按当前配置构建语言包仓库。
     * 路径与 key 风格全部来自配置，不再有硬编码默认值。
     */
    fun repository(): LocaleRepository? {
        val root = projectRoot ?: return null
        return LocaleRepository(
            projectRoot = root,
            localesDir = localesPath,
            pathMatcher = pathMatcher,
            keyStyle = keyStyle,
        )
    }

    companion object {
        /** 从项目读取当前配置。 */
        fun of(project: Project): I18nContext {
            val state = I18nSettings.getInstance(project).state
            return I18nContext(
                projectRoot = project.basePath?.let(::File),
                localesPath = state.localesPath.ifBlank { LocaleRepository.DEFAULT_LOCALES_DIR },
                pathMatcher = state.pathMatcher.ifBlank { PathMatcher.DEFAULT_PATTERN },
                sourceLocale = state.sourceLocale,
                displayLocales = state.displayLocales.filter { it.isNotBlank() },
                keyStyle = KeyStyle.from(state.keyStyle),
                usageMatchRegex = state.usageMatchRegex.filter { it.isNotBlank() },
                supportedExtensions = state.supportedExtensions
                    .filter { it.isNotBlank() }
                    .map { it.removePrefix(".").lowercase() }
                    .toSet(),
                inlineAnnotationEnabled = state.inlineAnnotationEnabled,
                annotationLocale = state.annotationLocale,
                fallbackToSource = state.fallbackToSource,
                overrideExisting = state.overrideExisting,
                sourceDirs = state.sourceDirs.filter { it.isNotBlank() },
                activeTranslationEngine = state.activeTranslationEngine,
                deeplApiKey = state.deeplApiKey,
                deeplIsFreeApi = state.deeplIsFreeApi,
                openaiApiKey = state.openaiApiKey,
                openaiModel = state.openaiModel,
                openaiBaseUrl = state.openaiBaseUrl,
            )
        }
    }
}