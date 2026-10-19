package com.hegg5577.i18n.actions

import com.hegg5577.i18n.locale.KeyStyle
import com.hegg5577.i18n.locale.LocaleRepository
import com.hegg5577.i18n.translator.TranslationQualityChecker.IssueType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 使用真实语言包验证质量检查的数据来源与语言标识。 */
class CheckTranslationQualityActionTest {

    @get:Rule
    val temp = TemporaryFolder()

    /** 源语言排在后面且展示语言不参与映射时，仍应报告实际译文缺失的占位符。 */
    @Test
    fun checksRealValuesWithConfiguredSourceAndActualLocale() {
        writeLocale("en", """{"greeting":"Hello"}""")
        writeLocale("zh", """{"greeting":"你好 {name}"}""")
        val repository = LocaleRepository(temp.root, "messages", "{locale}.json")

        val issues = CheckTranslationQualityAction().collectQualityIssues(repository, "zh")

        val issue = issues.single()
        assertEquals("greeting", issue.key)
        assertEquals("en", issue.language)
        assertEquals("你好 {name}", issue.source)
        assertEquals("Hello", issue.translation)
        assertTrue(issue.issues.any { it.type == IssueType.MISSING_PLACEHOLDER })
    }

    /** 自定义路径与平铺风格必须保留，正确译文和没有原文的额外键不应被误报。 */
    @Test
    fun respectsRepositoryConfigurationAndSkipsValidTranslations() {
        writeLocale("en", """{"greeting":"Hello {name}","nested":{"ignored":"{value}"}}""")
        writeLocale("fr", """{"greeting":"Salut {name}","extra":"{other}","nested":{"ignored":"Salut"}}""")
        val repository = LocaleRepository(temp.root, "messages", "{locale}.json", KeyStyle.FLAT)

        assertTrue(CheckTranslationQualityAction().collectQualityIssues(repository, "en").isEmpty())
    }

    /** 未配置存在的源语言时必须明确失败，避免随机选源语言或给出全部通过的结论。 */
    @Test
    fun rejectsMissingConfiguredSourceLocale() {
        writeLocale("en", """{"greeting":"Hello"}""")
        val repository = LocaleRepository(temp.root, "messages", "{locale}.json")

        val error = runCatching {
            CheckTranslationQualityAction().collectQualityIssues(repository, "zh")
        }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertEquals("未找到配置的源语言：zh", error?.message)
    }

    /** 为测试创建真实文件，避免用内存键名替代仓库读取的文本。 */
    private fun writeLocale(locale: String, json: String) {
        val file = File(temp.root, "messages/$locale.json")
        file.parentFile.mkdirs()
        file.writeText(json)
    }
}
