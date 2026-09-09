package io.github.nemf1s.editing.java

import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiJavaFile
import io.github.nemf1s.analysis.*
import io.github.nemf1s.analysis.java.JavaImportAnalyzer

class JavaImportEditPlanner : ImportEditPlanner {
    override val providerId: String = JavaImportAnalyzer.ID

    override fun plan(
        file: PsiFile,
        document: Document,
        snapshot: AnalysisSnapshot,
        accepted: List<Candidate>,
    ): ImportEditPlan? {
        if (file !is PsiJavaFile || snapshot.providerId != providerId ||
            snapshot.quality != AnalysisQuality.RELIABLE || !snapshot.committed || accepted.isEmpty()
        ) return null
        val byKey = snapshot.observations.groupBy { it.key }
        val selected = accepted.map { candidate ->
            byKey[candidate.key]?.singleOrNull()?.takeIf {
                it.supported && it.status == SemanticStatus.UNUSED
            } ?: return null
        }
        if (selected.map { it.key }.toSet().size != accepted.size) return null

        val deletions = selected.map { observation ->
            deletionRange(document, observation.range)?.let { range ->
                TextDeletion(range, document.getText(range))
            } ?: return null
        }
        if (deletions.sortedBy { it.range.startOffset }.zipWithNext().any {
                it.first.range.endOffset > it.second.range.startOffset
            }
        ) return null
        return ImportEditPlan(snapshot.token, deletions)
    }

    private fun deletionRange(document: Document, statement: TextRange): TextRange? {
        if (statement.startOffset < 0 || statement.endOffset > document.textLength) return null
        val line = document.getLineNumber(statement.startOffset)
        val endLine = document.getLineNumber(statement.endOffset)
        if (line != endLine) return null
        val lineStart = document.getLineStartOffset(line)
        val lineEnd = document.getLineEndOffset(line)
        val prefix = document.charsSequence.subSequence(lineStart, statement.startOffset)
        val suffix = document.charsSequence.subSequence(statement.endOffset, lineEnd)
        if (prefix.isBlank() && suffix.isBlank()) {
            val separatorEnd = when {
                lineEnd >= document.textLength -> lineEnd
                document.charsSequence[lineEnd] == '\r' &&
                    lineEnd + 1 < document.textLength && document.charsSequence[lineEnd + 1] == '\n' -> lineEnd + 2
                else -> lineEnd + 1
            }
            return TextRange(lineStart, separatorEnd)
        }
        if (prefix.isBlank() && suffix.trimStart().startsWith("//")) {
            return TextRange(lineStart, statement.endOffset)
        }
        return statement
    }
}
