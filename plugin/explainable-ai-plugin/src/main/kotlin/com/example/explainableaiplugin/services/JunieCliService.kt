package com.example.explainableaiplugin.services

import com.example.explainableaiplugin.settings.OpenAISettings
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.ProcessOutput
import com.intellij.execution.util.ExecUtil
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
            
            // Execute command with extended timeout (5 minutes = 300 seconds)
            // Junie AI code generation can take a while, especially for complex requests
            // Using CapturingProcessHandler for better process control
            val processHandler = CapturingProcessHandler(commandLine)
            
            // Automatically send "y\n" responses to stdin for any confirmation prompts
            // Send multiple "yes" responses to handle potential multiple prompts
            try {
                val processInput = processHandler.processInput
                processInput?.write("y\n".toByteArray(StandardCharsets.UTF_8))
                processInput?.write("y\n".toByteArray(StandardCharsets.UTF_8))
                processInput?.write("y\n".toByteArray(StandardCharsets.UTF_8))
                processInput?.flush()
            } catch (e: Exception) {
                println("[JunieCliService] Could not write to stdin: ${e.message}")
            }
            
            val output: ProcessOutput = processHandler.runProcess(300000)
            
            println("[JunieCliService] Exit code: ${output.exitCode}")
            println("[JunieCliService] Stdout: ${output.stdout}")
            println("[JunieCliService] Stderr: ${output.stderr}")
            
            when {
                output.exitCode == 0 -> {
                    Result.success("Code generation completed successfully!\n${output.stdout}")
                }
                output.exitCode == -1 && output.isTimeout -> {
                    // Process timed out - still might have made progress
                    Result.failure(
                        RuntimeException("Junie CLI timed out after 5 minutes. The process may still be running in the background.\n\nOutput so far:\n${output.stdout}")
                    )
                }
                output.exitCode == -1 -> {
                    // Process was interrupted but might have produced output
                    if (output.stdout.contains("Successfully authenticated") || output.stdout.isNotEmpty()) {
                        Result.success("Junie CLI process completed with output:\n${output.stdout}\n\nNote: Process exit code was -1, but output suggests it may have been successful. Check your files.")
                    } else {
                        Result.failure(
                            RuntimeException("Junie CLI was interrupted. Exit code: ${output.exitCode}\n${output.stderr}")
                        )
                    }
                }
                else -> {
                    Result.failure(
                        RuntimeException("Junie CLI failed with exit code ${output.exitCode}:\n${output.stderr}\n\nOutput:\n${output.stdout}")
                    )
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
