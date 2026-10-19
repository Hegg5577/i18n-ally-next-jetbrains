package com.hegg5577.i18n.ui

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.hegg5577.i18n.locale.TranslationSaver
import com.hegg5577.i18n.translator.PlaceholderProtector
import com.hegg5577.i18n.translator.TranslationEngine
import com.hegg5577.i18n.translator.TranslationEngineManager
import com.hegg5577.i18n.translator.TranslationException
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.io.File
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * 单个 key 的多语言详情与编辑弹窗。
 *
 * 布局对齐原版 i18n-ally 的 key 展开视图：左边 key 路径，右边每种语言一行，
 * 每行可直接编辑并保存。缺失的行标红提示，空的行给出占位。
 *
 * 存在的意义是「翻译对照」表只能看不能改 —— 真要补一条文案，
 * 不该去手动改JSON。缺失检测更是明确告诉你缺了哪些 key，点进去就该能填。
 */
class KeyEditorDialog(
    private val project: Project,
    private val key: String,
    private val sourceLocale: String,
    /** locale -> 当前译文；`null` 表示该语言包里根本没有这个 key。 */
    private val values: Map<String, String?>,
    private val locales: List<String>,
    /** 各语言的语言包文件路径，用于定位写入目标。 */
    private val localeFiles: Map<String, File>,
    private val onSaved: () -> Unit,
) : DialogWrapper(project, true) {

    /** 语言 -> 编辑框。 */
    private val fields: Map<String, JBTextField> =
        locales.associateWith { JBTextField(values[it].orEmpty(), 30) }

    /** 记录打开时各语言的原值，用于判断哪些需要写入。 */
    private val original: Map<String, String?> = values

    init {
        title = "编辑翻译 · $key"
        setOKButtonText("保存")
        setCancelButtonText("取消")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val root = JPanel(BorderLayout())
        root.border = JBUI.Borders.empty(10)

        //顶部：key 路径 + 复制按钮
        val header = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            add(JBLabel("Key").apply { font = UIUtil.getLabelFont() })
            add(JBLabel(key).apply {
                font = UIUtil.getLabelFont().deriveFont(Font.BOLD)
            })
        }
        root.add(header, BorderLayout.NORTH)

        root.add(buildForm(), BorderLayout.CENTER)
        root.add(buildFooter(), BorderLayout.SOUTH)

        root.preferredSize = Dimension(JBUI.scale(620), JBUI.scale(420))
        return root
    }

    /** 每行：语言标签 + 输入框 + 状态标记。 */
    private fun buildForm(): JComponent {
        val form = JPanel(GridBagLayout())
        val c = GridBagConstraints().apply {
            insets = Insets(3, 4, 3, 4)
            anchor = GridBagConstraints.WEST
        }

        var row = 0
        // 源语言排在最前，便于以它为基准填写其它语言
        val ordered = listOf(sourceLocale) + locales.filter { it != sourceLocale }

        for (locale in ordered) {
            c.gridx = 0
            c.gridy = row
            c.weightx = 0.0
            form.add(localeLabel(locale), c)

            c.gridx = 1
            c.weightx = 1.0
            c.fill = GridBagConstraints.HORIZONTAL
            fields[locale]?.let { form.add(it, c) }

            c.gridx = 2
            c.weightx = 0.0
            c.fill = GridBagConstraints.NONE
            form.add(statusLabel(locale), c)

            row++
        }
        return JBScrollPane(form)
    }

    private fun localeLabel(locale: String): JLabel = JBLabel(Flags.displayName(locale)).apply {
        font = UIUtil.getLabelFont()
        // 源语言加粗，作为整行的基准
        if (locale == sourceLocale) {
            font = font.deriveFont(Font.BOLD)
        }
    }

    /**
     * 状态标记：已翻译 / 待填写 / 缺失。
     * 缺失（语言包里根本没有这个 key）用红色提示，写入时会自动创建。
     */
    private fun statusLabel(locale: String): JLabel {
        val current = values[locale]
        val text: String
        val color: Color
        when {
            current == null -> {
                text = "缺失 · 保存后创建"
                color = JBColor.RED
            }
            current.isEmpty() -> {
                text = "待填写"
                color = JBColor.RED
            }
            else -> {
                text = "✓ 已翻译"
                color = JBColor.foreground()
            }
        }
        return JBLabel(text).apply {
            foreground = color
            font = UIUtil.getLabelFont()
        }
    }

    /** 底部：批量操作按钮。 */
    private fun buildFooter(): JPanel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
        border = JBUI.Borders.emptyTop(8)

        add(javax.swing.JButton("自动翻译").apply {
            isFocusable = false
            toolTipText = "使用翻译引擎自动填充缺失的译文"
            addActionListener { autoTranslate() }
        })

        add(javax.swing.JButton("用源语言填充空的译文").apply {
            isFocusable = false
            toolTipText = "把所有尚未填写（缺失或空）的语言填成源语言原文"
            addActionListener { fillFromSource() }
        })

        add(javax.swing.JButton("清空所有改动").apply {
            isFocusable = false
            addActionListener { resetFields() }
        })
    }

    /** 使用翻译引擎自动填充缺失的译文。 */
    private fun autoTranslate() {
        val sourceText = fields[sourceLocale]?.text.orEmpty().trim()
        if (sourceText.isBlank()) {
            com.intellij.openapi.ui.Messages.showErrorDialog(
                project,
                "源语言（${Flags.displayName(sourceLocale)}）不能为空",
                "无法翻译",
            )
            return
        }

        // 找出所有需要翻译的目标语言（缺失或空）
        val targetLocales = locales.filter { locale ->
            locale != sourceLocale && fields[locale]?.text.orEmpty().isBlank()
        }
        if (targetLocales.isEmpty()) {
            com.intellij.openapi.ui.Messages.showInfoMessage(
                project,
                "没有需要翻译的语言",
                "提示",
            )
            return
        }

        ProgressManager.getInstance().runProcessWithProgressSynchronously(
            {
                try {
                    doAutoTranslate(sourceText, targetLocales)
                } catch (e: Exception) {
                    com.intellij.openapi.ui.Messages.showErrorDialog(
                        project,
                        "翻译失败：${e.message}",
                        "自动翻译失败",
                    )
                }
            },
            "翻译中…",
            true,
            project,
        )
    }

    /** 按各自目标语言翻译后填入编辑框，避免把第一种语言的结果复用给其它语言。 */
    private fun doAutoTranslate(sourceText: String, targetLocales: List<String>) {
        val manager = TranslationEngineManager.getInstance(project)
        val engine = manager.getActiveEngine()

        for ((locale, translated) in translateTargets(engine, sourceText, sourceLocale, targetLocales)) {
            fields[locale]?.text = translated
        }
    }

    companion object {
        /** 每次请求只传一种目标语言，并按现有保护规则还原占位符。 */
        internal fun translateTargets(
            engine: TranslationEngine,
            sourceText: String,
            sourceLocale: String,
            targetLocales: List<String>,
        ): Map<String, String> {
            val protected = PlaceholderProtector.protect(sourceText)
            return targetLocales.associateWith { locale ->
                val raw = engine.translate(listOf(protected.text), locale, sourceLocale).singleOrNull()
                    ?: throw TranslationException("翻译引擎未返回 $locale 的单条译文")
                protected.restore(raw)
                    ?: throw TranslationException("$locale 译文的占位符丢失，已丢弃该条")
            }
        }
    }

    /** 把空缺的语言填成源语言原文。 */
    private fun fillFromSource() {
        val sourceText = fields[sourceLocale]?.text.orEmpty()
        if (sourceText.isBlank()) return

        for (locale in locales) {
            if (locale == sourceLocale) continue
            val field = fields[locale] ?: continue
            if (field.text.isBlank()) {
                field.text = sourceText
            }
        }
    }

    private fun resetFields() {
        for ((locale, field) in fields) {
            field.text = original[locale].orEmpty()
        }
    }

    override fun doValidate(): ValidationInfo? {
        // 源语言是基准，不允许为空：其它语言都以它为参照
        val sourceText = fields[sourceLocale]?.text.orEmpty()
        if (sourceText.isBlank()) {
            return ValidationInfo("源语言（${Flags.displayName(sourceLocale)}）不能为空", fields[sourceLocale])
        }
        return null
    }

    override fun doOKAction() {
        // 只提交真正改过的字段，避免无谓的写入与撤销记录
        val changes = LinkedHashMap<String, Map<String, String>>()
        for (locale in locales) {
            val field = fields[locale] ?: continue
            val before = original[locale].orEmpty()
            val after = field.text
            if (before == after) continue
            changes[locale] = mapOf(key to after)
        }

        if (changes.isNotEmpty()) {
            val repo = com.hegg5577.i18n.locale.LocaleRepository(
                projectRoot = project.basePath?.let(::File) ?: return,
                localesDir = com.hegg5577.i18n.settings.I18nContext.of(project).localesPath,
                pathMatcher = com.hegg5577.i18n.settings.I18nContext.of(project).pathMatcher,
                keyStyle = com.hegg5577.i18n.settings.I18nContext.of(project).keyStyle,
            )
            val result = TranslationSaver.save(project, repo, sourceLocale, changes)

            if (!result.isSuccess) {
                val detail = result.failed.entries.joinToString("；") { "${it.key}: ${it.value}" }
                com.intellij.openapi.ui.Messages.showErrorDialog(
                    project,
                    "以下语言保存失败：\n$detail",
                    "i18n 保存译文失败",
                )
                // 不关闭弹窗，让用户改完再试
                return
            }
            onSaved()
        }

        super.doOKAction()
    }
}
