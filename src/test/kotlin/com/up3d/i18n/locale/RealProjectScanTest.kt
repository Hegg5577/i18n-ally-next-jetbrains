package com.hegg5577.i18n.locale

import com.hegg5577.i18n.editor.KeyUsageScanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * 针对真实项目结构（`src/i18n/locales/{locale}/index.json`）的验证。
 *
 * 这类「拿真实数据跑一遍」的测试比构造样例更有价值 ——
 * 之前「一个国际化都显示不出来」就是样例太理想化、
 * 真实路径没被覆盖到导致的。这条测试直接指向真实的项目目录。
 */
class RealProjectScanTest {

    /** 外部测试数据由开发者显式提供，避免依赖公司目录。 */
    private val projectRoot = File(System.getenv("I18N_TEST_PROJECT") ?: "test-project")

    /** 未提供外部数据时跳过，保证公开源码不依赖开发者本机文件。 */
    private fun requireProject() {
        assumeTrue(
            "真实项目不存在，跳过：${projectRoot.path}",
            projectRoot.isDirectory,
        )
    }

    @Test
    fun `真实项目的默认布局能被解析`() {
        requireProject()

        val repo = LocaleRepository(projectRoot, "src/i18n/locales")
        val scanned = repo.scanLocales()

        assertTrue("应至少扫到 en 与 zh 两个语言包，实际：${scanned.map { it.locale }}", scanned.size >= 2)
        val locales = scanned.map { it.locale }
        assertTrue("应包含 en，实际：$locales", locales.contains("en"))
        assertTrue("应包含 zh，实际：$locales", locales.contains("zh"))
    }

    @Test
    fun `真实项目能读出上千个 key 与对应译文`() {
        requireProject()

        val repo = LocaleRepository(projectRoot, "src/i18n/locales")
        val en = repo.valuesOf("en")
        val zh = repo.valuesOf("zh")

        assertTrue("en 应有 1000+ 个 key，实际 ${en.size}", en.size > 1000)
        assertTrue("zh 应有 1000+ 个 key，实际 ${zh.size}", zh.size > 1000)

        // 抽样验证一个真实 key：两种语言都必须有值
        assertEquals("Return Order", en["common.returnOrder"])
        assertEquals("返回订单", zh["common.returnOrder"])
    }

    @Test
    fun `真实项目的译文索引可被行内提示使用`() {
        requireProject()

        // 与 I18nInlayCollector 走同一条路径：values() 是行内提示的唯一数据来源
        val values = TranslationIndex.values(
            projectRoot = projectRoot.path,
            locale = "zh",
            fallbackLocale = "en",
            localesDir = "src/i18n/locales",
            pathMatcher = PathMatcher.DEFAULT_PATTERN,
            keyStyle = KeyStyle.NESTED,
        )

        assertTrue("索引不应为空，否则行内译文全部无法显示", values.isNotEmpty())
        assertEquals("返回订单", values["common.returnOrder"])
    }

    @Test
    fun `真实项目的裸 t 调用能被识别`() {
        requireProject()

        val sample = """
            const a = t('common.returnOrder');
            const b = t('common.confirm');
            const c = this.props.t('common.cancel');
        """.trimIndent()

        // 走 scan() 的默认路径：与I18nInlayCollector 未配置自定义规则时的行为一致
        val hits = KeyUsageScanner.scan(sample).toList()

        val keys = hits.map { it.key }
        assertTrue("应识别出 3 个 key，实际：$keys", keys.containsAll(
            listOf("common.returnOrder", "common.confirm", "common.cancel")
        ))
    }
}
