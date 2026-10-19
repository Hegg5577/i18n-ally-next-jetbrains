package com.hegg5577.i18n.locale

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LocaleWriterTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `JSON 修改已有 key 并保持其他字段不变`() {
        val original = """
            {
              "common": {
                "cancel": "Cancel",
                "ok": "OK"
              }
            }
        """.trimIndent()
        val writer = JsonLocaleWriter(original)
        assertTrue(writer.set("common.cancel", "取消"))
        val text = writer.render()
        assertTrue(text.contains("\"cancel\": \"取消\""))
        assertTrue(text.contains("\"ok\": \"OK\""))
    }

    @Test
    fun `JSON 保持原有字段顺序并在末尾追加新字段`() {
        val original = """
            {
              "z": "1",
              "a": "2",
              "m": "3"
            }
        """.trimIndent()
        val writer = JsonLocaleWriter(original)
        writer.set("new", "x")
        val text = writer.render()
        val z = text.indexOf("\"z\"")
        val a = text.indexOf("\"a\"")
        val m = text.indexOf("\"m\"")
        val n = text.indexOf("\"new\"")
        assertTrue("保持原有顺序 z < a < m", z < a && a < m)
        assertTrue("新增 key 在末尾", m < n)
    }

    @Test
    fun `JSON 不转义 HTML 特殊字符`() {
        val writer = JsonLocaleWriter("{}")
        writer.set("html", "Detect Teeth & Continue")
        assertTrue(
            "不应将 & 转义为 \\u0026",
            writer.render().contains("Detect Teeth & Continue"),
        )
    }

    @Test
    fun `JSON 支持深层嵌套写入与读取`() {
        val writer = JsonLocaleWriter("""{"a":"1"}""")
        assertTrue(writer.set("deep.nested.key", "v"))
        val read = writer.read()
        assertEquals("1", read["a"])
        assertEquals("v", read["deep.nested.key"])
    }

    @Test
    /** 仅在提供外部数据时验证真实语言包，公开仓库不绑定本机目录。 */
    fun `真实项目 JSON 语言包修改单个 key`() {
        val projectRoot = File(System.getenv("I18N_TEST_PROJECT") ?: "test-project")
        val source = File(projectRoot, "src/i18n/locales/zh/index.json")
        assumeTrue("真实项目文件不存在，跳过: " + source.path, source.isFile)
        val original = source.readText()
        val before = JsonLocaleWriter(original).read()

        val writer = JsonLocaleWriter(original)
        assertTrue(writer.set("common.ok", "确定-测试"))
        val after = JsonLocaleWriter(writer.render()).read()

        assertEquals("确定-测试", after["common.ok"])
        assertEquals("key 总数应保持一致", before.size, after.size)
        for (key in before.keys - setOf("common.ok")) {
            assertEquals("key $key 的值不应改变", before[key], after[key])
        }
    }
}
