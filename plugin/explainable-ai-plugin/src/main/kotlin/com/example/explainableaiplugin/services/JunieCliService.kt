package com.example.explainableaiplugin.services

import com.example.explainableaiplugin.settings.OpenAISettings
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.ProcessOutput
import com.intellij.execution.util.ExecUtil
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
     * Execute Junie CLI with the given prompt
     * @param prompt User's prompt for code generation
     * @return Result with success or error message
     */
    suspend fun generateCode(prompt: String): Result<String> = withContext(Dispatchers.IO) {
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
            
            // Build command line for Junie CLI
            // Assuming junie CLI is installed and available in PATH
            // Command format: junie "prompt text"
            // Junie CLI requires JUNIE_API_KEY environment variable for authentication
            val commandLine = GeneralCommandLine()
                .withWorkDirectory(projectPath)
                .withEnvironment("JUNIE_API_KEY", token)
                .withExePath("junie")
            
            commandLine.addParameter(prompt)
            
            println("[JunieCliService] Executing command: ${commandLine.commandLineString}")
            println("[JunieCliService] Working directory: $projectPath")
            
            // Execute command with timeout (60 seconds)
            val output: ProcessOutput = ExecUtil.execAndGetOutput(commandLine, 60000)
            
            println("[JunieCliService] Exit code: ${output.exitCode}")
            println("[JunieCliService] Stdout: ${output.stdout}")
            println("[JunieCliService] Stderr: ${output.stderr}")
            
            // Check if Junie completed successfully based on output content
            val hasSuccessfulAuth = output.stdout.contains("Successfully authenticated")
            val hasEditedFiles = output.stdout.contains("Edited files") || output.stdout.contains("Updated ")
            val hasOperations = output.stdout.contains("●")
            val hasErrors = output.stderr.isNotEmpty() && !output.stderr.contains("SlowOperations")
            
            when {
                output.exitCode == 0 -> {
                    Result.success("Code generation completed successfully!\n\n${output.stdout}")
                }
                output.exitCode == -1 && hasSuccessfulAuth && (hasEditedFiles || hasOperations) && !hasErrors -> {
                    // Exit code -1 but Junie completed work successfully
                    Result.success("Code generation completed successfully!\n\n${output.stdout}\n\nNote: Process exited with code -1, but all operations completed successfully.")
                }
                output.exitCode == -1 && output.isTimeout -> {
                    // Process timed out
                    Result.failure(
                        RuntimeException("Junie CLI timed out after 5 minutes.\n\nOutput:\n${output.stdout}")
                    )
                }
                else -> {
                    // Other errors
                    val errorMessage = if (output.stderr.isNotEmpty()) {
                        "Junie CLI failed with exit code ${output.exitCode}:\n${output.stderr}\n\nOutput:\n${output.stdout}"
                    } else {
                        "Junie CLI failed with exit code ${output.exitCode}\n\nOutput:\n${output.stdout}"
                    }
                    Result.failure(RuntimeException(errorMessage))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
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
