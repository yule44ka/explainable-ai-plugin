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
    
    /**
     * Генерировать многоуровневое summary для выбранного кода
     * @param code Выделенный код для анализа
     * @param fileContext Полный контекст файла для лучшего понимания
     * @return CodeSummary объект с различными уровнями детализации
     */
    suspend fun generateCodeSummary(code: String, fileContext: String): Result<CodeSummary> = withContext(Dispatchers.IO) {
        val client = createClient() 
            ?: return@withContext Result.failure(
                IllegalStateException("API key not configured")
            )
        
        val prompt = """
You are an expert code summarizer. For the following code, generate 6 summaries, one for each combination of detail level (low, medium, high) and structure (unstructured, i.e., paragraph, structured, i.e., bulleted):
- low_unstructured: One-sentence, low-detail, paragraph style.
- low_structured: 2-3 short bullet points, low-detail, as a single string. Each bullet must start with "•" and be separated by \n. Never return an array.
- medium_unstructured: 2-3 sentences, medium-detail, paragraph style.
- medium_structured: 3-5 bullet points, medium-detail, as a single string. Use "•" for first-level bullets, and ENCOURAGE the use of two-level bullets (use "◦" for the second level, and indent the second-level bullet with 2 spaces before the "◦") when logical groupings exist. Bullets must be separated by \n. Never return an array.
- high_unstructured: 3-4 sentences, high-detail, paragraph style.
- high_structured: 4-8 bullet points, high-detail, as a single string. Use "•" for first-level bullets, and ENCOURAGE the use of two-level bullets (use "◦" for the second level, and indent the second-level bullet with 2 spaces before the "◦") when logical groupings exist. Bullets must be separated by \n. Never return an array.

IMPORTANT:
- For medium_structured and high_structured, if there are logical groupings, you should use two-level bullets ("•" and "◦"). For the second-level bullet ("◦"), always indent with 2 spaces before the "◦".
- The file context below is provided ONLY for reference to help understand the code's environment.
- Your summary MUST focus ONLY on the specific code snippet provided.
- Return your response as a JSON object with keys: title, low_unstructured, low_structured, medium_unstructured, medium_structured, high_unstructured, high_structured.

File Context (for reference only):
$fileContext

Code to summarize:
$code
        """.trimIndent()
        
        try {
            val result = client.sendPrompt(
                prompt = prompt,
                systemMessage = "You are an expert code analyzer that generates structured summaries.",
                model = getModel(),
                temperature = getTemperature(),
                maxTokens = getMaxTokens()
            )
            
            result.mapCatching { response ->
                // Парсим JSON ответ
                val jsonResponse = response.trim().removePrefix("```json").removeSuffix("```").trim()
                parseCodeSummary(jsonResponse)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * Парсинг JSON ответа в объект CodeSummary
     */
    private fun parseCodeSummary(jsonString: String): CodeSummary {
        // Простой парсинг JSON (можно использовать kotlinx.serialization для более надежного парсинга)
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        return json.decodeFromString(CodeSummary.serializer(), jsonString)
    }
}

/**
 * Модель данных для code summary с различными уровнями детализации
 */
@kotlinx.serialization.Serializable
data class CodeSummary(
    val title: String = "",
    val low_unstructured: String = "",
    val low_structured: String = "",
    val medium_unstructured: String = "",
    val medium_structured: String = "",
    val high_unstructured: String = "",
    val high_structured: String = ""
)
