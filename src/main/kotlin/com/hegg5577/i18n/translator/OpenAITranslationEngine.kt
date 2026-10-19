package com.hegg5577.i18n.translator

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI

/**
 * OpenAI 翻译引擎实现（基于 ChatGPT）。
 *
 * 需要有效的 OpenAI API Key（可从 https://platform.openai.com/account/api-keys 获取）。
 * 使用 GPT-3.5-turbo 模型以平衡成本和速度。
 *
 * @param apiKey OpenAI API Key
 * @param model 使用的模型，默认 `gpt-3.5-turbo`
 * @param baseUrl API 基础 URL，默认官方地址，可用于代理或自建 API
 */
class OpenAITranslationEngine(
    private val apiKey: String,
    private val model: String = "gpt-3.5-turbo",
    private val baseUrl: String = "https://api.openai.com/v1",
) : TranslationEngine {

    override val id: String = "openai"

    override val displayName: String = "OpenAI GPT（ChatGPT）"

    override fun translate(
        texts: List<String>,
        targetLocale: String,
        sourceLocale: String,
    ): List<String> {
        if (texts.isEmpty()) return emptyList()
        if (apiKey.isBlank()) {
            throw TranslationException("OpenAI API Key 未配置")
        }

        var lastError: Throwable? = null

        repeat(MAX_RETRIES) { attempt ->
            try {
                return requestBatch(texts, targetLocale, sourceLocale)
            } catch (e: Throwable) {
                lastError = e
                if (attempt < MAX_RETRIES - 1) {
                    Thread.sleep(RETRY_DELAY_MS * (attempt + 1))
                }
            }
        }
        throw TranslationException(
            "OpenAI 翻译失败（已重试 $MAX_RETRIES 次）：${lastError?.message ?: "未知原因"}",
            lastError,
        )
    }

    private fun requestBatch(
        texts: List<String>,
        targetLocale: String,
        sourceLocale: String,
    ): List<String> {
        val systemPrompt = """You are a professional translator. Translate the following texts from $sourceLocale to $targetLocale.
Return only the translated texts, one per line, in the exact same order as the input.
Do not add explanations, numbering, or any extra content."""

        // 将所有文本用特殊标记分隔，便于原样拆分
        val userMessage = texts.joinToString("\n<<<SEP>>>\n")

        val requestBody = JsonObject().apply {
            addProperty("model", model)
            addProperty("temperature", 0.3)
            add("messages", JsonArray().apply {
                add(JsonObject().apply {
                    addProperty("role", "system")
                    addProperty("content", systemPrompt)
                })
                add(JsonObject().apply {
                    addProperty("role", "user")
                    addProperty("content", userMessage)
                })
            })
        }

        val connection = URI.create("$baseUrl/chat/completions").toURL().openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("Authorization", "Bearer $apiKey")
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Content-Type", "application/json")
            connection.doOutput = true

            connection.outputStream.bufferedWriter(Charsets.UTF_8).use {
                it.write(requestBody.toString())
            }

            val code = connection.responseCode
            if (code == HTTP_TOO_MANY_REQUESTS) {
                throw TranslationException("OpenAI 被限流（HTTP 429），请降低并发或稍后再试")
            }
            if (code == HTTP_UNAUTHORIZED) {
                throw TranslationException("OpenAI API Key 无效或已过期")
            }
            if (code !in HTTP_OK until HTTP_MULTIPLE_CHOICES) {
                val error = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                throw TranslationException("OpenAI 服务返回异常状态码：$code，$error")
            }

            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return parseTranslation(body, texts.size)
        } catch (e: TranslationException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw TranslationException("请求超时", e)
        } catch (e: IOException) {
            throw TranslationException("网络不可达：${e.message}", e)
        } finally {
            connection.disconnect()
        }
    }

    /**
     * 解析 OpenAI ChatCompletion 响应。
     *
     * ```json
     * {
     *   "choices": [
     *     {
     *       "message": {
     *         "content": "译文1\n<<<SEP>>>\n译文2\n<<<SEP>>>\n译文3"
     *       }
     *     }
     *   ]
     * }
     * ```
     */
    private fun parseTranslation(body: String, expectedCount: Int): List<String> {
        val root = runCatching { JsonParser.parseString(body).asJsonObject }
            .getOrElse { throw TranslationException("无法解析 OpenAI 响应：$body") }

        val choices = runCatching { root.getAsJsonArray("choices") }
            .getOrNull() ?: throw TranslationException("响应格式异常：缺少 choices 数组")

        if (choices.size() == 0) {
            throw TranslationException("OpenAI 返回空结果")
        }

        val firstChoice = choices[0].asJsonObject
        val message = runCatching { firstChoice.getAsJsonObject("message") }
            .getOrNull() ?: throw TranslationException("响应格式异常：缺少 message")

        val content = message.get("content")?.asString
            ?: throw TranslationException("响应格式异常：缺少 content")

        // 按分隔符拆分，并过滤空行
        val results = content.split("\n<<<SEP>>>\n")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        if (results.size != expectedCount) {
            throw TranslationException("翻译结果数量不匹配：期望 $expectedCount 条，实际 ${results.size} 条")
        }

        return results
    }

    private companion object {
        const val USER_AGENT = "Mozilla/5.0"
        const val CONNECT_TIMEOUT_MS = 8_000
        const val READ_TIMEOUT_MS = 60_000
        const val MAX_RETRIES = 3
        const val RETRY_DELAY_MS = 600L
        const val HTTP_OK = 200
        const val HTTP_MULTIPLE_CHOICES = 300
        const val HTTP_TOO_MANY_REQUESTS = 429
        const val HTTP_UNAUTHORIZED = 401
    }
}
