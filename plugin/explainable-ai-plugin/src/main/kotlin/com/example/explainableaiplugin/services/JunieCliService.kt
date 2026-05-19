package com.example.explainableaiplugin.services

import com.example.explainableaiplugin.settings.OpenAISettings
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.*
import com.intellij.execution.util.ExecUtil
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Key
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.nio.charset.StandardCharsets

/**
 * Service for interacting with Junie CLI
 */
@Service(Service.Level.PROJECT)
class JunieCliService(private val project: Project) {
    
    private val settings = OpenAISettings.getInstance()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    
    companion object {
        private const val JUNIE_CODE_MODEL = "gemini-3-flash-preview"
        private const val JUNIE_EXPLANATION_MODEL = "gemini-3.1-flash-lite"
        private const val SUMMARY_START_MARKER = "BEGIN_EXPLAINABLE_AI_SUMMARY_JSON"
        private const val SUMMARY_END_MARKER = "END_EXPLAINABLE_AI_SUMMARY_JSON"
        private const val MAPPING_START_MARKER = "BEGIN_EXPLAINABLE_AI_MAPPING_JSON"
        private const val MAPPING_END_MARKER = "END_EXPLAINABLE_AI_MAPPING_JSON"
        private const val SUMMARY_WITH_MAPPINGS_START_MARKER = "BEGIN_EXPLAINABLE_AI_SUMMARY_WITH_MAPPINGS_JSON"
        private const val SUMMARY_WITH_MAPPINGS_END_MARKER = "END_EXPLAINABLE_AI_SUMMARY_WITH_MAPPINGS_JSON"

        fun getInstance(project: Project): JunieCliService = project.service()
    }
    
    /**
     * Execute Junie CLI with the given prompt and stream output in real-time
     * @param prompt User's prompt for code generation
     * @param onOutputLine Callback for each line of output (called in real-time)
     * @return Result with success or error message
     */
    suspend fun generateCode(prompt: String, onOutputLine: (String) -> Unit): Result<String> = withContext(Dispatchers.IO) {
        try {
            val executionResult = executeJuniePrompt(
                prompt = prompt,
                model = JUNIE_CODE_MODEL,
                onOutputLine = onOutputLine
            ).recoverFromInvalidModel(prompt, onOutputLine).getOrThrow()
            val outputText = executionResult.outputText
            val jsonOutputText = executionResult.jsonOutputText
            val searchableOutput = outputText + "\n" + jsonOutputText
            
            // Check if Junie completed successfully based on output content
            val hasSuccessfulAuth = searchableOutput.contains("Successfully authenticated")
            val hasEditedFiles = searchableOutput.contains("Edited files") || searchableOutput.contains("Updated ")
            val hasOperations = searchableOutput.contains("●")
            
            when {
                executionResult.exitCode == 0 -> {
                    Result.success("Code generation completed successfully!")
                }
                executionResult.exitCode == -1 && hasSuccessfulAuth && (hasEditedFiles || hasOperations) -> {
                    Result.success("Code generation completed successfully!")
                }
                else -> {
                    val detailedError = extractJunieErrorMessage(jsonOutputText)
                    val message = detailedError ?: "Junie CLI failed with exit code ${executionResult.exitCode}"
                    Result.failure(RuntimeException(message))
                }
            }
        } catch (e: Exception) {
            println("[JunieCliService] Exception: ${e.message}")
            e.printStackTrace()
            Result.failure(e)
        }
    }

