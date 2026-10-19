package com.hegg5577.i18n.generator

import org.junit.jupiter.api.Test
import kotlin.test.assertTrue
import kotlin.test.assertContains

class TypeScriptCodeGeneratorTest {

    @Test
    fun testGenerateTypeDefinitions() {
        val generated = TypeScriptCodeGenerator.GeneratedCode(
            filePath = "src/types/i18n.ts",
            content = "test content"
        )

        assertTrue(generated.filePath.endsWith(".ts"))
        assertTrue(generated.language == "typescript")
    }

    @Test
    fun testTypeUnionGeneration() {
        val keys = setOf(
            "common.yes",
            "common.no",
            "errors.notFound",
            "errors.unauthorized"
        )

        // 这里我们测试生成的内容应该包含类型定义
        val generated = TypeScriptCodeGenerator.generateTypeDefinitions(
            projectRoot = java.io.File("."),
            localesDir = "locales",
            pathMatcher = "**/*.json",
            keyStyle = com.hegg5577.i18n.locale.KeyStyle(
                separator = ".",
                uppercase = false,
                camelCase = false
            )
        )

        // 验证生成的文件包含必要的导出
        assertTrue(generated.content.contains("export type I18nKeys"))
        assertTrue(generated.content.contains("export interface I18nMessages"))
    }

    @Test
    fun testStructuredSchemaGeneration() {
        val generated = TypeScriptCodeGenerator.generateStructuredSchema(
            projectRoot = java.io.File("."),
            localesDir = "locales",
            pathMatcher = "**/*.json",
            keyStyle = com.hegg5577.i18n.locale.KeyStyle(
                separator = ".",
                uppercase = false,
                camelCase = false
            )
        )

        assertTrue(generated.content.contains("export const I18nSchema"))
        assertTrue(generated.content.contains("export type I18nSchemaType"))
    }

    @Test
    fun testVueComponentTypesGeneration() {
        val generated = TypeScriptCodeGenerator.generateVueComponentTypes(
            projectRoot = java.io.File("."),
            localesDir = "locales",
            pathMatcher = "**/*.json",
            keyStyle = com.hegg5577.i18n.locale.KeyStyle(
                separator = ".",
                uppercase = false,
                camelCase = false
            )
        )

        assertTrue(generated.content.contains("import { computed, ref, Ref } from 'vue'"))
        assertTrue(generated.content.contains("export function useI18n"))
    }
}
