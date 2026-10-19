package com.hegg5577.i18n.locale

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 覆盖同语言多文件读取、写入定位及缓存失效，避免退化为只处理首文件。 */
class MultiFileLocaleTest {
    @get:Rule
    val temp = TemporaryFolder()

    /** 在隔离目录中创建语言包，避免测试依赖真实项目。 */
    private fun write(path: String, json: String): File = File(temp.root, "locales/$path").apply {
        parentFile.mkdirs()
        writeText(json)
    }

    /** 统一显式 namespace 布局，避免意外走 legacy 单文件规则。 */
    private fun repository(pattern: String = "{locale}/{namespace}.json", style: KeyStyle = KeyStyle.NESTED) =
        LocaleRepository(temp.root, "locales", pattern, style)

    /** 错误必须携带定位上下文，而非静默选择首文件。 */
    private fun expectFailure(fragment: String, action: () -> Unit) {
        try {
            action()
            fail("应拒绝无法唯一定位的文件")
        } catch (e: IllegalStateException) {
            assertTrue(e.message.orEmpty(), e.message.orEmpty().contains(fragment))
        }
    }

    /** 聚合保持语言唯一，旧 API 继续返回同一首文件。 */
    @Test
    fun `扫描和读取合并同语言全部文件`() {
        val common = write("en/common.json", """{"common":{"ok":"OK"}}""")
        val page = write("en/page.json", """{"page":{"title":"Title"}}""")
        write("zh/common.json", """{"common":{"ok":"好"}}""")
        val repo = repository()

        assertEquals(listOf(common, page), repo.filesFor("en"))
        assertEquals(listOf("common", "page"), repo.resolvedFiles().filter { it.locale == "en" }.map { it.namespace })
        assertEquals(listOf("en", "zh"), repo.scanLocales().map { it.locale })
        assertEquals(setOf("common.ok", "page.title"), repo.scanLocales().first().keys)
        assertEquals(mapOf("common.ok" to "OK", "page.title" to "Title"), repo.valuesOf("en"))
        assertEquals(common, repo.scanLocales().first().file)
        assertEquals(common, repo.localeFile("en"))
        assertEquals(common, repo.localeFiles()["en"])
        assertEquals(mapOf("page.title" to "Title"), repo.valuesOfFile(page))
        assertEquals(listOf("page.title"), repo.buildReport("en").missing["zh"])
        assertEquals(KeyStyle.NESTED, repo.keyStyle)
        assertTrue(repo.valuesOf("missing").isEmpty())
        assertTrue(repo.filesFor("missing").isEmpty())
    }

    /** 已有 key 的真实归属优先于源语言的 namespace。 */
    @Test
    fun `已有key定位唯一所属文件`() {
        write("en/common.json", """{"title":"Title"}""")
        write("zh/common.json", "{}")
        val page = write("zh/page.json", """{"title":"标题"}""")

        assertEquals(page, repository().localeFile("zh", "title", "en"))
    }

    /** 新 key 必须沿源语言所在 namespace 写入，而非取目标首文件。 */
    @Test
    fun `新增key定位源语言相同namespace`() {
        write("en/common.json", "{}")
        write("en/page.json", """{"title":"Title"}""")
        write("zh/common.json", "{}")
        val page = write("zh/page.json", "{}")

        assertEquals(page, repository().localeFile("zh", "title", "en"))
    }

    /** 即使目标只剩一个文件，也不能写进不相干的 namespace。 */
    @Test
    fun `目标namespace缺失明确报错`() {
        write("en/page.json", """{"title":"Title"}""")
        write("zh/common.json", "{}")

        expectFailure("namespace「page」") { repository().localeFile("zh", "title", "en") }
        expectFailure("namespace「page」") { repository().localeFile("ja", "title", "en") }
    }

    /** 重复 key 只能读取合并，写入时必须拒绝歧义。 */
    @Test
    fun `目标key重复时拒绝写入`() {
        write("zh/common.json", """{"title":"甲"}""")
        write("zh/page.json", """{"title":"乙"}""")

        expectFailure("多个文件") { repository().localeFile("zh", "title", "en") }
    }

    /** 源语言缺失或重复 key 时无法推断 namespace。 */
    @Test
    fun `源key缺失或歧义时拒绝写入`() {
        write("en/common.json", """{"title":"Title"}""")
        write("en/page.json", """{"title":"Page"}""")
        write("zh/common.json", "{}")

        expectFailure("源语言「en」") { repository().localeFile("zh", "title", "en") }
        expectFailure("源语言「en」") { repository().localeFile("zh", "new", "en") }
    }

