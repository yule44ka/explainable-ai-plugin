package com.example.explainableaiplugin.actions

import com.example.explainableaiplugin.services.OpenAIService
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
 * Action для генерации многоуровневого summary выбранного кода
 */
class GenerateSummaryAction : AnAction() {
    
    override fun update(e: AnActionEvent) {
        // Показываем action всегда, но делаем активным только если выделен текст
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
        
        // Получаем весь текст файла для контекста
        val document = editor.document
        val fileContext = document.text
        
        val openAIService = OpenAIService.getInstance(project)
        
        if (!openAIService.isConfigured()) {
            openAIService.showConfigurationWarning()
            return
        }
        
        // Запускаем в фоновом режиме с индикатором прогресса
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Generating Code Summary with AI...", true) {
                var summary: com.example.explainableaiplugin.services.CodeSummary? = null
                var error: Throwable? = null
                
                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true
                    indicator.text = "Sending request to OpenAI..."
                    
                    runBlocking {
                        val result = openAIService.generateCodeSummary(selectedText, fileContext)
                        result.onSuccess { 
                            summary = it 
                        }.onFailure { 
                            error = it 
                        }
                    }
                }
                
                override fun onSuccess() {
                    summary?.let { summaryData ->
                        // Открываем Tool Window и обновляем его содержимое
                        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("AI assistant")
                        toolWindow?.show {
                            // Обновляем содержимое Tool Window
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
     * Обновление Tool Window с результатами summary
     */
    private fun updateToolWindowWithSummary(
        project: com.intellij.openapi.project.Project,
        summary: com.example.explainableaiplugin.services.CodeSummary
    ) {
        // Эта функция будет вызываться из MyToolWindow для обновления UI
        // Сохраняем summary в project service для доступа из Tool Window
        project.putUserData(SUMMARY_KEY, summary)
        
        // Триггерим обновление Tool Window
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("AI assistant")
        toolWindow?.contentManager?.getContent(0)?.let { content ->
            // Tool Window автоматически обновится при следующем показе
        }
    }
    
    companion object {
        val SUMMARY_KEY = com.intellij.openapi.util.Key.create<com.example.explainableaiplugin.services.CodeSummary>("CODE_SUMMARY")
    }
}
