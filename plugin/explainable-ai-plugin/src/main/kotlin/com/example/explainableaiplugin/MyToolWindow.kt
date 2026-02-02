package com.example.explainableaiplugin

import com.example.explainableaiplugin.actions.GenerateSummaryAction
import com.example.explainableaiplugin.services.CodeSummary
import com.example.explainableaiplugin.services.OpenAIService
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.dsl.builder.panel
import kotlinx.coroutines.runBlocking
import java.awt.BorderLayout
import java.awt.Font
import javax.swing.*

class MyToolWindowFactory : ToolWindowFactory {
    override fun shouldBeAvailable(project: Project) = true

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val myToolWindow = MyToolWindow(project)
        val content = ContentFactory.getInstance().createContent(myToolWindow.getContent(), "", false)
        toolWindow.contentManager.addContent(content)
    }
}

class MyToolWindow(private val project: Project) {
    private val openAIService = OpenAIService.getInstance(project)
    private val mainPanel = JPanel(BorderLayout())
    private var summaryPanel: JPanel? = null
    private var currentSummary: CodeSummary? = null
    
    // Комбобоксы для выбора формата
    private val detailLevelCombo = ComboBox(arrayOf("Low Detail", "Medium Detail", "High Detail"))
    private val formatTypeCombo = ComboBox(arrayOf("Paragraph", "Bullet Points"))
    
    fun getContent(): JComponent {
        updateContent()
        return mainPanel
    }
    
    private fun updateContent() {
        mainPanel.removeAll()
        
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
            val controlPanel = createControlPanel()
            mainPanel.add(controlPanel, BorderLayout.NORTH)
            
            // Проверяем, есть ли сохраненный summary
            val summary = project.getUserData(GenerateSummaryAction.SUMMARY_KEY)
            if (summary != null) {
                currentSummary = summary
                displayCurrentSummary()
            }
        }
        
        mainPanel.revalidate()
        mainPanel.repaint()
    }
    
    private fun createControlPanel(): JPanel {
        return panel {
            row {
                label("✓ OpenAI API configured")
            }
            row {
                label("Model: ${openAIService.getModel()}").applyToComponent {
                    font = Font(font.name, Font.PLAIN, 10)
                }
            }
            separator()
            
            row {
                label("📝 Code Summary Generator").applyToComponent {
                    font = Font(font.name, Font.BOLD, 14)
                }
            }
            
            row {
                text("Select code in editor and click Generate")
            }
            
            separator()
            
            row {
                label("Detail Level:")
                cell(detailLevelCombo).applyToComponent {
                    selectedIndex = 1 // Medium по умолчанию
                    addActionListener {
                        if (currentSummary != null) {
                            displayCurrentSummary()
                        }
                    }
                }
            }
            
            row {
                label("Format:")
                cell(formatTypeCombo).applyToComponent {
                    selectedIndex = 0 // Paragraph по умолчанию
                    addActionListener {
                        if (currentSummary != null) {
                            displayCurrentSummary()
                        }
                    }
                }
            }
            
            row {
                button("🚀 Generate Summary") {
                    generateSummaryFromEditor()
                }.applyToComponent {
                    font = Font(font.name, Font.BOLD, 12)
                }
            }
            
            separator()
            
            row {
                button("⚙️ Settings") {
                    ShowSettingsUtil.getInstance().showSettingsDialog(
                        project,
                        "Explainable AI"
                    )
                }.applyToComponent {
                    font = Font(font.name, Font.PLAIN, 10)
                }
            }
        }
    }
    
    private fun generateSummaryFromEditor() {
        // Получаем текущий редактор
        val editor = FileEditorManager.getInstance(project).selectedTextEditor
        if (editor == null) {
            JOptionPane.showMessageDialog(
                mainPanel,
                "No editor is open",
                "Error",
                JOptionPane.WARNING_MESSAGE
            )
            return
        }
        
        val selectedText = editor.selectionModel.selectedText
        if (selectedText.isNullOrEmpty()) {
            JOptionPane.showMessageDialog(
                mainPanel,
                "Please select some code in the editor",
                "No Code Selected",
                JOptionPane.WARNING_MESSAGE
            )
            return
        }
        
        // Получаем весь текст файла для контекста
        val document = editor.document
        val fileContext = document.text
        
        // Запускаем генерацию в фоновом режиме
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Generating Code Summary...", true) {
                var summary: CodeSummary? = null
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
                        currentSummary = summaryData
                        project.putUserData(GenerateSummaryAction.SUMMARY_KEY, summaryData)
                        displayCurrentSummary()
                        openAIService.showSuccessNotification("Summary generated successfully!")
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
    
    private fun displayCurrentSummary() {
        val summary = currentSummary ?: return
        
        // Удаляем старую панель summary если есть
        summaryPanel?.let { mainPanel.remove(it) }
        
        // Определяем какой summary показывать на основе выбранных опций
        val detailLevel = when (detailLevelCombo.selectedIndex) {
            0 -> "low"
            1 -> "medium"
            2 -> "high"
            else -> "medium"
        }
        
        val isStructured = formatTypeCombo.selectedIndex == 1
        
        val summaryText = getSummaryText(summary, detailLevel, isStructured)
        
        // Создаем панель для отображения
        summaryPanel = JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
        }
        
        val contentPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
        }
        
        // Заголовок
        if (summary.title.isNotEmpty()) {
            contentPanel.add(JLabel("📝 " + summary.title).apply {
                font = Font(font.name, Font.BOLD, 14)
                border = BorderFactory.createEmptyBorder(0, 0, 10, 0)
            })
        }
        
        // Показываем выбранный формат
        val formatLabel = "${detailLevel.replaceFirstChar { it.uppercase() }} Detail - ${if (isStructured) "Bullet Points" else "Paragraph"}"
        contentPanel.add(JLabel(formatLabel).apply {
            font = Font(font.name, Font.ITALIC, 11)
            foreground = java.awt.Color.GRAY
            border = BorderFactory.createEmptyBorder(0, 0, 10, 0)
        })
        
        // Текст summary
        val textArea = JTextArea(summaryText).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            background = contentPanel.background
            border = BorderFactory.createEmptyBorder(5, 5, 5, 5)
            font = Font(font.name, Font.PLAIN, 12)
        }
        
        contentPanel.add(textArea)
        
        val scrollPane = JScrollPane(contentPanel).apply {
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            border = BorderFactory.createLineBorder(java.awt.Color.LIGHT_GRAY)
        }
        
        summaryPanel?.add(scrollPane, BorderLayout.CENTER)
        mainPanel.add(summaryPanel!!, BorderLayout.CENTER)
        mainPanel.revalidate()
        mainPanel.repaint()
    }
    
    private fun getSummaryText(summary: CodeSummary, detailLevel: String, isStructured: Boolean): String {
        return when {
            detailLevel == "low" && !isStructured -> summary.low_unstructured
            detailLevel == "low" && isStructured -> summary.low_structured
            detailLevel == "medium" && !isStructured -> summary.medium_unstructured
            detailLevel == "medium" && isStructured -> summary.medium_structured
            detailLevel == "high" && !isStructured -> summary.high_unstructured
            detailLevel == "high" && isStructured -> summary.high_structured
            else -> summary.medium_unstructured
        }
    }
    
    // Legacy method for compatibility with GenerateSummaryAction
    private fun displaySummary(summary: CodeSummary) {
        currentSummary = summary
        displayCurrentSummary()
    }
}