    suspend fun generateCodeSummary(
        contentToExplain: String,
        fileContext: String,
        isDiffInput: Boolean = false,
        agentTrace: String? = null,
        onOutputLine: (String) -> Unit = {}
    ): Result<CodeSummary> = withContext(Dispatchers.IO) {
        val responseFile = Files.createTempFile("junie-summary-response-", ".json")
        val normalizedAgentTrace = agentTrace?.trim().orEmpty()
        val explainedEntity = if (isDiffInput) "code diff" else "code snippet"
        val additionalContext = buildString {
            if (normalizedAgentTrace.isNotEmpty()) {
                appendLine()
                appendLine("Agent Trace:")
                appendLine(normalizedAgentTrace)
            }
        }.trimEnd()

        logSummaryGenerationContext(
            contentToExplain = contentToExplain,
            fileContext = fileContext,
            isDiffInput = isDiffInput,
            agentTrace = normalizedAgentTrace
        )

        val prompt = """
You are an expert code explainer. For the following $explainedEntity, generate 6 explanations of the whole input, one for each combination of detail level (low, medium, high) and structure (unstructured, i.e., paragraph, structured, i.e., bulleted):
- low_unstructured: One-sentence, low-detail, paragraph style.
- low_structured: 2-3 short bullet points, low-detail, as a single string. Each bullet must start with "•" and be separated by \n. Never return an array.
- medium_unstructured: 2-3 sentences, medium-detail, paragraph style.
- medium_structured: 3-5 bullet points, medium-detail, as a single string. Use "•" for first-level bullets, and ENCOURAGE the use of two-level bullets (use "◦" for the second level, and indent the second-level bullet with 2 spaces before the "◦") when logical groupings exist. Bullets must be separated by \n. Never return an array.
- high_unstructured: 3-4 sentences, high-detail, paragraph style.
- high_structured: 4-8 bullet points, high-detail, as a single string. Use "•" for first-level bullets, and ENCOURAGE the use of two-level bullets (use "◦" for the second level, and indent the second-level bullet with 2 spaces before the "◦") when logical groupings exist. Bullets must be separated by \n. Never return an array.

IMPORTANT:
- You MUST cover the ENTIRE $explainedEntity in the explanation — every relevant part must be addressed and explained. Do not skip any part.
- You MUST explain only the provided $explainedEntity, not the entire file.
- For medium_structured and high_structured, if there are logical groupings, you should use two-level bullets ("•" and "◦"). For the second-level bullet ("◦"), always indent with 2 spaces before the "◦".
- The file context and agent trace below are provided for reference to help understand the code's environment, why the code changed, and what the agent was doing.
- Use the agent trace to improve the explanation with relevant intent and sequence of changes when it helps, but do NOT turn the answer into a log recap.
- Your explanation MUST focus ONLY on the specific $explainedEntity provided.
- Do NOT use emojis anywhere in your response.
- Return ONLY a JSON object with keys: title, low_unstructured, low_structured, medium_unstructured, medium_structured, high_unstructured, high_structured.
- Do NOT wrap the JSON in markdown fences.
- Do NOT add any commentary before or after the JSON.
- Also write the final JSON payload exactly to this absolute file path and overwrite the file contents when possible:
${responseFile.toAbsolutePath()}
- Write plain UTF-8 text containing only the JSON object.
- Always output the same JSON between these exact marker lines so the caller can parse it even if file writing is unavailable:
$SUMMARY_START_MARKER
[your JSON here]
$SUMMARY_END_MARKER

File Context (for reference only):
$fileContext

$additionalContext

Input to explain:
$contentToExplain
        """.trimIndent()

        logModelPrompt(
            source = "JunieCliService.generateCodeSummary",
            model = JUNIE_EXPLANATION_MODEL,
            prompt = prompt
        )

        val executionResult = executeJuniePrompt(
            prompt = prompt,
            model = JUNIE_EXPLANATION_MODEL,
            onOutputLine = onOutputLine
        ).recoverFromInvalidModel(prompt, onOutputLine).getOrElse { throwable ->
            return@withContext Result.failure(throwable)
        }

        runCatching {
            val jsonResponse = waitForResponseFile(responseFile, { containsSummaryKeys(it) })
                ?: extractCodeSummaryJson(executionResult.outputText, executionResult.jsonOutputText)
            parseCodeSummary(jsonResponse)
        }.fold(
            onSuccess = { Result.success(it) },
            onFailure = { throwable ->
                val detailedError = extractJunieErrorMessage(executionResult.jsonOutputText)
                    ?: throwable.message
                    ?: "Junie explanation generation failed"
                Result.failure(RuntimeException(detailedError, throwable))
            }
        )
    }

