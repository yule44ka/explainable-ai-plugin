package com.example.explainableaiplugin.services

import com.example.explainableaiplugin.api.OpenAIClient
import com.example.explainableaiplugin.settings.OpenAISettings
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Service для взаимодействия с OpenAI API
 */
@Service(Service.Level.PROJECT)
class OpenAIService(private val project: Project) {
    
    private val settings = OpenAISettings.getInstance()
    
    companion object {
        fun getInstance(project: Project): OpenAIService = project.service()
    }
    
    /**
     * Проверить, настроен ли API ключ
     */
    fun isConfigured(): Boolean {
        return settings.isApiKeyConfigured()
    }
    
    /**
     * Показать уведомление о необходимости настройки API ключа
     */
    fun showConfigurationWarning() {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Explainable AI")
            .createNotification(
                "OpenAI API Key Not Configured",
                "Please configure your OpenAI API key in Settings -> Tools -> Explainable AI",
                NotificationType.WARNING
            )
            .notify(project)
    }
    
    /**
     * Показать уведомление об успехе
     */
    fun showSuccessNotification(message: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Explainable AI")
            .createNotification(
                "Success",
                message,
                NotificationType.INFORMATION
            )
            .notify(project)
    }
    
    /**
     * Показать уведомление об ошибке
     */
    fun showErrorNotification(message: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Explainable AI")
            .createNotification(
                "Error",
                message,
                NotificationType.ERROR
            )
            .notify(project)
    }
    
    /**
     * Получить API ключ (для использования в запросах)
     */
    fun getApiKey(): String? {
        return settings.getApiKey()
    }
    
    /**
     * Проверить и получить API ключ, показать предупреждение если не настроен
     */
    fun getApiKeyOrWarn(): String? {
        val apiKey = getApiKey()
        if (apiKey.isNullOrEmpty()) {
            showConfigurationWarning()
            return null
        }
        return apiKey
    }
    
    /**
     * Получить настройки для запросов к API
     */
    fun getApiEndpoint(): String = settings.apiEndpoint
    fun getModel(): String = settings.model
    fun getTemperature(): Double = settings.temperature
    fun getMaxTokens(): Int = settings.maxTokens
    
    /**
     * Создать клиента OpenAI с текущими настройками
     */
    private fun createClient(): OpenAIClient? {
        val apiKey = getApiKey()
        if (apiKey.isNullOrEmpty()) {
            return null
        }
        return OpenAIClient(apiKey, getApiEndpoint())
    }
    
    /**
     * Отправить простой текстовый запрос к OpenAI
     */
    suspend fun sendPrompt(
        prompt: String,
        systemMessage: String? = null
    ): Result<String> = withContext(Dispatchers.IO) {
        val client = createClient() 
            ?: return@withContext Result.failure(
                IllegalStateException("API key not configured")
            )
        
        try {
            client.sendPrompt(
                prompt = prompt,
                systemMessage = systemMessage,
                model = getModel(),
                temperature = getTemperature(),
                maxTokens = getMaxTokens()
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * Объяснить код с помощью AI
     */
    suspend fun explainCode(code: String): Result<String> {
        return sendPrompt(
            prompt = "Explain the following code:\n\n$code",
            systemMessage = "You are a helpful coding assistant that explains code clearly and concisely."
        )
    }
    
    /**
     * Найти возможные проблемы в коде
     */
    suspend fun analyzeCode(code: String): Result<String> {
        return sendPrompt(
            prompt = "Analyze the following code and identify potential issues, bugs, or improvements:\n\n$code",
            systemMessage = "You are an expert code reviewer. Provide constructive feedback on code quality, potential bugs, and improvements."
        )
    }
    
    /**
     * Предложить улучшения кода
     */
    suspend fun suggestImprovements(code: String): Result<String> {
        return sendPrompt(
            prompt = "Suggest improvements for the following code:\n\n$code",
            systemMessage = "You are an expert programmer. Suggest specific improvements for code readability, performance, and best practices."
        )
    }
    
    /**
     * Проверить соединение с API
     */
    suspend fun testConnection(): Result<String> {
        return sendPrompt(
            prompt = "Hello! Please respond with 'Connection successful' if you receive this message.",
            systemMessage = "You are a helpful assistant."
        )
    }
}
