package com.hegg5577.i18n.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * 诊断结果的一条检查项。
 */
data class DiagnosticItem(
    val ok: Boolean,
    val title: String,
    val detail: String = "",
    /** 失败时给出的修复建议，可为空。 */
    val hint: String = "",
)

/**
 * 诊断面板：把原先的 ASCII 文本报告换成结构化卡片列表。
 *
 * 每项一条：状态图标 + 标题 + 详情 + 修复建议。
 * 失败项用红色，扫一眼就知道问题在哪，不必在方括号里找 `[!!]`。
 *
 * 同样只用基础 Swing + JBColor 组装，避免依赖平台内部主题 API。
 */
class DiagnosticPanel(
    sections: List<Pair<String, List<DiagnosticItem>>>,
    conclusionOk: Boolean,
    conclusion: String,
) : JPanel(BorderLayout()) {

    private val errorColor = JBColor.RED

    init {
        border = JBUI.Borders.empty(12)

        val body = JPanel().apply {
            layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
        }

        for ((title, items) in sections) {
            body.add(sectionHeader(title))
            for (item in items) {
                body.add(itemCard(item))
            }
            body.add(javax.swing.Box.createVerticalStrut(JBUI.scale(6)))
        }

        body.add(sectionHeader("结论"))
        body.add(itemCard(DiagnosticItem(conclusionOk, conclusion)))

        add(JBScrollPane(body).apply {
            border = JBUI.Borders.empty()
            preferredSize = java.awt.Dimension(JBUI.scale(620), JBUI.scale(460))
        }, BorderLayout.CENTER)
    }

    private fun sectionHeader(title: String): JPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
        val label = JLabel(title).apply {
            font = UIUtil.getLabelFont()
            border = JBUI.Borders.customLineBottom(JBColor.border())
        }
        add(label)
        border = JBUI.Borders.emptyBottom(4)
    }

    private fun itemCard(item: DiagnosticItem): JPanel = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.empty(3, 3, 3, 6)

        add(JLabel(item.ok.toIcon()).apply {
            verticalAlignment = SwingConstants.TOP
        }, BorderLayout.WEST)

        val text = JPanel().apply {
            layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)

            add(JLabel(item.title).apply {
                foreground = if (item.ok) JBColor.foreground() else errorColor
                font = UIUtil.getLabelFont()
            })
            if (item.detail.isNotBlank()) {
                add(JLabel(item.detail).apply {
                    foreground = JBColor.foreground()
                    font = UIUtil.getLabelFont()
                })
            }
            if (item.hint.isNotBlank()) {
                add(JLabel("→ ${item.hint}").apply {
                    foreground = JBColor.GRAY
                    font = UIUtil.getLabelFont()
                })
            }
        }
        add(text, BorderLayout.CENTER)
    }

    private fun Boolean.toIcon(): Icon =
        if (this) AllIcons.General.InspectionsOK else AllIcons.General.InspectionsError
}

/**
 * 诊断弹窗包装，负责标题与「复制报告」按钮。
 *
 * 面板看的是结构化数据，复制到剪贴板的仍是纯文本 —— 便于贴到 issue 或聊天里。
 */
class DiagnosticDialog(
    project: Project?,
    fileName: String,
    private val reportText: String,
    private val sections: List<Pair<String, List<DiagnosticItem>>>,
    private val conclusionOk: Boolean,
    private val conclusion: String,
) : DialogWrapper(project, false) {

    init {
        title = "i18n 诊断 · $fileName"
        setOKButtonText("复制报告")
        setCancelButtonText("关闭")
        init()
    }

    override fun doOKAction() {
        runCatching {
            java.awt.Toolkit.getDefaultToolkit().systemClipboard
                .setContents(java.awt.datatransfer.StringSelection(reportText), null)
        }
        super.doOKAction()
    }

    override fun createCenterPanel(): JComponent =
        DiagnosticPanel(sections, conclusionOk, conclusion)
}