package com.example.explainableaiplugin

import com.example.explainableaiplugin.actions.GenerateSummaryAction
import com.example.explainableaiplugin.services.CodeSummary
import com.example.explainableaiplugin.services.OpenAIService
import com.example.explainableaiplugin.services.JunieCliService
import com.example.explainableaiplugin.services.CodeChangeDetector
import com.example.explainableaiplugin.services.FileChange
import com.example.explainableaiplugin.services.SummaryMappings
import com.example.explainableaiplugin.services.SummaryMapping
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.JBColor
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.dsl.builder.panel
import kotlinx.coroutines.runBlocking
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
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
    private val junieCliService = JunieCliService.getInstance(project)
    private val codeChangeDetector = CodeChangeDetector.getInstance(project)
    private val mainPanel = JPanel(BorderLayout())
    private var summaryPanel: JPanel? = null
    private var junieLogPanel: JPanel? = null
    private var changeSummaryPanel: JPanel? = null
    private var summaryTabPanel: JPanel? = null
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
    private var originalCode: String? = null
    private var startLine: Int = 1
    private val extraScrollTailPx = 900
    
    // Store all generated change summaries for interactive viewing
    private var currentChangeSummaries: List<ChangeSummaryResult> = emptyList()
    
    // Comboboxes for Code Summary tab
    private val detailLevelCombo = ComboBox(arrayOf("Low Detail", "Medium Detail", "High Detail"))
    private val formatTypeCombo = ComboBox(arrayOf("Paragraph", "Bullet Points"))
    
    // Model pricing data
    private val modelPricing = mapOf(
        "gpt-4.1" to "$2.00",
        "gpt-4.1-mini" to "$0.40",
        "gpt-4.1-nano" to "$0.10",
        "gpt-4o" to "$2.50",
        "gpt-4o-mini" to "$0.15"
    )
    
    // Combobox for model selection with prices (Code Summary tab)
    private val modelCombo = ComboBox(modelPricing.map { (model, price) -> "$model | $price" }.toTypedArray())
    
    // Comboboxes for Code Generation tab
    private val generationModelCombo = ComboBox(modelPricing.map { (model, price) -> "$model | $price" }.toTypedArray())
    private val generationDetailLevelCombo = ComboBox(arrayOf("Low Detail", "Medium Detail", "High Detail"))
    private val generationFormatTypeCombo = ComboBox(arrayOf("Paragraph", "Bullet Points"))
    
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
        
        // Check if both API key and Junie token are configured
        if (!isOpenAIConfigured || !isJunieConfigured) {
            // Show panel suggesting configuration
            val setupPanel = panel {
                if (!isOpenAIConfigured) {
                    row {
                        label("OpenAI API key is not configured")
                    }
                    row {
                        text("To use AI features, please configure your OpenAI API key.")
                    }
                }
                
                if (!isJunieConfigured) {
                    row {
                        label("Junie API Key is not configured")
                    }
                    row {
                        text("To use Junie features, please configure your Junie API Key.")
                    }
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
            // Both credentials configured - show main interface with tabs
            val tabbedPane = JTabbedPane()
            
            // Create Summary tab
            summaryTabPanel = createSummaryTab()
            tabbedPane.addTab("Code Summary", null, summaryTabPanel, "Generate and view code summaries")
            
            // Create Code Generation tab
            generationTabPanel = createCodeGenerationTab()
            tabbedPane.addTab("Code Generation", null, generationTabPanel, "Generate code with Junie AI")
            
            mainPanel.add(tabbedPane, BorderLayout.CENTER)
            
            // Check if there's a saved summary
            val summary = project.getUserData(GenerateSummaryAction.SUMMARY_KEY)
            if (summary != null) {
                currentSummary = summary
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
        summaryTabPanel = summaryContainer
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
        
        // Get selected model (extract model name from "model | price"format)
        val selectedModelWithPrice = modelCombo.selectedItem as? String
        val selectedModel = selectedModelWithPrice?.split(" | ")?.firstOrNull()?.trim() ?: openAIService.getModel()
        
        // Run generation in background
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Generating Code Summary...", true) {
                var summary: CodeSummary? = null
                var mappings: SummaryMappings? = null
                var error: Throwable? = null
                
                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = false
                    indicator.fraction = 0.0
                    indicator.text = "Generating summary with $selectedModel..."
                    runBlocking {
                        // Stage 1: Generate summary
                        val summaryResult = openAIService.generateCodeSummary(selectedText, fileContext, selectedModel)
                        summaryResult.onSuccess { 
                            summary = it
                            indicator.fraction = 0.3
                            indicator.text = "Building mappings..."
                            // Stage 2: Build mappings for all 6 summary types
                            val mappingKeys = listOf(
                                "low_unstructured" to it.low_unstructured,
                                "low_structured" to it.low_structured,
                                "medium_unstructured" to it.medium_unstructured,
                                "medium_structured" to it.medium_structured,
                                "high_unstructured" to it.high_unstructured,
                                "high_structured" to it.high_structured
                            )
                            
                            val mappingResults = mutableMapOf<String, List<SummaryMapping>>()
                            mappingKeys.forEachIndexed { index, (key, summaryText) ->
                                if (summaryText.isNotEmpty()) {
                                    val mappingResult = openAIService.buildSummaryMapping(
                                        selectedText, 
                                        summaryText, 
                                        startLine,
                                        selectedModel
                                    )
                                    mappingResult.onSuccess { mapping ->
                                        mappingResults[key] = mapping
                                    }.onFailure { e ->
                                        println("[MyToolWindow] Failed to build mapping for $key: ${e.message}")
                                    }
                                }
                                indicator.fraction = 0.3 + (0.7 * (index + 1) / mappingKeys.size)
                            }
                            
                            // Create SummaryMappings object
                            mappings = SummaryMappings(
                                low_unstructured = mappingResults["low_unstructured"] ?: emptyList(),
                                low_structured = mappingResults["low_structured"] ?: emptyList(),
                                medium_unstructured = mappingResults["medium_unstructured"] ?: emptyList(),
                                medium_structured = mappingResults["medium_structured"] ?: emptyList(),
                                high_unstructured = mappingResults["high_unstructured"] ?: emptyList(),
                                high_structured = mappingResults["high_structured"] ?: emptyList()
                            )
                        }.onFailure { 
                            error = it 
                        }
                    }
                }
                
                override fun onSuccess() {
                    summary?.let { summaryData ->
                        currentSummary = summaryData
                        currentMappings = mappings
                        project.putUserData(GenerateSummaryAction.SUMMARY_KEY, summaryData)
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
        val targetPanel = summaryTabPanel ?: return
        
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
            })
        }
        
        // Show selected format
        val formatLabel = "${detailLevel.replaceFirstChar { it.uppercase() }} Detail - ${if (isStructured) "Bullet Points" else "Paragraph"}"
        targetPanel.add(JLabel(formatLabel).apply {
            font = Font(font.name, Font.ITALIC, 11)
            foreground = Color.GRAY
            border = BorderFactory.createEmptyBorder(0, 0, 10, 0)
            alignmentX = JComponent.LEFT_ALIGNMENT
        })
        
        // Display summary with interactive mapping
        if (mappings.isNotEmpty()) {
            val interactivePanel = createInteractiveSummaryPanel(summaryText, mappings)
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
    }
    
    private fun getMappingsForKey(key: String): List<SummaryMapping> {
        val mappings = currentMappings ?: return emptyList()
        return getMappingsForChangeSummary(mappings, key)
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
    
    private fun createInteractiveSummaryPanel(summaryText: String, mappings: List<SummaryMapping>): JPanel {
        println("[createInteractiveSummaryPanel] Creating panel with ${mappings.size} mappings")
        mappings.forEachIndexed { idx, mapping ->
            println("Mapping $idx: '${mapping.explanationComponent}' -> ${mapping.codeSegments.size} segments")
            mapping.codeSegments.forEach { seg ->
                println(" - Line ${seg.line}: '${seg.code}'")
            }
        }
        
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(5, 5, 5, 5)
        }
        
        var currentIndex = 0
        val sortedMappings = mappings.sortedBy { summaryText.indexOf(it.explanationComponent) }
        
        sortedMappings.forEachIndexed { index, mapping ->
            val componentStart = summaryText.indexOf(mapping.explanationComponent, currentIndex)
            if (componentStart == -1) {
                println("[createInteractiveSummaryPanel] WARNING: Component not found: '${mapping.explanationComponent}'")
                return@forEachIndexed
            }
            
            // Add text before component (if any)
            if (componentStart > currentIndex) {
                val beforeText = summaryText.substring(currentIndex, componentStart)
                panel.add(JLabel(beforeText).apply {
                    font = Font(font.name, Font.PLAIN, 12)
                    alignmentX = JComponent.LEFT_ALIGNMENT
                })
            }
            
            // Add clickable component with color highlighting
            val color = mappingColors[index % mappingColors.size]
            val labelColor = Color(color.red, color.green, color.blue)
            val baseBorder = BorderFactory.createLineBorder(labelColor, 1)
            val labelBorder = BorderFactory.createCompoundBorder(
                baseBorder,
                BorderFactory.createEmptyBorder(2, 4, 2, 4)
            )
            val componentLabel = JLabel(mapping.explanationComponent).apply {
                font = Font(font.name, Font.PLAIN, 12)
                background = labelColor
                isOpaque = true
                border = labelBorder
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                alignmentX = JComponent.LEFT_ALIGNMENT
                
                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) {
                        println("[MouseClick] Clicked on: '${mapping.explanationComponent}'")
                        println("[MouseClick] Code segments: ${mapping.codeSegments.size}")
                        highlightCodeInEditor(mapping.codeSegments, labelColor)
                    }

                    override fun mouseEntered(e: MouseEvent) {
                        background = labelColor
                        border = labelBorder
                        repaint()
                    }

                    override fun mouseExited(e: MouseEvent) {
                        background = labelColor
                        border = labelBorder
                        repaint()
                    }
                })
            }
            
            panel.add(componentLabel)
            currentIndex = componentStart + mapping.explanationComponent.length
        }
        
        // Add remaining text (if any)
        if (currentIndex < summaryText.length) {
            val remainingText = summaryText.substring(currentIndex)
            panel.add(JLabel(remainingText).apply {
                font = Font(font.name, Font.PLAIN, 12)
                alignmentX = JComponent.LEFT_ALIGNMENT
            })
        }
        
        println("[createInteractiveSummaryPanel] Panel created successfully")
        return panel
    }
    
    private fun highlightCodeInEditor(codeSegments: List<com.example.explainableaiplugin.services.CodeSegment>, color: Color) {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor
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
            
            if (lineNumber >= 0 && lineNumber < editor.document.lineCount) {
                val lineStartOffset = editor.document.getLineStartOffset(lineNumber)
                val lineEndOffset = editor.document.getLineEndOffset(lineNumber)
                val lineText = editor.document.getText(
                    com.intellij.openapi.util.TextRange(lineStartOffset, lineEndOffset)
                )
                
                println("[highlightCodeInEditor] Line $lineNumber text: '$lineText'")
                println("[highlightCodeInEditor] Looking for: '${segment.code}'")
                
                // Try to find code in line
                var segmentIndex = lineText.indexOf(segment.code)
                
                // If exact match not found, try without leading/trailing spaces
                if (segmentIndex == -1) {
                    val trimmedCode = segment.code.trim()
                    segmentIndex = lineText.indexOf(trimmedCode)
                    if (segmentIndex != -1) {
                        println("[highlightCodeInEditor] Found trimmed match at index $segmentIndex")
                        val startOffset = lineStartOffset + segmentIndex
                        val endOffset = startOffset + trimmedCode.length
                        
                        markupModel.addRangeHighlighter(
                            startOffset,
                            endOffset,
                            HighlighterLayer.SELECTION + 1,
                            textAttributes,
                            HighlighterTargetArea.EXACT_RANGE
                        )
                        highlightCount++
                        println("[highlightCodeInEditor] Added highlight at $startOffset-$endOffset")
                    }
                } else {
                    println("[highlightCodeInEditor] Found exact match at index $segmentIndex")
                    val startOffset = lineStartOffset + segmentIndex
                    val endOffset = startOffset + segment.code.length
                    
                    markupModel.addRangeHighlighter(
                        startOffset,
                        endOffset,
                        HighlighterLayer.SELECTION + 1,
                        textAttributes,
                        HighlighterTargetArea.EXACT_RANGE
                    )
                    highlightCount++
                    println("[highlightCodeInEditor] Added highlight at $startOffset-$endOffset")
                }
                
                if (segmentIndex == -1) {
                    println("[highlightCodeInEditor] WARNING: Could not find segment in line!")
                }
            } else {
                println("[highlightCodeInEditor] WARNING: Line number $lineNumber out of bounds (total lines: ${editor.document.lineCount})")
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
                    
                    // Get selected model from generation tab
                    val selectedModelWithPrice = generationModelCombo.selectedItem as? String
                    val selectedModel = selectedModelWithPrice?.split(" | ")?.firstOrNull()?.trim() 
                        ?: openAIService.getModel()
                    
                    // Get detail level and format from generation tab
                    val detailLevel = when (generationDetailLevelCombo.selectedIndex) {
                        0 -> "low"
                        1 -> "medium"
                        2 -> "high"
                        else -> "medium"
                    }
                    val isStructured = generationFormatTypeCombo.selectedIndex == 1
                    val summaryKey = "${detailLevel}_${if (isStructured) "structured" else "unstructured"}"
                    SwingUtilities.invokeLater {
                        junieLogTextArea.append("Using model: $selectedModel\n")
                        junieLogTextArea.append("Detail level: ${detailLevel.replaceFirstChar { it.uppercase() }}, Format: ${if (isStructured) "Bullet Points" else "Paragraph"}\n")
                    }
                    
                    // Generate summaries for each changed segment
                    var processedSegments = 0
                    val totalSegments = fileChanges.sumOf { it.changedSegments.size }
                    
                    fileChanges.forEach { fileChange ->
                        SwingUtilities.invokeLater {
                            junieLogTextArea.append("\n Processing ${fileChange.filePath.substringAfterLast("/")}\n")
                        }
                        
                        fileChange.changedSegments.forEach { segment ->
                            indicator.fraction = processedSegments.toDouble() / totalSegments
                            indicator.text = "Generating summary ${processedSegments + 1}/$totalSegments..."
                            runBlocking {
                                // Only process ADDED or MODIFIED segments with substantial code
                                if ((segment.changeType == com.example.explainableaiplugin.services.ChangeType.ADDED || 
                                     segment.changeType == com.example.explainableaiplugin.services.ChangeType.MODIFIED) &&
                                    segment.newCode.trim().isNotEmpty() &&
                                    segment.newCode.trim().lines().size >= 3) {
                                    
                                    SwingUtilities.invokeLater {
                                        junieLogTextArea.append(" • Lines ${segment.startLine}-${segment.endLine}: Generating summary...\n")
                                    }
                                    
                                    // Generate summary for this segment
                                    val summaryResult = openAIService.generateCodeSummary(
                                        segment.newCode,
                                        segment.newCode, // Use segment as context
                                        selectedModel
                                    )
                                    
                                    summaryResult.onSuccess { summary ->
                                        SwingUtilities.invokeLater {
                                            junieLogTextArea.append("Summary generated\n")
                                            junieLogTextArea.append("Building mappings for all formats...\n")
                                        }
                                        
                                        // Build mappings for all 6 summary types (like in Code Summary tab)
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
                                                val mappingResult = openAIService.buildSummaryMapping(
                                                    segment.newCode,
                                                    summaryText,
                                                    segment.startLine,
                                                    selectedModel
                                                )
                                                mappingResult.onSuccess { mapping ->
                                                    mappingResults[key] = mapping
                                                }.onFailure { e ->
                                                    println("[processCodeChanges] Failed to build mapping for $key: ${e.message}")
                                                }
                                            }
                                        }
                                        
                                        SwingUtilities.invokeLater {
                                            junieLogTextArea.append("Mappings built for ${mappingResults.size} formats\n")
                                        }
                                        
                                        // Create SummaryMappings with all mappings
                                        val summaryMappings = SummaryMappings(
                                            low_unstructured = mappingResults["low_unstructured"] ?: emptyList(),
                                            low_structured = mappingResults["low_structured"] ?: emptyList(),
                                            medium_unstructured = mappingResults["medium_unstructured"] ?: emptyList(),
                                            medium_structured = mappingResults["medium_structured"] ?: emptyList(),
                                            high_unstructured = mappingResults["high_unstructured"] ?: emptyList(),
                                            high_structured = mappingResults["high_structured"] ?: emptyList()
                                        )
                                        
                                        changeSummaries.add(ChangeSummaryResult(
                                            filePath = fileChange.filePath,
                                            startLine = segment.startLine,
                                            endLine = segment.endLine,
                                            code = segment.newCode,
                                            summary = summary,
                                            mappings = summaryMappings,
                                            detailLevel = detailLevel,
                                            isStructured = isStructured
                                        ))
                                    }.onFailure { e ->
                                        SwingUtilities.invokeLater {
                                            junieLogTextArea.append("Failed: ${e.message}\n")
                                        }
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
                val interactivePanel = createInteractiveSummaryPanel(summaryText, mappings)
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
        
        // Scroll to bottom to show summaries
        SwingUtilities.invokeLater {
            val scrollPane = SwingUtilities.getAncestorOfClass(JScrollPane::class.java, targetPanel) as? JScrollPane
            scrollPane?.let {
                val vertical = it.verticalScrollBar
                vertical.value = vertical.maximum
            }
        }
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
