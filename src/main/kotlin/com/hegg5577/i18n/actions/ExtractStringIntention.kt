package com.hegg5577.i18n.actions

import com.intellij.codeInsight.intention.IntentionAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.util.IncorrectOperationException
import com.hegg5577.i18n.locale.LocaleRepository
import com.hegg5577.i18n.settings.I18nContext
import com.hegg5577.i18n.ui.ExtractStringDialog
import java.io.File

/**
 * 硬编码字符串提取 Intention。
 *
 * 当光标在字符串字面量上时，右键菜单或 Alt+Enter 弹出「提取为 i18n key」选项。
 * 点击后弹窗选择或新建 key，将其替换为对应的翻译调用。
 */
class ExtractStringIntention : IntentionAction {

    override fun getText(): String = "提取为 i18n key"

    override fun getFamilyName(): String = "i18n 字符串提取"

    override fun isAvailable(project: Project, editor: Editor?, file: PsiFile?): Boolean {
        if (editor == null || file == null) return false
        if (!isApplicableFile(file)) return false

        val offset = editor.caretModel.offset
        val element = file.findElementAt(offset) ?: return false

        // 检查当前位置是否在字符串字面量内
        return isStringLiteral(element)
    }

    @Throws(IncorrectOperationException::class)
    override fun invoke(project: Project, editor: Editor?, file: PsiFile?) {
        if (editor == null || file == null) return

        val offset = editor.caretModel.offset
        val element = file.findElementAt(offset) ?: return
        val stringValue = extractStringValue(element, editor.document.text) ?: return

        if (stringValue.isEmpty() || stringValue.length > 500) return

        // 获取 i18n 配置
        val context = I18nContext.of(project)
        val repo = context.repository() ?: return

        // 弹出对话框让用户选择或创建 key
        val dialog = ExtractStringDialog(project, stringValue, repo, context.sourceLocale)
        if (!dialog.showAndGet()) return

        val keyName = dialog.selectedKey ?: return
        val translations = dialog.translations ?: return

        // 保存翻译
        val saveFailed = !saveTranslations(project, repo, keyName, translations, context.sourceLocale)
        if (saveFailed) return

        // 替换代码中的字符串为翻译调用
        replaceStringWithCall(editor, element, keyName)
    }

    private fun isApplicableFile(file: PsiFile): Boolean {
        val extensions = setOf("js", "jsx", "ts", "tsx", "vue", "html", "htm")
        val fileName = file.name.lowercase()
        return extensions.any { fileName.endsWith(".$it") }
    }

    private fun isStringLiteral(element: PsiElement?): Boolean {
        if (element == null) return false
        val elementType = element.node?.elementType?.toString() ?: return false
        return "STRING_TEMPLATE" in elementType || "STRING_LITERAL" in elementType ||
               "STRING" in elementType
    }

    private fun extractStringValue(element: PsiElement, documentText: String): String? {
        // 尝试从元素文本中提取字符串值
        val text = element.text
        return when {
            text.startsWith("\"") && text.endsWith("\"") && text.length > 2 ->
                text.substring(1, text.length - 1).unescape()
            text.startsWith("'") && text.endsWith("'") && text.length > 2 ->
                text.substring(1, text.length - 1).unescape()
            text.startsWith("`") && text.endsWith("`") && text.length > 2 ->
                text.substring(1, text.length - 1).unescape()
            else -> null
        }
    }

    private fun String.unescape(): String {
        return this.replace("\\\\", "\\")
            .replace("\\\"", "\"")
            .replace("\\'", "'")
            .replace("\\n", "\n")
            .replace("\\t", "\t")
    }

    private fun saveTranslations(
        project: Project,
        repo: LocaleRepository,
        key: String,
        translations: Map<String, String>,
        sourceLocale: String,
    ): Boolean {
        val changes = mutableMapOf<String, Map<String, String>>()
        for ((locale, value) in translations) {
            changes[locale] = mapOf(key to value)
        }

        val result = com.hegg5577.i18n.locale.TranslationSaver.save(project, repo, sourceLocale, changes)
        if (!result.isSuccess) {
            val detail = result.failed.entries.joinToString("；") { "${it.key}: ${it.value}" }
            com.intellij.openapi.ui.Messages.showErrorDialog(
                project,
                "保存翻译失败：\n$detail",
                "i18n 提取失败",
            )
            return false
        }
        return true
    }

    private fun replaceStringWithCall(
        editor: Editor,
        element: PsiElement,
        keyName: String,
    ) {
        val startOffset = element.textOffset
        val endOffset = element.textOffset + element.textLength
        val callExpression = "t('$keyName')"
        editor.document.replaceString(startOffset, endOffset, callExpression)
    }

    override fun startInWriteAction(): Boolean = true
}
