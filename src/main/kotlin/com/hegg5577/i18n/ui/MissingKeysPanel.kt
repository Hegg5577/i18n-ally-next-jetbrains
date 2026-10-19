package com.hegg5577.i18n.ui

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.FlowLayout
import javax.swing.DefaultListModel
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.JTabbedPane
import javax.swing.ListSelectionModel

/**
 * 「缺失检测」面板。
 *
 * 回答两个语言包本身回答不了的问题：
 * - **缺失**：代码里写了 `t('a.b')`，语言包却没有 `a.b` —— 界面会直接显示原始 key
 * - **多余**：语言包定义了 key，代码里一次都没用 —— 通常是历史遗留
 *
 * 相比「翻译对照」只看语言包之间的差异，这一层看的是**代码与语言包的差异**，
 * 才是真正会导致线上问题的那一类。
 */
class MissingKeysPanel : JPanel(BorderLayout()) {

    /** 双击缺失项时回调，参数为 key。检测出来了就该能直接补上。 */
    var onEditKey: ((String) -> Unit)? = null

    private val missingListModel = DefaultListModel<String>()
    private val unusedListModel = DefaultListModel<String>()

    private val missingList = JList(missingListModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = MissingCellRenderer()
        addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (e.clickCount != 2 || e.button != java.awt.event.MouseEvent.BUTTON1) return
                val index = locationToIndex(e.point)
                if (index < 0) return
                val raw = missingListModel[index]
                // 显示文本是 "key    (N 处)"，这里取回纯 key
                val key = raw.substringBefore("(").trim()
                onEditKey?.invoke(key)
            }
        })
    }

    private val unusedList = JList(unusedListModel).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
    }

    private val summaryLabel = JBLabel()

    init {
        border = JBUI.Borders.empty(8)

        val tabs = JTabbedPane().apply {
            addTab("缺失的 key（代码用了但语言包没有）", JBScrollPane(missingList))
            addTab("多余的 key（语言包有但代码没用）", JBScrollPane(unusedList))
        }

        add(summaryLabel, BorderLayout.NORTH)
        add(tabs, BorderLayout.CENTER)
    }

    /** 更新数据。 */
    fun setData(
        missing: List<Pair<String, List<String>>>,
        unused: List<String>,
        definedCount: Int,
        usedCount: Int,
    ) {
        missingListModel.clear()
        // 引用文件多的排前面：这类缺失更可能是核心路径上的问题
        missing.sortedByDescending { it.second.size }.forEach { (key, files) ->
            missingListModel.addElement("$key    (${files.size} 处)")
        }

        unusedListModel.clear()
        unused.forEach { unusedListModel.addElement(it) }

        summaryLabel.text = buildString {
            append("语言包定义 $definedCount 个 key，代码引用 $usedCount 个。")
            append("  缺失 ${missing.size} 个")
            append("，多余 ${unused.size} 个。")
            if (missing.isEmpty() && unused.isEmpty()) {
                append("  没有发现问题。")
            } else if (missing.isNotEmpty()) {
                append("  双击缺失项可直接补写译文。")
            }
        }
    }

    /** 缺失项的渲染：key 用醒目的红色，与普通列表项区分开。 */
    private inner class MissingCellRenderer : javax.swing.DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: javax.swing.JList<*>?,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean,
        ): Component {
            val text = value?.toString().orEmpty()
            val label = super.getListCellRendererComponent(
                list, text, index, isSelected, cellHasFocus
            ) as JLabel
            if (!isSelected) {
                label.foreground = JBColor.RED
            }
            label.toolTipText = text.substringBefore("    ")
            return label
        }
    }
}