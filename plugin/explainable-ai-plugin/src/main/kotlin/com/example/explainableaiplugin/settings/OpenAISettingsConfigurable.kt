package com.example.explainableaiplugin.settings

import com.example.explainableaiplugin.services.OpenAIService
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.swing.JComponent

/**
 * UI for OpenAI settings in Settings -> Tools -> Explainable AI
 */
class OpenAISettingsConfigurable : Configurable {
    
    private var settingsPanel: DialogPanel? = null
    private val settings = OpenAISettings.getInstance()
    
    // UI components
    private val apiKeyField = JBPasswordField()
    private val junieTokenField = JBPasswordField()
    private val apiEndpointField = JBTextField()
    private val modelField = JBTextField()
    private val temperatureField = JBTextField()
    private val maxTokensField = JBTextField()
    private val explanationProviderCombo = javax.swing.JComboBox(
        ExplanationProvider.entries.map { it.displayName }.toTypedArray()
    )
    
    override fun getDisplayName(): String = "Explainable AI"
    
    override fun createComponent(): JComponent {
        settingsPanel = panel {
            group("OpenAI API Configuration") {
                row("API Key:") {
                    cell(apiKeyField)
                        .align(AlignX.FILL)
                        .comment("Your OpenAI API key (securely stored)")
                        .resizableColumn()
                }
                row {
                    link("Get API Key from OpenAI") {
                        java.awt.Desktop.getDesktop().browse(
                            java.net.URI("https://platform.openai.com/api-keys")
                        )
                    }
                    button("Test Connection") {
                        testConnection()
                    }
                }
            }
            
            group("Junie CLI Configuration") {
                row("Junie API Key:") {
                    cell(junieTokenField)
                        .align(AlignX.FILL)
                        .comment("Your Junie API Key (securely stored)")
                        .resizableColumn()
                }
                row {
                    link("Get API Key from Junie") {
                        java.awt.Desktop.getDesktop().browse(
                            java.net.URI("https://junie.jetbrains.com/cli")
                        )
                    }
                }
            }
            
            group("Advanced Settings") {
                separator()

                row("Explanation Provider:") {
                    cell(explanationProviderCombo)
                        .comment("Choose Junie or the legacy OpenAI API calls for explanations and mappings")
                }
                
                row("API Endpoint:") {
                    cell(apiEndpointField)
                        .align(AlignX.FILL)
                        .comment("OpenAI API endpoint URL")
                }
                
                row("Model:") {
                    cell(modelField)
                        .align(AlignX.FILL)
                        .comment("Model to use (e.g., gpt-4, gpt-3.5-turbo)")
                }
                
                row("Temperature:") {
                    cell(temperatureField)
                        .comment("0.0 to 2.0 (higher = more creative)")
                }
                
                row("Max Tokens:") {
                    cell(maxTokensField)
                        .comment("Maximum tokens in response")
                }
            }
            
            group("About") {
                row {
                    text("""
                        The API key is stored securely using your operating system's credential storage.
                        It will not be included in any configuration files or source control.
                    """.trimIndent())
                }
            }
        }
        
        reset()
        return settingsPanel!!
    }
    
    private fun testConnection() {
        // First apply current settings
        if (isModified()) {
            apply()
        }
        
        val project = ProjectManager.getInstance().defaultProject
        val openAIService = OpenAIService.getInstance(project)
        
        if (!openAIService.isConfigured()) {
            Messages.showWarningDialog(
                "Please configure API key first",
                "API Key Required"
            )
            return
        }
        
        // Show "Testing..." dialog
        ApplicationManager.getApplication().executeOnPooledThread {
            CoroutineScope(Dispatchers.IO).launch {
                val result = openAIService.testConnection()
                
                ApplicationManager.getApplication().invokeLater {
                    result.onSuccess { response ->
                        Messages.showInfoMessage(
                            "Connection successful!\n\nResponse: $response",
                            "Connection Test"
                        )
                    }.onFailure { error ->
                        Messages.showErrorDialog(
                            "Connection failed!\n\nError: ${error.message}",
                            "Connection Test"
                        )
                    }
                }
            }
        }
    }
    
    override fun isModified(): Boolean {
        val currentApiKey = settings.getApiKey() ?: ""
        val newApiKey = String(apiKeyField.password)
        
        val currentJunieToken = settings.getJunieToken() ?: ""
        val newJunieToken = String(junieTokenField.password)
        
        return newApiKey != currentApiKey ||
                newJunieToken != currentJunieToken ||
                ExplanationProvider.entries[explanationProviderCombo.selectedIndex] != settings.explanationProvider ||
                apiEndpointField.text != settings.apiEndpoint ||
                modelField.text != settings.model ||
                temperatureField.text != settings.temperature.toString() ||
                maxTokensField.text != settings.maxTokens.toString()
    }
    
    override fun apply() {
        val newApiKey = String(apiKeyField.password)
        if (newApiKey.isNotEmpty()) {
            settings.setApiKey(newApiKey)
        }
        
        val newJunieToken = String(junieTokenField.password)
        if (newJunieToken.isNotEmpty()) {
            settings.setJunieToken(newJunieToken)
        }
        
        settings.apiEndpoint = apiEndpointField.text
        settings.model = modelField.text
        settings.explanationProvider = ExplanationProvider.entries[explanationProviderCombo.selectedIndex]
        
        try {
            settings.temperature = temperatureField.text.toDouble()
        } catch (e: NumberFormatException) {
            // Keep current value if parsing fails
        }
        
        try {
            settings.maxTokens = maxTokensField.text.toInt()
        } catch (e: NumberFormatException) {
            // Keep current value if parsing fails
        }
    }
    
    override fun reset() {
        val apiKey = settings.getApiKey() ?: ""
        apiKeyField.text = apiKey
        
        val junieToken = settings.getJunieToken() ?: ""
        junieTokenField.text = junieToken
        
        apiEndpointField.text = settings.apiEndpoint
        modelField.text = settings.model
        temperatureField.text = settings.temperature.toString()
        maxTokensField.text = settings.maxTokens.toString()
        explanationProviderCombo.selectedIndex = settings.explanationProvider.ordinal
    }
    
    override fun disposeUIResources() {
        settingsPanel = null
    }
}
