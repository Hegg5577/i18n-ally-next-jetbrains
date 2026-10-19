package com.hegg5577.i18n.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil
import com.hegg5577.i18n.config.I18nYamlConfigLoader

/**
 * 项目级 i18n 配置。
 *
 * 存语言包路径、源语言、key 匹配正则等，替代原先散落在各处常量的硬编码。
 * 落到项目级 `.idea/i18n-helper.xml`，团队成员共享同一份配置。
 *
 * 设计对齐 i18n-ally-next 的配置项命名（`localesPaths` / `sourceLanguage` /
 * `pathMatcher` / `usageMatchRegex`），方便从 VS Code 迁移过来的用户直接对应理解。
 */
@Service(Service.Level.PROJECT)
@State(
    name = "I18nHelperSettings",
    storages = [Storage("i18n-helper.xml")],
)
class I18nSettings : PersistentStateComponent<I18nSettings.State> {

    data class State(
        /** 语言包根目录，相对项目根。例如 `src/i18n/locales`。 */
        var localesPath: String = DEFAULT_LOCALES_DIR,

        /**
         * 语言包路径匹配模式，支持 `{locale}` 与 `{namespace}` 占位符。
         *
         * 留空时按[DEFAULT_PATH_MATCHER] 处理。示例：
         * - `{locale}/{namespace}.json`
         * - `{namespace}.{locale}.json`
         * - `{locale}.json`
         */
        var pathMatcher: String = DEFAULT_PATH_MATCHER,

        /** 源语言代码，作为「缺失」判定的基准。 */
        var sourceLocale: String = "en",

        /** 侧边栏优先展示的语言；为空则按字母序。 */
        var displayLocales: MutableList<String> = mutableListOf("zh"),

        /**
         * key 提取正则，`{key}` 是必填占位符，会被替换为真实的 key 匹配模式。
         *
         * 例：`\bt\(\s*['"`]({key})['"`]`匹配 `t('common.ok')`。
         * 留空时使用内置框架的规则集合。
         */
        var usageMatchRegex: MutableList<String> = mutableListOf(),

        /** 参与扫描的文件后缀。 */
        var supportedExtensions: MutableList<String> = mutableListOf(
            "js", "jsx", "mjs", "cjs", "ts", "tsx", "vue", "html", "htm",
        ),

        /**
         * key 在语言包里的组织形式。
         *
         * - `nested`：嵌套 JSON，`{"a":{"b":"x"}}` 对应 key `a.b`
         * - `flat`：单层 JSON，key 原样作为顶层字段名
         * - `auto`：嵌套与平铺都支持（查找时先试整体 key，再试点号路径）
         */
        var keyStyle: String = "nested",

        /** 是否启用命名空间（`ns:key` 形式）。 */
        var namespaceEnabled: Boolean = false,

        /** 命名空间分隔符。 */
        var namespaceDelimiter: String = ":",

        /** 行内译文提示是否启用。 */
        var inlineAnnotationEnabled: Boolean = true,

        /** 行内提示的译文语言；为空表示跟随 [displayLocales] 第一项。 */
        var annotationLocale: String = "",

        /** 首选语言缺 key 时是否回退显示源语言原文。 */
        var fallbackToSource: Boolean = true,

        /** 批量翻译时是否覆盖已有译文。默认关，避免误伤人工翻译。 */
        var overrideExisting: Boolean = false,

        /** 扫描源码的目录（逗号分隔，相对项目根）。用于「缺失检测」比对 key 使用情况。 */
        var sourceDirs: MutableList<String> = mutableListOf("src"),

        /** 活跃的翻译引擎 ID。默认使用 Google 翻译（免费）。 */
        var activeTranslationEngine: String = "google-free",

        /** DeepL API Key。 */
        var deeplApiKey: String = "",

        /** DeepL 是否使用免费版 API。 */
        var deeplIsFreeApi: Boolean = true,

        /** OpenAI API Key。 */
        var openaiApiKey: String = "",

        /** OpenAI 模型名称。默认 gpt-3.5-turbo。 */
        var openaiModel: String = "gpt-3.5-turbo",

        /** OpenAI API 基础 URL。支持代理或自建 API。 */
        var openaiBaseUrl: String = "https://api.openai.com/v1",
    )

