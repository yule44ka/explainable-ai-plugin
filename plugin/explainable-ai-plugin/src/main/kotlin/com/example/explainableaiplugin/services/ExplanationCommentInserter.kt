package com.example.explainableaiplugin.services

import com.intellij.lang.LanguageCommenters
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager

@Service(Service.Level.PROJECT)
class ExplanationCommentInserter(private val project: Project) {

    companion object {
        fun getInstance(project: Project): ExplanationCommentInserter = project.service()
    }

    fun insertHighDetailBulletComments(editor: Editor, mappings: List<SummaryMapping>): Int {
        return insertHighDetailBulletCommentsWithResult(editor, mappings).insertedCount
    }

    fun insertHighDetailBulletComments(filePath: String, mappings: List<SummaryMapping>): Int {
        return insertHighDetailBulletCommentsWithResult(filePath, mappings).insertedCount
    }

    fun insertHighDetailBulletCommentsWithResult(editor: Editor, mappings: List<SummaryMapping>): CommentInsertionResult {
        val file = FileDocumentManager.getInstance().getFile(editor.document)
        return insertIntoDocument(editor.document, file, mappings)
    }

    fun insertHighDetailBulletCommentsWithResult(filePath: String, mappings: List<SummaryMapping>): CommentInsertionResult {
        val file = LocalFileSystem.getInstance().findFileByPath(filePath) ?: return CommentInsertionResult()
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return CommentInsertionResult()
        return insertIntoDocument(document, file, mappings)
    }

    private fun insertIntoDocument(
        document: Document,
        file: VirtualFile?,
        mappings: List<SummaryMapping>
    ): CommentInsertionResult {
        val insertions = buildInsertions(document, file, mappings)
        if (insertions.isEmpty()) return CommentInsertionResult()

        val writeRunnable = Runnable {
            WriteCommandAction.runWriteCommandAction(project, "Add AI Explanation Comments", null, Runnable {
                insertions
                    .sortedByDescending { it.lineIndex }
                    .forEach { insertion ->
                        val offset = document.getLineStartOffset(insertion.lineIndex)
                        document.insertString(offset, insertion.text)
                    }
                FileDocumentManager.getInstance().saveDocument(document)
            })
        }

        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) {
            writeRunnable.run()
        } else {
            application.invokeAndWait(writeRunnable)
        }

        return CommentInsertionResult(
            insertedCount = insertions.size,
            lineShifts = insertions.map {
                CommentLineShift(
                    originalLine = it.lineIndex + 1,
                    addedLines = it.lineCount
                )
            }
        )
    }

    private fun buildInsertions(
        document: Document,
        file: VirtualFile?,
        mappings: List<SummaryMapping>
    ): List<CommentInsertion> {
        if (document.lineCount == 0) return emptyList()

        val formatter = commentFormatter(file)
        return mappings
            .mapNotNull { mapping ->
                val lineIndex = mapping.codeSegments
                    .map { it.line - 1 }
                    .filter { it >= 0 }
                    .minOrNull()
                    ?.coerceAtMost(document.lineCount - 1)
                    ?: return@mapNotNull null

                val explanation = removeLineNumberReferences(mapping.explanationComponent).trim()
                if (explanation.isEmpty()) return@mapNotNull null

                val indent = lineIndent(document, lineIndex)
                CommentInsertion(
                    lineIndex = lineIndex,
                    text = formatter.format(indent, explanation)
                )
            }
            .groupBy { it.lineIndex }
            .map { (lineIndex, entries) ->
                CommentInsertion(
                    lineIndex = lineIndex,
                    text = entries.joinToString("") { it.text }
                )
            }
    }

    private fun lineIndent(document: Document, lineIndex: Int): String {
        val start = document.getLineStartOffset(lineIndex)
        val end = document.getLineEndOffset(lineIndex)
        val line = document.charsSequence.subSequence(start, end).toString()
        return line.takeWhile { it == ' ' || it == '\t' }
    }

    private fun removeLineNumberReferences(text: String): String {
        return text
            .lineSequence()
            .map { line ->
                line
                    .replace(Regex("(?i)^\\s*(?:line|lines)\\s+\\d+(?:\\s*[-–]\\s*\\d+)?\\s*[:,-]?\\s*"), "")
                    .replace(Regex("(?i)\\s*\\((?:line|lines)\\s+\\d+(?:\\s*[-–]\\s*\\d+)?\\)"), "")
                    .replace(Regex("(?i)\\s*,?\\s*(?:on|at|in)\\s+(?:line|lines)\\s+\\d+(?:\\s*[-–]\\s*\\d+)?\\b"), "")
                    .replace(Regex("(?i)\\s*,?\\s*(?:line|lines)\\s+\\d+(?:\\s*[-–]\\s*\\d+)?\\b"), "")
                    .trimEnd()
            }
            .joinToString("\n")
    }

    private fun commentFormatter(file: VirtualFile?): CommentFormatter {
        val psiFile = file?.let { PsiManager.getInstance(project).findFile(it) }
        val commenter = psiFile?.let { LanguageCommenters.INSTANCE.forLanguage(it.language) }
        val linePrefix = commenter?.lineCommentPrefix
        if (!linePrefix.isNullOrBlank()) {
            return CommentFormatter { indent, explanation ->
                explanation.lineSequence()
                    .map { it.trimEnd() }
                    .filter { it.isNotBlank() }
                    .joinToString(separator = "\n", postfix = "\n") { line ->
                        "$indent$linePrefix $line"
                    }
            }
        }

        val blockPrefix = commenter?.blockCommentPrefix ?: fallbackBlockCommentPrefix(file)
        val blockSuffix = commenter?.blockCommentSuffix ?: fallbackBlockCommentSuffix(file)
        return CommentFormatter { indent, explanation ->
            explanation.lineSequence()
                .map { it.trimEnd() }
                .filter { it.isNotBlank() }
                .joinToString(separator = "\n", postfix = "\n") { line ->
                    "$indent$blockPrefix $line $blockSuffix"
                }
        }
    }

    private fun fallbackBlockCommentPrefix(file: VirtualFile?): String {
        return when (file?.extension?.lowercase()) {
            "html", "htm", "xml", "xhtml", "svg" -> "<!--"
            else -> "/*"
        }
    }

    private fun fallbackBlockCommentSuffix(file: VirtualFile?): String {
        return when (file?.extension?.lowercase()) {
            "html", "htm", "xml", "xhtml", "svg" -> "-->"
            else -> "*/"
        }
    }

    private fun interface CommentFormatter {
        fun format(indent: String, explanation: String): String
    }

    private data class CommentInsertion(
        val lineIndex: Int,
        val text: String
    ) {
        val lineCount: Int
            get() = text.count { it == '\n' }
    }
}

data class CommentInsertionResult(
    val insertedCount: Int = 0,
    val lineShifts: List<CommentLineShift> = emptyList()
)

data class CommentLineShift(
    val originalLine: Int,
    val addedLines: Int
)
