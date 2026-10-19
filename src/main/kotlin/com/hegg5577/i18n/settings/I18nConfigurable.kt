package com.hegg5577.i18n.settings

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTabbedPane

/**
 * i18n 设置面板。
 *
 * 每一项都对应 i18n-ally-next 的同名配置，方便从 VS Code 迁移过来的用户对照理解：
 * 语言包根目录、路径模式 `pathMatcher`、源语言 `sourceLanguage`、显示语言、
 * key 匹配正则 `usageMatchRegex`、key 风格 `keyStyle`。
 *
 * 刻意实现 [Configurable] 而非继承 `BoundConfigurable` —— 后者在本平台是
 * Kotlin DSL 版本（返回 `DialogPanel`），字段绑定要写成 DSL 形式，
 * 反而不如显式写 reset/apply 直观，也更容易看清「哪些字段会存盘」。
 */
class I18nConfigurable(private val project: Project) : Configurable {

    private val state get() = I18nSettings.getInstance(project).state

    private val localesPathField = JBTextField()
    private val sourceLocaleField = JBTextField()
    private val annotationLocaleField = JBTextField()
    private val displayLocalesField = JBTextField()
    private val extensionsField = JBTextField()
    private val namespaceDelimiterField = JBTextField()
    private val sourceDirsField = JBTextField()

    private val keyStyleCombo = ComboBox(DefaultComboBoxModel(arrayOf("nested", "flat", "auto")))

    /** 路径模式做成可编辑下拉：常见布局直接选，也允许手输任意模式。 */
    private val matcherCombo = ComboBox(
        DefaultComboBoxModel(
            arrayOf(
                "{locale}/{file}",
                "{locale}.json",
                "{locale}/{namespace}.json",
                "{namespace}.{locale}.json",
            )
        )
    ).apply { isEditable = true }

    private val regexArea = JBTextArea().apply {
        lineWrap = true
        wrapStyleWord = true
        rows = 7
        font = UIUtil.getLabelFont()
    }

    private val inlineEnabled = JBCheckBox("启用行内译文提示")
    private val fallbackToSource = JBCheckBox("未翻译时回退显示源语言原文")
    private val overrideExisting = JBCheckBox("批量翻译时覆盖已有译文（默认关闭，避免误伤人工译文）")
    private val namespaceEnabled = JBCheckBox("启用命名空间")

    private val translationEnginePanel = TranslationEnginePanel(project)

    private var panel: JComponent? = null

    override fun getDisplayName(): String = "i18n Helper"

    override fun createComponent(): JComponent {
        val root = JPanel(BorderLayout())

        // 基础配置选项卡
        val basicForm = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("语言包根目录"), localesPathField, 1, false)
            .addLabeledComponent(JBLabel("路径模式"), matcherCombo, 1, false)
            .addComponent(
                JPanel().apply {
                    add(
                        JBLabel(
                            "<html>用 <code>{locale}</code>、<code>{namespace}</code>、<code>{file}</code> 描述目录结构，" +
                                "例如 <code>{locale}/{file}</code> 表示 <code>locales/en/index.json</code></html>"
                        )
                    )
                }
            )
            .addLabeledComponent(JBLabel("源语言"), sourceLocaleField, 1, false)
            .addLabeledComponent(JBLabel("行内提示语言（留空跟随首选展示语言）"), annotationLocaleField, 1, false)
            .addLabeledComponent(JBLabel("优先展示的语言（逗号分隔）"), displayLocalesField, 1, false)
            .addLabeledComponent(JBLabel("key 风格"), keyStyleCombo, 1, false)
            .addLabeledComponent(JBLabel("扫描的文件后缀（逗号分隔）"), extensionsField, 1, false)
            .addLabeledComponent(JBLabel("源码目录（逗号分隔，用于缺失检测）"), sourceDirsField, 1, false)
            .addComponent(namespaceEnabled)
            .addLabeledComponent(JBLabel("命名空间分隔符"), namespaceDelimiterField, 1, false)
            .addSeparator()
            .addComponent(inlineEnabled)
            .addComponent(fallbackToSource)
            .addComponent(overrideExisting)
            .addSeparator()
            .addComponent(
                JBLabel("key 匹配正则（每行一条，<code>{key}</code> 为占位符；留空则使用内置规则）")
            )
            .addComponent(JBScrollPane(regexArea))

