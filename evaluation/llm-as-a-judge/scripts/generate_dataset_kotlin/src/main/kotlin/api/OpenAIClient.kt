package api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * HTTP client for working with OpenAI API
 */
class OpenAIClient(
    private val apiKey: String,
    private val apiEndpoint: String = "https://api.openai.com/v1"
) {
    
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    
    /**
     * Send request to ChatGPT API
     */
    fun sendChatRequest(
        messages: List<ChatMessage>,
        model: String = "gpt-4",
        temperature: Double = 0.7,
        maxTokens: Int = 2000
    ): Result<ChatResponse> {
        val requestBody = ChatRequest(
            model = model,
            messages = messages,
            temperature = temperature,
            max_tokens = maxTokens
        )
        
        val requestJson = json.encodeToString(ChatRequest.serializer(), requestBody)
        val body = requestJson.toRequestBody("application/json".toMediaType())
        
        val request = Request.Builder()
            .url("$apiEndpoint/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build()
        
        return try {
            httpClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string()
                
                if (!response.isSuccessful) {
                    val errorMessage = responseBody ?: "Unknown error"
                    return Result.failure(IOException("OpenAI API error: ${response.code} - $errorMessage"))
                }
                
                if (responseBody == null) {
                    return Result.failure(IOException("Empty response from OpenAI API"))
                }
                
                val chatResponse = json.decodeFromString(ChatResponse.serializer(), responseBody)
                Result.success(chatResponse)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * Simplified method for sending a single request.
     * Returns the response text paired with token usage from the API response.
     */
    fun sendPrompt(
        prompt: String,
        systemMessage: String? = null,
        model: String = "gpt-4",
        temperature: Double = 0.7,
        maxTokens: Int = 2000
    ): Result<Pair<String, Usage?>> {
        val messages = mutableListOf<ChatMessage>()

        if (systemMessage != null) {
            messages.add(ChatMessage("system", systemMessage))
        }

        messages.add(ChatMessage("user", prompt))

        return sendChatRequest(messages, model, temperature, maxTokens).map { response ->
            Pair(response.choices.firstOrNull()?.message?.content ?: "", response.usage)
        }
    }
}

/**
 * Data models for OpenAI API
 */

@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double = 0.7,
    val max_tokens: Int = 2000,
    val stream: Boolean = false
)

@Serializable
data class ChatMessage(
    val role: String,
    val content: String
)

@Serializable
data class ChatResponse(
    val id: String,
    val choices: List<Choice>,
    val usage: Usage? = null
)

@Serializable
data class Choice(
    val index: Int,
    val message: ChatMessage,
    val finish_reason: String? = null
)

@Serializable
data class Usage(
    val prompt_tokens: Int,
    val completion_tokens: Int,
    val total_tokens: Int
)
