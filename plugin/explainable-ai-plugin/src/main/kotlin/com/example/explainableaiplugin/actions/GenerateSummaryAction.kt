package com.example.explainableaiplugin.actions

import com.example.explainableaiplugin.services.OpenAIService
import com.example.explainableaiplugin.services.JunieCliService
import com.example.explainableaiplugin.services.CodeSummary
import com.example.explainableaiplugin.services.CodeSummaryWithMappings
import com.example.explainableaiplugin.settings.ExplanationProvider
import com.example.explainableaiplugin.settings.OpenAISettings
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindowManager
import kotlinx.coroutines.runBlocking

/**
 * Action for generating multi-level summary of selected code
 */
class GenerateSummaryAction : AnAction() {
    
    override fun update(e: AnActionEvent) {
        // Show action always, but make active only if text is selected
        val editor = e.getData(CommonDataKeys.EDITOR)
        val hasSelection = editor?.selectionModel?.hasSelection() ?: false
        e.presentation.isEnabledAndVisible = true
        e.presentation.isEnabled = hasSelection
    }
    
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        
        val selectedText = editor.selectionModel.selectedText
        if (selectedText.isNullOrEmpty()) {
            Messages.showWarningDialog(
                project,
                "Please select some code to generate summary",
                "No Code Selected"
            )
            return
        }
        
        // Get full file text for context
        val document = editor.document
        val fileContext = document.text
        val sourceFilePath = FileDocumentManager.getInstance().getFile(document)?.path
        val startLine = editor.selectionModel.selectionStartPosition?.line?.plus(1) ?: 1
        
        val openAIService = OpenAIService.getInstance(project)
        val junieCliService = JunieCliService.getInstance(project)
        val settings = OpenAISettings.getInstance()
        val provider = settings.explanationProvider
        
        when (provider) {
            ExplanationProvider.JUNIE -> {
                if (!openAIService.isJunieTokenConfigured()) {
                    openAIService.showJunieConfigurationWarning()
                    return
                }
            }
            ExplanationProvider.OPENAI_API -> {
                if (!openAIService.isConfigured()) {
                    openAIService.showConfigurationWarning()
                    return
                }
            }
        }
        
        // Run in background with progress indicator
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Generating Code Summary with AI...", true) {
                var summaryWithMappings: CodeSummaryWithMappings? = null
                var error: Throwable? = null
                
                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true
                    indicator.text = "Generating summary and mappings with ${provider.displayName}..."
                    
                    runBlocking {
                        val result = when (provider) {
                            ExplanationProvider.JUNIE -> junieCliService.generateCodeSummaryWithMappings(
                                contentToExplain = selectedText,
                                fileContext = fileContext,
                                mappingCode = selectedText,
                                realStartLine = startLine
                            )
                            ExplanationProvider.OPENAI_API -> openAIService.generateCodeSummaryWithMappings(
                                contentToExplain = selectedText,
                                fileContext = fileContext,
                                mappingCode = selectedText,
                                realStartLine = startLine,
                                model = settings.model
                            )
                        }
                        result.onSuccess { 
                            summaryWithMappings = it
                        }.onFailure { 
                            error = it 
                        }
                    }
                }
                
                override fun onSuccess() {
                    summaryWithMappings?.let { summaryData ->
                        // Open Tool Window and update its content
                        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("AI assistant")
                        toolWindow?.show {
                            // Update Tool Window content
                            updateToolWindowWithSummary(project, summaryData, sourceFilePath)
                        }
                    }
                }
                
                override fun onThrowable(error: Throwable) {
                    openAIService.showErrorNotification("Failed to generate summary: ${error.message}")
                }
                
                override fun onFinished() {
                    error?.let {
                        openAIService.showErrorNotification("Failed to generate summary: ${it.message}")
                    }
                }
            }
        )
    }
    
    /**
     * Update Tool Window with summary results
     */
    private fun updateToolWindowWithSummary(
        project: com.intellij.openapi.project.Project,
        summaryWithMappings: CodeSummaryWithMappings,
        sourceFilePath: String?
    ) {
        // This function will be called from MyToolWindow to update UI
        // Save summary in project service for access from Tool Window
        project.putUserData(SUMMARY_KEY, summaryWithMappings.summary)
        project.putUserData(SUMMARY_WITH_MAPPINGS_KEY, summaryWithMappings)
        sourceFilePath?.let { project.putUserData(SUMMARY_FILE_PATH_KEY, it) }
        
        // Trigger Tool Window update
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("AI assistant")
        toolWindow?.contentManager?.getContent(0)?.let { content ->
            // Tool Window will automatically update on next show
        }
    }
    
    companion object {
        val SUMMARY_KEY = com.intellij.openapi.util.Key.create<CodeSummary>("CODE_SUMMARY")
        val SUMMARY_WITH_MAPPINGS_KEY = com.intellij.openapi.util.Key.create<CodeSummaryWithMappings>("CODE_SUMMARY_WITH_MAPPINGS")
        val SUMMARY_FILE_PATH_KEY = com.intellij.openapi.util.Key.create<String>("CODE_SUMMARY_FILE_PATH")
    }
}
