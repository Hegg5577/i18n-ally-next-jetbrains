package com.hegg5577.i18n.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * 批量删除或重命名 key 的确认对话框。
 *
 * 显示操作范围（涉及几个 key、几种语言），让用户确认后再执行不可逆的操作。
 */
class BatchOperationDialog(
    private val project: Project,
    private val operationType: OperationType,
    private val selectedKeys: List<String>,
    private val displayLocales: List<String>,
) : DialogWrapper(project, true) {

    enum class OperationType {
        DELETE, RENAME
    }

    init {
        title = when (operationType) {
            OperationType.DELETE -> "批量删除 Key"
            OperationType.RENAME -> "批量重命名 Key"
        }
        setOKButtonText(when (operationType) {
            OperationType.DELETE -> "删除"
            OperationType.RENAME -> "重命名"
        })
        setCancelButtonText("取消")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val root = JPanel(BorderLayout())
        root.border = JBUI.Borders.empty(12)

        // 标题说明
        val title = JBLabel(buildTitle()).apply {
            font = font.deriveFont(font.size + 1f)
        }
        root.add(title, BorderLayout.NORTH)

        // 操作范围
        val infoPanel = JPanel().apply {
            layout = BorderLayout()
            border = JBUI.Borders.empty(12, 0, 0, 0)

            val info = buildString {
                appendLine("影响范围：")
                appendLine("  • Key 数量：${selectedKeys.size}")
                appendLine("  • 语言数量：${displayLocales.size}")
                appendLine()
                appendLine("影响的 Key：")
                selectedKeys.forEachIndexed { index, key ->
                    if (index < 20) {
                        appendLine("  • $key")
                    }
                }
                if (selectedKeys.size > 20) {
                    appendLine("  • ... 以及 ${selectedKeys.size - 20} 个其他 Key")
                }
            }

            val textArea = JBTextArea(info).apply {
                isEditable = false
                lineWrap = true
                wrapStyleWord = true
                margin = JBUI.insets(4)
                font = com.intellij.util.ui.UIUtil.getLabelFont()
            }
            add(textArea, BorderLayout.CENTER)
        }
        root.add(infoPanel, BorderLayout.CENTER)

        // 警告信息
        val warning = JBLabel(buildWarning()).apply {
            foreground = JBUI.CurrentTheme.Label.disabledForeground()
        }
        root.add(warning, BorderLayout.SOUTH)

        root.preferredSize = Dimension(JBUI.scale(520), JBUI.scale(360))
        return root
    }

    private fun buildTitle(): String = when (operationType) {
        OperationType.DELETE -> "确认删除 ${selectedKeys.size} 个 Key？"
        OperationType.RENAME -> "确认重命名 ${selectedKeys.size} 个 Key？"
    }

    private fun buildWarning(): String = when (operationType) {
        OperationType.DELETE -> "此操作将从全部 ${displayLocales.size} 种语言的语言包中删除这些 Key。可通过 Ctrl+Z 撤销。"
        OperationType.RENAME -> "此操作将在全部 ${displayLocales.size} 种语言的语言包中重命名这些 Key。可通过 Ctrl+Z 撤销。"
    }
}
