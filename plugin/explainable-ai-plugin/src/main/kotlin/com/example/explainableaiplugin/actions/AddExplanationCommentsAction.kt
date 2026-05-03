package com.example.explainableaiplugin.actions

import com.example.explainableaiplugin.services.ExplanationCommentInserter
import com.example.explainableaiplugin.services.JunieCliService
import com.example.explainableaiplugin.services.OpenAIService
import com.example.explainableaiplugin.settings.ExplanationProvider
import com.example.explainableaiplugin.settings.OpenAISettings
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages
import kotlinx.coroutines.runBlocking

class AddExplanationCommentsAction : AnAction() {

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible = true
        e.presentation.isEnabled = editor?.selectionModel?.hasSelection() ?: false
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val selectedText = editor.selectionModel.selectedText

        if (selectedText.isNullOrEmpty()) {
            Messages.showWarningDialog(
                project,
                "Please select code to explain with comments",
                "No Code Selected"
            )
            return
        }

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

        val document = editor.document
        val fileContext = document.text
        val startLine = editor.selectionModel.selectionStartPosition?.line?.plus(1) ?: 1

        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Adding AI Explanation Comments...", true) {
                var insertedCount = 0
                var error: Throwable? = null

                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true
                    indicator.text = "Generating high-detail bullet mapping with ${provider.displayName}..."

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

                        result.onSuccess { summaryWithMappings ->
                            insertedCount = ExplanationCommentInserter.getInstance(project)
                                .insertHighDetailBulletComments(
                                    editor = editor,
                                    mappings = summaryWithMappings.mappings.high_structured
                                )
                        }.onFailure {
                            error = it
                        }
                    }
                }

                override fun onSuccess() {
                    if (error == null) {
                        openAIService.showSuccessNotification(
                            "Added $insertedCount AI explanation comment block(s)"
                        )
                    }
                }

                override fun onThrowable(error: Throwable) {
                    openAIService.showErrorNotification("Failed to add explanation comments: ${error.message}")
                }

                override fun onFinished() {
                    error?.let {
                        openAIService.showErrorNotification("Failed to add explanation comments: ${it.message}")
                    }
                }
            }
        )
    }
}
