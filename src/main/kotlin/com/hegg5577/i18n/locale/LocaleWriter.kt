package com.hegg5577.i18n.locale

interface LocaleWriter {
    fun read(): Map<String, String>
    fun set(dottedKey: String, value: String): Boolean
    fun render(): String
}

class JsonLocaleWriter(text: String, private val keyStyle: KeyStyle = KeyStyle.NESTED) : LocaleWriter {

    private val root = com.google.gson.JsonParser.parseString(text).asJsonObject

    /** 与读取配置一致地展开 key，完整字段名优先，避免自动模式覆盖平铺值。 */
    override fun read(): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        fun walk(obj: com.google.gson.JsonObject, prefix: String) {
            for ((name, value) in obj.entrySet()) {
                val key = if (prefix.isEmpty()) name else "$prefix.$name"
                when {
                    value.isJsonObject && keyStyle != KeyStyle.FLAT -> walk(value.asJsonObject, key)
                    value.isJsonPrimitive -> out[key] = value.asString
                }
            }
        }
        walk(root, "")
        if (keyStyle == KeyStyle.AUTO) {
            for ((name, value) in root.entrySet()) {
                if (value.isJsonPrimitive) out[name] = value.asString
            }
        }
        return out
    }

    /** 保留调用方原有的布尔返回约定，写入规则集中在 setValue。 */
    override fun set(dottedKey: String, value: String): Boolean = runCatching {
        setValue(dottedKey, value)
        true
    }.getOrDefault(false)

    /** 平铺字段原位更新；自动模式中新 key 沿用文件风格，路径冲突拒绝覆盖。 */
    internal fun setValue(key: String, value: String) {
        require(key.isNotBlank()) { "key 不能为空" }
        val flat = keyStyle == KeyStyle.FLAT || root.has(key) ||
            (keyStyle == KeyStyle.AUTO && key !in read() &&
                root.entrySet().any { '.' in it.key && it.value.isJsonPrimitive })
        if (flat) {
            require(root[key]?.isJsonObject != true && root[key]?.isJsonArray != true) { "key 路径冲突：$key 已有子节点" }
            root.addProperty(key, value)
        } else {
            LocaleFileWriter.setNested(root, key, value)
        }
    }

    /** 删除实际字段，清理空父节点，同时保留其他译文。 */
    internal fun remove(key: String) {
        if (root.has(key)) {
            root.remove(key)
            return
        }
        if (keyStyle == KeyStyle.FLAT) return
        val parts = key.split('.')
        val parents = mutableListOf<Pair<com.google.gson.JsonObject, String>>()
        var current = root
        for (part in parts.dropLast(1)) {
            val child = current[part] ?: return
            if (!child.isJsonObject) return
            parents += current to part
            current = child.asJsonObject
        }
        current.remove(parts.last())
        for ((parent, part) in parents.asReversed()) {
            if (parent[part].asJsonObject.size() != 0) break
            parent.remove(part)
        }
    }

    /** 先检查目标并写入，再删除旧字段，避免重命名失败丢失原文。 */
    internal fun rename(oldKey: String, newKey: String) {
        if (oldKey == newKey) return
        val values = read()
        val value = values[oldKey] ?: return
        require(!values.containsKey(newKey)) { "目标 key 已存在：$newKey" }
        setValue(newKey, value)
        remove(oldKey)
    }

    /** 统一序列化格式，保持原有字段顺序。 */
    override fun render(): String = LocaleFileWriter.render(root)
}
