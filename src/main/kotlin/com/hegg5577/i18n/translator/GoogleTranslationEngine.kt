package com.hegg5577.i18n.translator

import com.google.gson.JsonParser
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URLEncoder

/**
 * 基于 Google 翻译免费端点（`client=gtx`）的实现，**无需任何密钥**。
 *
 * 两点实测结论决定了这里的实现方式：
 * 1. **该端点一次只能翻译一条**。多写几个 `q=` 参数、或把多条用 `\n` 拼成一个 `q`，
 *    都只会返回第一条的译文（实测确认）。所以只能逐条请求，靠并发与重试换吞吐。
 * 2. **偶发连接被重置**（`SSLException: EOF occurred in violation of protocol`），
 *    重试即可成功（实测 6/6）。因此必须做重试，否则批量任务会因偶发抖动整体失败。
 */
class GoogleTranslationEngine : TranslationEngine {

    override val id: String = "google-free"

    override val displayName: String = "Google 翻译（免费端点，无需密钥）"

    override fun translate(
        texts: List<String>,
        targetLocale: String,
        sourceLocale: String,
    ): List<String> = texts.map { translateOne(it, targetLocale, sourceLocale) }

    /** 单条翻译，失败自动重试。 */
    fun translateOne(
        text: String,
        targetLocale: String,
        sourceLocale: String,
    ): String {
        if (text.isBlank()) return text

        val sl = googleCode(sourceLocale)
        val tl = googleCode(targetLocale)
        var lastError: Throwable? = null

        repeat(MAX_RETRIES) { attempt ->
            try {
                return request(text, sl, tl)
            } catch (e: Throwable) {
                lastError = e
                if (attempt < MAX_RETRIES - 1) {
                    Thread.sleep(RETRY_DELAY_MS * (attempt + 1))
                }
            }
        }
        throw TranslationException(
            "翻译失败（已重试 $MAX_RETRIES 次）：${lastError?.message ?: "未知原因"}",
            lastError,
        )
    }

    private fun request(text: String, sl: String, tl: String): String {
        val url = java.net.URI.create(
            ENDPOINT +
                "&sl=$sl" +
                "&tl=$tl" +
                "&q=" + URLEncoder.encode(text, Charsets.UTF_8.name())
        ).toURL()

        val connection = url.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.setRequestProperty("Accept", "application/json")

            val code = connection.responseCode
            if (code == HTTP_TOO_MANY_REQUESTS) {
                throw TranslationException("被限流（HTTP 429），请稍后再试或降低并发")
            }
            if (code !in HTTP_OK until HTTP_MULTIPLE_CHOICES) {
                throw TranslationException("翻译服务返回异常状态码：$code")
            }

            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return parseTranslation(body)
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
     * 解析 `[[["译文","原文",…],["译文片段2",…]],null,"en",…]`。
     * 一条原文可能被切成多个片段，需要按顺序拼接起来。
     */
    private fun parseTranslation(body: String): String {
        val root = runCatching { JsonParser.parseString(body).asJsonArray }
            .getOrElse { throw TranslationException("翻译服务返回了无法解析的内容：$body") }

        val segments = runCatching { root[0].asJsonArray }
            .getOrNull() ?: throw TranslationException("翻译结果为空")

        val builder = StringBuilder()
        for (segment in segments) {
            val translated = runCatching { segment.asJsonArray[0] }.getOrNull()
            if (translated != null && translated.isJsonPrimitive) {
                builder.append(translated.asString)
            }
        }
        if (builder.isEmpty()) throw TranslationException("未能从响应中取到译文：$body")
        return builder.toString()
    }

    /** i18next 语言码 -> Google 语言码。 */
    private fun googleCode(locale: String): String = when (locale.lowercase()) {
        "zh" -> "zh-CN"
        else -> locale
    }

    private companion object {
        const val ENDPOINT = "https://translate.googleapis.com/translate_a/single?client=gtx&dt=t"
        const val USER_AGENT = "Mozilla/5.0"
        const val CONNECT_TIMEOUT_MS = 8_000
        const val READ_TIMEOUT_MS = 15_000
        const val MAX_RETRIES = 3
        const val RETRY_DELAY_MS = 600L
        const val HTTP_OK = 200
        const val HTTP_MULTIPLE_CHOICES = 300
        const val HTTP_TOO_MANY_REQUESTS = 429
    }
}
