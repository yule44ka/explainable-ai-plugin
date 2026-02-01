package com.example.explainableaiplugin

import com.example.explainableaiplugin.services.OpenAIService
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.dsl.builder.panel
import java.awt.BorderLayout
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel

class MyToolWindowFactory : ToolWindowFactory {
    override fun shouldBeAvailable(project: Project) = true

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val openAIService = OpenAIService.getInstance(project)
        
        val mainPanel = JPanel(BorderLayout())
        
        // Проверяем, настроен ли API ключ
        if (!openAIService.isConfigured()) {
            // Показываем панель с предложением настроить
            val setupPanel = panel {
                row {
                    label("⚠️ OpenAI API key is not configured")
                }
                row {
                    text("To use AI features, please configure your OpenAI API key.")
                }
                row {
                    button("Open Settings") {
                        ShowSettingsUtil.getInstance().showSettingsDialog(
                            project,
                            "Explainable AI"
                        )
                    }
                }
            }
            mainPanel.add(setupPanel, BorderLayout.CENTER)
        } else {
            // API ключ настроен - показываем основной интерфейс
            val workingPanel = panel {
                row {
                    label("✓ OpenAI API is configured")
                }
                row {
                    label("Model: ${openAIService.getModel()}")
                }
                separator()
                row {
                    text("AI assistant is ready to help with code explanations.")
                }
                row {
                    button("Open Settings") {
                        ShowSettingsUtil.getInstance().showSettingsDialog(
                            project,
                            "Explainable AI"
                        )
                    }
                }
            }
            mainPanel.add(workingPanel, BorderLayout.NORTH)
        }
        
        val content = ContentFactory.getInstance().createContent(mainPanel, "", false)
        toolWindow.contentManager.addContent(content)
    }
}