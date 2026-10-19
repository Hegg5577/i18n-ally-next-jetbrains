package com.hegg5577.i18n.locale

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class TranslationSaverBatchOperationsTest {
    /** 调用真实删除逻辑，确保删除后仍保留相邻译文。 */
    @Test
    fun testDeleteKeyFromNestedJson() {
        val writer = JsonLocaleWriter("""{"common":{"ok":"OK","cancel":"Cancel"},"keep":"value"}""")
        writer.remove("common.cancel")
        assertEquals(mapOf("common.ok" to "OK", "keep" to "value"), writer.read())
    }

    /** 删除最后一个叶节点时清理空父节点。 */
    @Test
    fun testDeleteLastKeyRemovesParent() {
        val writer = JsonLocaleWriter("""{"a":{"b":{"c":"value"}},"keep":"yes"}""")
        writer.remove("a.b.c")
        assertFalse(JsonParser.parseString(writer.render()).asJsonObject.has("a"))
        assertEquals(mapOf("keep" to "yes"), writer.read())
    }

    /** 不存在的路径不影响已有文本值。 */
    @Test
    fun testDeleteNonExistentKeyDoesNothing() {
        val writer = JsonLocaleWriter("""{"exists":"yes"}""")
        writer.remove("does.not.exist")
        assertEquals(mapOf("exists" to "yes"), writer.read())
    }

    /** 重命名必须移动原值并移除旧 key。 */
    @Test
    fun testRenameKeyMovesValue() {
        val writer = JsonLocaleWriter("""{"old":{"key":"value"},"keep":"yes"}""")
        writer.rename("old.key", "new.key")
        assertEquals(mapOf("new.key" to "value", "keep" to "yes"), writer.read())
        assertFalse(JsonParser.parseString(writer.render()).asJsonObject.has("old"))
    }

    /** 冲突时原值和目标值都必须保留。 */
    @Test
    fun testRenameConflictDoesNotChangeValues() {
        val writer = JsonLocaleWriter("""{"old":"source","new":"target"}""")
        assertTrue(runCatching { writer.rename("old", "new") }.isFailure)
        assertEquals(mapOf("old" to "source", "new" to "target"), writer.read())
    }

    /** 平铺 key 的删除不能误删同名前缀。 */
    @Test
    fun testDeleteFlatKey() {
        val writer = JsonLocaleWriter("""{"common.ok":"OK","common.cancel":"Cancel"}""", KeyStyle.FLAT)
        writer.remove("common.ok")
        assertEquals(mapOf("common.cancel" to "Cancel"), writer.read())
    }

    /** 平铺格式保存后仍使用完整字段名。 */
    @Test
    fun testSaveFlatKeyInPlace() {
        val updated = TranslationSaver.applyChanges("""{"common.ok":"OK"}""", mapOf("common.ok" to "确定"), KeyStyle.FLAT)
        val root = JsonParser.parseString(updated).asJsonObject
        assertEquals("确定", root["common.ok"].asString)
        assertFalse(root.has("common"))
    }

    /** 混合格式中更新嵌套 key 时不能额外产生同名平铺字段。 */
    @Test
    fun testAutoKeepsExistingNestedKey() {
        val updated = TranslationSaver.applyChanges(
            """{"flat.key":"flat","common":{"ok":"OK"}}""",
            mapOf("common.ok" to "确定"), KeyStyle.AUTO,
        )
        val root = JsonParser.parseString(updated).asJsonObject
        assertEquals("确定", root["common"].asJsonObject["ok"].asString)
        assertFalse(root.has("common.ok"))
    }

    /** 自动模式新 key 沿用已有平铺字段的风格。 */
    @Test
    fun testAutoAddsFlatKey() {
        val updated = TranslationSaver.applyChanges("""{"common.ok":"OK"}""", mapOf("common.cancel" to "取消"), KeyStyle.AUTO)
        assertEquals("取消", JsonParser.parseString(updated).asJsonObject["common.cancel"].asString)
    }

    /** 中间节点已有原文时拒绝覆盖。 */
    @Test
    fun testSaveRejectsScalarParentConflict() {
        val writer = JsonLocaleWriter("""{"common":"通用"}""")
        assertTrue(runCatching { writer.setValue("common.ok", "确定") }.isFailure)
        assertEquals(mapOf("common" to "通用"), writer.read())
    }

    /** 父节点重名也不能吞掉现有子节点。 */
    @Test
    fun testSaveRejectsObjectReplacement() {
        val writer = JsonLocaleWriter("""{"common":{"ok":"确定"}}""")
        assertTrue(runCatching { writer.setValue("common", "通用") }.isFailure)
        assertEquals(mapOf("common.ok" to "确定"), writer.read())
    }
}
