package com.example.explainableaiplugin

import com.example.explainableaiplugin.actions.GenerateSummaryAction
import com.example.explainableaiplugin.services.CodeSummary
import com.example.explainableaiplugin.services.OpenAIService
import com.example.explainableaiplugin.services.JunieCliService
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
    private val mainPanel = JPanel(BorderLayout())
    private var summaryPanel: JPanel? = null
    private var currentSummary: CodeSummary? = null
    private var currentMappings: SummaryMappings? = null
    private var originalCode: String? = null
    private var startLine: Int = 1
    
    // Comboboxes for format selection
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
    
    // Combobox for model selection with prices
    private val modelCombo = ComboBox(modelPricing.map { (model, price) -> "$model | $price" }.toTypedArray())
    
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
                        label("⚠️ OpenAI API key is not configured")
                    }
                    row {
                        text("To use AI features, please configure your OpenAI API key.")
                    }
                }
                
                if (!isJunieConfigured) {
                    row {
                        label("⚠️ Junie API Key is not configured")
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
            // Both credentials configured - show main interface
            val controlPanel = createControlPanel()
            mainPanel.add(controlPanel, BorderLayout.NORTH)
            
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
    
    private fun createControlPanel(): JPanel {
        return panel {
            row {
                label("✓ OpenAI API configured")
            }
            row {
                label("✓ Junie API Key configured")
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
                button("🚀 Generate Summary") {
                    generateSummaryFromEditor()
                }.applyToComponent {
                    font = Font(font.name, Font.BOLD, 12)
                }
            }
            
            separator()
            
            row {
                label("🤖 Junie Code Generation").applyToComponent {
                    font = Font(font.name, Font.BOLD, 14)
                }
            }
            
            row {
                text("Enter your prompt to generate code")
            }
            
            val promptTextField = JTextField(30)
            row {
                label("Prompt:")
                cell(promptTextField)
            }
            
            row {
                button("✨ Generate Code") {
                    generateCodeWithJunie(promptTextField.text)
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
        
        // Get selected model (extract model name from "model | price" format)
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
        
        // Remove old summary panel if exists
        summaryPanel?.let { mainPanel.remove(it) }
        
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
        
        // Create panel for display
        summaryPanel = JPanel(BorderLayout()).apply {
            border = BorderFactory.createEmptyBorder(10, 10, 10, 10)
        }
        
        val contentPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
        }
        
        // Title
        if (summary.title.isNotEmpty()) {
            contentPanel.add(JLabel("📝 " + summary.title).apply {
                font = Font(font.name, Font.BOLD, 14)
                border = BorderFactory.createEmptyBorder(0, 0, 10, 0)
            })
        }
        
        // Show selected format
        val formatLabel = "${detailLevel.replaceFirstChar { it.uppercase() }} Detail - ${if (isStructured) "Bullet Points" else "Paragraph"}"
        contentPanel.add(JLabel(formatLabel).apply {
            font = Font(font.name, Font.ITALIC, 11)
            foreground = java.awt.Color.GRAY
            border = BorderFactory.createEmptyBorder(0, 0, 10, 0)
        })
        
        // Display summary with interactive mapping
        if (mappings.isNotEmpty()) {
            contentPanel.add(createInteractiveSummaryPanel(summaryText, mappings))
        } else {
            // If no mapping, show plain text
            val textArea = JTextArea(summaryText).apply {
                isEditable = false
                lineWrap = true
                wrapStyleWord = true
                background = contentPanel.background
                border = BorderFactory.createEmptyBorder(5, 5, 5, 5)
                font = Font(font.name, Font.PLAIN, 12)
            }
            contentPanel.add(textArea)
        }
        
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
    
    private fun getMappingsForKey(key: String): List<SummaryMapping> {
        val mappings = currentMappings ?: return emptyList()
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
            println("  Mapping $idx: '${mapping.summaryComponent}' -> ${mapping.codeSegments.size} segments")
            mapping.codeSegments.forEach { seg ->
                println("    - Line ${seg.line}: '${seg.code}'")
            }
        }
        
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = BorderFactory.createEmptyBorder(5, 5, 5, 5)
        }
        
        var currentIndex = 0
        val sortedMappings = mappings.sortedBy { summaryText.indexOf(it.summaryComponent) }
        
        sortedMappings.forEachIndexed { index, mapping ->
            val componentStart = summaryText.indexOf(mapping.summaryComponent, currentIndex)
            if (componentStart == -1) {
                println("[createInteractiveSummaryPanel] WARNING: Component not found: '${mapping.summaryComponent}'")
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
            val componentLabel = JLabel(mapping.summaryComponent).apply {
                font = Font(font.name, Font.PLAIN, 12)
                background = color
                isOpaque = true
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(color.darker(), 1),
                    BorderFactory.createEmptyBorder(2, 4, 2, 4)
                )
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                alignmentX = JComponent.LEFT_ALIGNMENT
                
                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) {
                        println("[MouseClick] Clicked on: '${mapping.summaryComponent}'")
                        println("[MouseClick] Code segments: ${mapping.codeSegments.size}")
                        highlightCodeInEditor(mapping.codeSegments, color)
                    }
                    
                    override fun mouseEntered(e: MouseEvent) {
                        background = color.brighter()
                    }
                    
                    override fun mouseExited(e: MouseEvent) {
                        background = color
                    }
                })
            }
            
            panel.add(componentLabel)
            currentIndex = componentStart + mapping.summaryComponent.length
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
            println("  - Line ${segment.line}: '${segment.code}'")
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
        
        // Run generation in background
        ProgressManager.getInstance().run(
            object : Task.Backgroundable(project, "Generating Code with Junie...", true) {
                var result: Result<String>? = null
                
                override fun run(indicator: ProgressIndicator) {
                    indicator.isIndeterminate = true
                    indicator.text = "Executing Junie CLI with your prompt..."
                    
                    runBlocking {
                        result = junieCliService.generateCode(prompt)
                    }
                }
                
                override fun onSuccess() {
                    result?.onSuccess { message ->
                        openAIService.showSuccessNotification(message)
                    }?.onFailure { error ->
                        openAIService.showErrorNotification("Failed to generate code: ${error.message}")
                    }
                }
                
                override fun onThrowable(error: Throwable) {
                    openAIService.showErrorNotification("Failed to generate code: ${error.message}")
                }
                
                override fun onFinished() {
                    result?.onFailure { error ->
                        openAIService.showErrorNotification("Failed to generate code: ${error.message}")
                    }
                }
            }
        )
    }
}