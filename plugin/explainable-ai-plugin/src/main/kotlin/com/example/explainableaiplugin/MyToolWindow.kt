package com.example.explainableaiplugin

import com.example.explainableaiplugin.actions.GenerateSummaryAction
import com.example.explainableaiplugin.services.CodeSummary
import com.example.explainableaiplugin.services.CodeSummaryWithMappings
import com.example.explainableaiplugin.services.OpenAIService
import com.example.explainableaiplugin.services.JunieCliService
import com.example.explainableaiplugin.services.CodeChangeDetector
import com.example.explainableaiplugin.services.CommentLineShift
import com.example.explainableaiplugin.services.ExplanationCommentInserter
import com.example.explainableaiplugin.services.FileChange
import com.example.explainableaiplugin.services.SummaryMappings
import com.example.explainableaiplugin.services.SummaryMapping
import com.example.explainableaiplugin.settings.ExplanationProvider
import com.example.explainableaiplugin.settings.OpenAISettings
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.dsl.builder.panel
import kotlinx.coroutines.runBlocking
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Font
import java.awt.Point
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.*
import javax.swing.text.DefaultHighlighter

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
    private val junieCliService = JunieCliService.getInstance(project)
    private val codeChangeDetector = CodeChangeDetector.getInstance(project)
    private val explanationCommentInserter = ExplanationCommentInserter.getInstance(project)
    private val settings = OpenAISettings.getInstance()
    private val mainPanel = JPanel(BorderLayout())
    private var junieLogPanel: JPanel? = null
    private var changeSummaryPanel: JPanel? = null
    private var summaryTabPanel: JPanel? = null
    private var summaryContentPanel: JPanel? = null
    private var generationTabPanel: JPanel? = null
    private val junieLogTextArea = JTextArea().apply {
        isEditable = false
        lineWrap = true
        wrapStyleWord = true
        font = Font("Monospaced", Font.PLAIN, 11)
        background = JBColor(Color(250, 250, 250), Color(43, 43, 43))
        foreground = JBColor(Color(50, 50, 50), Color(169, 183, 198))
    }
    private var currentSummary: CodeSummary? = null
    private var currentMappings: SummaryMappings? = null
    private var currentSummaryFilePath: String? = null
    private var originalCode: String? = null
    private var startLine: Int = 1
    private val extraScrollTailPx = 900
    
    // Store all generated change summaries for interactive viewing
    private var currentChangeSummaries: List<ChangeSummaryResult> = emptyList()
    
    // Comboboxes for Code Summary tab
    private val detailLevelCombo = ComboBox(arrayOf("Low Detail", "Medium Detail", "High Detail"))
    private val formatTypeCombo = ComboBox(arrayOf("Paragraph", "Bullet Points"))
    private val explanationProviderCombo = ComboBox(ExplanationProvider.entries.map { it.displayName }.toTypedArray())
    
    // Model pricing data
    private val modelPricing = mapOf(
        "gpt-5.5" to "$5.00",
        "gpt-5.4" to "$2.50",
        "gpt-5.4-mini" to "$0.75",
        "gpt-4.1" to "$2.00",
        "gpt-4.1-mini" to "$0.40",
        "gpt-4.1-nano" to "$0.10",
        "o3" to "$2.00",
        "gpt-4o" to "$2.50",
        "gpt-4o-mini" to "$0.15"
    )
    
    // Combobox for model selection with prices (Code Summary tab)
    private val modelCombo = ComboBox(modelPricing.map { (model, price) -> "$model | $price" }.toTypedArray())
    
    // Comboboxes for Code Generation tab
    private val generationModelCombo = ComboBox(modelPricing.map { (model, price) -> "$model | $price" }.toTypedArray())
    private val generationDetailLevelCombo = ComboBox(arrayOf("Low Detail", "Medium Detail", "High Detail"))
    private val generationFormatTypeCombo = ComboBox(arrayOf("Paragraph", "Bullet Points"))
    private val generationExplanationProviderCombo = ComboBox(ExplanationProvider.entries.map { it.displayName }.toTypedArray())
    
    // Color palette for mapping (should match NaturalEdit)
    private val mappingColors = listOf(
        Color(255, 179, 198, 128), // pink
        Color(185, 251, 192, 128), // green
        Color(255, 214, 165, 128), // orange
        Color(208, 191, 255, 128), // purple
        Color(163, 211, 255, 128), // blue
        Color(255, 218, 193, 128), // peach
        Color(255, 250, 205, 128), // yellow
        Color(224, 187, 228, 128), // lavender
        Color(254, 200, 216, 128), // pastel rose
        Color(199, 206, 234, 128), // periwinkle
        Color(181, 234, 215, 128)  // mint
    )

    fun getContent(): JComponent {
        updateContent()
        return mainPanel
    }

    private fun configureVerticalScroll(scrollPane: JScrollPane) {
        scrollPane.verticalScrollBar.unitIncrement = 24
        scrollPane.verticalScrollBar.blockIncrement = 120
    }

    private fun scrollToComponentTop(component: JComponent) {
        SwingUtilities.invokeLater {
            val scrollPane = SwingUtilities.getAncestorOfClass(JScrollPane::class.java, component) as? JScrollPane
                ?: return@invokeLater
            val viewport = scrollPane.viewport
            val view = viewport.view ?: return@invokeLater
            val boundsInView = SwingUtilities.convertRectangle(component.parent, component.bounds, view)
            viewport.viewPosition = Point(viewport.viewPosition.x, boundsInView.y.coerceAtLeast(0))
        }
    }

    private fun addExtraScrollTail(container: JPanel) {
        val tail = Box.createVerticalStrut(extraScrollTailPx).apply {
            maximumSize = java.awt.Dimension(Int.MAX_VALUE, extraScrollTailPx)
        }
        container.add(tail)
    }

    private fun forwardMouseWheelToParent(child: JScrollPane, parent: JScrollPane) {
        child.addMouseWheelListener { e ->
            val bar = parent.verticalScrollBar
            val delta = e.unitsToScroll * bar.unitIncrement
            val max = bar.maximum - bar.visibleAmount
            bar.value = (bar.value + delta).coerceIn(bar.minimum, max)
            e.consume()
        }
    }
    
    private fun updateContent() {
        mainPanel.removeAll()
        
        val isOpenAIConfigured = openAIService.isConfigured()
        val isJunieConfigured = openAIService.isJunieTokenConfigured()
        
        // Show the main UI if at least one explanation provider is configured.
        if (!isOpenAIConfigured && !isJunieConfigured) {
            // Show panel suggesting configuration
            val setupPanel = panel {
                row {
                    label("AI credentials are not configured")
                }
                row {
                    text("Configure an OpenAI API key or Junie API Key to use explanations.")
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
            // At least one provider is configured - show main interface with tabs.
            val tabbedPane = JTabbedPane()
            
            // Create Summary tab
            val codeSummaryTab = createSummaryTab()
            summaryTabPanel = codeSummaryTab
            tabbedPane.addTab("Code Summary", null, codeSummaryTab, "Generate and view code summaries")
            
            // Create Code Generation tab
            generationTabPanel = createCodeGenerationTab()
            tabbedPane.addTab("Code Generation", null, generationTabPanel, "Generate code with Junie AI")
            
            mainPanel.add(tabbedPane, BorderLayout.CENTER)
            
            // Check if there's a saved summary
            val summaryWithMappings = project.getUserData(GenerateSummaryAction.SUMMARY_WITH_MAPPINGS_KEY)
            val summary = project.getUserData(GenerateSummaryAction.SUMMARY_KEY)
            when {
                summaryWithMappings != null -> {
                    currentSummary = summaryWithMappings.summary
                    currentMappings = summaryWithMappings.mappings
                    currentSummaryFilePath = project.getUserData(GenerateSummaryAction.SUMMARY_FILE_PATH_KEY)
                }
                summary != null -> {
                    currentSummary = summary
                    currentSummaryFilePath = project.getUserData(GenerateSummaryAction.SUMMARY_FILE_PATH_KEY)
                }
            }
            if (currentSummary != null) {
                displayCurrentSummary()
            }
        }
        
        mainPanel.revalidate()
        mainPanel.repaint()
    }
    
    /**
     * Create Summary tab content
     */
    private fun createSummaryTab(): JPanel {
        val tabPanel = JPanel(BorderLayout())
        
        // Control panel for summary
        val controlPanel = panel {
            row {
                label("Code Summary Generator").applyToComponent {
                    font = Font(font.name, Font.BOLD, 14)
                }
            }
            
            row {
                text("Select code in editor and click Generate to create AI-powered summaries")
            }
            
            separator()

            row {
                label("Explanation Provider:")
                cell(explanationProviderCombo).applyToComponent {
                    selectedIndex = settings.explanationProvider.ordinal
                    toolTipText = "Use Junie or legacy OpenAI API calls for summaries and mappings"
                    addActionListener {
                        settings.explanationProvider = selectedExplanationProvider(explanationProviderCombo)
                    }
                }
            }
            
            row {
                label("Model:")
                cell(modelCombo).applyToComponent {
                    // Set current model from settings as default
                    val currentModel = openAIService.getModel()
                    for (i in 0 until itemCount) {
                        val itemModel = getItemAt(i)?.split(" | ")?.firstOrNull()
                        if (itemModel == currentModel) {
                            selectedIndex = i
                            break
                        }
                    }
                    // If current model not in list, select first item
                    if (selectedIndex == -1) {
                        selectedIndex = 0
                    }
                }
            }
            
            row {
                label("Detail Level:")
                cell(detailLevelCombo).applyToComponent {
                    selectedIndex = 1 // Medium by default
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
                    selectedIndex = 0 // Paragraph by default
                    addActionListener {
                        if (currentSummary != null) {
                            displayCurrentSummary()
                        }
                    }
                }
            }
            
            row {
                button("Generate Summary") {
                    generateSummaryFromEditor()
                }.applyToComponent {
                    font = Font(font.name, Font.BOLD, 12)
                }
            }

            row {
                button("Add Explanation Comments") {
                    insertSummaryExplanationComments()
                }.applyToComponent {
                    toolTipText = "Insert high-detail bullet explanations as comments before mapped code chunks"
                    font = Font(font.name, Font.PLAIN, 12)
                }
            }
            
            separator()
            
            row {
                button("Settings") {
                    ShowSettingsUtil.getInstance().showSettingsDialog(
                        project,
                        "Explainable AI"
                        )
                }.applyToComponent {
                    font = Font(font.name, Font.PLAIN, 10)
                }
            }
        }
        
        // Create main scrollable container
        val mainContainer = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
        }
        
        // Add control panel
        controlPanel.alignmentX = JComponent.LEFT_ALIGNMENT
        mainContainer.add(controlPanel)
        
        // Add separator
        mainContainer.add(Box.createVerticalStrut(15))
        mainContainer.add(JSeparator(SwingConstants.HORIZONTAL).apply {
            maximumSize = java.awt.Dimension(Integer.MAX_VALUE, 2)
            alignmentX = JComponent.LEFT_ALIGNMENT
        })
        mainContainer.add(Box.createVerticalStrut(15))
        
        // Container for summary (will be populated by displayCurrentSummary)
        val summaryContainer = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = JComponent.LEFT_ALIGNMENT
        }
        mainContainer.add(summaryContainer)
        
        // Store reference for later use
        summaryContentPanel = summaryContainer
        addExtraScrollTail(mainContainer)
        
        // Wrap in scroll pane
        val scrollPane = JScrollPane(mainContainer).apply {
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            border = null
        }
        configureVerticalScroll(scrollPane)
        
        tabPanel.add(scrollPane, BorderLayout.CENTER)
        return tabPanel
    }
    
    /**
     * Create Code Generation tab content
     */
    private fun createCodeGenerationTab(): JPanel {
        val tabPanel = JPanel(BorderLayout())
        
        // Control panel for code generation
        val controlPanel = panel {
            row {
                label("Junie Code Generation").applyToComponent {
                    font = Font(font.name, Font.BOLD, 14)
                }
            }
            
            row {
                text("Enter a natural language prompt to generate or modify code")
            }
            
            separator()
            
            val promptTextField = JTextField(30)
            row {
                label("Prompt:")
                cell(promptTextField).applyToComponent {
                    toolTipText = "Describe what code you want to generate or modify"
                    }
            }
            
            row {
                button("Generate Code") {
                    generateCodeWithJunie(promptTextField.text)
                }.applyToComponent {
                    font = Font(font.name, Font.BOLD, 12)
                }
            }

            row {
                button("Add Explanation Comments") {
                    insertGenerationExplanationComments()
                }.applyToComponent {
                    toolTipText = "Insert high-detail bullet explanations as comments before changed code chunks"
                    font = Font(font.name, Font.PLAIN, 12)
                }
            }
            
            separator()
            
            row {
                label("Summary Settings for Code Changes").applyToComponent {
                    font = Font(font.name, Font.BOLD, 12)
                }
            }
            
            row {
                text("Configure how changed code will be explained after generation")
            }
            
            separator()

            row {
                label("Explanation Provider:")
                cell(generationExplanationProviderCombo).applyToComponent {
                    selectedIndex = settings.explanationProvider.ordinal
                    toolTipText = "Use Junie or legacy OpenAI API calls for summaries and mappings"
                    addActionListener {
                        settings.explanationProvider = selectedExplanationProvider(generationExplanationProviderCombo)
                    }
                }
            }
            
            row {
                label("Model:")
                cell(generationModelCombo).applyToComponent {
                    // Set current model from settings as default
                    val currentModel = openAIService.getModel()
                    for (i in 0 until itemCount) {
                        val itemModel = getItemAt(i)?.split(" | ")?.firstOrNull()
                        if (itemModel == currentModel) {
                            selectedIndex = i
                            break
                        }
                    }
                    // If current model not in list, select first item
                    if (selectedIndex == -1) {
                        selectedIndex = 0
                    }
                    toolTipText = "AI model to use for generating summaries"
                }
            }
            
            row {
                label("Detail Level:")
                cell(generationDetailLevelCombo).applyToComponent {
                    selectedIndex = 1 // Medium by default
                    toolTipText = "Level of detail for summaries"
                    addActionListener {
                        if (currentChangeSummaries.isNotEmpty()) {
                            displayGenerationSummaries()
                        }
                    }
                }
            }
            
            row {
                label("Format:")
                cell(generationFormatTypeCombo).applyToComponent {
                    selectedIndex = 0 // Paragraph by default
                    toolTipText = "Summary format: paragraph or bullet points"
                    addActionListener {
                        if (currentChangeSummaries.isNotEmpty()) {
                            displayGenerationSummaries()
                        }
                    }
                }
            }
            
            separator()
            
            row {
                button("Settings") {
                    ShowSettingsUtil.getInstance().showSettingsDialog(
                        project,
                        "Explainable AI"
                        )
                }.applyToComponent {
                    font = Font(font.name, Font.PLAIN, 10)
                }
            }
        }
        
        // Create scrollable content area that includes control panel, logs and summaries
        createGenerationContentArea(tabPanel, controlPanel)
        
        return tabPanel
    }
    
    /**
     * Create scrollable content area for control panel, logs and summaries
     */
    private fun createGenerationContentArea(parentPanel: JPanel, controlPanel: JComponent) {
        // Create vertical container for everything (control panel + logs + summaries)
        val contentContainer = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
        }
        
        // Add control panel first
        controlPanel.alignmentX = JComponent.LEFT_ALIGNMENT
        contentContainer.add(controlPanel)
        
        // Add separator
        contentContainer.add(Box.createVerticalStrut(15))
        contentContainer.add(JSeparator(SwingConstants.HORIZONTAL).apply {
            maximumSize = java.awt.Dimension(Integer.MAX_VALUE, 2)
            alignmentX = JComponent.LEFT_ALIGNMENT
        })
        contentContainer.add(Box.createVerticalStrut(15))
        
        // Create log panel
        junieLogPanel = JPanel(BorderLayout()).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Color.LIGHT_GRAY),
                BorderFactory.createEmptyBorder(10, 10, 10, 10)
            )
            maximumSize = java.awt.Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE)
            alignmentX = JComponent.LEFT_ALIGNMENT
        }
        
        val logTitleLabel = JLabel("Generation Log").apply {
            font = Font(font.name, Font.BOLD, 12)
            border = BorderFactory.createEmptyBorder(0, 0, 10, 0)
        }
        
        junieLogTextArea.apply {
            rows = 15
            minimumSize = java.awt.Dimension(400, 200)
        }
        
        val logTextScrollPane = JScrollPane(junieLogTextArea).apply {
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
            border = null
        }
        
        junieLogPanel?.add(logTitleLabel, BorderLayout.NORTH)
        junieLogPanel?.add(logTextScrollPane, BorderLayout.CENTER)
        
        contentContainer.add(junieLogPanel!!)
        
        // Create summary panel (initially hidden, will be added after generation)
        changeSummaryPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(20, 0, 0, 0)
            alignmentX = JComponent.LEFT_ALIGNMENT
            isVisible = false
        }
        
        contentContainer.add(changeSummaryPanel!!)
        addExtraScrollTail(contentContainer)
        
        // Wrap everything in a scroll pane
        val mainScrollPane = JScrollPane(contentContainer).apply {
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            border = null
        }
        configureVerticalScroll(mainScrollPane)
        forwardMouseWheelToParent(logTextScrollPane, mainScrollPane)
        
        parentPanel.add(mainScrollPane, BorderLayout.CENTER)
    }

    private fun selectedExplanationProvider(comboBox: ComboBox<String>): ExplanationProvider {
        val selected = comboBox.selectedItem as? String
        return ExplanationProvider.fromValue(selected)
    }

    private fun selectedModel(comboBox: ComboBox<String>): String {
        return (comboBox.selectedItem as? String)
            ?.substringBefore(" | ")
            ?.takeIf { it.isNotBlank() }
            ?: openAIService.getModel()
    }

    private fun isProviderConfigured(provider: ExplanationProvider): Boolean {
        return when (provider) {
            ExplanationProvider.JUNIE -> openAIService.isJunieTokenConfigured()
            ExplanationProvider.OPENAI_API -> openAIService.isConfigured()
        }
    }

    private fun showProviderConfigurationWarning(provider: ExplanationProvider) {
        when (provider) {
            ExplanationProvider.JUNIE -> openAIService.showJunieConfigurationWarning()
            ExplanationProvider.OPENAI_API -> openAIService.showConfigurationWarning()
        }
    }

    private suspend fun generateCodeSummaryWithProvider(
        provider: ExplanationProvider,
        contentToExplain: String,
        fileContext: String,
        isDiffInput: Boolean = false,
        agentTrace: String? = null,
        model: String? = null,
        onOutputLine: (String) -> Unit = {}
    ): Result<CodeSummary> {
        return when (provider) {
            ExplanationProvider.JUNIE -> junieCliService.generateCodeSummary(
                contentToExplain = contentToExplain,
                fileContext = fileContext,
                isDiffInput = isDiffInput,
                agentTrace = agentTrace,
                onOutputLine = onOutputLine
            )
            ExplanationProvider.OPENAI_API -> openAIService.generateCodeSummary(
                contentToExplain = contentToExplain,
                fileContext = fileContext,
                isDiffInput = isDiffInput,
                agentTrace = agentTrace,
                model = model
            )
        }
    }

    private suspend fun buildSummaryMappingWithProvider(
        provider: ExplanationProvider,
        code: String,
        summaryText: String,
        realStartLine: Int = 1,
        model: String? = null,
        onOutputLine: (String) -> Unit = {}
    ): Result<List<SummaryMapping>> {
        return when (provider) {
            ExplanationProvider.JUNIE -> junieCliService.buildSummaryMapping(
                code = code,
                summaryText = summaryText,
                realStartLine = realStartLine,
                onOutputLine = onOutputLine
            )
            ExplanationProvider.OPENAI_API -> openAIService.buildSummaryMapping(
                code = code,
                summaryText = summaryText,
                realStartLine = realStartLine,
                model = model
            )
        }
    }

    private suspend fun generateCodeSummaryAndMappingsWithProvider(
        provider: ExplanationProvider,
        contentToExplain: String,
        fileContext: String,
        mappingCode: String,
        realStartLine: Int = 1,
        isDiffInput: Boolean = false,
        agentTrace: String? = null,
        model: String? = null,
        onOutputLine: (String) -> Unit = {}
    ): Result<CodeSummaryWithMappings> {
        if (provider == ExplanationProvider.JUNIE) {
            return junieCliService.generateCodeSummaryWithMappings(
                contentToExplain = contentToExplain,
                fileContext = fileContext,
                mappingCode = mappingCode,
                realStartLine = realStartLine,
                isDiffInput = isDiffInput,
                agentTrace = agentTrace,
                onOutputLine = onOutputLine
            )
        }

        val summaryResult = generateCodeSummaryWithProvider(
            provider = provider,
            contentToExplain = contentToExplain,
            fileContext = fileContext,
            isDiffInput = isDiffInput,
            agentTrace = agentTrace,
            model = model,
            onOutputLine = onOutputLine
        )

        val summary = summaryResult.getOrElse { throwable ->
            return Result.failure(throwable)
        }

        val mappingKeys = listOf(
            "low_unstructured" to summary.low_unstructured,
            "low_structured" to summary.low_structured,
            "medium_unstructured" to summary.medium_unstructured,
            "medium_structured" to summary.medium_structured,
            "high_unstructured" to summary.high_unstructured,
            "high_structured" to summary.high_structured
        )

        val mappingResults = mutableMapOf<String, List<SummaryMapping>>()
        mappingKeys.forEach { (key, summaryText) ->
            if (summaryText.isNotEmpty()) {
                val mappingResult = buildSummaryMappingWithProvider(
                    provider = provider,
                    code = mappingCode,
                    summaryText = summaryText,
                    realStartLine = realStartLine,
                    model = model,
                    onOutputLine = onOutputLine
                )
                mappingResult.onSuccess { mapping ->
                    mappingResults[key] = mapping
                }.onFailure { e ->
                    println("[generateCodeSummaryAndMappingsWithProvider] Failed to build mapping for $key: ${e.message}")
                }
            }
        }

        return Result.success(
            CodeSummaryWithMappings(
                summary = summary,
                mappings = SummaryMappings(
                    low_unstructured = mappingResults["low_unstructured"] ?: emptyList(),
                    low_structured = mappingResults["low_structured"] ?: emptyList(),
                    medium_unstructured = mappingResults["medium_unstructured"] ?: emptyList(),
                    medium_structured = mappingResults["medium_structured"] ?: emptyList(),
                    high_unstructured = mappingResults["high_unstructured"] ?: emptyList(),
                    high_structured = mappingResults["high_structured"] ?: emptyList()
                )
            )
        )
    }
    
    private fun generateSummaryFromEditor() {
        // Get current editor
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
        
        // Save original code and position
        originalCode = selectedText
        startLine = editor.selectionModel.selectionStartPosition?.line?.plus(1) ?: 1
        
        // Get full file text for context
        val document = editor.document
        val fileContext = document.text
        val sourceFilePath = FileDocumentManager.getInstance().getFile(document)?.path
        val provider = selectedExplanationProvider(explanationProviderCombo)
        val model = selectedModel(modelCombo)

        if (!isProviderConfigured(provider)) {
            showProviderConfigurationWarning(provider)
            return
        }
        
        // Run generation in background
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Generating Code Summary...", true) {
                var summary: CodeSummary? = null
                var mappings: SummaryMappings? = null
                var error: Throwable? = null
                
                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = false
                    indicator.fraction = 0.0
                    indicator.text = "Generating summary with ${provider.displayName}..."
                    runBlocking {
                        val summaryWithMappingsResult = generateCodeSummaryAndMappingsWithProvider(
                            provider = provider,
                            contentToExplain = selectedText,
                            fileContext = fileContext,
                            mappingCode = selectedText,
                            realStartLine = startLine,
                            model = model
                        )
                        summaryWithMappingsResult.onSuccess {
                            summary = it.summary
                            mappings = it.mappings
                            indicator.fraction = 1.0
                        }.onFailure { 
                            error = it 
                        }
                    }
                }
                
                override fun onSuccess() {
                    summary?.let { summaryData ->
                        currentSummary = summaryData
                        currentMappings = mappings
                        currentSummaryFilePath = sourceFilePath
                        project.putUserData(GenerateSummaryAction.SUMMARY_KEY, summaryData)
                        mappings?.let {
                            project.putUserData(
                                GenerateSummaryAction.SUMMARY_WITH_MAPPINGS_KEY,
                                CodeSummaryWithMappings(summary = summaryData, mappings = it)
                            )
                        }
                        sourceFilePath?.let { project.putUserData(GenerateSummaryAction.SUMMARY_FILE_PATH_KEY, it) }
                        displayCurrentSummary()
                        openAIService.showSuccessNotification("Summary and mappings generated successfully!")
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
        val targetPanel = summaryContentPanel ?: return
        var summaryAnchor: JComponent? = null
        
        // Clear previous content
        targetPanel.removeAll()
        
        // Determine which summary to show based on selected options
        val detailLevel = when (detailLevelCombo.selectedIndex) {
            0 -> "low"
                        1 -> "medium"
                        2 -> "high"
                        else -> "medium"
                    }
        
        val isStructured = formatTypeCombo.selectedIndex == 1
        
        val summaryText = getSummaryText(summary, detailLevel, isStructured)
        val mappingKey = "${detailLevel}_${if (isStructured) "structured" else "unstructured"}"
        val mappings = getMappingsForKey(mappingKey)
        
        // Title
        if (summary.title.isNotEmpty()) {
            targetPanel.add(JLabel(" " + summary.title).apply {
                font = Font(font.name, Font.BOLD, 14)
                border = BorderFactory.createEmptyBorder(0, 0, 10, 0)
                alignmentX = JComponent.LEFT_ALIGNMENT
            }.also { summaryAnchor = it })
        }
        
        // Show selected format
        val formatLabelComponent = JLabel(
            "${detailLevel.replaceFirstChar { it.uppercase() }} Detail - ${if (isStructured) "Bullet Points" else "Paragraph"}"
        ).apply {
            font = Font(font.name, Font.ITALIC, 11)
            foreground = Color.GRAY
            border = BorderFactory.createEmptyBorder(0, 0, 10, 0)
            alignmentX = JComponent.LEFT_ALIGNMENT
        }
        targetPanel.add(formatLabelComponent)
        if (summaryAnchor == null) {
            summaryAnchor = formatLabelComponent
        }
        
        // Display summary with interactive mapping
        if (mappings.isNotEmpty()) {
            val interactivePanel = createInteractiveSummaryPanel(summaryText, mappings, currentSummaryFilePath)
            interactivePanel.alignmentX = JComponent.LEFT_ALIGNMENT
            targetPanel.add(interactivePanel)
        } else {
            // If no mapping, show plain text
            val textArea = JTextArea(summaryText).apply {
                isEditable = false
                lineWrap = true
                wrapStyleWord = true
                background = targetPanel.background
                border = BorderFactory.createEmptyBorder(5, 5, 5, 5)
                font = Font(font.name, Font.PLAIN, 12)
                alignmentX = JComponent.LEFT_ALIGNMENT
            }
            targetPanel.add(textArea)
        }
        
        targetPanel.revalidate()
        targetPanel.repaint()
        summaryAnchor?.let { scrollToComponentTop(it) }
    }
    
    private fun getMappingsForKey(key: String): List<SummaryMapping> {
        val mappings = currentMappings ?: return emptyList()
        return getMappingsForChangeSummary(mappings, key)
    }

    private fun insertSummaryExplanationComments() {
        val filePath = currentSummaryFilePath
        if (filePath == null) {
            JOptionPane.showMessageDialog(
                mainPanel,
                "Generate a summary from an editor file first",
                "Error",
                JOptionPane.WARNING_MESSAGE
            )
            return
        }

        val mappings = currentMappings?.high_structured.orEmpty()
        if (mappings.isEmpty()) {
            JOptionPane.showMessageDialog(
                mainPanel,
                "Generate a summary with mappings first",
                "No High-Detail Bullet Mapping",
                JOptionPane.WARNING_MESSAGE
            )
            return
        }

        val result = explanationCommentInserter.insertHighDetailBulletCommentsWithResult(filePath, mappings)
        currentMappings = currentMappings?.let { shiftSummaryMappings(it, result.lineShifts) }
        currentMappings?.let { shiftedMappings ->
            currentSummary?.let { summary ->
                project.putUserData(
                    GenerateSummaryAction.SUMMARY_WITH_MAPPINGS_KEY,
                    CodeSummaryWithMappings(summary = summary, mappings = shiftedMappings)
                )
            }
        }
        displayCurrentSummary()
        openAIService.showSuccessNotification("Added ${result.insertedCount} AI explanation comment block(s)")
    }

    private fun insertGenerationExplanationComments() {
        if (currentChangeSummaries.isEmpty()) {
            JOptionPane.showMessageDialog(
                mainPanel,
                "Generate code and summaries first",
                "No Generated Explanations",
                JOptionPane.WARNING_MESSAGE
            )
            return
        }

        val shiftsByFile = mutableMapOf<String, List<CommentLineShift>>()
        var insertedCount = 0
        currentChangeSummaries
            .groupBy { it.filePath }
            .forEach { (filePath, summariesForFile) ->
                val mappings = summariesForFile.flatMap { it.mappings.high_structured }
                if (mappings.isNotEmpty()) {
                    val result = explanationCommentInserter.insertHighDetailBulletCommentsWithResult(
                        filePath = filePath,
                        mappings = mappings
                    )
                    insertedCount += result.insertedCount
                    shiftsByFile[filePath] = result.lineShifts
                }
            }

        if (shiftsByFile.isNotEmpty()) {
            currentChangeSummaries = currentChangeSummaries.map { changeSummary ->
                val shifts = shiftsByFile[changeSummary.filePath].orEmpty()
                if (shifts.isEmpty()) {
                    changeSummary
                } else {
                    changeSummary.copy(mappings = shiftSummaryMappings(changeSummary.mappings, shifts))
                }
            }
            displayGenerationSummaries()
        }

        if (insertedCount == 0) {
            JOptionPane.showMessageDialog(
                mainPanel,
                "No high-detail bullet mappings are available for insertion",
                "No High-Detail Bullet Mapping",
                JOptionPane.WARNING_MESSAGE
            )
        } else {
            openAIService.showSuccessNotification("Added $insertedCount AI explanation comment block(s)")
        }
    }

    private fun shiftSummaryMappings(
        mappings: SummaryMappings,
        shifts: List<CommentLineShift>
    ): SummaryMappings {
        if (shifts.isEmpty()) return mappings

        return SummaryMappings(
            low_unstructured = shiftMappings(mappings.low_unstructured, shifts),
            low_structured = shiftMappings(mappings.low_structured, shifts),
            medium_unstructured = shiftMappings(mappings.medium_unstructured, shifts),
            medium_structured = shiftMappings(mappings.medium_structured, shifts),
            high_unstructured = shiftMappings(mappings.high_unstructured, shifts),
            high_structured = shiftMappings(mappings.high_structured, shifts)
        )
    }

    private fun shiftMappings(
        mappings: List<SummaryMapping>,
        shifts: List<CommentLineShift>
    ): List<SummaryMapping> {
        return mappings.map { mapping ->
            mapping.copy(
                codeSegments = mapping.codeSegments.map { segment ->
                    segment.copy(line = shiftedLine(segment.line, shifts))
                }
            )
        }
    }

    private fun shiftedLine(line: Int, shifts: List<CommentLineShift>): Int {
        return line + shifts
            .filter { shift -> shift.originalLine <= line }
            .sumOf { it.addedLines }
    }
    
    private fun getMappingsForChangeSummary(mappings: SummaryMappings, key: String): List<SummaryMapping> {
        return when (key) {
            "low_unstructured" -> mappings.low_unstructured
            "low_structured" -> mappings.low_structured
            "medium_unstructured" -> mappings.medium_unstructured
            "medium_structured" -> mappings.medium_structured
            "high_unstructured" -> mappings.high_unstructured
            "high_structured" -> mappings.high_structured
            else -> emptyList()
        }
    }
    
    private fun createInteractiveSummaryPanel(
        summaryText: String,
        mappings: List<SummaryMapping>,
        filePath: String? = null
    ): JPanel {
        println("[createInteractiveSummaryPanel] Creating panel with ${mappings.size} mappings")
        mappings.forEachIndexed { idx, mapping ->
            println("Mapping $idx: '${mapping.explanationComponent}' -> ${mapping.codeSegments.size} segments")
            mapping.codeSegments.forEach { seg ->
                println(" - Line ${seg.line}: '${seg.code}'")
            }
        }

        data class MappingRange(
            val start: Int,
            val endExclusive: Int,
            val mapping: SummaryMapping,
            val color: Color
        )

        val textArea = JTextArea().apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            text = summaryText
            font = Font(font.name, Font.PLAIN, 12)
            border = BorderFactory.createEmptyBorder(5, 5, 5, 5)
            cursor = Cursor.getDefaultCursor()
            alignmentX = JComponent.LEFT_ALIGNMENT
            maximumSize = java.awt.Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
            columns = 1
            isOpaque = false
        }.apply {
            highlighter.removeAllHighlights()
        }

        val mappingRanges = mutableListOf<MappingRange>()
        var currentIndex = 0
        val sortedMappings = mappings.sortedBy { summaryText.indexOf(it.explanationComponent, currentIndex) }

        sortedMappings.forEachIndexed { index, mapping ->
            val componentStart = summaryText.indexOf(mapping.explanationComponent, currentIndex)
            if (componentStart == -1) {
                println("[createInteractiveSummaryPanel] WARNING: Component not found: '${mapping.explanationComponent}'")
                return@forEachIndexed
            }

            val labelColor = mappingColors[index % mappingColors.size].let { Color(it.red, it.green, it.blue) }
            val painter = DefaultHighlighter.DefaultHighlightPainter(labelColor)
            textArea.highlighter.addHighlight(
                componentStart,
                componentStart + mapping.explanationComponent.length,
                painter
            )

            mappingRanges.add(
                MappingRange(
                    start = componentStart,
                    endExclusive = componentStart + mapping.explanationComponent.length,
                    mapping = mapping,
                    color = labelColor
                )
            )

            currentIndex = componentStart + mapping.explanationComponent.length
        }

        val interactionHandler = object : MouseAdapter() {
            private fun findMappingAtPosition(position: Int): MappingRange? {
                return mappingRanges.firstOrNull { position >= it.start && position < it.endExclusive }
            }

            override fun mouseClicked(e: MouseEvent) {
                val position = textArea.viewToModel2D(e.point)
                val hit = findMappingAtPosition(position) ?: return
                println("[MouseClick] Clicked on: '${hit.mapping.explanationComponent}'")
                println("[MouseClick] Code segments: ${hit.mapping.codeSegments.size}")
                highlightCodeInEditor(hit.mapping.codeSegments, hit.color, filePath)
            }

            override fun mouseMoved(e: MouseEvent) {
                val position = textArea.viewToModel2D(e.point)
                val isMapping = findMappingAtPosition(position) != null
                textArea.cursor = Cursor.getPredefinedCursor(
                    if (isMapping) Cursor.HAND_CURSOR else Cursor.DEFAULT_CURSOR
                )
            }
        }

        textArea.addMouseListener(interactionHandler)
        textArea.addMouseMotionListener(interactionHandler)

        val panel = JPanel(BorderLayout()).apply {
            isOpaque = false
            alignmentX = JComponent.LEFT_ALIGNMENT
            maximumSize = java.awt.Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
            add(textArea, BorderLayout.CENTER)
        }

        println("[createInteractiveSummaryPanel] Panel created successfully")
        return panel
    }
    
    private fun highlightCodeInEditor(
        codeSegments: List<com.example.explainableaiplugin.services.CodeSegment>,
        color: Color,
        filePath: String? = null
    ) {
        val editor = editorForHighlight(filePath)
        if (editor == null) {
            println("[highlightCodeInEditor] No active editor")
            return
        }
        
        println("[highlightCodeInEditor] Highlighting ${codeSegments.size} segments with color $color")
        codeSegments.forEach { segment ->
            println(" - Line ${segment.line}: '${segment.code}'")
        }
        
        val markupModel = editor.markupModel
        
        // Remove all previous highlights with our special layer
        val toRemove = markupModel.allHighlighters.filter { 
            it.layer == HighlighterLayer.SELECTION + 1
        }
        toRemove.forEach { markupModel.removeHighlighter(it) }
        println("[highlightCodeInEditor] Removed ${toRemove.size} old highlights")
        
        // Add new highlights
        val textAttributes = TextAttributes().apply {
            backgroundColor = color
        }
        
        var highlightCount = 0
        codeSegments.forEach { segment ->
            // line in segment is already absolute (1-based), convert to 0-based
            val lineNumber = segment.line - 1
            println("[highlightCodeInEditor] Processing segment at line ${segment.line} (0-based: $lineNumber)")
            
            val range = findCodeSegmentRange(editor.document, lineNumber, segment.code)
            if (range != null) {
                markupModel.addRangeHighlighter(
                    range.first,
                    range.second,
                    HighlighterLayer.SELECTION + 1,
                    textAttributes,
                    HighlighterTargetArea.EXACT_RANGE
                )
                highlightCount++
                println("[highlightCodeInEditor] Added highlight at ${range.first}-${range.second}")
            } else {
                println("[highlightCodeInEditor] WARNING: Could not find segment in document!")
            }
        }
        
        println("[highlightCodeInEditor] Total highlights added: $highlightCount")
        
        // Scroll to first segment
        if (codeSegments.isNotEmpty()) {
            val firstLine = codeSegments.first().line - 1
            if (firstLine >= 0 && firstLine < editor.document.lineCount) {
                val offset = editor.document.getLineStartOffset(firstLine)
                editor.caretModel.moveToOffset(offset)
                editor.scrollingModel.scrollToCaret(com.intellij.openapi.editor.ScrollType.CENTER)
                println("[highlightCodeInEditor] Scrolled to line ${firstLine + 1}")
            }
        }
    }

    private fun editorForHighlight(filePath: String?): com.intellij.openapi.editor.Editor? {
        if (filePath.isNullOrBlank()) {
            return FileEditorManager.getInstance(project).selectedTextEditor
        }

        val file = LocalFileSystem.getInstance().findFileByPath(filePath)
            ?: return FileEditorManager.getInstance(project).selectedTextEditor

        return FileEditorManager.getInstance(project).openTextEditor(
            OpenFileDescriptor(project, file),
            true
        )
    }

    private fun findCodeSegmentRange(
        document: com.intellij.openapi.editor.Document,
        preferredLineIndex: Int,
        code: String
    ): Pair<Int, Int>? {
        val candidates = listOf(code, code.trim()).distinct().filter { it.isNotEmpty() }
        val preferredLines = buildList {
            if (preferredLineIndex in 0 until document.lineCount) add(preferredLineIndex)
            val windowStart = (preferredLineIndex - 5).coerceAtLeast(0)
            val windowEnd = (preferredLineIndex + 5).coerceAtMost(document.lineCount - 1)
            for (line in windowStart..windowEnd) {
                if (line !in this) add(line)
            }
        }

        preferredLines.forEach { line ->
            val lineStartOffset = document.getLineStartOffset(line)
            val lineEndOffset = document.getLineEndOffset(line)
            val lineText = document.getText(com.intellij.openapi.util.TextRange(lineStartOffset, lineEndOffset))
            candidates.forEach { candidate ->
                val index = lineText.indexOf(candidate)
                if (index != -1) {
                    val startOffset = lineStartOffset + index
                    return startOffset to startOffset + candidate.length
                }
            }
        }

        val fullText = document.text
        candidates.forEach { candidate ->
            val index = fullText.indexOf(candidate)
            if (index != -1) {
                return index to index + candidate.length
            }
        }

        return null
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
    
    /**
     * Remove ANSI color codes from string
     */
    private fun stripAnsiCodes(text: String): String {
        // Pattern for ANSI escape codes: ESC[...m or just [...m
        return text.replace(Regex("\u001b\\[[0-9;]*m"), "")
                   .replace(Regex("\\x1b\\[[0-9;]*m"), "")
                   .replace(Regex("\\[([0-9]{1,3}(;[0-9]{1,3})*)?m"), "")
    }

    private fun buildDiffContext(segment: com.example.explainableaiplugin.services.ChangedSegment): String {
        val oldCode = segment.oldCode.trimEnd()
        val newCode = segment.newCode.trimEnd()
        return buildString {
            appendLine("Change Type: ${segment.changeType}")
            appendLine("Line Range: ${segment.startLine}-${segment.endLine}")
            appendLine("--- Old Code ---")
            appendLine(if (oldCode.isNotEmpty()) oldCode else "[empty]")
            appendLine("+++ New Code +++")
            appendLine(if (newCode.isNotEmpty()) newCode else "[empty]")
        }.trim()
    }

    private fun buildAgentTraceContext(): String {
        val pluginLogPrefixes = listOf(
            "Capturing snapshot of open files...",
            "Captured snapshot of",
            "Starting Junie code generation...",
            "Starting Junie CLI...",
            "Working directory:",
            "Prompt:",
            "Process completed with exit code:",
            "Code generation completed successfully!",
            "Detecting code changes...",
            "Waiting for file system to sync...",
            "Refreshing virtual file system...",
            "Reloading ",
            "Analyzing changes...",
            "Found ",
            "No changes detected",
            "Using model:",
            "Detail level:",
            "Processing ",
            "Summary generated",
            "Building mappings",
            "Mappings built",
            "Summary with trace failed",
            "Failed:",
            "Generated ",
            "You can now change Detail Level",
            "Error:"
        )

        return junieLogTextArea.text
            .lineSequence()
            .map { stripAnsiCodes(it).trimEnd() }
            .filterNot { line ->
                val trimmedLine = line.trim()
                trimmedLine.isEmpty() ||
                    trimmedLine.matches(Regex("[-─]{10,}")) ||
                    trimmedLine.startsWith("• Lines ") ||
                    trimmedLine.startsWith("Lines ") ||
                    pluginLogPrefixes.any { prefix -> trimmedLine.startsWith(prefix) }
            }
            .joinToString("\n")
            .trim()
    }
    
    /**
     * Generate code using Junie CLI
     */
    private fun generateCodeWithJunie(prompt: String) {
        // Validate prompt
        if (prompt.isBlank()) {
            JOptionPane.showMessageDialog(
                mainPanel,
                "Please enter a prompt for code generation",
                "Empty Prompt",
                JOptionPane.WARNING_MESSAGE
            )
            return
        }
        
        // Check if Junie token is configured
        if (!openAIService.isJunieTokenConfigured()) {
            openAIService.showJunieConfigurationWarning()
            return
        }
        
        // Clear previous logs
        showJunieLogPanel()
        
        SwingUtilities.invokeLater {
            junieLogTextArea.append("Capturing snapshot of open files...\n")
        }
        
        // Capture snapshot of current files before generation
        codeChangeDetector.captureSnapshot()
        
        // Get count of captured files
        val fileEditorManager = FileEditorManager.getInstance(project)
        val openFileCount = fileEditorManager.openFiles.size
        
        SwingUtilities.invokeLater {
            junieLogTextArea.append("Captured snapshot of $openFileCount file(s)\n")
            junieLogTextArea.append("Starting Junie code generation...\n\n")
        }
        
        // Run generation in background
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Generating Code with Junie...", true) {
                var result: Result<String>? = null
                
                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true
                    indicator.text = "Executing Junie CLI..."
                    runBlocking {
                        result = junieCliService.generateCode(prompt) { line ->
                            // Update UI in real-time with each output line (strip ANSI codes)
                            SwingUtilities.invokeLater {
                                val cleanLine = stripAnsiCodes(line)
                                junieLogTextArea.append(cleanLine + "\n")
                                // Auto-scroll to bottom
                                junieLogTextArea.caretPosition = junieLogTextArea.document.length
                            }
                        }
                    }
                }
                
                override fun onSuccess() {
                    result?.onSuccess { message ->
                        SwingUtilities.invokeLater {
                            junieLogTextArea.append("\n $message\n")
                            junieLogTextArea.append("\n Detecting code changes...\n")
                        }
                        openAIService.showSuccessNotification(message)
                        
                        // Detect changes and generate summaries
                        processCodeChanges()
                    }?.onFailure { error ->
                        SwingUtilities.invokeLater {
                            junieLogTextArea.append("\n Error: ${error.message}\n")
                        }
                        openAIService.showErrorNotification("Failed to generate code: ${error.message}")
                    }
                }
                
                override fun onThrowable(error: Throwable) {
                    SwingUtilities.invokeLater {
                        junieLogTextArea.append("\n Error: ${error.message}\n")
                    }
                    openAIService.showErrorNotification("Failed to generate code: ${error.message}")
                }
                
                override fun onFinished() {
                    result?.onFailure { error ->
                        SwingUtilities.invokeLater {
                            junieLogTextArea.append("\n Error: ${error.message}\n")
                        }
                        openAIService.showErrorNotification("Failed to generate code: ${error.message}")
                    }
                }
            }
        )
    }
    
    /**
     * Detect code changes after generation and generate summaries
     */
    private fun processCodeChanges() {
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Analyzing Code Changes...", true) {
                var fileChanges: List<FileChange> = emptyList()
                val changeSummaries = mutableListOf<ChangeSummaryResult>()
                
                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = false
                    indicator.fraction = 0.0
                    
                    SwingUtilities.invokeLater {
                        junieLogTextArea.append("Waiting for file system to sync...\n")
                    }
                    
                    // Wait for Junie to finish writing files
                    Thread.sleep(2000)
                    
                    // Reload documents and refresh VFS to get latest changes from disk
                    com.intellij.openapi.application.ApplicationManager.getApplication().invokeAndWait {
                        val fileDocumentManager = com.intellij.openapi.fileEditor.FileDocumentManager.getInstance()
                        val fileEditorManager = com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project)
                        val vfs = com.intellij.openapi.vfs.VirtualFileManager.getInstance()
                        
                        SwingUtilities.invokeLater {
                            junieLogTextArea.append("Refreshing virtual file system...\n")
                        }
                        
                        // Refresh VFS to pick up file system changes
                        vfs.syncRefresh()
                        
                        // Reload all open files from disk
                        val openFiles = fileEditorManager.openFiles
                        SwingUtilities.invokeLater {
                            junieLogTextArea.append("Reloading ${openFiles.size} open files from disk...\n")
                        }
                        
                        openFiles.forEach { virtualFile ->
                            // Refresh individual file
                            virtualFile.refresh(false, false)
                            
                            // Reload document from disk
                            val document = fileDocumentManager.getDocument(virtualFile)
                            if (document != null) {
                                fileDocumentManager.reloadFromDisk(document)
                                println("[processCodeChanges] Reloaded: ${virtualFile.path}")
                            }
                        }
                    }
                    
                    SwingUtilities.invokeLater {
                        junieLogTextArea.append("Analyzing changes...\n")
                    }
                    
                    // Small delay to ensure documents are fully reloaded
                    Thread.sleep(500)
                    
                    // Detect changes
                    fileChanges = codeChangeDetector.detectChanges()
                    
                    SwingUtilities.invokeLater {
                        junieLogTextArea.append("Found ${fileChanges.size} file(s) with changes\n")
                    }
                    
                    if (fileChanges.isEmpty()) {
                        SwingUtilities.invokeLater {
                            junieLogTextArea.append("No changes detected\n")
                        }
                        return
                    }
                    
                    // Get detail level and format from generation tab
                    val detailLevel = when (generationDetailLevelCombo.selectedIndex) {
                        0 -> "low"
                        1 -> "medium"
                        2 -> "high"
                        else -> "medium"
                    }
                    val isStructured = generationFormatTypeCombo.selectedIndex == 1
                    val provider = selectedExplanationProvider(generationExplanationProviderCombo)
                    val model = selectedModel(generationModelCombo)
                    if (!isProviderConfigured(provider)) {
                        SwingUtilities.invokeLater {
                            junieLogTextArea.append("${provider.displayName} is not configured for summaries\n")
                        }
                        showProviderConfigurationWarning(provider)
                        return
                    }
                    SwingUtilities.invokeLater {
                        junieLogTextArea.append("Using ${provider.displayName} for summaries and mappings\n")
                        junieLogTextArea.append("Detail level: ${detailLevel.replaceFirstChar { it.uppercase() }}, Format: ${if (isStructured) "Bullet Points" else "Paragraph"}\n")
                    }
                    
                    // Generate summaries for each changed segment
                    var processedSegments = 0
                    val totalSegments = fileChanges.sumOf { it.changedSegments.size }
                    val agentTraceContext = buildAgentTraceContext()
                    
                    fileChanges.forEach { fileChange ->
                        SwingUtilities.invokeLater {
                            junieLogTextArea.append("\n Processing ${fileChange.filePath.substringAfterLast("/")}\n")
                        }
                        
                        fileChange.changedSegments.forEach { segment ->
                            indicator.fraction = processedSegments.toDouble() / totalSegments
                            indicator.text = "Generating summary ${processedSegments + 1}/$totalSegments with ${provider.displayName}..."
                            runBlocking {
                                // Process any segment that has actual content in old/new state
                                val hasContentToSummarize =
                                    segment.newCode.trim().isNotEmpty() || segment.oldCode.trim().isNotEmpty()
                                if (hasContentToSummarize) {
                                    
                                    SwingUtilities.invokeLater {
                                        junieLogTextArea.append(" • Lines ${segment.startLine}-${segment.endLine}: Generating summary...\n")
                                    }
                                    
                                    // Generate summary for this segment
                                    val diffContext = buildDiffContext(segment)
                                    val fileContext = buildString {
                                        appendLine("File: ${fileChange.filePath}")
                                        appendLine("Changed lines: ${segment.startLine}-${segment.endLine}")
                                        appendLine()
                                        append(segment.newCode)
                                    }.trim()
                                    val mappingSourceCode = segment.newCode.trim().ifEmpty { segment.oldCode.trim() }
                                    val mappingStartLine = segment.startLine

                                    val summaryWithMappingsResult = generateCodeSummaryAndMappingsWithProvider(
                                        provider = provider,
                                        contentToExplain = diffContext,
                                        fileContext = fileContext,
                                        mappingCode = mappingSourceCode,
                                        realStartLine = mappingStartLine,
                                        isDiffInput = true,
                                        agentTrace = agentTraceContext,
                                        model = model,
                                        onOutputLine = { line ->
                                            SwingUtilities.invokeLater {
                                                junieLogTextArea.append(stripAnsiCodes(line) + "\n")
                                                junieLogTextArea.caretPosition = junieLogTextArea.document.length
                                            }
                                        }
                                    )
                                    
                                    summaryWithMappingsResult.onSuccess { summaryWithMappings ->
                                        val summary = summaryWithMappings.summary
                                        SwingUtilities.invokeLater {
                                            junieLogTextArea.append("Summary generated via ${provider.displayName}\n")
                                            junieLogTextArea.append("Mappings generated for all formats\n")
                                        }
                                        
                                        changeSummaries.add(ChangeSummaryResult(
                                            filePath = fileChange.filePath,
                                            startLine = segment.startLine,
                                            endLine = segment.endLine,
                                            code = diffContext,
                                            summary = summary,
                                            mappings = summaryWithMappings.mappings,
                                            detailLevel = detailLevel,
                                            isStructured = isStructured
                                        ))
                                    }.onFailure { e ->
                                        SwingUtilities.invokeLater {
                                            junieLogTextArea.append("Failed: ${e.message}\n")
                                        }
                                    }
                                } else {
                                    SwingUtilities.invokeLater {
                                        junieLogTextArea.append(" • Lines ${segment.startLine}-${segment.endLine}: Skipped empty change segment\n")
                                    }
                                }
                            }
                            
                            processedSegments++
                        }
                    }
                    
                    indicator.fraction = 1.0
                }
                
                override fun onSuccess() {
                    SwingUtilities.invokeLater {
                        junieLogTextArea.append("\n Generated ${changeSummaries.size} summaries\n")
                        junieLogTextArea.append("You can now change Detail Level or Format above to view different summaries\n")
                    }
                    
                    if (changeSummaries.isNotEmpty()) {
                        // Save summaries for interactive viewing
                        currentChangeSummaries = changeSummaries
                        
                        // Display summaries in UI
                        displayChangeSummaries(changeSummaries)
                        openAIService.showSuccessNotification("Code changes analyzed successfully!")
                    }
                    
                    // Clear snapshots
                    codeChangeDetector.clearSnapshots()
                }
                
                override fun onThrowable(error: Throwable) {
                    SwingUtilities.invokeLater {
                        junieLogTextArea.append("\n Error processing changes: ${error.message}\n")
                    }
                    codeChangeDetector.clearSnapshots()
                }
            }
        )
    }
    
    /**
     * Refresh display with current settings
     */
    private fun displayGenerationSummaries() {
        if (currentChangeSummaries.isNotEmpty()) {
            displayChangeSummaries(currentChangeSummaries)
        }
    }
    
    /**
     * Display summaries for code changes below the logs
     */
    private fun displayChangeSummaries(summaries: List<ChangeSummaryResult>) {
        val targetPanel = changeSummaryPanel ?: return
        
        // Clear the summary panel
        targetPanel.removeAll()
        
        // Add separator line
        targetPanel.add(JSeparator(SwingConstants.HORIZONTAL).apply {
            maximumSize = java.awt.Dimension(Integer.MAX_VALUE, 2)
            border = BorderFactory.createEmptyBorder(10, 0, 10, 0)
            alignmentX = JComponent.LEFT_ALIGNMENT
        })
        
        // Get current settings from comboboxes
        val currentDetailLevel = when (generationDetailLevelCombo.selectedIndex) {
            0 -> "low"
                        1 -> "medium"
                        2 -> "high"
                        else -> "medium"
                    }
        val currentIsStructured = generationFormatTypeCombo.selectedIndex == 1
        
        // Title with current settings
        val formatInfo = "${currentDetailLevel.replaceFirstChar { it.uppercase() }} Detail - ${if (currentIsStructured) "Bullet Points" else "Paragraph"}"
        val titleLabel = JLabel("Code Changes Summary").apply {
            font = Font(font.name, Font.BOLD, 14)
            border = BorderFactory.createEmptyBorder(10, 0, 5, 0)
            alignmentX = JComponent.LEFT_ALIGNMENT
        }
        targetPanel.add(titleLabel)
        
        // Format info
        val formatLabel = JLabel("Current view: $formatInfo").apply {
            font = Font(font.name, Font.ITALIC, 11)
            foreground = JBColor(Color(120, 120, 120), Color(150, 150, 150))
            border = BorderFactory.createEmptyBorder(0, 0, 15, 0)
            alignmentX = JComponent.LEFT_ALIGNMENT
        }
        targetPanel.add(formatLabel)
        
        // Display each change summary
        summaries.forEachIndexed { index, changeSummary ->
            val changePanel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(JBColor(Color(210, 210, 210), Color(100, 100, 100))),
                    BorderFactory.createEmptyBorder(10, 10, 10, 10)
                )
                background = JBColor(Color(245, 247, 249), Color(60, 63, 65))
                alignmentX = JComponent.LEFT_ALIGNMENT
                maximumSize = java.awt.Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE)
            }
            
            // File info
            val fileName = changeSummary.filePath.substringAfterLast("/")
            changePanel.add(JLabel(" $fileName").apply {
                font = Font(font.name, Font.BOLD, 12)
                foreground = JBColor(Color(100, 60, 180), Color(187, 134, 252))
                border = BorderFactory.createEmptyBorder(0, 0, 5, 0)
            })
            
            // Line range
            changePanel.add(JLabel("Lines ${changeSummary.startLine}-${changeSummary.endLine}").apply {
                font = Font(font.name, Font.ITALIC, 10)
                foreground = Color(128, 128, 128)
                border = BorderFactory.createEmptyBorder(0, 0, 10, 0)
            })
            
            // Summary title
            if (changeSummary.summary.title.isNotEmpty()) {
                changePanel.add(JLabel(changeSummary.summary.title).apply {
                    font = Font(font.name, Font.BOLD, 12)
                    foreground = JBColor(Color(30, 30, 30), Color.WHITE)
                    border = BorderFactory.createEmptyBorder(0, 0, 8, 0)
                })
            }
            
            // Summary text with interactive mappings using current settings
            val summaryText = getSummaryText(changeSummary.summary, currentDetailLevel, currentIsStructured)
            val mappingKey = "${currentDetailLevel}_${if (currentIsStructured) "structured" else "unstructured"}"
            val mappings = getMappingsForChangeSummary(changeSummary.mappings, mappingKey)
            
            if (mappings.isNotEmpty()) {
                val interactivePanel = createInteractiveSummaryPanel(summaryText, mappings, changeSummary.filePath)
                interactivePanel.background = JBColor(Color(245, 247, 249), Color(60, 63, 65))
                changePanel.add(interactivePanel)
            } else {
                changePanel.add(JLabel(summaryText).apply {
                    font = Font(font.name, Font.PLAIN, 11)
                    foreground = JBColor(Color(80, 80, 80), Color(200, 200, 200))
                    border = BorderFactory.createEmptyBorder(0, 0, 10, 0)
                })
            }
            
            targetPanel.add(changePanel)
            
            // Add spacing between changes
            if (index < summaries.size - 1) {
                targetPanel.add(Box.createVerticalStrut(15))
            }
        }
        
        // Make panel visible and revalidate
        targetPanel.isVisible = true
        targetPanel.revalidate()
        targetPanel.repaint()
        
        scrollToComponentTop(titleLabel)
    }
    
    /**
     * Prepare log panel for new generation (clear previous logs and summaries)
     */
    private fun showJunieLogPanel() {
        SwingUtilities.invokeLater {
            // Clear logs
            junieLogTextArea.text = "" // Clear stored summaries
            currentChangeSummaries = emptyList()
            
            // Hide and clear previous summaries
            changeSummaryPanel?.isVisible = false
            changeSummaryPanel?.removeAll()
            
            // Scroll to top
            val scrollPane = SwingUtilities.getAncestorOfClass(JScrollPane::class.java, junieLogPanel) as? JScrollPane
            scrollPane?.let {
                it.verticalScrollBar.value = 0
            }
        }
    }
}

/**
 * Result of summary generation for a code change
 */
data class ChangeSummaryResult(
    val filePath: String,
    val startLine: Int,
    val endLine: Int,
    val code: String,
    val summary: CodeSummary,
    val mappings: SummaryMappings,
    val detailLevel: String = "medium",
    val isStructured: Boolean = false
)
