package com.hegg5577.i18n.actions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.hegg5577.i18n.locale.LocaleRepository
import com.hegg5577.i18n.locale.TranslationSaver
import com.hegg5577.i18n.settings.I18nContext
import com.hegg5577.i18n.ui.BatchOperationDialog
import com.hegg5577.i18n.ui.TranslationTablePanel
import com.hegg5577.i18n.ui.I18nPanelService

/**
 * 批量删除选中的 Key。
 *
 * 从侧边栏表格中右键菜单或工具栏触发，删除多个 key 时一次性清理全部语言包。
 */
class BatchDeleteKeysAction : AnAction() {

    /** 从翻译表格读取选中项，保存删除结果后刷新面板。 */
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val tablePanel = findTablePanel(project) ?: run {
            notify(project, "未找到翻译表格", NotificationType.WARNING)
            return
        }

        val selectedKeys = tablePanel.getSelectedKeys()
        if (selectedKeys.isEmpty()) {
            notify(project, "请先选择要删除的 Key", NotificationType.INFORMATION)
            return
        }

        val context = I18nContext.of(project)
        val repository = context.repository() ?: run {
            notify(project, "未找到语言包", NotificationType.WARNING)
            return
        }

        // 确认对话框
        val dialog = BatchOperationDialog(
            project,
            BatchOperationDialog.OperationType.DELETE,
            selectedKeys,
            context.displayLocales,
        )

        if (!dialog.showAndGet()) return

        // 执行删除
        val result = TranslationSaver.deleteKeys(project, repository, selectedKeys)

        if (result.succeeded.isNotEmpty()) {
            project.getService(I18nPanelService::class.java).refresh()
        }

        if (result.isSuccess) {
            notify(
                project,
                "成功删除 ${result.succeeded.size} 个语言的 Key，可按 Ctrl+Z 撤销",
                NotificationType.INFORMATION,
            )
        } else {
            val message = buildString {
                appendLine("删除失败。详情：")
                if (result.succeeded.isNotEmpty()) {
                    appendLine("成功：${result.succeeded.joinToString("、")}")
                }
                for ((locale, error) in result.failed) {
                    appendLine("$locale：$error")
                }
            }
            Messages.showErrorDialog(project, message, "删除 Key 失败")
        }
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val tablePanel = project?.let { findTablePanel(it) }
        val hasSelection = tablePanel?.getSelectedKeys()?.isNotEmpty() == true
        e.presentation.isEnabled = hasSelection
        e.presentation.text = when (val count = tablePanel?.getSelectedKeys()?.size) {
            null, 0 -> "删除选中的 Key"
            1 -> "删除 1 个 Key"
            else -> "删除 $count 个 Key"
        }
    }

    /** 按组件类型定位翻译表格，避免依赖页签顺序。 */
    private fun findTablePanel(project: Project): TranslationTablePanel? {
        val toolWindow = com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
            .getToolWindow("i18n-helper") ?: return null
        return toolWindow.contentManager.contents
            .firstNotNullOfOrNull { it.component as? TranslationTablePanel }
    }

    private fun notify(project: Project, message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("i18n.helper.notifications")
            .createNotification(message, type)
            .notify(project)
    }
}
