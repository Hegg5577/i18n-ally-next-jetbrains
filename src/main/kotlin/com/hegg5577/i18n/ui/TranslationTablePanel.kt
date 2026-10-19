package com.hegg5577.i18n.ui

import com.intellij.icons.AllIcons
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.table.AbstractTableModel

/**
 * 多语言翻译对照表。
 *
 * 表格结构对齐原版 i18n-ally 的 "manage" 视图：左侧 key，右侧每种语言一列，
 * 单元格内用「状态标记 + 译文」呈现，缺失 / 空一眼可辨。
 *
 * 用 [JBTable] 而非手写 Swing，是为了让排序、行高、主题适配、选中态全部走平台样式，
 * 避免又做成一个"看起来像原生但处处不对"的控件。
 */
class TranslationTablePanel(
    private var sourceLocale: String,
    private var displayLocales: List<String>,
) : JPanel(BorderLayout()) {

    private var model = RowTableModel(sourceLocale, displayLocales)
    private val table = JBTable(model)
    private val renderer = TranslationCellRenderer()

    /** 完整数据，搜索时从中筛选出可见行。 */
    private var allRows: List<TranslationRow> = emptyList()

    /** 当前搜索词，小写以支持不区分大小写匹配。 */
    private var keyword: String = ""

    /** 状态筛选：null 表示不过滤。 */
    private var statusFilter: TranslationStatus? = null

    /** 当前聚焦的 key（跟随选中行变化，供外部做跳转 / 翻译）。 */
    var selectedKey: String? = null
        private set

    /** 双击某行时回调，参数为该行的 key。用于打开 key 编辑弹窗。 */
    var onEditKey: ((String) -> Unit)? = null

    /** 多个 key 被选中时的回调。用于启用/禁用批量操作按钮。 */
    var onSelectionChanged: ((List<String>) -> Unit)? = null

    private val searchField = SearchTextField()
    private val statusLabel = JLabel()

    init {
        border = JBUI.Borders.empty()

        add(buildToolbar(), BorderLayout.NORTH)

        // 使用 JBScrollPane 并启用水平滚动条
        val scrollPane = JBScrollPane(table).apply {
            horizontalScrollBarPolicy = javax.swing.JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
            verticalScrollBarPolicy = javax.swing.JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
        }
        add(scrollPane, BorderLayout.CENTER)

        add(buildStatusBar(), BorderLayout.SOUTH)

        table.rowHeight = JBUI.scale(26)
        table.showHorizontalLines = false
        table.showVerticalLines = false
        table.selectionModel.selectionMode = ListSelectionModel.MULTIPLE_INTERVAL_SELECTION

        // 允许表头拖动，用户可以自由排序语言列的展示顺序
        table.tableHeader.reorderingAllowed = true
        table.tableHeader.resizingAllowed = true

        // 设置最小列宽以支持横向滚动
        table.autoResizeMode = javax.swing.JTable.AUTO_RESIZE_OFF

        table.fillsViewportHeight = false

        table.selectionModel.addListSelectionListener { event ->
            if (!event.valueIsAdjusting) {
                val selected = getSelectedKeys()
                selectedKey = selected.firstOrNull()
                onSelectionChanged?.invoke(selected)
            }
        }

        table.setDefaultRenderer(Any::class.java, renderer)
        renderer.sourceLocale = sourceLocale

        // 双击打开 key 编辑弹窗，对齐原版「点 key 展开多语言对照」的交互
        table.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (e.clickCount == 2 && e.button == java.awt.event.MouseEvent.BUTTON1) {
                    val row = table.rowAtPoint(e.point)
                    if (row >= 0) {
                        selectedKey = model.rowAt(row)?.key
                        selectedKey?.let { onEditKey?.invoke(it) }
                    }
                }
            }
        })
    }

    /** 顶部工具栏：搜索框 + 三个状态过滤按钮。 */
    private fun buildToolbar(): JPanel = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.empty(6, 6, 4, 6)

        add(searchField.apply {
            textEditor.emptyText.text = "搜索 key 或译文"
            addDocumentListener(object : javax.swing.event.DocumentListener {
                override fun insertUpdate(e: javax.swing.event.DocumentEvent) = applyFilter()
                override fun removeUpdate(e: javax.swing.event.DocumentEvent) = applyFilter()
                override fun changedUpdate(e: javax.swing.event.DocumentEvent) = applyFilter()
            })
        }, BorderLayout.CENTER)

        add(JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            add(filterButton("全部", null))
            add(filterButton("待填写", TranslationStatus.EMPTY))
            add(filterButton("缺失", TranslationStatus.MISSING))
        }, BorderLayout.EAST)
    }

    /**
     * 状态过滤按钮。
     *
     * 用 [com.intellij.ui.components.JButton] 的 toggle 形态实现，主题与平台一致；
     * 不自己画控件，避免又出现"看起来不像原生"的问题。
     */
    private fun filterButton(text: String, status: TranslationStatus?): JPanel {
        val button = javax.swing.JButton(text).apply {
            isFocusable = false
            toolTipText = when (status) {
                null -> "显示全部"
                TranslationStatus.EMPTY -> "只看 key 存在但值为空的条目"
                TranslationStatus.MISSING -> "只看语言包里根本没有的key"
                TranslationStatus.TRANSLATED -> "只看已翻译的条目"
            }
            addActionListener { statusFilter = status; applyFilter() }
        }
        return JPanel().apply { add(button) }
    }

    /** 底部状态栏：显示匹配数量与配置提示。 */
    private fun buildStatusBar(): JPanel = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.empty(4, 6, 6, 6)
        add(statusLabel.apply {
            foreground = JBUI.CurrentTheme.Label.disabledForeground()
        }, BorderLayout.CENTER)
    }

    /** 更新底部提示文案（配置错误等）。 */
    fun setStatus(text: String) {
        statusLabel.text = text
    }

    /**
     * 更新表格内容。
     *
     * 传入完整数据，实际显示哪些行由搜索词与状态筛选决定 ——
     * 过滤在数据层做而不是靠表格自带的行过滤器，避免排序与选行时的索引错位。
     */
    fun setRows(rows: List<TranslationRow>) {
        allRows = rows
        applyFilter()
    }

    /** 应用搜索词与状态筛选。 */
    private fun applyFilter() {
        keyword = searchField.text?.trim()?.lowercase() ?: ""
        val status = statusFilter

        val visible = allRows.filter { row ->
            val statusOk = status == null || status != TranslationStatus.TRANSLATED &&
                displayLocales.any { row.status(it) == status }
            val keywordOk = keyword.isEmpty() || row.matches(keyword)
            statusOk && keywordOk
        }

        val previousKey = selectedKey
        model.update(visible)
        updateCountLabel(visible.size)
        // 过滤后重新定位原选中行，避免用户每输一个字就丢失焦点
        previousKey?.let { selectKey(it) }
    }

    /** 该行是否命中关键词：key 或任意语言的译文包含该词。 */
    private fun TranslationRow.matches(lowercaseKeyword: String): Boolean {
        if (key.lowercase().contains(lowercaseKeyword)) return true
        return values.values.any { it.lowercase().contains(lowercaseKeyword) }
    }

    private fun updateCountLabel(visible: Int) {
        val filterInfo = buildString {
            if (keyword.isNotEmpty()) append("搜索「$keyword」 · ")
            statusFilter?.let { append("${it.label} · ") }
        }
        val progress = statusFilter?.let { filterInfo } ?: ""
        statusLabel.text = "$progress 共 $visible 条${if (visible != allRows.size) " / 全部 ${allRows.size} 条" else ""}"
    }

    /** 更新语言列表。语言集合变化时（如新增语言包）调用，会重建列。 */
    fun setLocales(source: String, displays: List<String>) {
        if (source == sourceLocale && displays == displayLocales) return
        sourceLocale = source
        displayLocales = displays
        renderer.sourceLocale = source

        val previous = model
        model = RowTableModel(source, displays)
        table.model = model
        // 换 model 会清空选中，把原来的行数据接回去，避免刷新后表格闪空
        model.update(previous.currentRows())
        allRows = previous.currentRows()
    }

    /** 按 key 选中一行；不存在则清除选中。 */
    fun selectKey(key: String?) {
        if (key == null) {
            table.clearSelection()
            return
        }
        val idx = model.indexOfKey(key)
        if (idx >= 0) {
            table.selectionModel.setSelectionInterval(idx, idx)
            table.scrollRectToVisible(table.getCellRect(idx, 0, true))
        }
    }

    /** 当前选中的整行数据。 */
    fun selectedRow(): TranslationRow? =
        table.selectedRow.takeIf { it >= 0 }?.let { model.rowAt(it) }

    /** 获取全部选中的 key 列表。 */
    fun getSelectedKeys(): List<String> {
        val indices = table.selectedRows
        return indices.map { model.rowAt(it)?.key }.filterNotNull()
    }

    /** 供外部与测试读取的表格引用。 */
    fun asTable(): JBTable = table

    /**
     * 表格模型。
     *
     * 第 0 列是 key（普通字符串，交给默认渲染器），其余列是 [CellData]，
     * 由 [TranslationCellRenderer] 处理状态色与占位符。
     */
    private class RowTableModel(
        private val sourceLocale: String,
        private val displayLocales: List<String>,
    ) : AbstractTableModel() {

        /** 列顺序：Key 第一列，然后是源语言，然后是其他显示语言。 */
        private val localeOrder: List<String> = listOf(sourceLocale) + displayLocales
        // 列标题：Key + 源语言 + 其他语言
        private val columns: List<String> = listOf("Key") + listOf(Flags.displayName(sourceLocale)) + displayLocales.map { Flags.displayName(it) }

        private var rows: List<TranslationRow> = emptyList()

        override fun getRowCount(): Int = rows.size

        override fun getColumnCount(): Int = columns.size

        override fun getColumnName(column: Int): String = columns[column]

        /** 第 0 列返回 String（Key），其余返回 CellData 以走自定义渲染器。 */
        override fun getColumnClass(columnIndex: Int): Class<*> =
            if (columnIndex == 0) String::class.java else Any::class.java

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val row = rows[rowIndex]
            if (columnIndex == 0) return row.key
            return CellData(row, localeOrder[columnIndex - 1])
        }

        fun update(newRows: List<TranslationRow>) {
            rows = newRows
            fireTableStructureChanged()
        }

        fun rowAt(index: Int): TranslationRow? = rows.getOrNull(index)

        fun indexOfKey(key: String): Int = rows.indexOfFirst { it.key == key }

        fun currentRows(): List<TranslationRow> = rows
    }

    companion object {
        fun preferredPanelSize(): Dimension = Dimension(JBUI.scale(860), JBUI.scale(520))
    }
}