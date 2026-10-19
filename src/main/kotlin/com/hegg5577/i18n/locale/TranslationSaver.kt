package com.hegg5577.i18n.locale

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import java.io.File

/** 统一准备语言包修改，全部校验通过后才在一个撤销命令中提交。 */
object TranslationSaver {
    data class Result(
        val succeeded: List<String> = emptyList(),
        val failed: Map<String, String> = emptyMap(),
    ) {
        val isSuccess: Boolean get() = failed.isEmpty() && succeeded.isNotEmpty()
    }

    /** 按 key 所属文件保存，缺失译文沿用源语言的文件归属。 */
    fun save(
        project: Project,
        repo: LocaleRepository,
        sourceLocale: String,
        edits: Map<String, Map<String, String>>,
        onlyMissing: Boolean = false,
    ): Result = onEventThread {
        val changes = linkedMapOf<Document, String>()
        val locales = linkedSetOf<String>()
        val failed = linkedMapOf<String, String>()
        for ((locale, values) in edits) {
            if (values.isEmpty()) continue
            try {
                val existing = repo.filesFor(locale).associateWith { file ->
                    JsonLocaleWriter(document(file).text, repo.keyStyle).read()
                }
                // 缺失检查与写入同处事件线程，保留等待翻译期间新增的未保存译文。
                val pending = if (onlyMissing) values.filterKeys { key -> existing.values.none { key in it } } else values
                if (pending.isEmpty()) continue
                val grouped = pending.entries.groupBy { (key, _) ->
                    val matches = existing.filterValues { key in it }.keys
                    require(matches.size <= 1) { "key 出现在多个文件中：$key" }
                    matches.singleOrNull() ?: repo.localeFile(locale, key, sourceLocale)
                        ?: error("未找到 $locale 对应的语言包文件：$key")
                }
                for ((file, entries) in grouped) {
                    val target = document(file)
                    changes[target] = applyChanges(target.text, entries.associate { it.key to it.value }, repo.keyStyle)
                }
                locales += locale
            } catch (e: com.intellij.openapi.progress.ProcessCanceledException) {
                throw e
            } catch (e: Exception) {
                failed[locale] = e.message ?: "保存失败"
            }
        }
        commit(project, "i18n: 保存翻译", changes, locales, failed)
    }

    /** 用实际 key 风格生成新文本，冲突时保留原文并向调用者返回错误。 */
    internal fun applyChanges(
        sourceText: String,
        changes: Map<String, String>,
        keyStyle: KeyStyle = KeyStyle.NESTED,
    ): String {
        val writer = JsonLocaleWriter(sourceText, keyStyle)
        for ((key, value) in changes) writer.setValue(key, value)
        return writer.render()
    }

    /** 从真实译文值判断缺失，空字符串也需要补全。 */
    fun missingFor(repo: LocaleRepository, sourceLocale: String, targetLocales: List<String>): Map<String, List<String>> {
        val source = repo.valuesOf(sourceLocale)
        return targetLocales.associateWith { locale ->
            val values = repo.valuesOf(locale)
            source.keys.filter { values[it].isNullOrBlank() }
        }
    }

    /** 遍历全部语言文件删除 key，准备失败时不提交部分结果。 */
    fun deleteKeys(project: Project, repo: LocaleRepository, keysToDelete: List<String>): Result = onEventThread {
        val changes = linkedMapOf<Document, String>()
        val locales = linkedSetOf<String>()
        val failed = linkedMapOf<String, String>()
        for (resolved in repo.resolvedFiles()) {
            try {
                val target = document(resolved.file)
                val writer = JsonLocaleWriter(target.text, repo.keyStyle)
                if (keysToDelete.none { it in writer.read() }) continue
                keysToDelete.forEach { writer.remove(it) }
                changes[target] = writer.render()
                locales += resolved.locale
            } catch (e: com.intellij.openapi.progress.ProcessCanceledException) {
                throw e
            } catch (e: Exception) {
                failed[resolved.file.path] = e.message ?: "删除失败"
            }
        }
        commit(project, "i18n: 删除 key", changes, locales, failed)
    }

    /** 同时重命名所有语言文件和源码引用，目标冲突时整个操作不写入。 */
    fun renameKey(
        project: Project,
        repo: LocaleRepository,
        oldKey: String,
        newKey: String,
        sourceUpdates: Map<Document, String> = emptyMap(),
    ): Result = onEventThread {
        if (oldKey == newKey) return@onEventThread Result()
        val changes = linkedMapOf<Document, String>()
        val locales = linkedSetOf<String>()
        val failed = linkedMapOf<String, String>()
        for (resolved in repo.resolvedFiles()) {
            try {
                val target = document(resolved.file)
                val writer = JsonLocaleWriter(target.text, repo.keyStyle)
                val values = writer.read()
                require(newKey !in values) { "目标 key 已存在：$newKey" }
                if (oldKey !in values) continue
                writer.rename(oldKey, newKey)
                changes[target] = writer.render()
                locales += resolved.locale
            } catch (e: com.intellij.openapi.progress.ProcessCanceledException) {
                throw e
            } catch (e: Exception) {
                failed[resolved.file.path] = e.message ?: "重命名失败"
            }
        }
        if (changes.isEmpty() && failed.isEmpty()) failed[oldKey] = "未找到原 key"
        changes.putAll(sourceUpdates)
        commit(project, "i18n: 重命名 key", changes, locales, failed)
    }

    /** 使用 IDE 最新的未保存文本，避免磁盘旧值覆盖编辑器改动。 */
    private fun document(file: File): Document {
        val virtualFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(file)
            ?: error("找不到文件：${file.path}")
        require(virtualFile.isWritable) { "文件只读：${file.path}" }
        return FileDocumentManager.getInstance().getDocument(virtualFile)
            ?: error("无法读取文档：${file.path}")
    }

    /** 所有文件先校验可写性，再在同一命令写入，失败时恢复已修改文档。 */
    private fun commit(
        project: Project,
        title: String,
        changes: Map<Document, String>,
        locales: Set<String>,
        failures: Map<String, String>,
    ): Result {
        if (failures.isNotEmpty()) return Result(failed = failures)
        if (changes.isEmpty()) return Result()
        if (changes.keys.any { !it.isWritable }) return Result(failed = mapOf(title to "存在只读文档，未保存任何修改"))
        val originals = changes.keys.associateWith { it.text }
        var failure: String? = null
        WriteCommandAction.runWriteCommandAction(project, title, null, Runnable {
            try {
                for ((document, text) in changes) document.setText(text)
            } catch (e: Exception) {
                originals.forEach { (document, text) -> document.setText(text) }
                if (e is com.intellij.openapi.progress.ProcessCanceledException) throw e
                failure = e.message ?: "写入失败，已恢复原文"
            }
        })
        failure?.let { return Result(failed = mapOf(title to it)) }
        changes.keys.forEach { FileDocumentManager.getInstance().saveDocument(it) }
        return Result(succeeded = locales.toList())
    }

    /** 文档读取和修改都在事件线程完成，避免后台任务与用户编辑互相覆盖。 */
    private fun onEventThread(action: () -> Result): Result {
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) return action()
        var result = Result()
        application.invokeAndWait { result = action() }
        return result
    }
}