        val basicPanel = JPanel(BorderLayout()).apply {
            add(basicForm.panel, BorderLayout.CENTER)
        }

        // 使用 TabbedPane 来组织不同的配置分类
        val tabbedPane = JTabbedPane().apply {
            addTab("基本设置", basicPanel)
            addTab("翻译引擎", translationEnginePanel.createPanel())
        }

        root.add(tabbedPane, BorderLayout.CENTER)
        root.preferredSize = Dimension(JBUI.scale(720), JBUI.scale(760))

        panel = root
        reset()
        return root
    }

    /** 把配置载入界面。 */
    override fun reset() {
        localesPathField.text = state.localesPath
        matcherCombo.selectedItem = state.pathMatcher
        sourceLocaleField.text = state.sourceLocale
        annotationLocaleField.text = state.annotationLocale
        displayLocalesField.text = state.displayLocales.joinToString(",")
        keyStyleCombo.selectedItem = state.keyStyle
        extensionsField.text = state.supportedExtensions.joinToString(",")
        sourceDirsField.text = state.sourceDirs.joinToString(",")
        namespaceEnabled.isSelected = state.namespaceEnabled
        namespaceDelimiterField.text = state.namespaceDelimiter
        inlineEnabled.isSelected = state.inlineAnnotationEnabled
        fallbackToSource.isSelected = state.fallbackToSource
        overrideExisting.isSelected = state.overrideExisting
        regexArea.text = state.usageMatchRegex.joinToString("\n")
        translationEnginePanel.reset()
    }

    /** 把界面写回配置，并刷新受影响的缓存与面板。 */
    override fun apply() {
        state.localesPath = localesPathField.text.trim()
        state.pathMatcher = (matcherCombo.selectedItem as? String ?: "").trim()
        state.sourceLocale = sourceLocaleField.text.trim()
        state.annotationLocale = annotationLocaleField.text.trim()
        state.displayLocales = splitList(displayLocalesField.text).toMutableList()
        state.keyStyle = (keyStyleCombo.selectedItem as? String ?: "nested")
        state.supportedExtensions = splitList(extensionsField.text)
            .map { it.removePrefix(".") }
            .toMutableList()
        state.sourceDirs = splitList(sourceDirsField.text)
            .ifEmpty { listOf("src") }
            .toMutableList()
        state.namespaceEnabled = namespaceEnabled.isSelected
        state.namespaceDelimiter = namespaceDelimiterField.text.trim().ifEmpty { ":" }
        state.inlineAnnotationEnabled = inlineEnabled.isSelected
        state.fallbackToSource = fallbackToSource.isSelected
        state.overrideExisting = overrideExisting.isSelected
        state.usageMatchRegex = regexLines().toMutableList()
        translationEnginePanel.apply()

        // 配置变了，缓存必须失效，否则面板与行内提示还显示旧数据
        project.getService(com.hegg5577.i18n.ui.I18nPanelService::class.java).refresh()
    }

    override fun isModified(): Boolean {
        val s = state
        return localesPathField.text.trim() != s.localesPath ||
            (matcherCombo.selectedItem as? String ?: "").trim() != s.pathMatcher ||
            sourceLocaleField.text.trim() != s.sourceLocale ||
            annotationLocaleField.text.trim() != s.annotationLocale ||
            splitList(displayLocalesField.text) != s.displayLocales ||
            (keyStyleCombo.selectedItem as? String) != s.keyStyle ||
            splitList(extensionsField.text).map { it.removePrefix(".") } != s.supportedExtensions ||
            namespaceEnabled.isSelected != s.namespaceEnabled ||
            namespaceDelimiterField.text.trim().ifEmpty { ":" } != s.namespaceDelimiter ||
            inlineEnabled.isSelected != s.inlineAnnotationEnabled ||
            fallbackToSource.isSelected != s.fallbackToSource ||
            overrideExisting.isSelected != s.overrideExisting ||
            regexLines() != s.usageMatchRegex ||
            translationEnginePanel.isModified()
    }

    override fun disposeUIResources() {
        panel = null
    }

    /** 逗号分隔字符串 -> 列表，兼容中英文逗号并去空白。 */
    private fun splitList(text: String): List<String> =
        text.split(',', '，').map { it.trim() }.filter { it.isNotEmpty() }

    /** 正则文本框 -> 非空行列表。 */
    private fun regexLines(): List<String> =
        regexArea.text.lines().map { it.trim() }.filter { it.isNotEmpty() }
}
