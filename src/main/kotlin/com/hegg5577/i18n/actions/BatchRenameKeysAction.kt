package com.hegg5577.i18n.actions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.InputValidator
import com.intellij.openapi.ui.Messages
import com.hegg5577.i18n.locale.LocaleRepository
import com.hegg5577.i18n.locale.TranslationSaver
import com.hegg5577.i18n.settings.I18nContext
import com.hegg5577.i18n.ui.BatchOperationDialog
import com.hegg5577.i18n.ui.TranslationTablePanel
import com.hegg5577.i18n.ui.I18nPanelService

class BatchRenameKeysAction : AnAction() {

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val tablePanel = findTablePanel(project) ?: run {
            notify(project, "未找到翻译表格", NotificationType.WARNING)
            return
        }

        val selectedKeys = tablePanel.getSelectedKeys()
        if (selectedKeys.isEmpty()) {
            notify(project, "请先选择要重命名的 Key", NotificationType.INFORMATION)
            return
        }

        val context = I18nContext.of(project)
        val repository = context.repository() ?: run {
            notify(project, "未找到语言包", NotificationType.WARNING)
            return
        }

        // 单个 key 直接重命名
        if (selectedKeys.size == 1) {
            performSingleRename(project, selectedKeys[0], repository)
            return
        }

        // 多个 key 提供简单的前缀替换
        performPrefixReplace(project, selectedKeys, repository)
    }

    /** 校验单个新名称，保存重命名结果后刷新面板。 */
    private fun performSingleRename(
        project: Project,
        oldKey: String,
        repository: LocaleRepository,
    ) {
        val validator = object : InputValidator {
            override fun checkInput(input: String): Boolean {
                return input.isNotEmpty() && input.matches(Regex("[\\w.\\-]+"))
            }

            override fun canClose(input: String): Boolean {
                return checkInput(input)
            }
        }

        val newKey = Messages.showInputDialog(
            project,
            "输入新 Key:",
            "重命名 Key",
            Messages.getQuestionIcon(),
            oldKey,
            validator,
        ) ?: return

        if (newKey == oldKey) return

        val result = renameWithReferences(project, repository, oldKey, newKey)

        if (result.succeeded.isNotEmpty()) {
            project.getService(I18nPanelService::class.java).refresh()
        }

        if (result.isSuccess) {
            notify(project, "成功重命名 Key，可按 Ctrl+Z 撤销", NotificationType.INFORMATION)
        } else {
            val message = buildString {
                appendLine("重命名失败。详情：")
                for ((locale, error) in result.failed) {
                    appendLine("$locale：$error")
                }
            }
            Messages.showErrorDialog(project, message, "重命名 Key 失败")
        }
    }

    /** 批量替换匹配的前缀，在保存结果返回后统一刷新面板。 */
    private fun performPrefixReplace(
        project: Project,
        selectedKeys: List<String>,
        repository: LocaleRepository,
    ) {
        val validator = object : InputValidator {
            override fun checkInput(input: String): Boolean {
                return input.isEmpty() || input.matches(Regex("[\\w.\\-]*"))
            }

            override fun canClose(input: String): Boolean {
                return true
            }
        }

        val oldPrefix = Messages.showInputDialog(
            project,
            "输入要替换的旧前缀:",
            "前缀替换",
            Messages.getQuestionIcon(),
            "",
            validator,
        ) ?: return

        val newPrefix = Messages.showInputDialog(
            project,
            "输入新前缀:",
            "前缀替换",
            Messages.getQuestionIcon(),
            "",
            validator,
        ) ?: return

        val keysToRename = selectedKeys.filter { it.startsWith(oldPrefix) }
        if (keysToRename.isEmpty()) {
            notify(project, "没有匹配的 Key", NotificationType.INFORMATION)
            return
        }

        val mapping = keysToRename.associate { oldKey ->
            oldKey to oldKey.replaceFirst(oldPrefix, newPrefix)
        }

        val dialog = BatchOperationDialog(
            project,
            BatchOperationDialog.OperationType.RENAME,
            keysToRename,
            I18nContext.of(project).displayLocales,
        )

        if (!dialog.showAndGet()) return

        var succeeded = 0
        var failed = 0
        var hasChanges = false

        for ((oldKey, newKey) in mapping) {
            val result = renameWithReferences(project, repository, oldKey, newKey)
            if (result.succeeded.isNotEmpty()) hasChanges = true
            if (result.isSuccess) succeeded++ else failed++
        }

        if (hasChanges) {
            project.getService(I18nPanelService::class.java).refresh()
        }

        val message = when {
            failed == 0 -> "成功重命名 $succeeded 个 Key，可按 Ctrl+Z 撤销"
            else -> "重命名结果：成功 $succeeded 个，失败 $failed 个"
        }

        notify(project, message, NotificationType.INFORMATION)
    }

    /** 复用源码重命名准备逻辑，使表格入口也不会留下旧引用。 */
    private fun renameWithReferences(
        project: Project,
        repository: LocaleRepository,
        oldKey: String,
        newKey: String,
    ): TranslationSaver.Result {
        if (oldKey == newKey) return TranslationSaver.Result()
        return try {
            val updates = I18nRenameKeyHandler().prepareSourceUpdates(project, I18nContext.of(project), oldKey, newKey)
            TranslationSaver.renameKey(project, repository, oldKey, newKey, updates)
        } catch (e: com.intellij.openapi.progress.ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            TranslationSaver.Result(failed = mapOf(oldKey to (e.message ?: "源码引用更新失败")))
        }
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val tablePanel = project?.let { findTablePanel(it) }
        val hasSelection = tablePanel?.getSelectedKeys()?.isNotEmpty() == true
        e.presentation.isEnabled = hasSelection
        e.presentation.text = when (val count = tablePanel?.getSelectedKeys()?.size) {
            null, 0 -> "重命名选中的 Key"
            1 -> "重命名 1 个 Key"
            else -> "批量重命名 $count 个 Key"
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
