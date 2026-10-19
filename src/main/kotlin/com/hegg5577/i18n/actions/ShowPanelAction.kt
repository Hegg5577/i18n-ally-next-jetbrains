package com.hegg5577.i18n.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.hegg5577.i18n.ui.showI18nToolWindow

/** 打开侧边栏的多语言翻译对照表。 */
class ShowPanelAction : AnAction() {
    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        project.getService(com.hegg5577.i18n.ui.I18nPanelService::class.java).refresh()
        project.showI18nToolWindow()
    }
}