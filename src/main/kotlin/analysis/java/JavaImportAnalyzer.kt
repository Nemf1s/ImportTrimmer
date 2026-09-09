package io.github.nemf1s.analysis.java

import com.intellij.openapi.editor.Document
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.util.TextRange
import com.intellij.psi.*
import com.intellij.psi.codeStyle.JavaCodeStyleManager
import com.intellij.psi.util.PsiTreeUtil
import io.github.nemf1s.analysis.*

class JavaImportAnalyzer : ImportAnalyzer {
    override val providerId: String = ID

    override fun supports(file: PsiFile): Boolean = file is PsiJavaFile

    override fun analyze(
        file: PsiFile,
        document: Document,
        token: FreshnessToken,
        anchors: List<OccurrenceAnchor>,
    ): AnalysisSnapshot {
        val javaFile = file as? PsiJavaFile
            ?: return AnalysisSnapshot(ID, token, AnalysisQuality.UNSUPPORTED, reason = "Not a Java file")
        if (!PsiDocumentManager.getInstance(file.project).isCommitted(document)) {
            return AnalysisSnapshot(ID, token, AnalysisQuality.DEFERRED, reason = "PSI is not committed", committed = false)
        }
        if (PsiTreeUtil.findChildOfType(javaFile, PsiErrorElement::class.java) != null) {
            return AnalysisSnapshot(ID, token, AnalysisQuality.DEFERRED, reason = "Java syntax is incomplete")
        }

        val statements = javaFile.importList?.allImportStatements.orEmpty()
        if (statements.isEmpty()) return AnalysisSnapshot(ID, token, AnalysisQuality.RELIABLE)
        val redundant = try {
            JavaCodeStyleManager.getInstance(file.project).findRedundantImports(javaFile)
        } catch (_: IndexNotReadyException) {
            return AnalysisSnapshot(ID, token, AnalysisQuality.DEFERRED, reason = "Java indices are unavailable")
        } ?: return AnalysisSnapshot(ID, token, AnalysisQuality.DEFERRED, reason = "Redundant-import analysis unavailable")

        val signatures = statements.groupingBy(::signature).eachCount()
        val observations = try {
            statements.map { statement ->
            ProgressManager.checkCanceled()
            val reference = statement.importReference
            val resolved = reference != null && if (statement is PsiImportStaticStatement) {
                reference.multiResolve(false).any { it.isValidResult && it.element != null }
            } else {
                reference.resolve() != null
            }
            val psiText = statement.text
            val semicolon = psiText.indexOf(';')
            val expectedText = if (semicolon >= 0) psiText.substring(0, semicolon + 1) else psiText
            val psiRange = statement.textRange
            val range = TextRange(psiRange.startOffset, psiRange.startOffset + expectedText.length)
            val stableAnchor = anchors.singleOrNull {
                it.range.startOffset == range.startOffset && it.text == expectedText
            }
            val key = stableAnchor?.key ?: OccurrenceKey(ID, signature(statement) + "@" + range.startOffset)
            val supported = semicolon >= 0 && statement.javaClass.simpleName != "PsiImportModuleStatement" &&
                !expectedText.contains("/*") && !expectedText.contains("//") &&
                !expectedText.contains('\n') && !expectedText.contains('\r') &&
                signatures[signature(statement)] == 1
            val status = when {
                !supported || !resolved -> SemanticStatus.UNKNOWN
                statement in redundant -> SemanticStatus.UNUSED
                else -> SemanticStatus.USED
            }
            ImportObservation(
                key = key,
                displayText = displayText(statement),
                status = status,
                supported = supported,
                range = range,
                expectedText = expectedText,
                promptText = promptText(statement),
            )
            }
        } catch (_: IndexNotReadyException) {
            return AnalysisSnapshot(ID, token, AnalysisQuality.DEFERRED, reason = "Java indices are unavailable")
        }

        // The platform finder can omit an import for a reference it cannot resolve. Never treat that as proof of use.
        val unresolved = try {
            PsiTreeUtil.collectElementsOfType(javaFile, PsiJavaCodeReferenceElement::class.java).any {
                PsiTreeUtil.getParentOfType(it, PsiImportStatementBase::class.java, false) == null &&
                    PsiTreeUtil.getParentOfType(it, PsiPackageStatement::class.java, false) == null &&
                    it.parent !is PsiJavaCodeReferenceElement &&
                    it.resolve() == null
            }
        } catch (_: IndexNotReadyException) {
            return AnalysisSnapshot(ID, token, AnalysisQuality.DEFERRED, reason = "Java indices are unavailable")
        }
        if (unresolved) {
            return AnalysisSnapshot(ID, token, AnalysisQuality.DEFERRED, reason = "An in-file Java reference is unresolved")
        }
        return AnalysisSnapshot(
            ID,
            token,
            AnalysisQuality.RELIABLE,
            observations,
            interactionRange = javaFile.importList?.textRange,
        )
    }

    private fun signature(statement: PsiImportStatementBase): String {
        val static = statement is PsiImportStaticStatement
        val path = statement.importReference?.qualifiedName ?: statement.text
        return (if (static) "static:" else "type:") + path + if (statement.isOnDemand) ":*" else ""
    }

    private fun displayText(statement: PsiImportStatementBase): String {
        val path = statement.importReference?.qualifiedName ?: statement.text
        val wildcard = if (statement.isOnDemand) ".*" else ""
        return if (statement is PsiImportStaticStatement) "static $path$wildcard" else "$path$wildcard"
    }

    private fun promptText(statement: PsiImportStatementBase): String {
        val display = displayText(statement)
        return if (statement is PsiImportStaticStatement || statement.isOnDemand) display
        else display.substringAfterLast('.')
    }

    companion object { const val ID = "java" }
}
