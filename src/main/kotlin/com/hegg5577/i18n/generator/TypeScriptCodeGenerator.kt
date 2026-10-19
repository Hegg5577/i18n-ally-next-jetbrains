package com.hegg5577.i18n.generator

import com.hegg5577.i18n.locale.LocaleRepository
import com.hegg5577.i18n.locale.KeyStyle
import java.io.File

/**
 * TypeScript 类型定义代码生成器。
 *
 * 从翻译键自动生成 TypeScript 类型定义，支持：
 * - 类型化的翻译函数签名
 * - 自动补全
 * - 运行时检查
 * - 多层嵌套键支持
 */
object TypeScriptCodeGenerator {

    data class GeneratedCode(
        val filePath: String,
        val content: String,
        val language: String = "typescript",
    )

    /**
     * 生成 TypeScript 类型定义。
     *
     * 输出示例：
     * ```typescript
     * export type I18nKeys =
     *   | "settings.title"
     *   | "settings.language"
     *   | "errors.notFound";
     *
     * export interface I18nMessages {
     *   "settings.title": string;
     *   "settings.language": string;
     *   "errors.notFound": string;
     * }
     *
     * export const i18n = {
     *   t: (key: I18nKeys, ...args: any[]): string => /* ... */
     * };
     * ```
     */
    fun generateTypeDefinitions(
        projectRoot: File,
        localesDir: String,
        pathMatcher: String,
        keyStyle: KeyStyle,
        outputFile: String = "src/types/i18n.ts",
    ): GeneratedCode {
        val repo = LocaleRepository(projectRoot, localesDir, pathMatcher, keyStyle)
        val locales = repo.scanLocales()

        // 收集所有键
        val allKeys = locales
            .flatMap { it.keys }
            .toSortedSet()

        // 生成类型定义
        val typeUnion = generateTypeUnion(allKeys)
        val messageInterface = generateMessageInterface(allKeys)
        val functionSignature = generateFunctionSignature()

        val content = buildString {
            appendLine("/**")
            appendLine(" * 自动生成的国际化类型定义")
            appendLine(" * 生成时间: ${java.time.LocalDateTime.now()}")
            appendLine(" * 请勿手动修改")
            appendLine(" */")
            appendLine()
            append(typeUnion)
            appendLine()
            append(messageInterface)
            appendLine()
            append(functionSignature)
        }

        return GeneratedCode(
            filePath = outputFile,
            content = content,
        )
    }

    /**
     * 生成类型联合。
     */
    private fun generateTypeUnion(keys: Set<String>): String {
        return buildString {
            appendLine("export type I18nKeys =")
            keys.forEachIndexed { index, key ->
                append("  | \"$key\"")
                if (index < keys.size - 1) {
                    appendLine()
                } else {
                    appendLine(";")
                }
            }
        }
    }

    /**
     * 生成消息接口。
     */
    private fun generateMessageInterface(keys: Set<String>): String {
        return buildString {
            appendLine("export interface I18nMessages {")
            keys.forEach { key ->
                appendLine("  \"$key\": string;")
            }
            appendLine("}")
        }
    }

    /**
     * 生成翻译函数签名。
     */
    private fun generateFunctionSignature(): String {
        return buildString {
            appendLine("export interface I18nFunction {")
            appendLine("  (key: I18nKeys, ...args: any[]): string;")
            appendLine("  locale?: string;")
            appendLine("  messages?: I18nMessages;")
            appendLine("}")
            appendLine()
            appendLine("export const i18n: I18nFunction = (key, ...args) => {")
            appendLine("  // 运行时实现")
            appendLine("  return key;")
            appendLine("};")
        }
    }

    /**
     * 生成结构化的类型定义（支持 autocomplete 的嵌套对象）。
     *
     * 输出示例：
     * ```typescript
     * export const I18nSchema = {
     *   settings: {
     *     title: true,
     *     language: true,
     *   },
     *   errors: {
     *     notFound: true,
     *   }
     * };
     *
     * // 配合 autocomplete 工具使用
     * export type I18nSchemaType = typeof I18nSchema;
     * ```
     */
    fun generateStructuredSchema(
        projectRoot: File,
        localesDir: String,
        pathMatcher: String,
        keyStyle: KeyStyle,
        outputFile: String = "src/types/i18n-schema.ts",
    ): GeneratedCode {
        val repo = LocaleRepository(projectRoot, localesDir, pathMatcher, keyStyle)
        val locales = repo.scanLocales()

        val allKeys = locales
            .flatMap { it.keys }
            .toSortedSet()

        // 构建嵌套结构
        val schema = buildNestedSchema(allKeys, keyStyle)

        val content = buildString {
            appendLine("/**")
            appendLine(" * 自动生成的国际化键结构")
            appendLine(" * 用于编辑器自动补全")
            appendLine(" * 生成时间: ${java.time.LocalDateTime.now()}")
            appendLine(" */")
            appendLine()
            appendLine("export const I18nSchema = ${schema};")
            appendLine()
            appendLine("export type I18nSchemaType = typeof I18nSchema;")
        }

        return GeneratedCode(
            filePath = outputFile,
            content = content,
        )
    }

    /**
     * 构建嵌套的键结构。
     */
    private fun buildNestedSchema(keys: Set<String>, keyStyle: com.hegg5577.i18n.locale.KeyStyle): String {
        val nested = mutableMapOf<String, Any>()
        val separator = "."  // 默认使用点号作为分隔符

        for (key in keys) {
            val parts = key.split(separator)
            var current: MutableMap<String, Any> = nested

            for ((index, part) in parts.withIndex()) {
                if (index == parts.size - 1) {
                    current[part] = true
                } else {
                    @Suppress("UNCHECKED_CAST")
                    current = current.getOrPut(part) { mutableMapOf<String, Any>() } as MutableMap<String, Any>
                }
            }
        }

        return mapToTypeScriptObject(nested)
    }

    /**
     * 将 Map 转换为 TypeScript 对象字面量。
     */
    private fun mapToTypeScriptObject(map: Map<String, Any>, indent: Int = 0): String {
        val indentStr = "  ".repeat(indent)
        val nextIndent = "  ".repeat(indent + 1)

        return buildString {
            append("{\n")

            map.forEach { (key, value) ->
                append(nextIndent)
                append("\"$key\": ")

                when (value) {
                    is Boolean -> append("true")
                    is Map<*, *> -> {
                        @Suppress("UNCHECKED_CAST")
                        append(mapToTypeScriptObject(value as Map<String, Any>, indent + 1))
                    }
                }

                appendLine(",")
            }

            append(indentStr)
            append("}")
        }
    }

    /**
     * 生成 Vue 3 组件类型定义。
     */
    fun generateVueComponentTypes(
        projectRoot: File,
        localesDir: String,
        pathMatcher: String,
        keyStyle: KeyStyle,
        outputFile: String = "src/types/i18n-vue.ts",
    ): GeneratedCode {
        val repo = LocaleRepository(projectRoot, localesDir, pathMatcher, keyStyle)
        val locales = repo.scanLocales()

        val allKeys = locales
            .flatMap { it.keys }
            .toSortedSet()

        val content = buildString {
            appendLine("/**")
            appendLine(" * Vue 3 国际化组件类型")
            appendLine(" * 生成时间: ${java.time.LocalDateTime.now()}")
            appendLine(" */")
            appendLine()
            appendLine("import { computed, ref, Ref } from 'vue';")
            appendLine()
            appendLine("export type I18nKey = ${generateTypeUnionInline(allKeys)};")
            appendLine()
            appendLine("export interface UseI18nOptions {")
            appendLine("  locale?: Ref<string>;")
            appendLine("  messages?: Record<string, Record<I18nKey, string>>;")
            appendLine("}")
            appendLine()
            appendLine("export function useI18n(options?: UseI18nOptions) {")
            appendLine("  const locale = ref(options?.locale?.value ?? 'en');")
            appendLine("  const messages = computed(() => options?.messages ?? {});")
            appendLine()
            appendLine("  const t = (key: I18nKey, ...args: any[]): string => {")
            appendLine("    const msg = messages.value[locale.value]?.[key];")
            appendLine("    if (!msg) return key;")
            appendLine("    return formatMessage(msg, args);")
            appendLine("  };")
            appendLine()
            appendLine("  return { t, locale };")
            appendLine("}")
            appendLine()
            appendLine("function formatMessage(msg: string, args: any[]): string {")
            appendLine("  let result = msg;")
            appendLine("  args.forEach((arg, i) => {")
            appendLine("    result = result.replace(`{${'$'}{i}}`, String(arg));")
            appendLine("  });")
            appendLine("  return result;")
            appendLine("}")
        }

        return GeneratedCode(
            filePath = outputFile,
            content = content,
        )
    }

    /**
     * 生成类型联合（单行）。
     */
    private fun generateTypeUnionInline(keys: Set<String>): String {
        return keys.joinToString(" | ") { "\"$it\"" }
    }

    /**
     * 写入生成的代码到文件。
     */
    fun writeToFile(generated: GeneratedCode, projectRoot: File): Boolean {
        return try {
            val file = File(projectRoot, generated.filePath)
            file.parentFile?.mkdirs()
            file.writeText(generated.content)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
