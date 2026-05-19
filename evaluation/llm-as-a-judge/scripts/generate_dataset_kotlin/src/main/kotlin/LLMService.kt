import api.OpenAIClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/**
 * Standalone LLM service for dataset generation.
 *
 * Contains generateCodeSummary() and buildSummaryMapping() extracted verbatim
 * from OpenAIService.kt (com.example.explainableaiplugin.services) — only the
 * IntelliJ platform scaffolding (@Service, Project, notifications) is removed.
 */
class LLMService(
    apiKey: String,
    private val model: String = "gpt-4.1",
    private val temperature: Double = 0.3,
    private val maxTokens: Int = 16000,
    apiEndpoint: String = "https://api.openai.com/v1"
) {
    private val client = OpenAIClient(apiKey, apiEndpoint)

    // -------------------------------------------------------------------------
    // generateCodeSummary — copied verbatim from OpenAIService.kt
    // -------------------------------------------------------------------------

    suspend fun generateCodeSummary(
        code: String,
        fileContext: String,
        model: String? = null
    ): Result<Pair<CodeSummary, api.Usage?>> = withContext(Dispatchers.IO) {
        val modelToUse = model ?: this@LLMService.model

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
                temperature = temperature,
                maxTokens = maxTokens
            )

            result.mapCatching { (response, usage) ->
                Pair(parseCodeSummary(extractJson(response)), usage)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun extractJson(response: String): String {
        val trimmed = response.trim()
        // Extract content between ```json ... ``` or ``` ... ``` fences
        val fenceRegex = Regex("```(?:json)?\\s*([\\s\\S]*?)```")
        val match = fenceRegex.find(trimmed)
        if (match != null) return match.groupValues[1].trim()
        return trimmed
    }

    /**
     * Fixes a common LLM malformation where string values are wrapped with \"...\"
     * (backslash-escaped outer quotes) instead of plain "..." JSON strings.
     *
     * Example input:  "code": \"experiment.create(name='dist')\"
     * Example output: "code": "experiment.create(name='dist')"
     *
     * The regex matches: colon + optional whitespace + \" + content + \"
     * where content may include properly escaped sequences (e.g. \" for inner quotes).
     * It only fires when the value starts with \", so well-formed JSON is unchanged.
     */
    private fun sanitizeMappingJson(json: String): String {
        return json.replace(
            Regex(""":\s*\\"((?:[^"\\]|\\.)*)\\"""")
        ) { match -> ": \"${match.groupValues[1]}\"" }
    }

    private fun parseCodeSummary(jsonString: String): CodeSummary {
        val json = Json { ignoreUnknownKeys = true }
        return json.decodeFromString(CodeSummary.serializer(), jsonString)
    }

    // -------------------------------------------------------------------------
    // buildSummaryMapping — copied verbatim from OpenAIService.kt
    // -------------------------------------------------------------------------

    suspend fun buildSummaryMapping(
        code: String,
        summaryText: String,
        realStartLine: Int = 1,
        model: String? = null
    ): Result<Pair<List<SummaryMapping>, api.Usage?>> = withContext(Dispatchers.IO) {
        val modelToUse = model ?: this@LLMService.model

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
                temperature = temperature,
                maxTokens = maxTokens
            )

            result.mapCatching { (response, usage) ->
                val jsonResponse = sanitizeMappingJson(extractJson(response))
                val json = Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                }

                val mappings = json.decodeFromString<List<SummaryMapping>>(jsonResponse)

                val correctedMappings = mappings.map { mapping ->
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

                val filteredMappings = correctedMappings.mapNotNull { mapping ->
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

                Pair(filteredMappings, usage)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

// -----------------------------------------------------------------------------
// Data models — copied verbatim from OpenAIService.kt
// -----------------------------------------------------------------------------

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

@kotlinx.serialization.Serializable
data class CodeSegment(
    val code: String,
    val line: Int
)

@kotlinx.serialization.Serializable
data class SummaryMapping(
    val explanationComponent: String,
    val codeSegments: List<CodeSegment>
)

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