    /** 无 namespace 的 legacy 布局仍支持未知 key 与不存在的语言。 */
    @Test
    fun `单文件新增key向后兼容`() {
        val en = write("en/index.json", "{}")
        write("en/other.json", """{"ignored":"value"}""")
        val repo = repository("{locale}/{file}")

        assertEquals(listOf(en), repo.filesFor("en"))
        assertEquals(en, repo.localeFile("en", "new", "en"))
        assertNull(repo.localeFile("zh", "new", "en"))
        assertNull(repo.resolvedFiles().single().namespace)
        assertNull(ResolvedLocale("en", en).namespace)
    }

    /** 默认文件不存在时仍只回退到一个 JSON 文件。 */
    @Test
    fun `file默认回退保持单文件规则`() {
        val first = write("en/a.json", "{}")
        write("en/b.json", "{}")

        assertEquals(listOf(first), repository("{locale}/{file}").filesFor("en"))
    }

    /** 同一文件名中的两个 token 必须分别提取，namespace 可以带点号。 */
    @Test
    fun `提取复合文件名的namespace和locale`() {
        val en = write("admin.common.en.json", """{"title":"Title"}""")
        val zh = write("admin.common.zh-CN.json", "{}")
        write("unrelated.json", "{}")
        val repo = repository("{namespace}.{locale}.json")

        assertEquals(listOf("en", "zh-CN"), repo.resolvedFiles().map { it.locale })
        assertEquals(listOf("admin.common", "admin.common"), repo.resolvedFiles().map { it.namespace })
        assertEquals(listOf(en), repo.filesFor("en"))
        assertEquals(zh, repo.localeFile("zh-CN", "title", "en"))
    }

    /** namespace 作为目录时必须传递到最终文件。 */
    @Test
    fun `提取目录namespace及无后缀token的文件namespace`() {
        write("common/en/index.json", "{}")
        assertEquals("common", repository("{namespace}/{locale}/{file}").resolvedFiles().single().namespace)
        write("zh/page.json", "{}")
        assertEquals("page", repository("{locale}/{namespace}").resolvedFiles().single().namespace)
    }

    /** AUTO 顶层完整 key 的优先级不受 JSON 属性顺序影响。 */
    @Test
    fun `AUTO优先顶层完整key且保留原嵌套行为`() {
        write("en/index.json", """{"common.ok":"Flat","common":{"ok":"Nested","cancel":"Cancel"}}""")
        write("zh/index.json", """{"common":{"ok":"嵌套"},"common.ok":"平铺"}""")
        val auto = repository("{locale}/{file}", KeyStyle.AUTO)

        assertEquals("Flat", auto.valuesOf("en")["common.ok"])
        assertEquals("平铺", auto.valuesOf("zh")["common.ok"])
        assertEquals("Cancel", auto.valuesOf("en")["common.cancel"])
        assertEquals("Nested", repository("{locale}/{file}").valuesOf("en")["common.ok"])
        assertEquals(mapOf("common.ok" to "Flat"), repository("{locale}/{file}", KeyStyle.FLAT).valuesOf("en"))
    }

    /** 非首文件的修改、增删、重命名及回退语言变化都必须使缓存失效。 */
    @Test
    fun `索引指纹包含全部文件及回退语言`() {
        write("zh/a.json", """{"a":"甲"}""")
        val second = write("zh/b.json", """{"b":"乙"}""")
        val fallback = write("en/a.json", """{"fallback":"First"}""")
        val pattern = "{locale}/{namespace}.json"
        TranslationIndex.invalidate()
        try {
            assertEquals("乙", TranslationIndex.values(temp.root.path, "zh", "en", "locales", pattern)["b"])
            val stamp = second.lastModified()
            second.writeText("""{"b":"更新后的乙"}""")
            assertTrue(second.setLastModified(stamp))
            assertEquals("更新后的乙", TranslationIndex.values(temp.root.path, "zh", "en", "locales", pattern)["b"])
            second.writeText("""{"b":"同长度新值"}""")
            assertTrue(second.setLastModified(stamp + 2000))
            assertEquals("同长度新值", TranslationIndex.values(temp.root.path, "zh", "en", "locales", pattern)["b"])
            val added = write("zh/c.json", """{"c":"丙"}""")
            assertEquals("丙", TranslationIndex.values(temp.root.path, "zh", "en", "locales", pattern)["c"])
            val renamed = File(added.parentFile, "d.json")
            assertTrue(added.renameTo(renamed))
            renamed.writeText("""{"d":"丁"}""")
            assertNull(TranslationIndex.values(temp.root.path, "zh", "en", "locales", pattern)["c"])
            assertTrue(renamed.delete())
            assertNull(TranslationIndex.values(temp.root.path, "zh", "en", "locales", pattern)["d"])
            fallback.writeText("""{"fallback":"Changed fallback"}""")
            assertEquals("Changed fallback", TranslationIndex.values(temp.root.path, "zh", "en", "locales", pattern)["fallback"])
            assertEquals(2, TranslationIndex.countFor(temp.root.path, "zh", "locales", pattern))
        } finally {
            TranslationIndex.invalidate()
        }
    }
}
