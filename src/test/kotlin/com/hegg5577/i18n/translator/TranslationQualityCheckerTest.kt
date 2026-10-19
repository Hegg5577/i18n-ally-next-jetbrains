package com.hegg5577.i18n.translator

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TranslationQualityCheckerTest {

    @Test
    fun testParameterCountMismatch() {
        val result = TranslationQualityChecker.check(
            key = "message.format",
            language = "zh",
            source = "Hello {0}, you have {1} messages",
            translation = "你好 {0}"
        )

        assertFalse(result.isValid)
        val mismatchIssue = result.issues.find {
            it.type == TranslationQualityChecker.IssueType.PARAMETER_COUNT_MISMATCH
        }
        assertNotNull(mismatchIssue)
    }

    @Test
    fun testMissingPlaceholder() {
        val result = TranslationQualityChecker.check(
            key = "greeting",
            language = "zh",
            source = "Hello {name}",
            translation = "你好世界"
        )

        assertFalse(result.isValid)
        val missingIssue = result.issues.find {
            it.type == TranslationQualityChecker.IssueType.MISSING_PLACEHOLDER
        }
        assertNotNull(missingIssue)
    }

    @Test
    fun testExtraPlaceholder() {
        val result = TranslationQualityChecker.check(
            key = "simple",
            language = "zh",
            source = "Hello",
            translation = "你好 {0}"
        )

        assertFalse(result.isValid)
        val extraIssue = result.issues.find {
            it.type == TranslationQualityChecker.IssueType.EXTRA_PLACEHOLDER
        }
        assertNotNull(extraIssue)
    }

    @Test
    fun testValidTranslation() {
        val result = TranslationQualityChecker.check(
            key = "greeting",
            language = "zh",
            source = "Hello {0}, welcome!",
            translation = "你好 {0}，欢迎！"
        )

        assertTrue(result.isValid)
    }

    @Test
    fun testHtmlTagMismatch() {
        val result = TranslationQualityChecker.check(
            key = "html.content",
            language = "zh",
            source = "<b>Bold</b> text",
            translation = "粗体文本"
        )

        assertFalse(result.isValid)
        val htmlIssue = result.issues.find {
            it.type == TranslationQualityChecker.IssueType.HTML_TAG_MISMATCH
        }
        assertNotNull(htmlIssue)
    }

    @Test
    fun testSuspiciouslyShortTranslation() {
        val result = TranslationQualityChecker.check(
            key = "long.text",
            language = "zh",
            source = "This is a very long English text that should translate to something meaningful",
            translation = "很短"
        )

        assertFalse(result.isValid)
        val shortIssue = result.issues.find {
            it.type == TranslationQualityChecker.IssueType.SUSPICIOUSLY_SHORT
        }
        assertNotNull(shortIssue)
    }

    private fun assertFalse(condition: Boolean) = assertTrue(!condition)
    private fun assertNotNull(obj: Any?) = assertTrue(obj != null)
}
