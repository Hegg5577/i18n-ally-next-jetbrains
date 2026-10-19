package com.hegg5577.i18n.actions

import com.hegg5577.i18n.editor.KeyUsageScanner
import com.hegg5577.i18n.settings.I18nContext
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.io.File

/** 覆盖源码 key 的精确替换、最新文档读取及准备失败时不写入的行为。 */
class I18nRenameKeyActionTest : BasePlatformTestCase() {

    /** 同名参数、相似 key、注释和包含调用示例的字符串都必须保持原样。 */
    fun testOnlyRealCallsChange() {
        val source = """
            // t('old.key')
            /* i18n.t('old.key') */
            const example = "t('old.key')";
            const template = `t('old.key')`;
            const plain = 'old.key';
            t('old.key', { label: 'old.key' });
            i18n.t("old.key");
            ${'$'}t('old.key');
            translate('old.key', { value: 'old.key' });
            t('old.key.extra');
        """.trimIndent()
        val file = myFixture.configureByText("calls.js", source)

        val updated = I18nRenameKeyHandler().renameSourceText(file, source, "old.key", "new.long.key")

        assertEquals(
            """
                // t('old.key')
                /* i18n.t('old.key') */
                const example = "t('old.key')";
                const template = `t('old.key')`;
                const plain = 'old.key';
                t('new.long.key', { label: 'old.key' });
                i18n.t("new.long.key");
                ${'$'}t('new.long.key');
                translate('new.long.key', { value: 'old.key' });
                t('old.key.extra');
            """.trimIndent(),
            updated,
        )
        assertEquals(source, file.text)
    }

    /** 自定义规则优先，只有命名捕获组对应的 key 能被修改。 */
    fun testCustomPatternOnlyChangesItsKeyCapture() {
        val source = "lookup('old.key', 'old.key'); t('old.key');"
        val file = myFixture.configureByText("custom.js", source)
        val patterns = listOf("""lookup\('(?<key>[\w.]+)'""")

        val updated = I18nRenameKeyHandler().renameSourceText(file, source, "old.key", "new.key", patterns)

        assertEquals("lookup('new.key', 'old.key'); t('old.key');", updated)
    }

    /** 从未保存的 Document 读取引用，并遵守源码目录、扩展名和依赖目录限制。 */
    fun testPreparationUsesLatestDocumentAndConfiguredScope() {
        val source = myFixture.addFileToProject("src/current.js", "t('disk.key');")
        myFixture.addFileToProject("outside/ignored.js", "t('old.key');")
        myFixture.addFileToProject("src/ignored.ts", "t('old.key');")
        myFixture.addFileToProject("src/node_modules/ignored.js", "t('old.key');")
        val document = FileDocumentManager.getInstance().getDocument(source.virtualFile)!!
        WriteCommandAction.runWriteCommandAction(project) {
            document.setText("t('old.key'); const label = 'old.key';")
        }
        val context = I18nContext.of(project).copy(
            projectRoot = File(myFixture.tempDirFixture.tempDirPath),
            sourceDirs = listOf("src", "src"),
            supportedExtensions = setOf("js"),
            usageMatchRegex = emptyList(),
        )

        val updates = I18nRenameKeyHandler().prepareSourceUpdates(project, context, "old.key", "new.key")

        assertEquals(mapOf(document to "t('new.key'); const label = 'old.key';"), updates)
        assertEquals("t('old.key'); const label = 'old.key';", document.text)
    }

    /** 提示扫描的截断不能让重命名留下旧引用，达到上限时必须停止准备。 */
    fun testScanLimitRejectsPartialRename() {
        val source = "t('old.key');\n".repeat(KeyUsageScanner.MAX_HINTS_PER_FILE + 1)
        val file = myFixture.configureByText("large.js", source)

        try {
            I18nRenameKeyHandler().renameSourceText(file, source, "old.key", "new.key")
            fail("达到扫描上限时应拒绝重命名")
        } catch (exception: IllegalStateException) {
            assertTrue(exception.message.orEmpty().contains("扫描上限"))
        }
        assertEquals(source, file.text)
    }

    /** 相同调用结束位置对应多个 key 范围时，拒绝猜测要替换的引用。 */
    fun testAmbiguousCustomPatternsRejectPreparation() {
        val source = "lookup('old.key', 'old.key');"
        val file = myFixture.configureByText("ambiguous.js", source)
        val patterns = listOf(
            """lookup\('(?<key>[\w.]+)'""",
            """lookup\('old.key', '(?<key>[\w.]+)'""",
        )

        try {
            I18nRenameKeyHandler().renameSourceText(file, source, "old.key", "new.key", patterns)
            fail("引用位置存在歧义时应拒绝重命名")
        } catch (exception: IllegalStateException) {
            assertTrue(exception.message.orEmpty().contains("无法唯一定位"))
        }
        assertEquals(source, file.text)
    }
}