    private var state = State()
    private var project: Project? = null
    private var yamlConfig: Map<String, Any> = emptyMap()

    override fun getState(): State = state

    override fun loadState(s: State) {
        XmlSerializerUtil.copyBean(s, state)
    }

    /**
     * 设置项目实例并加载 YAML 配置。
     * 由 IDE 框架自动调用。
     */
    fun initProject(p: Project) {
        this.project = p
        yamlConfig = I18nYamlConfigLoader.loadProjectConfig(p)
        applyYamlOverrides()
    }

    /**
     * 将 YAML 配置中的值应用到当前状态，覆盖 Settings XML。
     * 优先级：YAML > Settings > 默认值
     */
    private fun applyYamlOverrides() {
        if (yamlConfig.isEmpty()) return

        state.localesPath = I18nYamlConfigLoader.getString(yamlConfig, "localesPath", state.localesPath)
        state.sourceLocale = I18nYamlConfigLoader.getString(yamlConfig, "sourceLocale", state.sourceLocale)
        state.pathMatcher = I18nYamlConfigLoader.getString(yamlConfig, "pathMatcher", state.pathMatcher)
        state.keyStyle = I18nYamlConfigLoader.getString(yamlConfig, "keyStyle", state.keyStyle)

        state.displayLocales = I18nYamlConfigLoader.getStringList(yamlConfig, "displayLocales", state.displayLocales).toMutableList()
        state.supportedExtensions = I18nYamlConfigLoader.getStringList(yamlConfig, "supportedExtensions", state.supportedExtensions).toMutableList()
        state.usageMatchRegex = I18nYamlConfigLoader.getStringList(yamlConfig, "usageMatchRegex", state.usageMatchRegex).toMutableList()
        state.sourceDirs = I18nYamlConfigLoader.getStringList(yamlConfig, "sourceDirs", state.sourceDirs).toMutableList()

        state.inlineAnnotationEnabled = I18nYamlConfigLoader.getBoolean(yamlConfig, "inlineAnnotationEnabled", state.inlineAnnotationEnabled)
        state.annotationLocale = I18nYamlConfigLoader.getString(yamlConfig, "annotationLocale", state.annotationLocale)
        state.fallbackToSource = I18nYamlConfigLoader.getBoolean(yamlConfig, "fallbackToSource", state.fallbackToSource)
        state.overrideExisting = I18nYamlConfigLoader.getBoolean(yamlConfig, "overrideExisting", state.overrideExisting)
        state.namespaceEnabled = I18nYamlConfigLoader.getBoolean(yamlConfig, "namespaceEnabled", state.namespaceEnabled)
        state.namespaceDelimiter = I18nYamlConfigLoader.getString(yamlConfig, "namespaceDelimiter", state.namespaceDelimiter)

        state.activeTranslationEngine = I18nYamlConfigLoader.getString(yamlConfig, "activeTranslationEngine", state.activeTranslationEngine)
        state.deeplApiKey = I18nYamlConfigLoader.getString(yamlConfig, "deeplApiKey", state.deeplApiKey)
        state.deeplIsFreeApi = I18nYamlConfigLoader.getBoolean(yamlConfig, "deeplIsFreeApi", state.deeplIsFreeApi)
        state.openaiApiKey = I18nYamlConfigLoader.getString(yamlConfig, "openaiApiKey", state.openaiApiKey)
        state.openaiModel = I18nYamlConfigLoader.getString(yamlConfig, "openaiModel", state.openaiModel)
        state.openaiBaseUrl = I18nYamlConfigLoader.getString(yamlConfig, "openaiBaseUrl", state.openaiBaseUrl)
    }

    /** 便捷访问：源语言代码。 */
    val sourceLocale: String get() = state.sourceLocale

    /** 便捷访问：语言包根目录。 */
    val localesPath: String get() = state.localesPath

    /** 便捷访问：是否已加载 YAML 配置。 */
    val hasYamlConfig: Boolean get() = yamlConfig.isNotEmpty()

    companion object {
        const val DEFAULT_LOCALES_DIR = "src/i18n/locales"

        /** 默认路径模式：每语言一个目录，目录内index.json 为该语言全部文案。 */
        const val DEFAULT_PATH_MATCHER = "{locale}/{file}"

        fun getInstance(project: Project): I18nSettings =
            project.getService(I18nSettings::class.java)
    }
}
