package com.hegg5577.i18n.translator

import com.hegg5577.i18n.settings.I18nSettings
import com.intellij.testFramework.fixtures.BasePlatformTestCase

/** 验证引擎管理器的所有读取入口都使用当前配置。 */
class TranslationEngineManagerTest : BasePlatformTestCase() {

    /** 切换引擎后应立即生效，未知或空配置继续回退到默认引擎。 */
    fun testActiveEngineFollowsConfiguration() {
        val state = I18nSettings.getInstance(project).state
        val manager = TranslationEngineManager.getInstance(project)
        state.activeTranslationEngine = "deepl"
        assertEquals("deepl", manager.getActiveEngine().id)

        state.activeTranslationEngine = "openai"
        assertEquals("openai", manager.getActiveEngine().id)

        state.activeTranslationEngine = "unknown"
        assertEquals("google-free", manager.getActiveEngine().id)

        state.activeTranslationEngine = ""
        assertEquals("google-free", manager.getActiveEngine().id)
    }

    /** 初次读取后修改 DeepL 密钥与端点类型，按 ID 读取也必须得到最新配置。 */
    fun testDeepLConfigurationIsNotCached() {
        val state = I18nSettings.getInstance(project).state
        val manager = TranslationEngineManager.getInstance(project)
        state.deeplApiKey = "old-key"
        state.deeplIsFreeApi = true
        val before = manager.getEngine("deepl")!!

        state.deeplApiKey = "new-key"
        state.deeplIsFreeApi = false
        val after = manager.getEngine("deepl")!!

        assertNotSame(before, after)
        assertEquals("new-key", configurationValue(after, "apiKey"))
        assertEquals(false, configurationValue(after, "isFreeApi"))
    }

    /** 修改 OpenAI 密钥、模型和地址后，活跃引擎与引擎列表均应更新。 */
    fun testOpenAIConfigurationIsNotCached() {
        val state = I18nSettings.getInstance(project).state
        val manager = TranslationEngineManager.getInstance(project)
        state.activeTranslationEngine = "openai"
        manager.listEngines()
        manager.getActiveEngine()

        state.openaiApiKey = "new-key"
        state.openaiModel = "configured-model"
        state.openaiBaseUrl = "http://localhost:12345/v1"

        for (engine in listOf(manager.getActiveEngine(), manager.listEngines().first { it.id == "openai" })) {
            assertEquals("new-key", configurationValue(engine, "apiKey"))
            assertEquals("configured-model", configurationValue(engine, "model"))
            assertEquals("http://localhost:12345/v1", configurationValue(engine, "baseUrl"))
        }
    }

    /** 直接检查构造参数，避免回归测试发起真实网络请求或暴露配置读取接口。 */
    private fun configurationValue(engine: TranslationEngine, name: String): Any? =
        engine.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(engine)
}
