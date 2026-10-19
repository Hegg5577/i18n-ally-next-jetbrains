package com.hegg5577.i18n.actions

import com.hegg5577.i18n.locale.LocaleRepository
import com.hegg5577.i18n.ui.I18nPanelService
import com.hegg5577.i18n.ui.TranslationTablePanel
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.RegisterToolWindowTask
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.content.ContentFactory
import java.io.File
import java.nio.file.Files
import javax.swing.JPanel

/** 覆盖批量操作对页签顺序的兼容，以及保存与面板刷新的先后关系。 */
class BatchKeyActionsTest : BasePlatformTestCase() {

    /** 翻译表格不在第一个页签时，两个操作仍应返回同一实际表格。 */
    fun testFindTablePanelAcrossTabs() {
        val manager = ToolWindowManager.getInstance(project)
        val toolWindow = manager.getToolWindow("i18n-helper")
            ?: manager.registerToolWindow(RegisterToolWindowTask.closable("i18n-helper", null))
        val contents = toolWindow.contentManager
        contents.removeAllContents(true)
        val factory = ContentFactory.getInstance()
        contents.addContent(factory.createContent(JPanel(), "概览", false))
        contents.addContent(factory.createContent(JPanel(), "检测", false))
        val table = TranslationTablePanel("en", emptyList())
        val tableContent = factory.createContent(table, "翻译对照", false)
        contents.addContent(tableContent)
        contents.setSelectedContent(contents.contents.first())

        for (action in listOf(BatchDeleteKeysAction(), BatchRenameKeysAction())) {
            val find = action.javaClass.getDeclaredMethod("findTablePanel", Project::class.java)
            find.isAccessible = true
            assertSame(table, find.invoke(action, project))
            contents.removeContent(tableContent, false)
            assertNull(find.invoke(action, project))
            contents.addContent(tableContent)
        }
    }

    /** 单项重命名无需伪造事件，也应在保存后通知服务；其他未保存文档保持原状。 */
    fun testRenameRefreshesServiceAfterSaving() {
        val root = Files.createTempDirectory("i18n-batch-actions").toFile()
        val documentManager = FileDocumentManager.getInstance()
        val service = project.getService(I18nPanelService::class.java)
        val repository = LocaleRepository(root, "locales", "{locale}.json")
        var refreshes = 0
        val unsubscribe = service.subscribe {
            refreshes++
            assertEquals(mapOf("new" to "Hello"), repository.valuesOf("en"))
        }
        val previousInput = TestDialogManager.setTestInputDialog { "new" }
        try {
            val localeFile = File(root, "locales/en.json")
            localeFile.parentFile.mkdirs()
            localeFile.writeText("""{"old":"Hello"}""")
            val unrelatedFile = File(root, "notes.txt").apply { writeText("original") }
            val unrelatedVirtualFile = LocalFileSystem.getInstance()
                .refreshAndFindFileByIoFile(unrelatedFile)!!
            val unrelatedDocument = documentManager.getDocument(unrelatedVirtualFile)!!
            try {
                WriteCommandAction.runWriteCommandAction(project) {
                    unrelatedDocument.setText("unsaved")
                }
                val action = BatchRenameKeysAction()
                val rename = action.javaClass.getDeclaredMethod(
                    "performSingleRename", Project::class.java, String::class.java, LocaleRepository::class.java,
                )
                rename.isAccessible = true
                rename.invoke(action, project, "old", repository)

                assertEquals(1, refreshes)
                assertTrue(documentManager.isDocumentUnsaved(unrelatedDocument))
                assertEquals("original", unrelatedFile.readText())
            } finally {
                documentManager.reloadFromDisk(unrelatedDocument)
            }
        } finally {
            TestDialogManager.setTestInputDialog(previousInput)
            unsubscribe()
            root.deleteRecursively()
        }
    }
}
