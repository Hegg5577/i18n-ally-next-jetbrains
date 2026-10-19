package com.hegg5577.i18n.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JProgressBar

/**
 * 「概览」面板：翻译项目的一眼概览。
 *
 * 展示：
 * - 每种语言的翻译进度（进度条）
 * - 统计数据（总 key 数、缺失数、多余数）
 * - 快捷操作按钮（刷新、诊断、设置）
 *
 * 目的是让用户打开侧边栏就能看到项目状态一目了然，无需切 tab 翻。
 */
class OverviewPanel : JPanel(BorderLayout()) {

    private val progressContainer = JPanel()
    private val statsLabel = JBLabel()
    private val actionsPanel = JPanel()

    init {
        border = JBUI.Borders.empty(12, 12, 12, 12)

        // 标题
        add(JBLabel("翻译概览").apply {
            font = UIUtil.getLabelFont().deriveFont(14f)
        }, BorderLayout.NORTH)

        // 主体：进度条 + 统计 + 快捷操作
        val body = JPanel().apply {
            layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
            border = JBUI.Borders.emptyTop(8)

            // 进度条容器
            add(JBLabel("语言完成度").apply {
                font = UIUtil.getLabelFont().deriveFont(11f)
                foreground = JBColor.GRAY
                border = JBUI.Borders.emptyBottom(4)
            })

            progressContainer.apply {
                layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
                border = JBUI.Borders.emptyBottom(12)
            }
            add(progressContainer)

            // 统计数据
            add(JBLabel("统计数据").apply {
                font = UIUtil.getLabelFont().deriveFont(11f)
                foreground = JBColor.GRAY
                border = JBUI.Borders.emptyBottom(4)
            })

            add(statsLabel.apply {
                border = JBUI.Borders.empty(6, 0)
            })

            // 快捷操作
            add(JBLabel("快捷操作").apply {
                font = UIUtil.getLabelFont().deriveFont(11f)
                foreground = JBColor.GRAY
                border = JBUI.Borders.emptyTop(8).apply {
                    JBUI.Borders.emptyBottom(4)
                }
            })

            actionsPanel.apply {
                layout = FlowLayout(FlowLayout.LEFT, 4, 4)
                border = JBUI.Borders.empty(6)
            }
            add(actionsPanel)

            // BoxLayout 按各组件的对齐轴排布，统一左对齐以免短标题和统计文字偏到中间。
            components.forEach { component ->
                if (component is JComponent) component.alignmentX = Component.LEFT_ALIGNMENT
            }
        }

        add(body, BorderLayout.CENTER)
    }

    /**
     * 更新进度数据。
     *
     * @param progress locale -> (已翻译数, 总数)
     */
    fun setProgress(progress: Map<String, Pair<Int, Int>>) {
        progressContainer.removeAll()

        for ((locale, pair) in progress) {
            val (done, total) = pair
            val percent = if (total > 0) (done * 100) / total else 0

            val row = JPanel(BorderLayout()).apply {
                border = JBUI.Borders.empty(2, 0, 2, 0)

                add(JBLabel(Flags.displayName(locale)).apply {
                    preferredSize = Dimension(JBUI.scale(80), JBUI.scale(20))
                }, BorderLayout.WEST)

                add(JProgressBar().apply {
                    minimum = 0
                    maximum = 100
                    value = percent
                    string = "$percent% ($done/$total)"
                    isStringPainted = true
                    preferredSize = Dimension(JBUI.scale(200), JBUI.scale(20))
                }, BorderLayout.CENTER)
            }

            progressContainer.add(row)
        }

        progressContainer.revalidate()
        progressContainer.repaint()
    }

    /**
     * 更新统计数据。
     */
    fun setStats(
        definedCount: Int,
        usedCount: Int,
        missingCount: Int,
        unusedCount: Int,
    ) {
        statsLabel.text = buildString {
            append("<html>")
            append("定义 key：<b>$definedCount</b> | ")
            append("使用 key：<b>$usedCount</b><br/>")
            append("缺失 key：<b style='color:#FF5555'>$missingCount</b> | ")
            append("多余 key：<b style='color:#FF9800'>$unusedCount</b>")
            append("</html>")
        }
    }

    /**
     * 设置快捷操作按钮。
     */
    fun setActions(
        onRefresh: () -> Unit,
        onDiagnose: () -> Unit,
        onSettings: () -> Unit,
    ) {
        actionsPanel.removeAll()

        val refreshBtn = javax.swing.JButton("刷新").apply {
            icon = AllIcons.Actions.Refresh
            isFocusable = false
            addActionListener { onRefresh() }
            toolTipText = "重新扫描语言包和代码引用"
        }

        val diagnoseBtn = javax.swing.JButton("诊断").apply {
            icon = AllIcons.General.InspectionsOK
            isFocusable = false
            addActionListener { onDiagnose() }
            toolTipText = "检查配置和路径"
        }

        val settingsBtn = javax.swing.JButton("设置").apply {
            icon = AllIcons.General.Settings
            isFocusable = false
            addActionListener { onSettings() }
            toolTipText = "打开 i18n 设置面板"
        }

        actionsPanel.add(refreshBtn)
        actionsPanel.add(diagnoseBtn)
        actionsPanel.add(settingsBtn)

        actionsPanel.revalidate()
        actionsPanel.repaint()
    }
}
