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
 * Service for interacting with OpenAI API
 */
@Service(Service.Level.PROJECT)
class OpenAIService(private val project: Project) {
    
    private val settings = OpenAISettings.getInstance()
    
    companion object {
        fun getInstance(project: Project): OpenAIService = project.service()
    }
    
    /**
     * Check if API key is configured
     */
    fun isConfigured(): Boolean {
        return settings.isApiKeyConfigured()
    }
    
    /**
     * Check if Junie CLI Token is configured
     */
    fun isJunieTokenConfigured(): Boolean {
        return settings.isJunieTokenConfigured()
    }
    
    /**
     * Check if all required credentials are configured
     */
    fun isFullyConfigured(): Boolean {
        return isConfigured() && isJunieTokenConfigured()
    }
    
    /**
     * Show notification about the need to configure API key
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
     * Show notification about the need to configure Junie API Key
     */
    fun showJunieConfigurationWarning() {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Explainable AI")
            .createNotification(
                "Junie API Key Not Configured",
                "Please configure your Junie API Key in Settings -> Tools -> Explainable AI. Get your key at https://junie.jetbrains.com/cli",
                NotificationType.WARNING
            )
            .notify(project)
    }
    
    /**
     * Show success notification
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
     * Show error notification
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
     * Get API key (for use in requests)
     */
    fun getApiKey(): String? {
        return settings.getApiKey()
    }
    
    /**
     * Check and get API key, show warning if not configured
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
     * Get Junie CLI Token (for use in requests)
     */
    fun getJunieToken(): String? {
        return settings.getJunieToken()
    }
    
    /**
     * Check and get Junie CLI Token, show warning if not configured
     */
    fun getJunieTokenOrWarn(): String? {
        val token = getJunieToken()
        if (token.isNullOrEmpty()) {
            showJunieConfigurationWarning()
            return null
        }
        return token
    }
    
    /**
     * Get settings for API requests
     */
    fun getApiEndpoint(): String = settings.apiEndpoint
    fun getModel(): String = settings.model
    fun getTemperature(): Double = settings.temperature
    fun getMaxTokens(): Int = settings.maxTokens
    
    /**
     * Create OpenAI client with current settings
     */
    private fun createClient(): OpenAIClient? {
        val apiKey = getApiKey()
        if (apiKey.isNullOrEmpty()) {
            return null
        }
        return OpenAIClient(apiKey, getApiEndpoint())
    }
    
    /**
     * Send simple text request to OpenAI
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
     * Explain code using AI
     */
    suspend fun explainCode(code: String): Result<String> {
        return sendPrompt(
            prompt = "Explain the following code:\n\n$code",
            systemMessage = "You are a helpful coding assistant that explains code clearly and concisely."
        )
    }
    
    /**
     * Find possible issues in code
     */
    suspend fun analyzeCode(code: String): Result<String> {
        return sendPrompt(
            prompt = "Analyze the following code and identify potential issues, bugs, or improvements:\n\n$code",
            systemMessage = "You are an expert code reviewer. Provide constructive feedback on code quality, potential bugs, and improvements."
        )
    }
    
    /**
     * Suggest code improvements
     */
    suspend fun suggestImprovements(code: String): Result<String> {
        return sendPrompt(
            prompt = "Suggest improvements for the following code:\n\n$code",
            systemMessage = "You are an expert programmer. Suggest specific improvements for code readability, performance, and best practices."
        )
    }
    
    /**
     * Test API connection
     */
    suspend fun testConnection(): Result<String> {
        return sendPrompt(
            prompt = "Hello! Please respond with 'Connection successful' if you receive this message.",
            systemMessage = "You are a helpful assistant."
        )
    }
    
