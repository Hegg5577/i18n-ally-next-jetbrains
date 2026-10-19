package com.hegg5577.i18n.config

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import org.yaml.snakeyaml.Yaml
import java.io.File

/**
 * 从项目根目录 `.i18nrc.yml` 加载 i18n 配置。
 *
 * 配置优先级（高 → 低）：
 * 1. 项目级 `.i18nrc.yml`（在项目根目录）
 * 2. IDE 全局 Settings（存储在 `.idea/i18n-helper.xml`）
 * 3. 插件内置默认值
 *
 * `.i18nrc.yml` 示例：
 * ```yaml
 * localesPath: src/lang/locales
 * sourceLocale: en
 * pathMatcher: "{locale}/{file}"
 * keyStyle: nested
 * displayLocales:
 *   - zh
 *   - en
 * supportedExtensions:
 *   - js
 *   - jsx
 *   - ts
 *   - tsx
 *   - vue
 * inlineAnnotationEnabled: true
 * annotationLocale: zh
 * ```
 */
object I18nYamlConfigLoader {

    private val LOG = Logger.getInstance(I18nYamlConfigLoader::class.java)
    private const val CONFIG_FILE = ".i18nrc.yml"

    /**
     * 尝试从项目根目录加载 `.i18nrc.yml` 配置。
     *
     * @param project 当前 IDE 项目
     * @return 解析后的配置 map，无配置文件时返回空 map
     */
    fun loadProjectConfig(project: Project): Map<String, Any> {
        val configFile = File(project.basePath ?: return emptyMap(), CONFIG_FILE)

        if (!configFile.exists()) {
            LOG.debug("未找到 .i18nrc.yml 配置文件：${configFile.absolutePath}")
            return emptyMap()
        }

        return try {
            LOG.info("加载 .i18nrc.yml 配置：${configFile.absolutePath}")
            val yaml = Yaml()
            val config = yaml.load<Map<String, Any>>(configFile.readText())
            config ?: emptyMap()
        } catch (e: Exception) {
            LOG.warn("解析 .i18nrc.yml 失败：${e.message}", e)
            emptyMap()
        }
    }

    /**
     * 从 YAML 配置 map 中提取字符串字段。
     */
    fun getString(config: Map<String, Any>, key: String, defaultValue: String = ""): String {
        val value = config[key]
        return when (value) {
            is String -> value.takeIf { it.isNotBlank() } ?: defaultValue
            null -> defaultValue
            else -> {
                LOG.warn("配置项 '$key' 期望为字符串，实际为 ${value::class.simpleName}")
                defaultValue
            }
        }
    }

    /**
     * 从 YAML 配置 map 中提取布尔字段。
     */
    fun getBoolean(config: Map<String, Any>, key: String, defaultValue: Boolean = false): Boolean {
        val value = config[key]
        return when (value) {
            is Boolean -> value
            is String -> value.lowercase() in listOf("true", "yes", "1")
            null -> defaultValue
            else -> {
                LOG.warn("配置项 '$key' 期望为布尔值，实际为 ${value::class.simpleName}")
                defaultValue
            }
        }
    }

    /**
     * 从 YAML 配置 map 中提取字符串列表。
     */
    @Suppress("UNCHECKED_CAST")
    fun getStringList(config: Map<String, Any>, key: String, defaultValue: List<String> = emptyList()): List<String> {
        val value = config[key]
        return when (value) {
            is List<*> -> value.mapNotNull { item ->
                when (item) {
                    is String -> item
                    else -> {
                        LOG.warn("列表 '$key' 中包含非字符串项：${item?.let { it::class.simpleName }}")
                        null
                    }
                }
            }
            null -> defaultValue
            else -> {
                LOG.warn("配置项 '$key' 期望为列表，实际为 ${value::class.simpleName}")
                defaultValue
            }
        }
    }
}
