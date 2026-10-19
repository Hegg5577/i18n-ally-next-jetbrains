package com.hegg5577.i18n.locale

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 路径匹配与语言包解析的回归测试。
 *
 * 这层刻意不依赖任何 IDE API（纯文件操作），就是为了能在没有 IDE 的环境下
 * 用普通 JUnit 快速验证 —— 之前「一个国际化都显示不出来」就是路径解析
 * 提前 return 导致的，这类问题必须有测试兜住。
 */
class PathMatcherTest {

    @get:Rule
    val temp = TemporaryFolder()

    /**
     * 项目语言包根目录。配置里的 `localesPath` 指向它，
     * 所以 PathMatcher 收到的根就是这一层，模式 `{locale}/{file}` 从 `locales` 开始匹配。
     */
    private val localesRoot: File
        get() = File(temp.root, "locales").apply { mkdirs() }

    private fun write(path: String, content: String): File {
        val file = File(path)
        file.parentFile.mkdirs()
        file.writeText(content)
        return file
    }

    private fun locales(vararg entries: Pair<String, String>) {
        for ((locale, json) in entries) {
            write(File(localesRoot, "$locale/index.json").path, json)
        }
    }

    // region 路径模式

    @Test
    fun `每语言一目录`() {
        locales("en" to """{"a":"A"}""", "zh" to """{"a":"甲"}""")

        val result = PathMatcher("{locale}/{file}").resolve(localesRoot)

        assertEquals(setOf("en", "zh"), result.map { it.locale }.toSet())
        assertTrue(result.all { it.file.isFile })
    }

    @Test
    fun `每语言一文件`() {
        write(File(localesRoot, "en.json").path, """{"a":"A"}""")
        write(File(localesRoot, "zh.json").path, """{"a":"甲"}""")

        val result = PathMatcher("{locale}.json").resolve(localesRoot)

        assertEquals(setOf("en", "zh"), result.map { it.locale }.toSet())
    }

    @Test
    fun `目录名含连字符的语言`() {
        locales("zh-CN" to """{"a":"甲"}""", "pt-BR" to """{"a":"乙"}""")

        val result = PathMatcher("{locale}/{file}").resolve(localesRoot)

        assertEquals(setOf("zh-CN", "pt-BR"), result.map { it.locale }.toSet())
    }

    @Test
    fun `目录不存在时返回空而不是抛异常`() {
        val result = PathMatcher("{locale}/{file}").resolve(File(temp.root, "not-exist"))

        assertTrue(result.isEmpty())
    }

    @Test
    fun `缺少默认文件名的目录不会产出无效路径`() {
        // en 目录下没有 index.json，只有其它文件 —— 不应产出 File(en, "en") 这种假路径
        write(File(localesRoot, "en/readme.md").path, "x")
        locales("zh" to """{"a":"甲"}""")

        val result = PathMatcher("{locale}/{file}").resolve(localesRoot)

        // zh 有 index.json 仍应命中；en 没有 json 时不应产出脏数据
        assertTrue(result.none { !it.file.isFile })
    }

    /** 端到端：真实项目里最常见的 `locales/{locale}/{namespace}.json` 结构。 */
    @Test
    fun `命名空间结构`() {
        write(File(localesRoot, "en/common.json").path, """{"ok":"OK"}""")
        write(File(localesRoot, "zh/common.json").path, """{"ok":"好"}""")

        val result = PathMatcher("{locale}/{namespace}.json").resolve(localesRoot)

        assertEquals(setOf("en", "zh"), result.map { it.locale }.toSet())
    }

    /** 端到端：仓库读到的 key 必须与磁盘上的文件对得上。 */
    @Test
    fun `从默认布局读出 key 与译文`() {
        locales(
            "en" to """{"common":{"cancel":"Cancel","confirm":"Confirm"}}""",
            "zh" to """{"common":{"cancel":"取消"}}""",
        )

        val repo = LocaleRepository(temp.root, "locales")
        val scanned = repo.scanLocales()

        assertEquals(listOf("en", "zh"), scanned.map { it.locale })
        assertEquals(setOf("common.cancel", "common.confirm"), scanned.first { it.locale == "en" }.keys)
        assertEquals(setOf("common.cancel"), scanned.first { it.locale == "zh" }.keys)
        assertEquals("取消", repo.valuesOf("zh")["common.cancel"])
    }

    // endregion

    // region 语言包内容解析

    @Test
    fun `嵌套结构展平为点号key`() {
        locales("en" to """{"common":{"cancel":"Cancel","ok":"OK"}}""")

        val repo = LocaleRepository(temp.root, "locales")
        val values = repo.valuesOf("en")

        assertEquals("Cancel", values["common.cancel"])
        assertEquals("OK", values["common.ok"])
    }

    @Test
    fun `平铺结构保留完整 key`() {
        locales("en" to """{"common.cancel":"Cancel"}""")

        val repo = LocaleRepository(temp.root, "locales", keyStyle = KeyStyle.FLAT)
        val values = repo.valuesOf("en")

        assertEquals("Cancel", values["common.cancel"])
    }

    @Test
    fun `扫描结果包含所有语言`() {
        locales(
            "en" to """{"a":"A"}""",
            "zh" to """{"a":"甲"}""",
            "ja" to """{"a":"あ"}""",
        )

        val repo = LocaleRepository(temp.root, "locales")
        val result = repo.scanLocales()

        assertEquals(3, result.size)
        assertEquals(listOf("en", "ja", "zh"), result.map { it.locale })
    }

    @Test
    fun `缺失与多余的 key 判定正确`() {
        locales(
            "en" to """{"a":"A","b":"B","c":"C"}""",
            // zh 缺 c，多了 x
            "zh" to """{"a":"甲","b":"乙","x":"叉"}""",
        )

        val report = LocaleRepository(temp.root, "locales").buildReport("en")

        assertEquals(listOf("c"), report.missing["zh"])
        assertEquals(listOf("x"), report.extra["zh"])
        assertEquals(false, report.isConsistent)
    }

    @Test
    fun `源语言不存在时退化为 key 最多的语言而非字母序首个`() {
        // 故意让 ar 在字母序最前，但它的 key 少于 zh：
        // 若按字母序取首个，基准会变成 ar，报告全错
        locales(
            "ar" to """{"only":"عربي"}""",
            "zh" to """{"a":"甲","b":"乙","c":"丙"}""",
        )

        val report = LocaleRepository(temp.root, "locales").buildReport("nonexistent")

        // 基准应为 zh，于是 zh 没有缺失也没有多余
        assertTrue(report.missing["zh"].isNullOrEmpty())
        // ar 缺 a/b/c
        assertEquals(listOf("a", "b", "c"), report.missing["ar"])
    }

    // endregion
}