package com.hegg5577.i18n.editor

import com.hegg5577.i18n.settings.I18nContext
import com.hegg5577.i18n.settings.I18nSettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.psi.PsiManager
import java.net.URLEncoder

/** 覆盖语言方言注册、提示语言优先级和文档编辑链接的回归场景。 */
class I18nEditorTest : BasePlatformTestCase() {

    /** 现代 JS/JSX 与 TSX 不应因独立语言 ID 而漏掉行内提示。 */
    fun testJavaScriptAndTypeScriptDialectsAreSupported() {
        assertTrue(I18nInlayProviderFactory.supports("ECMAScript 6"))
        assertTrue(I18nInlayProviderFactory.supports("TypeScript JSX"))
        assertTrue(I18nInlayProviderFactory.supports("JavaScript"))
        assertTrue(I18nInlayProviderFactory.supports("TypeScript"))
        assertFalse(I18nInlayProviderFactory.supports("JSON"))
    }

    /** 默认跟随首选展示语言，同时保留用户显式指定的行内语言。 */
    fun testAnnotationLocaleFollowsSettings() {
        val settings = I18nSettings.getInstance(project)
        settings.loadState(I18nSettings.State(displayLocales = mutableListOf("ja", "en")))
        assertEquals("ja", I18nContext.of(project).effectiveAnnotationLocale)

        settings.state.annotationLocale = "fr"
        assertEquals("fr", I18nContext.of(project).effectiveAnnotationLocale)

        settings.state.annotationLocale = ""
        settings.state.displayLocales.clear()
        settings.state.sourceLocale = "en"
        assertEquals("en", I18nContext.of(project).effectiveAnnotationLocale)
    }

    /** 编辑链接必须走平台回调，特殊字符不能破坏 HTML 或 key 的传递。 */
    fun testHoverUsesDocumentationLinkAndEscapesTranslations() {
        val key = "common.a+b & <确认>"
        val html = I18nHoverDocumentationProvider().buildHoverHtml(key, mapOf("zh" to "<确认> & 继续"))

        assertTrue(html.contains("href=\"i18n.edit?key=${URLEncoder.encode(key, "UTF-8")}\""))
        assertTrue(html.contains("&lt;确认&gt; &amp; 继续"))
        assertFalse(html.contains("<确认>"))
    }

    /** 文档解析不能打开弹窗，点击编辑链接应被消费，阻止继续分发给其他 Provider。 */
    fun testEditLinkIsHandledOnlyOnClick() {
        val provider = I18nHoverDocumentationProvider()
        val psiManager = PsiManager.getInstance(project)

        assertNull(provider.getDocumentationElementForLink(psiManager, "i18n.edit?key=common.save", null))
        assertFalse(provider.handleExternalLink(psiManager, "https://example.com", null))
        assertTrue(provider.handleExternalLink(psiManager, "i18n.edit?key=", null))
        assertTrue(provider.handleExternalLink(psiManager, "i18n.edit?key=%ZZ", null))
        // 缺少项目上下文时也消费编辑链接，避免平台将其继续分发或当作外部地址打开。
        assertTrue(provider.handleExternalLink(null, "i18n.edit?key=common.save", null))
    }
}
