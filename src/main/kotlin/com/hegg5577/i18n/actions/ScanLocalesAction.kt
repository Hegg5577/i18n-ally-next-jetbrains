package com.hegg5577.i18n.actions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.hegg5577.i18n.locale.LocaleReport
import com.hegg5577.i18n.locale.LocaleRepository
import java.io.File

/**
 * 扫描项目内所有语言包，输出 key 一致性报告。
 *
 * 里程碑 0 的验证入口：打通「IDE 动作 → 读项目文件 → 解析 JSON → 反馈结果」全链路。
 */
class ScanLocalesAction : AnAction() {

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val root = project.basePath?.let(::File)

        if (root == null || !root.isDirectory) {
            notify(project, "无法定位项目根目录", NotificationType.WARNING)
            return
        }

        val context = com.hegg5577.i18n.settings.I18nContext.of(project)
        val repository = context.repository() ?: return
        val report = repository.buildReport(context.sourceLocale)

        if (report.isEmpty) {
            notify(
                project,
                "未找到语言包。当前配置：路径 ${context.localesPath} + 模式 ${context.pathMatcher}",
                NotificationType.WARNING,
            )
            return
        }

        Messages.showInfoMessage(project, renderReport(report), "i18n 语言包一致性报告")
        notify(project, "扫描完成：共 ${report.locales.size} 个语言包", NotificationType.INFORMATION)
    }

    private fun renderReport(report: LocaleReport): String = buildString {
        val baseline = report.locales.firstOrNull { it.locale == "en" } ?: report.locales.first()
        appendLine("语言包数量：${report.locales.size}")
        appendLine("基准语言：${baseline.locale}（${baseline.keys.size} 个 key）")
        appendLine()

        if (report.isConsistent) {
            appendLine("所有语言的 key 完全一致。")
        } else {
            for ((locale, keys) in report.missing) {
                appendLine("缺失 · $locale（${keys.size} 个）")
                appendLine("  ${preview(keys)}")
            }
            for ((locale, keys) in report.extra) {
                appendLine("多余 · $locale（${keys.size} 个）")
                appendLine("  ${preview(keys)}")
            }
        }
    }

    private fun preview(keys: List<String>): String {
        val head = keys.take(8).joinToString(", ")
        return if (keys.size > 8) "$head …" else head
    }

    private fun notify(project: Project, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("i18n.helper.notifications")
            .createNotification(content, type)
            .notify(project)
    }
}