    /**
     * Generate multi-level summary for selected code
     * @param code Selected code for analysis
     * @param fileContext Full file context for better understanding
     * @param model Model to use for generation (if null, uses default from settings)
     * @return CodeSummary object with different levels of detail
     */
    suspend fun generateCodeSummary(
        code: String, 
        fileContext: String, 
        model: String? = null
    ): Result<CodeSummary> = withContext(Dispatchers.IO) {
        val client = createClient() 
            ?: return@withContext Result.failure(
                IllegalStateException("API key not configured")
            )
        
        val modelToUse = model ?: getModel()
        
        val prompt = """
You are an expert code explainer. For the following code, generate 6 explanations of the whole code, one for each combination of detail level (low, medium, high) and structure (unstructured, i.e., paragraph, structured, i.e., bulleted):
- low_unstructured: One-sentence, low-detail, paragraph style.
- low_structured: 2-3 short bullet points, low-detail, as a single string. Each bullet must start with "•" and be separated by \n. Never return an array.
- medium_unstructured: 2-3 sentences, medium-detail, paragraph style.
- medium_structured: 3-5 bullet points, medium-detail, as a single string. Use "•" for first-level bullets, and ENCOURAGE the use of two-level bullets (use "◦" for the second level, and indent the second-level bullet with 2 spaces before the "◦") when logical groupings exist. Bullets must be separated by \n. Never return an array.
- high_unstructured: 3-4 sentences, high-detail, paragraph style.
- high_structured: 4-8 bullet points, high-detail, as a single string. Use "•" for first-level bullets, and ENCOURAGE the use of two-level bullets (use "◦" for the second level, and indent the second-level bullet with 2 spaces before the "◦") when logical groupings exist. Bullets must be separated by \n. Never return an array.

IMPORTANT:
- You MUST cover the ENTIRE code in the explanation — every part of the code (every function, block, statement, or significant line) must be addressed and explained. Do not skip any part.
- For medium_structured and high_structured, if there are logical groupings, you should use two-level bullets ("•" and "◦"). For the second-level bullet ("◦"), always indent with 2 spaces before the "◦".
- The file context below is provided ONLY for reference to help understand the code's environment.
- Your explanation MUST focus ONLY on the specific code snippet provided.
- Return your response as a JSON object with keys: title, low_unstructured, low_structured, medium_unstructured, medium_structured, high_unstructured, high_structured.

File Context (for reference only):
$fileContext

Code to explain:
$code
        """.trimIndent()
        
        try {
            val result = client.sendPrompt(
                prompt = prompt,
                systemMessage = "You are an expert code analyzer that generates structured explanations.",
                model = modelToUse,
                temperature = getTemperature(),
                maxTokens = getMaxTokens()
            )
            
            result.mapCatching { response ->
                // Parse JSON response
                val jsonResponse = response.trim().removePrefix("```json").removeSuffix("```").trim()
                parseCodeSummary(jsonResponse)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
    
    /**
     * Parse JSON response to CodeSummary object
     */
    private fun parseCodeSummary(jsonString: String): CodeSummary {
        // Simple JSON parsing (can use kotlinx.serialization for more robust parsing)
        val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
        return json.decodeFromString(CodeSummary.serializer(), jsonString)
    }
    
    /**
     * Build mapping between summary and code using LLM
     * @param code Code for mapping
     * @param summaryText Summary text
     * @param realStartLine Real starting line of code (1-based)
     * @param model Model to use for generation (if null, uses default from settings)
     * @return List of mappings between summary components and code segments
     */
    suspend fun buildSummaryMapping(
        code: String,
        summaryText: String,
        realStartLine: Int = 1,
        model: String? = null
    ): Result<List<SummaryMapping>> = withContext(Dispatchers.IO) {
        val client = createClient() 
            ?: return@withContext Result.failure(
                IllegalStateException("API key not configured")
            )
        
        val modelToUse = model ?: getModel()
        
        // Add line numbers to code
        val codeWithLineNumbers = code.split("\n")
            .mapIndexed { idx, line -> "${idx + realStartLine}: $line" }
            .joinToString("\n")
        
        val prompt = """
You are an expert at code-to-explanation mapping. Given the following code and explanation, extract up to 10 key explanation components (phrases or semantic units) from the explanation.

IMPORTANT:
1. Each explanationComponent you extract MUST be a substring (exact part) of the explanation text below.
2. Extract explanationComponents in the exact order they appear in the explanation text.
3. Do NOT hallucinate or invent explanation components that do not appear in the explanation.
4. FULL COVERAGE REQUIRED: Every line of the code MUST be covered by at least one mapping. Go through all lines of code and ensure each line appears in at least one codeSegments entry. Do not leave any line unmapped.

For each explanationComponent, extract one or more relevant code segments from the code that best match the meaning of the explanation component.
- For each code segment, return both the code fragment (as a string) and its line number.
- CRITICAL: The line number MUST be the EXACT line number shown before the colon in the code below (e.g., if the code line is "7: int x = 5;", the line number is 7).
- Prefer to use a complete code statement (such as a full line, assignment, function definition, or block) as the code segment if it clearly represents the explanation component's meaning.
- If a full statement is not appropriate or would be ambiguous, you should use a smaller, relevant fragment (such as a variable, function name, operator, or part of an expression).
- Only include enough code to make the mapping meaningful and unambiguous.
- If a code segment contains multiple lines, split them into separate objects in the codeSegments array.
- After building all mappings, verify that every line of the code appears in at least one codeSegments entry. If any lines are missing, add them to the most relevant existing explanationComponent.

Return as a JSON array of objects:
[
  {
    "explanationComponent": "exact phrase from explanation",
    "codeSegments": [
      { "code": "relevant code fragment", "line": 5 },
      { "code": "another relevant code fragment", "line": 10 }
    ]
  },
  ...
]

Code (each line is prefixed with its absolute line number):
$codeWithLineNumbers

Explanation:
$summaryText
        """.trimIndent()
        
        try {
            val result = client.sendPrompt(
                prompt = prompt,
                systemMessage = "You are an expert at code analysis and mapping.",
                model = modelToUse,
                temperature = getTemperature(),
                maxTokens = getMaxTokens()
            )
            
            result.mapCatching { response ->
                // Parse JSON response
                val jsonResponse = response.trim().removePrefix("```json").removeSuffix("```").trim()
                val json = kotlinx.serialization.json.Json { 
                    ignoreUnknownKeys = true 
                    isLenient = true
                }
                
                val mappings = json.decodeFromString<List<SummaryMapping>>(jsonResponse)
                
                // Check and correct line numbers if LLM returned relative numbers
                val correctedMappings = mappings.map { mapping ->
                    // Check first segment - if its line < realStartLine,
                    // it means LLM returned relative numbers (1, 2, 3...)
                    val needsCorrection = mapping.codeSegments.any { it.line < realStartLine }
                    
                    if (needsCorrection) {
                        println("[buildSummaryMapping] Detected relative line numbers, correcting by adding offset ${realStartLine - 1}")
                        val correctedSegments = mapping.codeSegments.map { segment ->
                            CodeSegment(
                                code = segment.code,
                                line = segment.line + realStartLine - 1
                            )
                        }
                        SummaryMapping(
                            explanationComponent = mapping.explanationComponent,
                            codeSegments = correctedSegments
                        )
                    } else {
                        mapping
                    }
                }
                
                correctedMappings.mapNotNull { mapping ->
                    val exactMatch = summaryText.contains(mapping.explanationComponent)
                    if (exactMatch) {
                        mapping
                    } else {
                        val fuzzy = findFuzzyMatchInText(summaryText, mapping.explanationComponent)
                        if (fuzzy != null) {
                            println("[buildSummaryMapping] fuzzy-remapped \"${mapping.explanationComponent}\" → \"$fuzzy\"")
                            SummaryMapping(explanationComponent = fuzzy, codeSegments = mapping.codeSegments)
                        } else {
                            println("[buildSummaryMapping] explanationComponent not found in summary (dropped): ${mapping.explanationComponent}")
                            null
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

/**
 * Data model for code summary with different levels of detail
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

/**
 * Data model for code segment in mapping
 */
@kotlinx.serialization.Serializable
data class CodeSegment(
    val code: String,
    val line: Int
)

/**
 * Data model for mapping between summary component and code
 */
@kotlinx.serialization.Serializable
data class SummaryMapping(
    val explanationComponent: String,
    val codeSegments: List<CodeSegment>
)

/**
 * Container for all summary mappings
 */
data class SummaryMappings(
    val low_unstructured: List<SummaryMapping> = emptyList(),
    val low_structured: List<SummaryMapping> = emptyList(),
    val medium_unstructured: List<SummaryMapping> = emptyList(),
    val medium_structured: List<SummaryMapping> = emptyList(),
    val high_unstructured: List<SummaryMapping> = emptyList(),
    val high_structured: List<SummaryMapping> = emptyList()
)

// -----------------------------------------------------------------------------
// Fuzzy matching utilities (mirrors findBestMatch logic in messageHandler.ts)
// -----------------------------------------------------------------------------

private const val FUZZY_MATCH_THRESHOLD = 0.75
private const val FUZZY_MAX_PATTERN_LENGTH = 300

/**
 * Tries to find [pattern] inside [text] using three strategies:
 *  1. Exact substring match
 *  2. Case-insensitive substring match
 *  3. Sliding-window normalised Levenshtein similarity (only for patterns
 *     shorter than [FUZZY_MAX_PATTERN_LENGTH])
 *
 * Returns the actual matching substring from [text] so callers can replace a
 * paraphrased explanationComponent with the real text from the summary.
 * Returns null when no match with acceptable quality is found.
 */
fun findFuzzyMatchInText(text: String, pattern: String, threshold: Double = FUZZY_MATCH_THRESHOLD): String? {
    if (pattern.isEmpty()) return null

    // 1. Exact match
    if (text.contains(pattern)) return pattern

    // 2. Case-insensitive match
    val lowerText = text.lowercase()
    val lowerPattern = pattern.lowercase()
    val ciIdx = lowerText.indexOf(lowerPattern)
    if (ciIdx != -1) return text.substring(ciIdx, ciIdx + pattern.length)

    // 3. Sliding-window similarity (skip for very long patterns — too costly)
    if (pattern.length > FUZZY_MAX_PATTERN_LENGTH || pattern.length > text.length) return null

    val windowSize = pattern.length
    var bestScore = 0.0
    var bestWindow: String? = null

    for (i in 0..text.length - windowSize) {
        val window = text.substring(i, i + windowSize)
        val score = stringSimilarity(window, pattern)
        if (score > bestScore) {
            bestScore = score
            bestWindow = window
        }
    }

    return if (bestScore >= threshold) bestWindow else null
}

private fun stringSimilarity(a: String, b: String): Double {
    val maxLen = maxOf(a.length, b.length)
    if (maxLen == 0) return 1.0
    return 1.0 - levenshteinDistance(a, b).toDouble() / maxLen
}

private fun levenshteinDistance(a: String, b: String): Int {
    val m = a.length
    val n = b.length
    val dp = Array(m + 1) { IntArray(n + 1) }
    for (i in 0..m) dp[i][0] = i
    for (j in 0..n) dp[0][j] = j
    for (i in 1..m) {
        for (j in 1..n) {
            dp[i][j] = if (a[i - 1] == b[j - 1]) {
                dp[i - 1][j - 1]
            } else {
                1 + minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1])
            }
        }
    }
    return dp[m][n]
}
