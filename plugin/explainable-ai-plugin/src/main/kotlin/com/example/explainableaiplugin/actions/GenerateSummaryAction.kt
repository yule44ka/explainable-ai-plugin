package com.example.explainableaiplugin.actions

import com.example.explainableaiplugin.services.OpenAIService
import com.example.explainableaiplugin.services.JunieCliService
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
        
        val openAIService = OpenAIService.getInstance(project)
        val junieCliService = JunieCliService.getInstance(project)
        
        if (!openAIService.isJunieTokenConfigured()) {
            openAIService.showJunieConfigurationWarning()
            return
        }
        
        // Run in background with progress indicator
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Generating Code Summary with AI...", true) {
                var summary: com.example.explainableaiplugin.services.CodeSummary? = null
                var error: Throwable? = null
                
                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true
                    indicator.text = "Sending request to Junie..."
                    
                    runBlocking {
                        val result = junieCliService.generateCodeSummary(
                            contentToExplain = selectedText,
                            fileContext = fileContext
                        )
                        result.onSuccess { 
                            summary = it 
                        }.onFailure { 
                            error = it 
                        }
                    }
                }
                
                override fun onSuccess() {
                    summary?.let { summaryData ->
                        // Open Tool Window and update its content
                        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("AI assistant")
                        toolWindow?.show {
                            // Update Tool Window content
                            updateToolWindowWithSummary(project, summaryData)
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
        summary: com.example.explainableaiplugin.services.CodeSummary
    ) {
        // This function will be called from MyToolWindow to update UI
        // Save summary in project service for access from Tool Window
        project.putUserData(SUMMARY_KEY, summary)
        
        // Trigger Tool Window update
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("AI assistant")
        toolWindow?.contentManager?.getContent(0)?.let { content ->
            // Tool Window will automatically update on next show
        }
    }
    
    companion object {
        val SUMMARY_KEY = com.intellij.openapi.util.Key.create<com.example.explainableaiplugin.services.CodeSummary>("CODE_SUMMARY")
    }
}
