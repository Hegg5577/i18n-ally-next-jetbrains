package com.hegg5577.i18n.locale

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject

/**
 * 语言包写回。
 *
 * 三条硬性约束，都是为了保证「改动前后 diff 干净」：
 * 1. **必须保序**。gson 的 [JsonObject] 内部是 LinkedTreeMap，遍历即原顺序；
 *    语言包并非按字母序排列（例如 `more` 紧跟在 `copy_success` 之后），
 *    一旦排序会让整个文件的 diff 全部失效。
 * 2. **必须 `disableHtmlEscaping()`**。项目里有 9 个 key 含 `&`、`<`、`>`
 *    （如 `Detect Teeth & Continue`、`Tooth position <span>…</span> selected`），
 *    gson 默认会把它们转义成 `&`、`<`，文件立刻变得不可读。
 * 3. **缩进 2 空格 + 末尾换行**，与现有 13 个文件保持一致（已逐个做过 round-trip 校验）。
 */
object LocaleFileWriter {

    private val GSON: Gson = GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .create()

    /** 序列化为与源文件完全一致风格的 JSON 文本。 */
    fun render(root: JsonObject): String = GSON.toJson(root) + "\n"

    /**
     * 按点号路径写入值，父层级不存在时逐级创建。
     *
     * 已存在的父节点保持原位；新节点追加到父对象末尾 —— 不重排、不动已有内容。
     */
    fun setNested(root: JsonObject, dottedKey: String, value: String) {
        val parts = dottedKey.split('.')
        var current = root

        for (i in 0 until parts.lastIndex) {
            val existing = current[parts[i]]
            require(existing == null || existing.isJsonObject) {
                "key 路径冲突：${parts.take(i + 1).joinToString(".")} 已有非对象值"
            }
            current = if (existing != null && existing.isJsonObject) {
                existing.asJsonObject
            } else {
                JsonObject().also { current.add(parts[i], it) }
            }
        }
        require(current[parts.last()]?.isJsonObject != true && current[parts.last()]?.isJsonArray != true) {
            "key 路径冲突：$dottedKey 已有子节点"
        }
        current.addProperty(parts[parts.lastIndex], value)
    }
}
