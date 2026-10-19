package com.hegg5577.i18n.actions

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.refactoring.RefactoringActionHandler
import com.intellij.refactoring.actions.BaseRefactoringAction
import com.hegg5577.i18n.editor.KeyUsageScanner
import com.hegg5577.i18n.locale.DEFAULT_SOURCE_DIRS
import com.hegg5577.i18n.locale.TranslationIndex
import com.hegg5577.i18n.locale.TranslationSaver
import com.hegg5577.i18n.settings.I18nContext
import java.io.File

/**
 * i18n Key 重命名 Action。
 *
 * 通过 Refactor → Rename 或 Shift+F6 触发，支持重命名代码中和语言包中的 key。
 */
class I18nRenameKeyAction : BaseRefactoringAction() {

    override fun isAvailableInEditorOnly(): Boolean = false

    override fun isEnabledOnElements(elements: Array<PsiElement>): Boolean {
        return elements.size == 1 && isI18nKeyString(elements[0])
    }

    override fun getHandler(dataContext: DataContext): RefactoringActionHandler {
        return I18nRenameKeyHandler()
    }

    private fun isI18nKeyString(element: PsiElement): Boolean {
        val text = element.text
        return (text.startsWith("\"") && text.endsWith("\"")) ||
               (text.startsWith("'") && text.endsWith("'"))
    }
}

/**
 * i18n Key 重命名的重构处理程序。
 *
 * 源码引用先完成准备，再与语言包一起提交，保证一次撤销可恢复全部修改。
 */
class I18nRenameKeyHandler : RefactoringActionHandler {

    override fun invoke(project: Project, elements: Array<PsiElement>, dataContext: DataContext?) {
        if (elements.isEmpty()) return

        val element = elements[0]
        val oldKey = extractKey(element) ?: return

        // 弹出重命名对话框
        val newKey = showRenameDialog(project, oldKey) ?: return
        if (newKey == oldKey) return

        // 执行重命名
        performRename(project, oldKey, newKey)
    }

    override fun invoke(project: Project, editor: Editor?, file: PsiFile?, dataContext: DataContext?) {
        if (editor == null || file == null) return

        val element = file.findElementAt(editor.caretModel.offset) ?: return
        invoke(project, arrayOf(element), dataContext)
    }

    private fun extractKey(element: PsiElement): String? {
        val text = element.text
        return when {
            text.startsWith("\"") && text.endsWith("\"") && text.length > 2 ->
                text.substring(1, text.length - 1)
            text.startsWith("'") && text.endsWith("'") && text.length > 2 ->
                text.substring(1, text.length - 1)
            else -> null
        }
    }

    private fun showRenameDialog(project: Project, oldKey: String): String? {
        val validator = object : com.intellij.openapi.ui.InputValidator {
            override fun checkInput(input: String): Boolean {
                return input.isNotEmpty() && input.matches(Regex("""[\w.\-]+"""))
            }

            override fun canClose(input: String): Boolean {
                return checkInput(input)
            }
        }

        return Messages.showInputDialog(
            project,
            "Enter new key name:",
            "Rename i18n Key",
            Messages.getQuestionIcon(),
            oldKey,
            validator,
        )
    }

    /** 先准备源码修改，由统一入口检查冲突、删除旧 key 并提交同一撤销命令。 */
    private fun performRename(project: Project, oldKey: String, newKey: String) {
        val context = I18nContext.of(project)
        val repo = context.repository() ?: return

        try {
            val sourceUpdates = prepareSourceUpdates(project, context, oldKey, newKey)
            val result = TranslationSaver.renameKey(project, repo, oldKey, newKey, sourceUpdates)
            if (!result.isSuccess) {
                val detail = result.failed.entries.joinToString("；") { "${it.key}: ${it.value}" }
                Messages.showErrorDialog(project, "Failed to save:\n$detail", "Rename Failed")
            } else {
                Messages.showInfoMessage(project, "✓ Renamed '$oldKey' to '$newKey'", "Rename Complete")

                // 刷新 UI
                project.getService(com.hegg5577.i18n.ui.I18nPanelService::class.java).refresh()
            }
        } catch (e: com.intellij.openapi.progress.ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            Messages.showErrorDialog(project, "Rename failed: ${e.message}", "Rename Error")
        }
    }

