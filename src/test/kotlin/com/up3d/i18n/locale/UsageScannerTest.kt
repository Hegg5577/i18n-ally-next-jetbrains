package com.hegg5577.i18n.locale

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * key 使用情况扫描测试。
 *
 * 验证「缺失检测」的数据来源正确 —— 这是唯一能发现
 * 「代码写了 t('a.b') 但语言包没有 a.b」的机制。
 */
class UsageScannerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun write(path: String, content: String) {
        val file = File(path)
        file.parentFile.mkdirs()
        file.writeText(content)
    }

    @Test
    fun `扫描出代码里引用的 key 及其文件`() {
        write(File(temp.root, "src/a.jsx").path, """const x = t('common.ok');""")
        write(File(temp.root, "src/b.jsx").path, """const y = t('common.ok'); const z = t('user.name');""")

        val usages = UsageScanner.scan(temp.root, listOf("src"))

        assertEquals(setOf("common.ok", "user.name"), usages.keys)
        assertEquals(2, usages["common.ok"]!!.fileCount)
        assertEquals(1, usages["user.name"]!!.fileCount)
    }

    @Test
    fun `跳过 node_modules 与构建产物`() {
        write(File(temp.root, "src/a.jsx").path, """t('real.key');""")
        write(File(temp.root, "node_modules/pkg/index.js").path, """t('dep.key');""")
        write(File(temp.root, "dist/bundle.js").path, """t('dist.key');""")

        val usages = UsageScanner.scan(temp.root, listOf("src"))

        assertTrue(usages.containsKey("real.key"))
        assertFalse("依赖目录里的 key 不该被计入", usages.containsKey("dep.key"))
        assertFalse("构建产物里的 key 不该被计入", usages.containsKey("dist.key"))
    }

    @Test
    fun `按扩展名过滤`() {
        write(File(temp.root, "src/a.jsx").path, """t('jsx.key');""")
        write(File(temp.root, "src/b.md").path, """t('md.key');""")

        val usages = UsageScanner.scan(temp.root, listOf("src"), setOf("jsx"))

        assertTrue(usages.containsKey("jsx.key"))
        assertFalse(usages.containsKey("md.key"))
    }

    @Test
    fun `识别多种 t 写法`() {
        write(
            File(temp.root, "src/a.jsx").path,
            """
            t('k1');
            i18n.t('k2');
            this.props.t('k3');
            ${'$'}t('k4');
            """.trimIndent(),
        )

        val usages = UsageScanner.scan(temp.root, listOf("src"))

        assertTrue("应识别出 4 个 key，实际 ${usages.keys}", usages.keys.containsAll(
            listOf("k1", "k2", "k3", "k4")
        ))
    }

    @Test
    fun `分析出缺失与多余的 key`() {
        write(File(temp.root, "src/a.jsx").path, """t('used.key'); t('ghost.key');""")
        write(File(temp.root, "locales/en/index.json").path, """{"used":{"key":"Used"},"orphan":{"key":"Orphan"}}""")

        val report = UsageScanner.analyze(
            projectRoot = temp.root,
            localesDir = "locales",
            pathMatcher = PathMatcher.DEFAULT_PATTERN,
            keyStyle = KeyStyle.NESTED,
            sourceDirs = listOf("src"),
        )

        assertEquals(listOf("ghost.key"), report.missing)
        assertEquals(listOf("orphan.key"), report.unused)
        assertEquals(2, report.definedCount)
        assertEquals(2, report.usedCount)
    }

    @Test
    /** 外部样例项目可选，避免其他开发者因缺少本机数据而失败。 */
    fun `真实项目能找出缺失的 key`() {
        val projectRoot = File(System.getenv("I18N_TEST_PROJECT") ?: "test-project")
        assumeTrue("真实项目不存在，跳过：$projectRoot", projectRoot.isDirectory)

        val report = UsageScanner.analyze(
            projectRoot = projectRoot,
            localesDir = "src/i18n/locales",
            pathMatcher = PathMatcher.DEFAULT_PATTERN,
            keyStyle = KeyStyle.NESTED,
            sourceDirs = listOf("src"),
        )

        assertTrue("语言包应有 1000+ 个 key，实际 ${report.definedCount}", report.definedCount > 1000)
        assertTrue("代码应引用 100+ 个 key，实际 ${report.usedCount}", report.usedCount > 100)

        // 该项目已知的缺失：Common.cancel（疑似大小写写错）与 nest.confirmAddNum
        assertTrue(
            "应检出已知缺失 Common.cancel，实际 ${report.missing}",
            report.missing.contains("Common.cancel"),
        )
    }
}
