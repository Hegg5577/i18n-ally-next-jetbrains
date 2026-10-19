package com.hegg5577.i18n.translator

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.hegg5577.i18n.settings.I18nContext

/**
 * 翻译引擎工厂与管理器。
 *
 * 职责：
 * 1. 注册和管理所有可用的翻译引擎
 * 2. 根据配置选择当前活跃的引擎
 * 3. 提供便捷的翻译 API
 */
@Service(Service.Level.PROJECT)
class TranslationEngineManager(private val project: Project) {

    /** 按最新配置创建引擎，避免永久缓存旧密钥、模型和端点。 */
    private val engines: Map<String, TranslationEngine>
        get() {
            val context = I18nContext.of(project)
            return mapOf(
                // Google 翻译：免费，无需配置
                GoogleTranslationEngine().let { it.id to it },
                // DeepL：需要配置 API Key
                DeepLTranslationEngine(
                    apiKey = context.deeplApiKey,
                    isFreeApi = context.deeplIsFreeApi,
                ).let { it.id to it },
                // OpenAI：需要配置 API Key
                OpenAITranslationEngine(
                    apiKey = context.openaiApiKey,
                    model = context.openaiModel,
                    baseUrl = context.openaiBaseUrl,
                ).let { it.id to it },
            )
        }

    /** 获取所有可用的引擎列表。 */
    fun listEngines(): List<TranslationEngine> = engines.values.toList()

    /** 根据 ID 获取引擎。 */
    fun getEngine(id: String): TranslationEngine? = engines[id]

    /** 获取当前活跃的引擎。 */
    fun getActiveEngine(): TranslationEngine {
        val context = I18nContext.of(project)
        val engineId = context.activeTranslationEngine.ifEmpty { "google-free" }
        return engines[engineId] ?: GoogleTranslationEngine()
    }

    /**
     * 使用当前活跃引擎进行翻译。
     *
     * @throws TranslationException 翻译失败
     */
    fun translate(
        texts: List<String>,
        targetLocale: String,
        sourceLocale: String = "en",
    ): List<String> {
        val engine = getActiveEngine()
        return try {
            engine.translate(texts, targetLocale, sourceLocale)
        } catch (e: TranslationException) {
            throw e
        }
    }

    companion object {
        /** 获取项目的翻译引擎管理器。 */
        fun getInstance(project: Project): TranslationEngineManager =
            project.getService(TranslationEngineManager::class.java)
    }
}
