package com.hegg5577.i18n.actions

import com.hegg5577.i18n.locale.LocaleRepository
import com.hegg5577.i18n.translator.TranslationEngine
import com.hegg5577.i18n.translator.TranslationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** 验证批量翻译使用传入引擎，并在保存前保留现有译文。 */
class BatchTranslateActionTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /** 预览与实际翻译必须使用同一引擎，并通过通用接口传递源语言。 */
    @Test
    fun usesSelectedEngineForPreviewAndTranslation() {
        val action = BatchTranslateAction()
        val requests = mutableListOf<Triple<List<String>, String, String>>()
        val engine = object : TranslationEngine {
            override val id = "configured-engine"
            override val displayName = "配置的翻译引擎"

            /** 回显受保护文本，以验证请求参数与占位符还原。 */
            override fun translate(texts: List<String>, targetLocale: String, sourceLocale: String): List<String> {
                requests += Triple(texts, targetLocale, sourceLocale)
                return texts
            }
        }
        val unit = BatchTranslateAction.TranslateUnit("ja", "你好 {name}", listOf("greeting"))

        assertTrue(action.buildPreview(listOf(unit), "zh", engine).contains("翻译引擎：配置的翻译引擎"))
        assertEquals("你好 {name}", action.translateUnit(unit, "zh", engine))
        assertEquals(listOf(Triple(listOf("你好 __P0__"), "ja", "zh")), requests)
    }

    /** 单条请求不能接受多条响应，防止错误地把第一条结果写入语言包。 */
    @Test(expected = TranslationException::class)
    fun rejectsUnexpectedTranslationCount() {
        val engine = object : TranslationEngine {
            override val id = "fake"
            override val displayName = "测试引擎"

            /** 模拟引擎返回与请求数量不符的响应。 */
            override fun translate(texts: List<String>, targetLocale: String, sourceLocale: String): List<String> =
                listOf("第一条", "多余结果")
        }

        BatchTranslateAction().translateUnit(
            BatchTranslateAction.TranslateUnit("zh", "Hello", listOf("greeting")),
            "en",
            engine,
        )
    }

    /** 等待期间新增的人工译文以及已有空值都应保留，只保存仍缺失的 key。 */
    @Test
    fun rechecksMissingKeysBeforeSaving() {
        val root = temporaryFolder.root
        val localeDirectory = temporaryFolder.newFolder("locales", "zh")
        val file = localeDirectory.resolve("index.json")
        file.writeText("{}")
        val repository = LocaleRepository(root, localesDir = "locales")
        val edits = mapOf("zh" to mapOf("manual" to "机器译文", "empty" to "自动填充", "missing" to "新译文"))
        val action = BatchTranslateAction()

        assertEquals(edits, action.missingEdits(repository, edits))
        file.writeText("""{"manual":"人工译文","empty":""}""")

        assertEquals(mapOf("zh" to mapOf("missing" to "新译文")), action.missingEdits(repository, edits))
        assertTrue(action.missingEdits(repository, mapOf("zh" to mapOf("manual" to "机器译文"))).isEmpty())
    }
}
