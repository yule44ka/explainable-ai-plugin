package com.example.explainableaiplugin.actions

import com.example.explainableaiplugin.services.OpenAIService
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages
import kotlinx.coroutines.runBlocking

/**
 * Action для анализа кода на возможные проблемы
 */
class AnalyzeCodeAction : AnAction() {
    
    override fun update(e: AnActionEvent) {
        // Показываем action только если выделен текст
        val editor = e.getData(CommonDataKeys.EDITOR)
        val hasSelection = editor?.selectionModel?.hasSelection() ?: false
        e.presentation.isEnabled = hasSelection
    }
    
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        
        val selectedText = editor.selectionModel.selectedText
        if (selectedText.isNullOrEmpty()) {
            Messages.showWarningDialog(
                project,
                "Please select some code to analyze",
                "No Code Selected"
            )
            return
        }
        
        val openAIService = OpenAIService.getInstance(project)
        
        if (!openAIService.isConfigured()) {
            openAIService.showConfigurationWarning()
            return
        }
        
        // Запускаем в фоновом режиме с индикатором прогресса
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Analyzing Code with AI...", true) {
                var analysis: String? = null
                var error: Throwable? = null
                
                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true
                    indicator.text = "Sending request to OpenAI..."
                    
                    runBlocking {
                        val result = openAIService.analyzeCode(selectedText)
                        result.onSuccess { 
                            analysis = it 
                        }.onFailure { 
                            error = it 
                        }
                    }
                }
                
                override fun onSuccess() {
                    analysis?.let {
                        Messages.showMessageDialog(
                            project,
                            it,
                            "Code Analysis",
                            Messages.getInformationIcon()
                        )
                    }
                }
                
                override fun onThrowable(error: Throwable) {
                    openAIService.showErrorNotification("Failed to analyze code: ${error.message}")
                }
                
                override fun onFinished() {
                    error?.let {
                        openAIService.showErrorNotification("Failed to analyze code: ${it.message}")
                    }
                }
            }
        )
    }
}
