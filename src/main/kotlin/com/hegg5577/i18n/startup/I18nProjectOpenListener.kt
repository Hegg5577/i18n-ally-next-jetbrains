package com.hegg5577.i18n.startup

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManagerListener
import com.hegg5577.i18n.settings.I18nSettings

/**
 * 项目打开时初始化 i18n 配置（包括 YAML）。
 */
class I18nProjectOpenListener : ProjectManagerListener {

    override fun projectOpened(project: Project) {
        val settings = I18nSettings.getInstance(project)
        settings.initProject(project)
    }
}
