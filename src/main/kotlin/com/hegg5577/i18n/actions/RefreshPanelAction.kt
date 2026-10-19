package com.hegg5577.i18n.actions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.hegg5577.i18n.ui.I18nPanelService
import com.hegg5577.i18n.ui.showI18nToolWindow

/** 重新扫描语言包并刷新侧边栏表格。 */
class RefreshPanelAction : AnAction() {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val service = project.getService(I18nPanelService::class.java)
        service.refresh()

        val snapshot = service.snapshot()
        NotificationGroupManager.getInstance()
            .getNotificationGroup("i18n.helper.notifications")
            .createNotification(
                "已刷新：${snapshot.rows.size} 个 key，${snapshot.displayLocales.size} 种语言",
                NotificationType.INFORMATION,
            )
            .notify(project)
        project.showI18nToolWindow()
    }
}