    suspend fun buildSummaryMapping(
        code: String,
        summaryText: String,
        realStartLine: Int = 1,
        onOutputLine: (String) -> Unit = {}
    ): Result<List<SummaryMapping>> = withContext(Dispatchers.IO) {
        val responseFile = Files.createTempFile("junie-mapping-response-", ".json")
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
5. Do NOT use emojis anywhere in your response.

For each explanationComponent, extract one or more relevant code segments from the code that best match the meaning of the explanation component.
- For each code segment, return both the code fragment (as a string) and its line number.
- CRITICAL: The line number MUST be the EXACT line number shown before the colon in the code below (e.g., if the code line is "7: int x = 5;", the line number is 7).
- Prefer to use a complete code statement (such as a full line, assignment, function definition, or block) as the code segment if it clearly represents the explanation component's meaning.
- If a full statement is not appropriate or would be ambiguous, you should use a smaller, relevant fragment (such as a variable, function name, operator, or part of an expression).
- Only include enough code to make the mapping meaningful and unambiguous.
- If a code segment contains multiple lines, split them into separate objects in the codeSegments array.
- After building all mappings, verify that every line of the code appears in at least one codeSegments entry. If any lines are missing, add them to the most relevant existing explanationComponent.
- Return ONLY a JSON array and nothing else.
- Do NOT wrap the JSON in markdown fences.
- Also write the final JSON payload exactly to this absolute file path and overwrite the file contents when possible:
${responseFile.toAbsolutePath()}
- Write plain UTF-8 text containing only the JSON array.
- Always output the same JSON between these exact marker lines so the caller can parse it even if file writing is unavailable:
$MAPPING_START_MARKER
[your JSON here]
$MAPPING_END_MARKER

Return as a JSON array of objects:
[
  {
    "explanationComponent": "exact phrase from explanation",
    "codeSegments": [
      { "code": "relevant code fragment", "line": 5 },
      { "code": "another relevant code fragment", "line": 10 }
    ]
  }
]

Code (each line is prefixed with its absolute line number):
$codeWithLineNumbers

Explanation:
$summaryText
        """.trimIndent()

        logModelPrompt(
            source = "JunieCliService.buildSummaryMapping",
            model = JUNIE_EXPLANATION_MODEL,
            prompt = prompt
        )

        val executionResult = executeJuniePrompt(
            prompt = prompt,
            model = JUNIE_EXPLANATION_MODEL,
            onOutputLine = onOutputLine
        ).recoverFromInvalidModel(prompt, onOutputLine).getOrElse { throwable ->
            return@withContext Result.failure(throwable)
        }

        runCatching {
            val jsonResponse = waitForResponseFile(responseFile, { containsMappingKeys(it) })
                ?: extractJsonArray(executionResult.outputText, executionResult.jsonOutputText)
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

            correctedMappings.mapNotNull { mapping ->
                val exactMatch = summaryText.contains(mapping.explanationComponent)
                if (exactMatch) {
                    mapping
                } else {
                    val fuzzy = findFuzzyMatchInText(summaryText, mapping.explanationComponent)
                    if (fuzzy != null) {
                        println("[buildSummaryMapping] fuzzy-remapped \"${mapping.explanationComponent}\" -> \"$fuzzy\"")
                        SummaryMapping(explanationComponent = fuzzy, codeSegments = mapping.codeSegments)
                    } else {
                        println("[buildSummaryMapping] explanationComponent not found in summary (dropped): ${mapping.explanationComponent}")
                        null
                    }
                }
            }
        }.fold(
            onSuccess = { Result.success(it) },
            onFailure = { throwable ->
                val detailedError = extractJunieErrorMessage(executionResult.jsonOutputText)
                    ?: throwable.message
                    ?: "Junie explanation mapping failed"
                Result.failure(RuntimeException(detailedError, throwable))
            }
        )
    }

    suspend fun generateCodeSummaryWithMappings(
        contentToExplain: String,
        fileContext: String,
        mappingCode: String,
        realStartLine: Int = 1,
        isDiffInput: Boolean = false,
        agentTrace: String? = null,
        onOutputLine: (String) -> Unit = {}
    ): Result<CodeSummaryWithMappings> = withContext(Dispatchers.IO) {
        val responseFile = Files.createTempFile("junie-summary-with-mappings-response-", ".json")
        val normalizedAgentTrace = agentTrace?.trim().orEmpty()
        val explainedEntity = if (isDiffInput) "code diff" else "code snippet"
        val additionalContext = buildString {
            if (normalizedAgentTrace.isNotEmpty()) {
                appendLine()
                appendLine("Agent Trace:")
                appendLine(normalizedAgentTrace)
            }
        }.trimEnd()
        val codeWithLineNumbers = mappingCode.split("\n")
            .mapIndexed { idx, line -> "${idx + realStartLine}: $line" }
            .joinToString("\n")

        logSummaryGenerationContext(
            contentToExplain = contentToExplain,
            fileContext = fileContext,
            isDiffInput = isDiffInput,
            agentTrace = normalizedAgentTrace
        )

        val prompt = """
You are an expert code explainer and code-to-explanation mapper. For the following $explainedEntity, generate explanations and mappings in ONE response.

Generate 6 explanations of the whole input, one for each combination of detail level (low, medium, high) and structure (unstructured paragraph, structured bullets):
- low_unstructured: One-sentence, low-detail, paragraph style.
- low_structured: 2-3 short bullet points, low-detail, as a single string. Each bullet must start with "•" and be separated by \n. Never return an array.
- medium_unstructured: 2-3 sentences, medium-detail, paragraph style.
- medium_structured: 3-5 bullet points, medium-detail, as a single string. Use "•" for first-level bullets and "◦" for second-level bullets when useful. Every second-level bullet line MUST start with exactly two spaces before "◦", for example "  ◦ Nested point". Bullets must be separated by \n. Never return an array.
- high_unstructured: 3-4 sentences, high-detail, paragraph style.
- high_structured: 4-8 bullet points, high-detail, as a single string. Use "•" for first-level bullets and "◦" for second-level bullets when useful. Every second-level bullet line MUST start with exactly two spaces before "◦", for example "  ◦ Nested point". Bullets must be separated by \n. Never return an array.

For EACH of the 6 explanation strings, also build mappings from explanation components to code segments.

Mapping rules:
1. Each explanationComponent MUST be an exact substring of the corresponding explanation string.
2. Extract explanationComponents in the exact order they appear in that explanation string.
3. Do NOT hallucinate explanation components that do not appear in the explanation.
4. Every line of the numbered mapping code MUST be covered by at least one mapping for each explanation format.
5. For each code segment, return both the code fragment and the exact line number shown before the colon.
6. Prefer complete code statements when they clearly match the explanation component.
7. If a code segment contains multiple lines, split them into separate objects in codeSegments.

IMPORTANT:
- You MUST cover the ENTIRE $explainedEntity in the explanation.
- You MUST explain only the provided $explainedEntity, not the entire file.
- For medium_structured and high_structured, if there are second-level bullets, they MUST be indented with exactly two spaces before "◦". Do not place "◦" at the same indentation level as "•".
- The file context and agent trace are reference context only.
- Use the agent trace to improve the explanation when it helps, but do NOT turn the answer into a log recap.
- Do NOT use emojis anywhere in your response.
- Return ONLY a JSON object with this exact shape:
{
  "summary": {
    "title": "",
    "low_unstructured": "",
    "low_structured": "",
    "medium_unstructured": "",
    "medium_structured": "",
    "high_unstructured": "",
    "high_structured": ""
  },
  "mappings": {
    "low_unstructured": [
      {
        "explanationComponent": "exact phrase from summary.low_unstructured",
        "codeSegments": [
          { "code": "relevant code fragment", "line": 5 }
        ]
      }
    ],
    "low_structured": [],
    "medium_unstructured": [],
    "medium_structured": [],
    "high_unstructured": [],
    "high_structured": []
  }
}
- Do NOT wrap the JSON in markdown fences.
- Do NOT add commentary before or after the JSON.
- Also write the final JSON payload exactly to this absolute file path and overwrite the file contents when possible:
${responseFile.toAbsolutePath()}
- Write plain UTF-8 text containing only the JSON object.
- Always output the same JSON between these exact marker lines so the caller can parse it even if file writing is unavailable:
$SUMMARY_WITH_MAPPINGS_START_MARKER
[your JSON here]
$SUMMARY_WITH_MAPPINGS_END_MARKER

File Context (for reference only):
$fileContext

$additionalContext

Input to explain:
$contentToExplain

Code for mappings (each line is prefixed with its absolute line number):
$codeWithLineNumbers
        """.trimIndent()

        logModelPrompt(
            source = "JunieCliService.generateCodeSummaryWithMappings",
            model = JUNIE_EXPLANATION_MODEL,
            prompt = prompt
        )

        val executionResult = executeJuniePrompt(
            prompt = prompt,
            model = JUNIE_EXPLANATION_MODEL,
            onOutputLine = onOutputLine
        ).recoverFromInvalidModel(prompt, onOutputLine).getOrElse { throwable ->
            return@withContext Result.failure(throwable)
        }

        runCatching {
            val jsonResponse = waitForResponseFile(responseFile, { containsSummaryWithMappingsKeys(it) })
                ?: extractSummaryWithMappingsJson(executionResult.outputText, executionResult.jsonOutputText)
            val parsed = json.decodeFromString<CodeSummaryWithMappings>(jsonResponse)
            val normalizedSummary = parsed.summary.withNormalizedStructuredBulletIndent()
            CodeSummaryWithMappings(
                summary = normalizedSummary,
                mappings = normalizeSummaryMappings(normalizedSummary, parsed.mappings, realStartLine)
            )
        }.fold(
            onSuccess = { Result.success(it) },
            onFailure = { throwable ->
                println(
                    "[JunieCliService] Combined explanation-with-mappings response failed; " +
                        "falling back to separate explanation and mapping calls: ${throwable.message}"
                )
                generateCodeSummaryWithSeparateMappings(
                    contentToExplain = contentToExplain,
                    fileContext = fileContext,
                    mappingCode = mappingCode,
                    realStartLine = realStartLine,
                    isDiffInput = isDiffInput,
                    agentTrace = agentTrace,
                    onOutputLine = onOutputLine
                ).fold(
                    onSuccess = { Result.success(it) },
                    onFailure = { fallbackThrowable ->
                        val detailedError = extractJunieErrorMessage(executionResult.jsonOutputText)
                            ?: fallbackThrowable.message
                            ?: throwable.message
                            ?: "Junie explanation and mapping generation failed"
                        Result.failure(RuntimeException(detailedError, fallbackThrowable))
                    }
                )
            }
        )
    }

    private suspend fun generateCodeSummaryWithSeparateMappings(
        contentToExplain: String,
        fileContext: String,
        mappingCode: String,
        realStartLine: Int,
        isDiffInput: Boolean,
        agentTrace: String?,
        onOutputLine: (String) -> Unit
    ): Result<CodeSummaryWithMappings> {
        onOutputLine("Combined explanation-with-mappings JSON was not parseable; retrying with separate explanation and mapping calls...")

        val summary = generateCodeSummary(
            contentToExplain = contentToExplain,
            fileContext = fileContext,
            isDiffInput = isDiffInput,
            agentTrace = agentTrace,
            onOutputLine = onOutputLine
        ).getOrElse { throwable ->
            return Result.failure(throwable)
        }

        val mappingResults = mutableMapOf<String, List<SummaryMapping>>()
        val mappingInputs = listOf(
            "low_unstructured" to summary.low_unstructured,
            "low_structured" to summary.low_structured,
            "medium_unstructured" to summary.medium_unstructured,
            "medium_structured" to summary.medium_structured,
            "high_unstructured" to summary.high_unstructured,
            "high_structured" to summary.high_structured
        )

        mappingInputs.forEach { (key, summaryText) ->
            if (summaryText.isNotBlank()) {
                onOutputLine("Building $key mapping...")
                buildSummaryMapping(
                    code = mappingCode,
                    summaryText = summaryText,
                    realStartLine = realStartLine,
                    onOutputLine = onOutputLine
                ).onSuccess { mappings ->
                    mappingResults[key] = mappings
                }.onFailure { throwable ->
                    println("[JunieCliService] Failed to build fallback mapping for $key: ${throwable.message}")
                    onOutputLine("Failed to build $key mapping: ${throwable.message}")
                }
            }
        }

        return Result.success(
            CodeSummaryWithMappings(
                summary = summary,
                mappings = SummaryMappings(
                    low_unstructured = mappingResults["low_unstructured"] ?: emptyList(),
                    low_structured = mappingResults["low_structured"] ?: emptyList(),
                    medium_unstructured = mappingResults["medium_unstructured"] ?: emptyList(),
                    medium_structured = mappingResults["medium_structured"] ?: emptyList(),
                    high_unstructured = mappingResults["high_unstructured"] ?: emptyList(),
                    high_structured = mappingResults["high_structured"] ?: emptyList()
                )
            )
        )
    }

    private fun logSummaryGenerationContext(
        contentToExplain: String,
        fileContext: String,
        isDiffInput: Boolean,
        agentTrace: String
    ) {
        println(
            buildString {
                appendLine("[JunieCliService] Explanation generation context")
                appendLine("Provider: Junie")
                appendLine("Model: $JUNIE_EXPLANATION_MODEL")
                appendLine("Input type: ${if (isDiffInput) "code diff" else "code snippet"}")
                appendLine("--- Content to explain ---")
                appendLine(contentToExplain)
                appendLine("--- File context ---")
                appendLine(fileContext)
                if (agentTrace.isNotEmpty()) {
                    appendLine("--- Agent trace ---")
                    appendLine(agentTrace)
                }
                appendLine("--- End explanation context ---")
            }
        )
    }

    private fun logModelPrompt(source: String, model: String, prompt: String) {
        println(
            buildString {
                appendLine("[$source] Full prompt sent to model")
                appendLine("Model: $model")
                appendLine("--- Prompt start ---")
                appendLine(prompt)
                appendLine("--- Prompt end ---")
            }
        )
    }

    private fun executeJuniePrompt(
        prompt: String,
        model: String?,
        onOutputLine: (String) -> Unit
    ): Result<JunieExecutionResult> {
        val token = settings.getJunieToken()
        val projectPath = project.basePath
            ?: return Result.failure(IllegalStateException("Project path not found"))

        return runCatching {
            val jsonOutputFile = Files.createTempFile("junie-output-", ".json")
            val commandLine = GeneralCommandLine()
                .withWorkDirectory(projectPath)
                .withExePath("junie")
            if (!token.isNullOrBlank()) {
                commandLine.withEnvironment("JUNIE_API_KEY", token)
            }

            commandLine.addParameter("--output-format=json")
            commandLine.addParameter("--json-output-file=${jsonOutputFile.toAbsolutePath()}")
            if (!model.isNullOrBlank()) {
                commandLine.addParameter("--model=$model")
            }
            commandLine.addParameter(prompt)

            println("[JunieCliService] Executing command: ${commandLine.commandLineString}")
            println("[JunieCliService] Working directory: $projectPath")
            onOutputLine("Starting Junie CLI...")
            onOutputLine("Working directory: $projectPath")
            onOutputLine("─".repeat(50))

            val processHandler = OSProcessHandler(commandLine.withCharset(StandardCharsets.UTF_8))
            val fullOutput = StringBuilder()
            var exitCode = 0

            processHandler.addProcessListener(object : ProcessAdapter() {
                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    val text = event.text
                    if (text.isNotEmpty()) {
                        println("[JunieCliService] Output: $text")
                        fullOutput.append(text)

                        val outputPrefix = outputTypeLabel(outputType)
                        text.lines().forEach { line ->
                            onOutputLine("[$outputPrefix] $line")
                        }
                    }
                }

                override fun processTerminated(event: ProcessEvent) {
                    exitCode = event.exitCode
                    println("[JunieCliService] Process terminated with exit code: $exitCode")
                    onOutputLine("─".repeat(50))
                    onOutputLine("Process completed with exit code: $exitCode")
                }
            })

            processHandler.startNotify()
            processHandler.waitFor(60000)

            val outputText = fullOutput.toString()
            println("[JunieCliService] Full output:\n$outputText")
            val jsonOutputText = appendJsonOutputFile(jsonOutputFile, onOutputLine)

            JunieExecutionResult(
                exitCode = exitCode,
                outputText = outputText,
                jsonOutputText = jsonOutputText
            )
        }.onFailure { throwable ->
            println("[JunieCliService] Exception: ${throwable.message}")
            throwable.printStackTrace()
        }
    }

    private fun Result<JunieExecutionResult>.recoverFromInvalidModel(
        prompt: String,
        onOutputLine: (String) -> Unit
    ): Result<JunieExecutionResult> {
        val executionResult = getOrElse { return this }
        val output = executionResult.outputText + "\n" + executionResult.jsonOutputText
        if (executionResult.exitCode == 0 || !output.contains("Invalid model:", ignoreCase = true)) {
            return this
        }

        onOutputLine("Configured Junie model is not available in this CLI version; retrying with Junie default model...")
        return executeJuniePrompt(
            prompt = prompt,
            model = null,
            onOutputLine = onOutputLine
        )
    }
    
    private fun outputTypeLabel(outputType: Key<*>): String {
        return when (outputType) {
            ProcessOutputTypes.STDOUT -> "stdout"
            ProcessOutputTypes.STDERR -> "stderr"
            ProcessOutputTypes.SYSTEM -> "system"
            else -> outputType.toString()
        }
    }
    
    private fun appendJsonOutputFile(jsonOutputFile: java.nio.file.Path, onOutputLine: (String) -> Unit): String {
        if (!Files.exists(jsonOutputFile) || Files.size(jsonOutputFile) == 0L) return ""
        
        val jsonOutputLines = Files.readAllLines(jsonOutputFile, StandardCharsets.UTF_8)
        onOutputLine("─".repeat(50))
        onOutputLine("Junie JSON output file: ${jsonOutputFile.toAbsolutePath()}")
        onOutputLine("Junie JSON output:")
        jsonOutputLines.forEach { line ->
            onOutputLine("[json] $line")
        }
        return jsonOutputLines.joinToString("\n")
    }

    private fun extractJunieErrorMessage(jsonOutputText: String): String? {
        if (jsonOutputText.isBlank()) return null

        return runCatching {
            val root = Json.parseToJsonElement(jsonOutputText).jsonObject
            val errors = root["errors"]?.jsonArray
                ?.mapNotNull { element ->
                    runCatching { element.jsonPrimitive.content }
                        .getOrNull()
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                }
                .orEmpty()

            errors.joinToString("\n\n").ifBlank { null }
        }.getOrNull()
    }

    private fun parseCodeSummary(jsonString: String): CodeSummary {
        return json.decodeFromString(CodeSummary.serializer(), jsonString)
            .withNormalizedStructuredBulletIndent()
    }

    private fun CodeSummary.withNormalizedStructuredBulletIndent(): CodeSummary {
        return copy(
            low_structured = normalizeStructuredBulletIndent(low_structured),
            medium_structured = normalizeStructuredBulletIndent(medium_structured),
            high_structured = normalizeStructuredBulletIndent(high_structured)
        )
    }

    private fun normalizeStructuredBulletIndent(text: String): String {
        return text
            .lineSequence()
            .map { line ->
                val trimmedStart = line.trimStart()
                if (trimmedStart.startsWith("◦")) {
                    "  $trimmedStart"
                } else {
                    line
                }
            }
            .joinToString("\n")
    }

    private fun extractCodeSummaryJson(outputText: String, jsonOutputText: String): String {
        val directCandidates = listOf(outputText, jsonOutputText, "$outputText\n$jsonOutputText")

        directCandidates.forEach { candidate ->
            extractBetweenMarkers(candidate, SUMMARY_START_MARKER, SUMMARY_END_MARKER)
                ?.takeIf { containsSummaryKeys(it) }
                ?.let { return it }
        }

        directCandidates.forEach { candidate ->
            extractJsonObjectWithSummaryKeys(candidate)?.let { return it }
        }

        directCandidates.forEach { candidate ->
            extractSummaryFromEmbeddedJson(candidate)?.let { return it }
        }

        val diagnostic = buildJunieParseDiagnostic(outputText, jsonOutputText)
        println("[JunieCliService] Failed to extract explanation JSON.\n$diagnostic")
        throw IllegalStateException("Junie response did not contain a valid code explanation JSON object")
    }

    private fun extractJsonArray(outputText: String, jsonOutputText: String): String {
        val directCandidates = listOf(outputText, jsonOutputText, "$outputText\n$jsonOutputText")

        directCandidates.forEach { candidate ->
            extractBetweenMarkers(candidate, MAPPING_START_MARKER, MAPPING_END_MARKER)
                ?.takeIf { containsMappingKeys(it) }
                ?.let { return it }
        }

        directCandidates.forEach { candidate ->
            extractTopLevelJsonArray(candidate)?.let { return it }
        }

        directCandidates.forEach { candidate ->
            extractArrayFromEmbeddedJson(candidate)?.let { return it }
        }

        val diagnostic = buildJunieParseDiagnostic(outputText, jsonOutputText)
        println("[JunieCliService] Failed to extract mapping JSON.\n$diagnostic")
        throw IllegalStateException("Junie response did not contain a valid mapping JSON array")
    }

    private fun extractSummaryWithMappingsJson(outputText: String, jsonOutputText: String): String {
        val directCandidates = listOf(outputText, jsonOutputText, "$outputText\n$jsonOutputText")

        directCandidates.forEach { candidate ->
            extractBetweenMarkers(
                candidate,
                SUMMARY_WITH_MAPPINGS_START_MARKER,
                SUMMARY_WITH_MAPPINGS_END_MARKER
            )
                ?.takeIf { containsSummaryWithMappingsKeys(it) }
                ?.let { return it }
        }

        directCandidates.forEach { candidate ->
            extractJsonObjectWithSummaryWithMappingsKeys(candidate)?.let { return it }
        }

        directCandidates.forEach { candidate ->
            extractSummaryWithMappingsFromEmbeddedJson(candidate)?.let { return it }
        }

        val diagnostic = buildJunieParseDiagnostic(outputText, jsonOutputText)
        println("[JunieCliService] Failed to extract explanation-with-mappings JSON.\n$diagnostic")
        throw IllegalStateException("Junie response did not contain a valid explanation-with-mappings JSON object")
    }

    private fun extractJsonObjectWithSummaryKeys(text: String): String? {
        val fencedMatch = Regex("```(?:json)?\\s*([\\s\\S]*?)\\s*```", RegexOption.IGNORE_CASE)
            .find(text.trim())
        if (fencedMatch != null) {
            val payload = normalizeJsonPayload(fencedMatch.groupValues[1])
            if (containsSummaryKeys(payload)) {
                return payload
            }
        }

        val keyIndex = text.indexOf("\"low_unstructured\"")
        if (keyIndex == -1) return null

        val start = text.lastIndexOf('{', startIndex = keyIndex)
        val end = text.indexOfLast { it == '}' }
        if (start == -1 || end <= start) return null

        val candidate = normalizeJsonPayload(text.substring(start, end + 1))
        return candidate.takeIf { containsSummaryKeys(it) }
    }

    private fun extractJsonObjectWithSummaryWithMappingsKeys(text: String): String? {
        val fencedMatch = Regex("```(?:json)?\\s*([\\s\\S]*?)\\s*```", RegexOption.IGNORE_CASE)
            .find(text.trim())
        if (fencedMatch != null) {
            val payload = normalizeJsonPayload(fencedMatch.groupValues[1])
            if (containsSummaryWithMappingsKeys(payload)) {
                return payload
            }
        }

        return extractJsonObjectMatching(text, ::containsSummaryWithMappingsKeys)
    }

    private fun extractSummaryFromEmbeddedJson(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null

        val root = runCatching { json.parseToJsonElement(trimmed) }.getOrNull() ?: return null
        return findSummaryJsonInElement(root)
    }

    private fun extractSummaryWithMappingsFromEmbeddedJson(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null

        val root = runCatching { json.parseToJsonElement(trimmed) }.getOrNull() ?: return null
        return findSummaryWithMappingsJsonInElement(root)
    }

    private fun extractArrayFromEmbeddedJson(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null

        val root = runCatching { json.parseToJsonElement(trimmed) }.getOrNull() ?: return null
        return findMappingArrayInElement(root)
    }

    private fun extractBetweenMarkers(text: String, startMarker: String, endMarker: String): String? {
        val start = text.indexOf(startMarker)
        if (start == -1) return null

        val contentStart = start + startMarker.length
        val end = text.indexOf(endMarker, startIndex = contentStart)
        if (end == -1) return null

        return normalizeJsonPayload(text.substring(contentStart, end))
    }

    private fun readResponseFile(path: Path): String? {
        if (!Files.exists(path) || Files.size(path) == 0L) return null
        val payload = normalizeJsonPayload(Files.readString(path, StandardCharsets.UTF_8))
        if (payload.isEmpty()) return null

        return extractBetweenMarkers(
            payload,
            SUMMARY_WITH_MAPPINGS_START_MARKER,
            SUMMARY_WITH_MAPPINGS_END_MARKER
        )
            ?: extractBetweenMarkers(payload, SUMMARY_START_MARKER, SUMMARY_END_MARKER)
            ?: extractBetweenMarkers(payload, MAPPING_START_MARKER, MAPPING_END_MARKER)
            ?: payload
    }

    private fun waitForResponseFile(
        path: Path,
        isValidPayload: (String) -> Boolean,
        attempts: Int = 8,
        delayMs: Long = 250
    ): String? {
        repeat(attempts) {
            readResponseFile(path)?.let { payload ->
                if (isValidPayload(payload)) {
                    println("[JunieCliService] Response file ready: ${path.toAbsolutePath()}")
                    return payload
                }
                println("[JunieCliService] Response file exists but payload is not complete yet: ${path.toAbsolutePath()}")
            }
            Thread.sleep(delayMs)
        }
        println("[JunieCliService] Timed out waiting for response file: ${path.toAbsolutePath()}")
        return null
    }

    private fun normalizeJsonPayload(raw: String): String {
        return raw
            .lineSequence()
            .map { line ->
                line
                    .removePrefix("[stdout] ")
                    .removePrefix("[json] ")
                    .removePrefix("│ ")
                    .removePrefix("| ")
                    .trimEnd()
            }
            .dropWhile { it.isBlank() }
            .joinToString("\n")
            .trim()
    }

    private fun findSummaryJsonInElement(element: JsonElement): String? {
        if (element is JsonObject) {
            val serialized = element.toString()
            if (containsSummaryKeys(serialized)) {
                return serialized
            }
        }

        return when (element) {
            is JsonObject -> {
                element.values.firstNotNullOfOrNull { value ->
                    when {
                        value is JsonObject || value is JsonArray -> findSummaryJsonInElement(value)
                        else -> runCatching {
                            val content = value.jsonPrimitive.content
                            extractBetweenMarkers(content, SUMMARY_START_MARKER, SUMMARY_END_MARKER)
                                ?: extractJsonObjectWithSummaryKeys(content)
                                ?: extractSummaryFromEmbeddedJson(content)
                        }.getOrNull()
                    }
                }
            }
            is JsonArray -> {
                element.firstNotNullOfOrNull { value -> findSummaryJsonInElement(value) }
            }
            else -> null
        }
    }

    private fun findSummaryWithMappingsJsonInElement(element: JsonElement): String? {
        if (element is JsonObject) {
            val serialized = element.toString()
            if (containsSummaryWithMappingsKeys(serialized)) {
                return serialized
            }
        }

        return when (element) {
            is JsonObject -> {
                element.values.firstNotNullOfOrNull { value ->
                    when {
                        value is JsonObject || value is JsonArray -> findSummaryWithMappingsJsonInElement(value)
                        else -> runCatching {
                            val content = value.jsonPrimitive.content
                            extractBetweenMarkers(
                                content,
                                SUMMARY_WITH_MAPPINGS_START_MARKER,
                                SUMMARY_WITH_MAPPINGS_END_MARKER
                            ) ?: extractJsonObjectWithSummaryWithMappingsKeys(content)
                                ?: extractSummaryWithMappingsFromEmbeddedJson(content)
                        }.getOrNull()
                    }
                }
            }
            is JsonArray -> {
                element.firstNotNullOfOrNull { value -> findSummaryWithMappingsJsonInElement(value) }
            }
            else -> null
        }
    }

    private fun findMappingArrayInElement(element: JsonElement): String? {
        return when (element) {
            is JsonArray -> {
                val serialized = element.toString()
                if (containsMappingKeys(serialized)) {
                    serialized
                } else {
                    element.firstNotNullOfOrNull { value -> findMappingArrayInElement(value) }
                }
            }
            is JsonObject -> {
                element.values.firstNotNullOfOrNull { value ->
                    when {
                        value is JsonObject || value is JsonArray -> findMappingArrayInElement(value)
                        else -> runCatching {
                            val content = value.jsonPrimitive.content
                            extractBetweenMarkers(content, MAPPING_START_MARKER, MAPPING_END_MARKER)
                                ?: extractTopLevelJsonArray(content)
                                ?: extractArrayFromEmbeddedJson(content)
                        }.getOrNull()
                    }
                }
            }
            else -> null
        }
    }

    private fun extractTopLevelJsonArray(text: String): String? {
        val trimmed = text.trim()

        val fencedMatch = Regex("```(?:json)?\\s*([\\s\\S]*?)\\s*```", RegexOption.IGNORE_CASE)
            .find(trimmed)
        if (fencedMatch != null) {
            val payload = normalizeJsonPayload(fencedMatch.groupValues[1])
            if (payload.startsWith("[") && payload.endsWith("]") && containsMappingKeys(payload)) {
                return payload
            }
        }

        val keyIndex = trimmed.indexOf("\"explanationComponent\"")
        if (keyIndex == -1) return null

        val start = trimmed.lastIndexOf('[', startIndex = keyIndex)
        val end = trimmed.indexOfLast { it == ']' }
        if (start == -1 || end <= start) return null

        val candidate = normalizeJsonPayload(trimmed.substring(start, end + 1))
        return candidate.takeIf { containsMappingKeys(it) }
    }

    private fun extractJsonObjectMatching(text: String, predicate: (String) -> Boolean): String? {
        val source = text.trim()
        source.forEachIndexed { index, char ->
            if (char != '{') return@forEachIndexed

            var depth = 0
            var inString = false
            var escaped = false

            for (cursor in index until source.length) {
                val current = source[cursor]

                if (escaped) {
                    escaped = false
                    continue
                }

                if (current == '\\' && inString) {
                    escaped = true
                    continue
                }

                if (current == '"') {
                    inString = !inString
                    continue
                }

                if (!inString) {
                    when (current) {
                        '{' -> depth++
                        '}' -> {
                            depth--
                            if (depth == 0) {
                                val candidate = normalizeJsonPayload(source.substring(index, cursor + 1))
                                if (predicate(candidate)) {
                                    return candidate
                                }
                                break
                            }
                        }
                    }
                }
            }
        }

        return null
    }

    private fun containsSummaryKeys(text: String): Boolean {
        return text.contains("\"low_unstructured\"") &&
            text.contains("\"low_structured\"") &&
            text.contains("\"medium_unstructured\"") &&
            text.contains("\"medium_structured\"") &&
            text.contains("\"high_unstructured\"") &&
            text.contains("\"high_structured\"")
    }

    private fun containsMappingKeys(text: String): Boolean {
        return text.contains("\"explanationComponent\"") && text.contains("\"codeSegments\"")
    }

    private fun containsSummaryWithMappingsKeys(text: String): Boolean {
        return text.contains("\"summary\"") &&
            text.contains("\"mappings\"") &&
            containsSummaryKeys(text)
    }

    private fun normalizeSummaryMappings(
        summary: CodeSummary,
        mappings: SummaryMappings,
        realStartLine: Int
    ): SummaryMappings {
        return SummaryMappings(
            low_unstructured = normalizeMappingsForSummary(summary.low_unstructured, mappings.low_unstructured, realStartLine),
            low_structured = normalizeMappingsForSummary(summary.low_structured, mappings.low_structured, realStartLine),
            medium_unstructured = normalizeMappingsForSummary(summary.medium_unstructured, mappings.medium_unstructured, realStartLine),
            medium_structured = normalizeMappingsForSummary(summary.medium_structured, mappings.medium_structured, realStartLine),
            high_unstructured = normalizeMappingsForSummary(summary.high_unstructured, mappings.high_unstructured, realStartLine),
            high_structured = normalizeMappingsForSummary(summary.high_structured, mappings.high_structured, realStartLine)
        )
    }

    private fun normalizeMappingsForSummary(
        summaryText: String,
        mappings: List<SummaryMapping>,
        realStartLine: Int
    ): List<SummaryMapping> {
        val correctedMappings = mappings.map { mapping ->
            val needsCorrection = mapping.codeSegments.any { it.line < realStartLine }
            if (needsCorrection) {
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

        return correctedMappings.mapNotNull { mapping ->
            val normalizedComponent = normalizeStructuredBulletIndent(mapping.explanationComponent)
            if (summaryText.contains(normalizedComponent)) {
                SummaryMapping(explanationComponent = normalizedComponent, codeSegments = mapping.codeSegments)
            } else {
                val fuzzy = findFuzzyMatchInText(summaryText, normalizedComponent)
                if (fuzzy != null) {
                    SummaryMapping(explanationComponent = fuzzy, codeSegments = mapping.codeSegments)
                } else {
                    println("[normalizeMappingsForSummary] explanationComponent not found in summary (dropped): $normalizedComponent")
                    null
                }
            }
        }
    }

    private fun buildJunieParseDiagnostic(outputText: String, jsonOutputText: String): String {
        return buildString {
            appendLine("stdout preview:")
            appendLine(outputText.take(2000))
            appendLine()
            appendLine("json output preview:")
            appendLine(jsonOutputText.take(2000))
        }.trimEnd()
    }
    
    /**
     * Check if Junie CLI is available in PATH
     */
    suspend fun isJunieCliAvailable(): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val commandLine = GeneralCommandLine()
                .withExePath("junie")
                .withParameters("--version")
            
            val output: ProcessOutput = ExecUtil.execAndGetOutput(commandLine, 5000)
            Result.success(output.exitCode == 0)
        } catch (e: Exception) {
            Result.success(false)
        }
    }

    private data class JunieExecutionResult(
        val exitCode: Int,
        val outputText: String,
        val jsonOutputText: String
    )
}
