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
import java.nio.file.Files
import java.nio.charset.StandardCharsets

/**
 * Service for interacting with Junie CLI
 */
@Service(Service.Level.PROJECT)
class JunieCliService(private val project: Project) {
    
    private val settings = OpenAISettings.getInstance()
    
    companion object {
        fun getInstance(project: Project): JunieCliService = project.service()
    }
    
    /**
     * Execute Junie CLI with the given prompt and stream output in real-time
     * @param prompt User's prompt for code generation
     * @param onOutputLine Callback for each line of output (called in real-time)
     * @return Result with success or error message
     */
    suspend fun generateCode(prompt: String, onOutputLine: (String) -> Unit): Result<String> = withContext(Dispatchers.IO) {
        val token = settings.getJunieToken()
        if (token.isNullOrEmpty()) {
            return@withContext Result.failure(
                IllegalStateException("Junie API Key not configured. Please set it in Settings -> Tools -> Explainable AI")
            )
        }
        
        try {
            // Get project base path
            val projectPath = project.basePath
            if (projectPath == null) {
                return@withContext Result.failure(
                    IllegalStateException("Project path not found")
                )
            }
            
            val jsonOutputFile = Files.createTempFile("junie-output-", ".json")
            
            // Build command line for Junie CLI
            val commandLine = GeneralCommandLine()
                .withWorkDirectory(projectPath)
                .withEnvironment("JUNIE_API_KEY", token)
                .withExePath("junie")
            
            commandLine.addParameter("--output-format=json")
            commandLine.addParameter("--json-output-file=${jsonOutputFile.toAbsolutePath()}")
            commandLine.addParameter(prompt)
            
            println("[JunieCliService] Executing command: ${commandLine.commandLineString}")
            println("[JunieCliService] Working directory: $projectPath")
            onOutputLine("Starting Junie CLI...")
            onOutputLine("Working directory: $projectPath")
            onOutputLine("Prompt: $prompt")
            onOutputLine("─".repeat(50))
            
            // Create process handler to stream output
            val processHandler = OSProcessHandler(commandLine.withCharset(StandardCharsets.UTF_8))
            
            val fullOutput = StringBuilder()
            var exitCode = 0
            
            // Add listener for real-time output
            processHandler.addProcessListener(object : ProcessAdapter() {
                override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
                    val text = event.text
                    if (text.isNotEmpty()) {
                        // Log to console
                        println("[JunieCliService] Output: $text")
                        
                        // Add to full output
                        fullOutput.append(text)
                        
                        val outputPrefix = outputTypeLabel(outputType)
                        
                        // Send to UI callback in real-time
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
            
            // Start process
            processHandler.startNotify()
            
            // Wait for process to complete (with timeout of 60 seconds)
            processHandler.waitFor(60000)
            
            val outputText = fullOutput.toString()
            println("[JunieCliService] Full output:\n$outputText")
            
            val jsonOutputText = appendJsonOutputFile(jsonOutputFile, onOutputLine)
            val searchableOutput = outputText + "\n" + jsonOutputText
            
            // Check if Junie completed successfully based on output content
            val hasSuccessfulAuth = searchableOutput.contains("Successfully authenticated")
            val hasEditedFiles = searchableOutput.contains("Edited files") || searchableOutput.contains("Updated ")
            val hasOperations = searchableOutput.contains("●")
            
            when {
                exitCode == 0 -> {
                    Result.success("Code generation completed successfully!")
                }
                exitCode == -1 && hasSuccessfulAuth && (hasEditedFiles || hasOperations) -> {
                    Result.success("Code generation completed successfully!")
                }
                else -> {
                    Result.failure(RuntimeException("Junie CLI failed with exit code $exitCode"))
                }
            }
        } catch (e: Exception) {
            println("[JunieCliService] Exception: ${e.message}")
            e.printStackTrace()
            Result.failure(e)
        }
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
}
