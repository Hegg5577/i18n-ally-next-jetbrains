package com.hegg5577.i18n.actions

import com.hegg5577.i18n.locale.LocaleRepository
import com.hegg5577.i18n.locale.TranslationSaver
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File
import java.nio.file.Files

/** 验证实际文档写入保留人工内容，并在准备失败时不产生部分修改。 */
class TranslationSaverDocumentTest : BasePlatformTestCase() {
    private lateinit var root: File

    /** 每个用例使用独立的物理目录，保证 VFS 与磁盘读取指向同一文件。 */
    override fun setUp() {
        super.setUp()
        root = Files.createTempDirectory("i18n-saver").toFile()
    }

    /** 测试数据用后清理，避免影响其它项目的语言包扫描。 */
    override fun tearDown() {
        try {
            root.deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    /** 任一语言出现路径冲突时，先前准备成功的语言也不能被写入。 */
    fun testConflictPreventsAllWrites() {
        val en = write("en.json", "{}")
        val zh = write("zh.json", """{"common":"通用"}""")
        val repo = LocaleRepository(root, "locales", "{locale}.json")

        val result = TranslationSaver.save(project, repo, "en", linkedMapOf(
            "en" to mapOf("common.ok" to "OK"),
            "zh" to mapOf("common.ok" to "确定"),
        ))

        assertFalse(result.isSuccess)
        assertTrue(result.failed.containsKey("zh"))
        assertEquals("{}", en.readText())
        assertEquals("""{"common":"通用"}""", zh.readText())
    }

    /** 保存前必须检查最新 Document，不能覆盖尚未保存的人工译文。 */
    fun testOnlyMissingPreservesUnsavedTranslation() {
        val file = write("zh.json", "{}")
        val document = document(file)
        WriteCommandAction.runWriteCommandAction(project) {
            document.setText("""{"manual":"人工译文"}""")
        }
        val repo = LocaleRepository(root, "locales", "{locale}.json")

        val result = TranslationSaver.save(project, repo, "en", mapOf(
            "zh" to mapOf("manual" to "机器译文", "missing" to "新译文"),
        ), onlyMissing = true)

        assertTrue(result.isSuccess)
        assertEquals(mapOf("manual" to "人工译文", "missing" to "新译文"), repo.valuesOf("zh"))
    }

    /** 目标 key 冲突时，语言包和已准备的源码更新都必须保持原样。 */
    fun testRenameConflictDoesNotWriteSource() {
        val localeFile = write("en.json", """{"old":"Old","new":"New"}""")
        val sourceFile = File(root, "source.js").apply { writeText("t('old')") }
        val source = document(sourceFile)
        val repo = LocaleRepository(root, "locales", "{locale}.json")

        val result = TranslationSaver.renameKey(project, repo, "old", "new", mapOf(source to "t('new')"))

        assertFalse(result.isSuccess)
        assertEquals("t('old')", source.text)
        assertEquals("t('old')", sourceFile.readText())
        assertEquals("""{"old":"Old","new":"New"}""", localeFile.readText())
    }

    /** 成功重命名时语言包必须移除旧 key，并提交对应源码更新。 */
    fun testRenameMovesTranslationAndUpdatesSource() {
        write("en.json", """{"old":"Hello"}""")
        write("zh.json", """{"old":"你好"}""")
        val sourceFile = File(root, "source.js").apply { writeText("t('old')") }
        val source = document(sourceFile)
        val repo = LocaleRepository(root, "locales", "{locale}.json")

        val result = TranslationSaver.renameKey(project, repo, "old", "new", mapOf(source to "t('new')"))

        assertTrue(result.isSuccess)
        assertEquals(mapOf("new" to "Hello"), repo.valuesOf("en"))
        assertEquals(mapOf("new" to "你好"), repo.valuesOf("zh"))
        assertEquals("t('new')", source.text)
        assertEquals("t('new')", sourceFile.readText())
    }

    /** 多文件布局新增译文应跟随源语言的 namespace，已有译文在原文件更新。 */
    fun testSaveUsesKeyOwnershipAcrossFiles() {
        write("en/common.json", """{"title":"Title"}""")
        val common = write("zh/common.json", "{}")
        val page = write("zh/page.json", """{"page":"旧值"}""")
        val repo = LocaleRepository(root, "locales", "{locale}/{namespace}.json")

        val result = TranslationSaver.save(project, repo, "en", mapOf(
            "zh" to mapOf("title" to "标题", "page" to "新值"),
        ))

        assertTrue(result.isSuccess)
        assertEquals(mapOf("title" to "标题"), repo.valuesOfFile(common))
        assertEquals(mapOf("page" to "新值"), repo.valuesOfFile(page))
    }

    /** 构造真实文件，使所有保存调用都经过正式的语言包定位入口。 */
    private fun write(name: String, text: String): File = File(root, "locales/$name").apply {
        parentFile.mkdirs()
        writeText(text)
    }

    /** 获取最新编辑器文档，模拟磁盘值与未保存内容不同的情况。 */
    private fun document(file: File): Document {
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)!!
        return FileDocumentManager.getInstance().getDocument(virtualFile)!!
    }
}