    /** 按配置扫描最新文档，任一文件无法安全准备时直接失败，不产生部分写入。 */
    internal fun prepareSourceUpdates(
        project: Project,
        context: I18nContext,
        oldKey: String,
        newKey: String,
    ): Map<Document, String> {
        val root = requireNotNull(context.projectRoot) { "无法确定项目源码目录" }
        val updates = LinkedHashMap<Document, String>()
        val visited = HashSet<String>()
        val documents = FileDocumentManager.getInstance()
        val psiDocuments = PsiDocumentManager.getInstance(project)
        val skipped = setOf("node_modules", "dist", "build", "out", ".git", ".idea",
            "coverage", ".next", ".nuxt", ".output", ".cache")

        for (dir in context.sourceDirs.ifEmpty { DEFAULT_SOURCE_DIRS }) {
            val sourceDir = File(dir).let { if (it.isAbsolute) it else File(root, dir) }
            val virtualDir = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(sourceDir)
            if (virtualDir == null) {
                check(!sourceDir.exists()) { "无法读取源码目录：$sourceDir" }
                continue
            }
            VfsUtilCore.iterateChildrenRecursively(virtualDir, { file ->
                !file.isDirectory || file.name.lowercase() !in skipped
            }) { file ->
                if (!file.isDirectory && visited.add(file.path) &&
                    TranslationIndex.isSupported(file.name, context.supportedExtensions)) {
                    val document = checkNotNull(documents.getDocument(file)) { "无法获取源码文档：${file.path}" }
                    psiDocuments.commitDocument(document)
                    val psiFile = checkNotNull(psiDocuments.getPsiFile(document)) { "无法解析源码：${file.path}" }
                    val text = document.text
                    val updated = renameSourceText(psiFile, text, oldKey, newKey, context.usageMatchRegex)
                    if (updated != text) {
                        check(file.isWritable && document.isWritable) { "源码文件只读：${file.path}" }
                        updates[document] = updated
                    }
                }
                true
            }
        }
        return updates
    }

    /** 使用扫描结果及捕获组双重校验位置，只倒序替换 key，保留调用参数和其他文本。 */
    internal fun renameSourceText(
        file: PsiFile,
        text: String,
        oldKey: String,
        newKey: String,
        customPatterns: List<String> = emptyList(),
    ): String {
        // 提示扫描允许截断，重命名必须完整，否则删除旧 key 后会留下失效引用。
        check(!KeyUsageScanner.isTooLarge(text.length)) { "源码超过安全扫描上限：${file.name}" }
        val rules = KeyUsageScanner.compileRules(customPatterns)
        val usages = KeyUsageScanner.scanWith(text, rules).toList()
        check(usages.size < KeyUsageScanner.MAX_HINTS_PER_FILE) { "源码引用达到扫描上限：${file.name}" }
        val targets = usages.filter { it.key == oldKey }
        if (targets.isEmpty()) return text

        val candidates = HashMap<Int, MutableSet<IntRange>>()
        for (rule in rules) {
            for (match in rule.regex.findAll(text)) {
                val group = match.groups["key"]
                    ?: match.groups.drop(1).firstOrNull { it != null && it.value.isNotBlank() }
                    ?: continue
                if (group.value.trim().trim('"', '\'') != oldKey) continue
                val start = group.range.first + group.value.indexOf(oldKey)
                val offset = KeyUsageScanner.hintOffset(text, match.range.last + 1)
                candidates.getOrPut(offset) { LinkedHashSet() }.add(start until start + oldKey.length)
            }
        }

        val ranges = targets.map { usage ->
            val range = candidates[usage.offset]?.singleOrNull()
            checkNotNull(range) { "无法唯一定位源码 key：${file.name}:${usage.offset}" }
            check(text.substring(range) == usage.key) { "源码 key 位置已变化：${file.name}" }
            range
        }.filter { isSourceReference(file, it) }.distinct().sortedByDescending { it.first }
        val updated = StringBuilder(text)
        var previousStart = text.length
        for (range in ranges) {
            check(range.last < previousStart) { "源码引用范围重叠：${file.name}" }
            updated.replace(range.first, range.last + 1, newKey)
            previousStart = range.first
        }
        return updated.toString()
    }

    /** 利用源码 PSI 排除注释和包裹整段调用的字符串，不额外猜测框架归属。 */
    private fun isSourceReference(file: PsiFile, range: IntRange): Boolean {
        val leaf = checkNotNull(file.findElementAt(range.first)) { "无法解析源码引用：${file.name}" }
        var element: PsiElement? = leaf
        while (element != null && element !is PsiFile) {
            if (element is PsiComment) return false
            element = element.parent
        }
        element = leaf
        while (element != null && element !is PsiFile) {
            val value = element.text
            if (value.length >= 2 && value.first() in "\"'`" && value.last() == value.first()) {
                return element.textRange.startOffset + 1 == range.first &&
                    element.textRange.endOffset - 1 == range.last + 1
            }
            element = element.parent
        }
        // 无字符串 PSI 的模板语法仍沿用扫描器规则；普通文本无法可靠排除伪调用，停止提交。
        check(file.language.id != "TEXT") { "缺少源码语言解析支持：${file.name}" }
        return true
    }
}

