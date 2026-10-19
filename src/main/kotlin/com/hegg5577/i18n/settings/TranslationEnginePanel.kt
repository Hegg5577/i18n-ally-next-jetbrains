package com.hegg5577.i18n.settings

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.hegg5577.i18n.translator.DeepLTranslationEngine
import com.hegg5577.i18n.translator.GoogleTranslationEngine
import com.hegg5577.i18n.translator.OpenAITranslationEngine
import com.hegg5577.i18n.translator.TranslationException
import javax.swing.DefaultComboBoxModel
import javax.swing.JComponent
import javax.swing.JPanel

class TranslationEnginePanel(private val project: Project) {

    private val state get() = I18nSettings.getInstance(project).state

    data class EngineOption(val id: String, val label: String) {
        override fun toString() = label
    }

    private val engineCombo = ComboBox(
        DefaultComboBoxModel(
            arrayOf(
                EngineOption("google-free", "Google 翻译（免费，无需密钥）"),
                EngineOption("deepl", "DeepL（需要 API Key）"),
                EngineOption("openai", "OpenAI ChatGPT（需要 API Key）"),
            )
        )
    ).apply {
        addActionListener { updateFieldsVisibility() }
    }

    private val deeplApiKeyField = JBPasswordField().apply { preferredSize = JBUI.size(250, 28) }
    private val deeplIsFreeApiCheckBox = JBCheckBox("使用免费版 API（api-free.deepl.com）")

    private val openaiApiKeyField = JBPasswordField().apply { preferredSize = JBUI.size(250, 28) }
    private val openaiModelField = JBTextField().apply { preferredSize = JBUI.size(250, 28) }
    private val openaiBaseUrlField = JBTextField().apply { preferredSize = JBUI.size(250, 28) }

    private val testConnectionButton = javax.swing.JButton("测试连接").apply {
        addActionListener { testConnection() }
    }

    private val helpLabel = JBLabel(
        "<html>" +
            "<b>引擎选择说明：</b><br/>" +
            "<b>• Google 翻译：</b>免费且无需配置，但可能被限流<br/>" +
            "<b>• DeepL：</b>质量最好，可从 https://www.deepl.com/pro 获取免费或专业 API Key<br/>" +
            "<b>• OpenAI：</b>ChatGPT 强大且灵活，可从 https://platform.openai.com/account/api-keys 获取 API Key<br/>" +
            "</html>"
    )

    fun createPanel(): JComponent {
        val form = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("翻译引擎"), engineCombo, 1, false)
            .addComponent(helpLabel)
            .addSeparator()
            .addComponent(JBLabel("<html><b>DeepL 配置</b></html>"))
            .addLabeledComponent(JBLabel("API Key"), deeplApiKeyField, 1, false)
            .addComponent(deeplIsFreeApiCheckBox)
            .addSeparator()
            .addComponent(JBLabel("<html><b>OpenAI 配置</b></html>"))
            .addLabeledComponent(JBLabel("API Key"), openaiApiKeyField, 1, false)
            .addLabeledComponent(JBLabel("模型（如 gpt-3.5-turbo）"), openaiModelField, 1, false)
            .addLabeledComponent(JBLabel("API 基础 URL（支持代理）"), openaiBaseUrlField, 1, false)
            .addComponent(JBLabel("<html><i>留空时使用官方地址 https://api.openai.com/v1</i></html>"))
            .addSeparator()
            .addComponent(testConnectionButton)

        val panel = JPanel().apply {
            layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
            add(form.panel)
            add(javax.swing.Box.createVerticalGlue())
        }

        reset()
        updateFieldsVisibility()
        return panel
    }

    private fun updateFieldsVisibility() {
        val selected = (engineCombo.selectedItem as? EngineOption)?.id ?: "google-free"
        deeplApiKeyField.isEnabled = selected == "deepl"
        deeplIsFreeApiCheckBox.isEnabled = selected == "deepl"
        openaiApiKeyField.isEnabled = selected == "openai"
        openaiModelField.isEnabled = selected == "openai"
        openaiBaseUrlField.isEnabled = selected == "openai"
    }

    private fun testConnection() {
        val selected = (engineCombo.selectedItem as? EngineOption)?.id ?: "google-free"
        try {
            when (selected) {
                "google-free" -> {
                    GoogleTranslationEngine().translate(listOf("hello"), "zh", "en")
                    Messages.showInfoMessage(project, "✓ Google 翻译连接成功", "连接测试")
                }
                "deepl" -> {
                    val apiKey = String(deeplApiKeyField.password).trim()
                    if (apiKey.isEmpty()) {
                        Messages.showErrorDialog(project, "请输入 DeepL API Key", "连接测试失败")
                        return
                    }
                    DeepLTranslationEngine(apiKey, deeplIsFreeApiCheckBox.isSelected)
                        .translate(listOf("hello"), "zh", "en")
                    Messages.showInfoMessage(project, "✓ DeepL 连接成功", "连接测试")
                }
                "openai" -> {
                    val apiKey = String(openaiApiKeyField.password).trim()
                    if (apiKey.isEmpty()) {
                        Messages.showErrorDialog(project, "请输入 OpenAI API Key", "连接测试失败")
                        return
                    }
                    OpenAITranslationEngine(
                        apiKey,
                        openaiModelField.text.trim().ifEmpty { "gpt-3.5-turbo" },
                        openaiBaseUrlField.text.trim().ifEmpty { "https://api.openai.com/v1" }
                    ).translate(listOf("hello"), "zh", "en")
                    Messages.showInfoMessage(project, "✓ OpenAI 连接成功", "连接测试")
                }
            }
        } catch (e: TranslationException) {
            Messages.showErrorDialog(project, "连接失败：${e.message}", "连接测试失败")
        } catch (e: Exception) {
            Messages.showErrorDialog(project, "测试出错：${e.message}", "连接测试失败")
        }
    }

    fun reset() {
        val current = state.activeTranslationEngine.ifEmpty { "google-free" }
        for (i in 0 until engineCombo.itemCount) {
            if ((engineCombo.getItemAt(i) as? EngineOption)?.id == current) {
                engineCombo.selectedIndex = i
                break
            }
        }
        deeplApiKeyField.text = state.deeplApiKey
        deeplIsFreeApiCheckBox.isSelected = state.deeplIsFreeApi
        openaiApiKeyField.text = state.openaiApiKey
        openaiModelField.text = state.openaiModel
        openaiBaseUrlField.text = state.openaiBaseUrl
    }

    fun apply() {
        state.activeTranslationEngine = (engineCombo.selectedItem as? EngineOption)?.id ?: "google-free"
        state.deeplApiKey = String(deeplApiKeyField.password)
        state.deeplIsFreeApi = deeplIsFreeApiCheckBox.isSelected
        state.openaiApiKey = String(openaiApiKeyField.password)
        state.openaiModel = openaiModelField.text.trim().ifEmpty { "gpt-3.5-turbo" }
        state.openaiBaseUrl = openaiBaseUrlField.text.trim().ifEmpty { "https://api.openai.com/v1" }
    }

    fun isModified(): Boolean {
        val selected = (engineCombo.selectedItem as? EngineOption)?.id ?: "google-free"
        return selected != state.activeTranslationEngine ||
            String(deeplApiKeyField.password) != state.deeplApiKey ||
            deeplIsFreeApiCheckBox.isSelected != state.deeplIsFreeApi ||
            String(openaiApiKeyField.password) != state.openaiApiKey ||
            openaiModelField.text.trim() != state.openaiModel ||
            openaiBaseUrlField.text.trim() != state.openaiBaseUrl
    }
}
