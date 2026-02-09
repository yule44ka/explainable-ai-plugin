package com.example.explainableaiplugin.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.editor.Document

/**
 * Service to detect code changes between snapshots
 */
@Service(Service.Level.PROJECT)
class CodeChangeDetector(private val project: Project) {
    
    companion object {
        fun getInstance(project: Project): CodeChangeDetector = project.service()
    }
    
    // Store file snapshots before generation
    private val fileSnapshots = mutableMapOf<String, String>()
    
    /**
     * Capture current state of all open files in the project
     */
    fun captureSnapshot() {
        fileSnapshots.clear()
        
        val fileDocumentManager = FileDocumentManager.getInstance()
        val fileEditorManager = FileEditorManager.getInstance(project)
        
        // Get all open files in editors
        val openFiles = fileEditorManager.openFiles
        
        println("[CodeChangeDetector] Capturing snapshots for ${openFiles.size} open files")
        
        openFiles.forEach { virtualFile ->
            val document = fileDocumentManager.getDocument(virtualFile)
            if (document != null) {
                val filePath = virtualFile.path
                val content = document.text
                fileSnapshots[filePath] = content
                println("[CodeChangeDetector] Captured snapshot for: $filePath (${content.length} chars)")
            } else {
                println("[CodeChangeDetector] WARNING: No document for file: ${virtualFile.path}")
            }
        }
        
        println("[CodeChangeDetector] Total snapshots captured: ${fileSnapshots.size}")
    }
    
    /**
     * Detect changes in files since last snapshot
     * Must be called after reloading files from disk
     * @return List of FileChange objects containing changed segments
     */
    fun detectChanges(): List<FileChange> {
        val changes = mutableListOf<FileChange>()
        
        val fileDocumentManager = FileDocumentManager.getInstance()
        val fileEditorManager = FileEditorManager.getInstance(project)
        
        println("[CodeChangeDetector] Starting change detection...")
        println("[CodeChangeDetector] Snapshots to check: ${fileSnapshots.size}")
        
        // Get current open files
        val openFiles = fileEditorManager.openFiles
        println("[CodeChangeDetector] Currently open files: ${openFiles.size}")
        
        // Build map of current file contents
        val currentFiles = openFiles.mapNotNull { virtualFile ->
            val document = fileDocumentManager.getDocument(virtualFile)
            if (document != null) {
                virtualFile.path to document.text
            } else {
                null
            }
        }.toMap()
        
        println("[CodeChangeDetector] Current files with documents: ${currentFiles.size}")
        
        // Compare snapshots with current state
        fileSnapshots.forEach { (filePath, oldContent) ->
            val newContent = currentFiles[filePath]
            
            if (newContent != null) {
                if (oldContent != newContent) {
                    println("[CodeChangeDetector] Detected change in: $filePath")
                    println("[CodeChangeDetector]   Old length: ${oldContent.length}, New length: ${newContent.length}")
                    val fileChange = analyzeChanges(filePath, oldContent, newContent)
                    if (fileChange.changedSegments.isNotEmpty()) {
                        changes.add(fileChange)
                        println("[CodeChangeDetector]   Added ${fileChange.changedSegments.size} changed segments")
                    }
                } else {
                    println("[CodeChangeDetector] No change in: $filePath")
                }
            } else {
                println("[CodeChangeDetector] WARNING: File not found in current state: $filePath")
            }
        }
        
        // Check for new files that weren't in snapshot
        currentFiles.keys.forEach { filePath ->
            if (!fileSnapshots.containsKey(filePath)) {
                println("[CodeChangeDetector] Detected new file: $filePath")
                val newContent = currentFiles[filePath] ?: ""
                changes.add(FileChange(
                    filePath = filePath,
                    changedSegments = listOf(ChangedSegment(
                        startLine = 1,
                        endLine = newContent.lines().size,
                        oldCode = "",
                        newCode = newContent,
                        changeType = ChangeType.ADDED
                    ))
                ))
            }
        }
        
        println("[CodeChangeDetector] Total file changes detected: ${changes.size}")
        changes.forEach { change ->
            println("  - ${change.filePath}: ${change.changedSegments.size} segments")
        }
        
        return changes
    }
    
    /**
     * Analyze differences between old and new content
     * Returns FileChange with detailed segment information
     */
    private fun analyzeChanges(filePath: String, oldContent: String, newContent: String): FileChange {
        val oldLines = oldContent.lines()
        val newLines = newContent.lines()
        
        val changedSegments = mutableListOf<ChangedSegment>()
        
        // Simple diff algorithm: find consecutive changed lines
        val maxLines = maxOf(oldLines.size, newLines.size)
        var segmentStart: Int? = null
        var segmentOldLines = mutableListOf<String>()
        var segmentNewLines = mutableListOf<String>()
        
        for (i in 0 until maxLines) {
            val oldLine = oldLines.getOrNull(i)
            val newLine = newLines.getOrNull(i)
            
            val isChanged = oldLine != newLine
            
            if (isChanged) {
                if (segmentStart == null) {
                    segmentStart = i + 1 // 1-based line numbers
                }
                segmentOldLines.add(oldLine ?: "")
                segmentNewLines.add(newLine ?: "")
            } else {
                // End of changed segment
                if (segmentStart != null) {
                    val changeType = determineChangeType(segmentOldLines, segmentNewLines)
                    changedSegments.add(ChangedSegment(
                        startLine = segmentStart,
                        endLine = i, // last changed line
                        oldCode = segmentOldLines.joinToString("\n"),
                        newCode = segmentNewLines.joinToString("\n"),
                        changeType = changeType
                    ))
                    
                    // Reset for next segment
                    segmentStart = null
                    segmentOldLines.clear()
                    segmentNewLines.clear()
                }
            }
        }
        
        // Handle last segment if file ends with changes
        if (segmentStart != null) {
            val changeType = determineChangeType(segmentOldLines, segmentNewLines)
            changedSegments.add(ChangedSegment(
                startLine = segmentStart,
                endLine = maxLines,
                oldCode = segmentOldLines.joinToString("\n"),
                newCode = segmentNewLines.joinToString("\n"),
                changeType = changeType
            ))
        }
        
        return FileChange(filePath, changedSegments)
    }
    
    /**
     * Determine the type of change (ADDED, REMOVED, MODIFIED)
     */
    private fun determineChangeType(oldLines: List<String>, newLines: List<String>): ChangeType {
        val hasOldContent = oldLines.any { it.isNotBlank() }
        val hasNewContent = newLines.any { it.isNotBlank() }
        
        return when {
            !hasOldContent && hasNewContent -> ChangeType.ADDED
            hasOldContent && !hasNewContent -> ChangeType.REMOVED
            else -> ChangeType.MODIFIED
        }
    }
    
    /**
     * Clear captured snapshots
     */
    fun clearSnapshots() {
        fileSnapshots.clear()
        println("[CodeChangeDetector] Snapshots cleared")
    }
}

/**
 * Represents changes in a single file
 */
data class FileChange(
    val filePath: String,
    val changedSegments: List<ChangedSegment>
)

/**
 * Represents a segment of changed code
 */
data class ChangedSegment(
    val startLine: Int,
    val endLine: Int,
    val oldCode: String,
    val newCode: String,
    val changeType: ChangeType
)

/**
 * Type of code change
 */
enum class ChangeType {
    ADDED,
    REMOVED,
    MODIFIED
}
