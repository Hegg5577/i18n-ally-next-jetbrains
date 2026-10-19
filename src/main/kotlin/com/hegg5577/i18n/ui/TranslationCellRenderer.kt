package com.hegg5577.i18n.ui

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTable
import javax.swing.table.TableCellRenderer

/**
 * 翻译对照表的单元格渲染器。
 *
 * 状态标记 + 译文文本，前景色随状态变化：
 * 已翻译与源语言用正常色，空/缺失用灰色，一眼可辨。
 *
 * **必须同时处理 String 与 [CellData]**：JTable 按「列的声明类型」查找渲染器，
 * 而 Swing 里 `Object` 是所有类型的父类，所以注册一次 `Any::class.java` 渲染器后
 * **所有列都会走这里**，包括 Key 列（值是 String）。
 * 之前只在 `as? CellData` 失败时一律返回 `<缺失>`，导致 Key 列整列显示「缺失」——
 * 看起来像语言包全空，实际是渲染器类型不匹配。
 *
 * 刻意只用最基础的 Swing 组件组装，不依赖 `SimpleTextAttributes` /
 * `JBUI.CurrentTheme` 等平台内部 API —— 这些在不同平台版本间签名会变，
 * 踩中就是编译失败。这里用 `JBColor` + `UIUtil` 取颜色和字体，是最稳的组合。
 */
internal class TranslationCellRenderer : TableCellRenderer {

    /** 基准列（源语言）由外层面板注入。 */
    var sourceLocale: String = "en"

    override fun getTableCellRendererComponent(
        table: JTable?,
        value: Any?,
        isSelected: Boolean,
        hasFocus: Boolean,
        row: Int,
        column: Int,
    ): Component {
        // Key 列：值就是 key 本身，原样显示
        if (value !is CellData) {
            return JLabel(value?.toString().orEmpty()).apply {
                font = UIUtil.getLabelFont()
                foreground = when {
                    isSelected && table != null -> table.selectionForeground
                    else -> JBColor.foreground()
                }
                border = JBUI.Borders.empty(2, 4)
            }
        }

        val isSource = value.locale == sourceLocale
        val status = value.row.status(value.locale)

        // 改进：缺失/空直接显示空字符串，不显示 <缺失> / <空>
        val text = value.row.values[value.locale].orEmpty()

        // 源语言列与已翻译项用正常色；缺失/空用灰色（去掉状态标记 √）
        val dimmed = !isSource && status != TranslationStatus.TRANSLATED

        val label = JLabel(text).apply {
            font = UIUtil.getLabelFont()
            foreground = when {
                isSelected && table != null -> table.selectionForeground
                dimmed -> JBColor.GRAY
                else -> JBColor.foreground()
            }
        }

        return JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            isOpaque = false
            border = if (isSource) {
                // 源语言列右侧一条竖线作为视觉锚点
                JBUI.Borders.customLineRight(JBColor.border())
            } else {
                JBUI.Borders.empty(2, 4)
            }
            add(label)
        }
    }
}

/** 单元格数据：行 + 该列对应的 locale。 */
internal data class CellData(val row: TranslationRow, val locale: String)