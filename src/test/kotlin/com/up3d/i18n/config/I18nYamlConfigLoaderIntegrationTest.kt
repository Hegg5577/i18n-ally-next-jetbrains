package com.hegg5577.i18n.config

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Test
import org.junit.Assert.*
import org.junit.Before
import org.junit.After

/**
 * 集成测试：验证 YAML 配置文件的加载和解析。
 */
class I18nYamlConfigLoaderIntegrationTest {

    private lateinit var tempDir: Path

    @Before
    fun setup() {
        tempDir = Files.createTempDirectory("i18n-yaml-test-")
    }

    @After
    fun cleanup() {
        tempDir.toFile().deleteRecursively()
    }

    @Test
    fun testLoadValidYamlConfig() {
        val yamlContent = """
localesPath: src/lang/locales
sourceLocale: en
pathMatcher: "{locale}/{file}"
keyStyle: nested
displayLocales:
  - zh
  - en
supportedExtensions:
  - js
  - jsx
  - ts
  - tsx
inlineAnnotationEnabled: true
annotationLocale: zh
        """.trimIndent()

        val configFile = File(tempDir.toFile(), ".i18nrc.yml")
        configFile.writeText(yamlContent)

        // 模拟加载（因为无法访问真实 Project）
        val yaml = org.yaml.snakeyaml.Yaml()
        val config = yaml.load<Map<String, Any>>(yamlContent)!!

        assertEquals("src/lang/locales", I18nYamlConfigLoader.getString(config, "localesPath"))
        assertEquals("en", I18nYamlConfigLoader.getString(config, "sourceLocale"))
        assertEquals("nested", I18nYamlConfigLoader.getString(config, "keyStyle"))
        assertTrue(I18nYamlConfigLoader.getBoolean(config, "inlineAnnotationEnabled"))
        assertEquals("zh", I18nYamlConfigLoader.getString(config, "annotationLocale"))

        val displayLocales = I18nYamlConfigLoader.getStringList(config, "displayLocales")
        assertEquals(listOf("zh", "en"), displayLocales)

        val extensions = I18nYamlConfigLoader.getStringList(config, "supportedExtensions")
        assertEquals(listOf("js", "jsx", "ts", "tsx"), extensions)
    }

    @Test
    fun testLoadYamlWithCompleteTranslationEngineConfig() {
        val yamlContent = """
activeTranslationEngine: deepl
deeplApiKey: test-key-123
deeplIsFreeApi: false
openaiApiKey: sk-test-key
openaiModel: gpt-4
openaiBaseUrl: https://api.example.com/v1
        """.trimIndent()

        val yaml = org.yaml.snakeyaml.Yaml()
        val config = yaml.load<Map<String, Any>>(yamlContent)!!

        assertEquals("deepl", I18nYamlConfigLoader.getString(config, "activeTranslationEngine"))
        assertEquals("test-key-123", I18nYamlConfigLoader.getString(config, "deeplApiKey"))
        assertFalse(I18nYamlConfigLoader.getBoolean(config, "deeplIsFreeApi"))
        assertEquals("sk-test-key", I18nYamlConfigLoader.getString(config, "openaiApiKey"))
        assertEquals("gpt-4", I18nYamlConfigLoader.getString(config, "openaiModel"))
        assertEquals("https://api.example.com/v1", I18nYamlConfigLoader.getString(config, "openaiBaseUrl"))
    }

    @Test
    fun testEmptyYamlReturnsDefaults() {
        val yamlContent = ""

        val yaml = org.yaml.snakeyaml.Yaml()
        val config = yaml.load<Map<String, Any>>(yamlContent) ?: emptyMap()

        assertEquals("default-dir", I18nYamlConfigLoader.getString(config, "localesPath", "default-dir"))
        assertEquals("en", I18nYamlConfigLoader.getString(config, "sourceLocale", "en"))
        assertFalse(I18nYamlConfigLoader.getBoolean(config, "inlineAnnotationEnabled", false))
        assertEquals(emptyList<String>(), I18nYamlConfigLoader.getStringList(config, "displayLocales"))
    }
}
