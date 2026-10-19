package com.hegg5577.i18n.ui

import com.hegg5577.i18n.translator.TranslationEngine
import com.hegg5577.i18n.translator.TranslationException
import org.junit.Assert.assertEquals
import org.junit.Test

/** 验证编辑弹窗对不同目标语言分别请求，并保留占位符。 */
class KeyEditorDialogTranslationTest {

    /** 同一原文翻译成中文和日文时，请求及结果必须保持各自语言。 */
    @Test
    fun translatesEachTargetLocaleSeparately() {
        val requests = mutableListOf<Triple<List<String>, String, String>>()
        val engine = object : TranslationEngine {
            override val id = "fake"
            override val displayName = "测试引擎"

            /** 回显目标语言与受保护原文，便于检查请求和占位符还原。 */
            override fun translate(texts: List<String>, targetLocale: String, sourceLocale: String): List<String> {
                requests += Triple(texts, targetLocale, sourceLocale)
                return texts.map { "$targetLocale: $it" }
            }
        }

        val result = KeyEditorDialog.translateTargets(engine, "Hello {name}", "en", listOf("zh", "ja"))

        assertEquals(mapOf("zh" to "zh: Hello {name}", "ja" to "ja: Hello {name}"), result)
        assertEquals(
            listOf(
                Triple(listOf("Hello __P0__"), "zh", "en"),
                Triple(listOf("Hello __P0__"), "ja", "en"),
            ),
            requests,
        )
    }

    /** 引擎没有返回结果时应明确失败，不能静默填入错误语言或空结果。 */
    @Test(expected = TranslationException::class)
    fun rejectsMissingTranslation() {
        val engine = object : TranslationEngine {
            override val id = "fake"
            override val displayName = "测试引擎"

            /** 模拟引擎返回空列表。 */
            override fun translate(texts: List<String>, targetLocale: String, sourceLocale: String): List<String> =
                emptyList()
        }

        KeyEditorDialog.translateTargets(engine, "Hello", "en", listOf("zh"))
    }
}
