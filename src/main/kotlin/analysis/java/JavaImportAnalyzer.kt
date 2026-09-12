package io.github.nemf1s.analysis.java

import com.intellij.openapi.editor.Document
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.IndexNotReadyException
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiErrorElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiImportStatementBase
import com.intellij.psi.PsiImportStaticStatement
import com.intellij.psi.PsiJavaCodeReferenceElement
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiPackageStatement
import com.intellij.psi.codeStyle.JavaCodeStyleManager
import com.intellij.psi.util.PsiTreeUtil
import io.github.nemf1s.analysis.AnalysisQuality
import io.github.nemf1s.analysis.AnalysisSnapshot
import io.github.nemf1s.analysis.FreshnessToken
import io.github.nemf1s.analysis.ImportAnalyzer
import io.github.nemf1s.analysis.ImportObservation
import io.github.nemf1s.analysis.OccurrenceAnchor
import io.github.nemf1s.analysis.OccurrenceKey
import io.github.nemf1s.analysis.SemanticStatus

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
            return AnalysisSnapshot(
                providerId = ID,
                token = token,
                quality = AnalysisQuality.DEFERRED,
                reason = "PSI is not committed",
                committed = false,
            )
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
        } ?: return AnalysisSnapshot(
            ID,
            token,
            AnalysisQuality.DEFERRED,
            reason = "Redundant-import analysis unavailable",
        )

        val signatures = statements.groupingBy(::signature).eachCount()
        val observations = try {
            statements.map { statement ->
                ProgressManager.checkCanceled()
                observeImport(
                    statement = statement,
                    signatureCount = signatures.getValue(signature(statement)),
                    isRedundant = statement in redundant,
                    anchors = anchors,
                )
            }
        } catch (_: IndexNotReadyException) {
            return AnalysisSnapshot(ID, token, AnalysisQuality.DEFERRED, reason = "Java indices are unavailable")
        }

        // The platform finder can omit an import for a reference it cannot resolve. Never treat that as proof of use.
        val hasUnresolvedReference = try {
            hasUnresolvedCodeReference(javaFile)
        } catch (_: IndexNotReadyException) {
            return AnalysisSnapshot(ID, token, AnalysisQuality.DEFERRED, reason = "Java indices are unavailable")
        }
        if (hasUnresolvedReference) {
            return AnalysisSnapshot(
                ID,
                token,
                AnalysisQuality.DEFERRED,
                reason = "An in-file Java reference is unresolved",
            )
        }
        return AnalysisSnapshot(
            ID,
            token,
            AnalysisQuality.RELIABLE,
            observations,
            interactionRange = javaFile.importList?.textRange,
        )
    }

    private fun observeImport(
        statement: PsiImportStatementBase,
        signatureCount: Int,
        isRedundant: Boolean,
        anchors: List<OccurrenceAnchor>,
    ): ImportObservation {
        val expectedText = expectedText(statement)
        val range = TextRange(statement.textRange.startOffset, statement.textRange.startOffset + expectedText.length)
        val stableAnchor = anchors.singleOrNull { anchor ->
            anchor.range.startOffset == range.startOffset && anchor.text == expectedText
        }
        val supported = isSupported(statement, expectedText, signatureCount)
        val status = when {
            !supported || !hasResolvableReference(statement) -> SemanticStatus.UNKNOWN
            isRedundant -> SemanticStatus.UNUSED
            else -> SemanticStatus.USED
        }
        return ImportObservation(
            key = stableAnchor?.key ?: OccurrenceKey(ID, "${signature(statement)}@${range.startOffset}"),
            displayText = displayText(statement),
            status = status,
            supported = supported,
            range = range,
            expectedText = expectedText,
            promptText = promptText(statement),
        )
    }

    private fun expectedText(statement: PsiImportStatementBase): String {
        val psiText = statement.text
        val semicolon = psiText.indexOf(';')
        return if (semicolon >= 0) psiText.substring(0, semicolon + 1) else psiText
    }

    private fun isSupported(
        statement: PsiImportStatementBase,
        expectedText: String,
        signatureCount: Int,
    ): Boolean =
        expectedText.endsWith(';') &&
            statement.javaClass.simpleName != "PsiImportModuleStatement" &&
            !expectedText.contains("/*") &&
            !expectedText.contains("//") &&
            !expectedText.contains('\n') &&
            !expectedText.contains('\r') &&
            signatureCount == 1

    private fun hasResolvableReference(statement: PsiImportStatementBase): Boolean {
        val reference = statement.importReference ?: return false
        return if (statement is PsiImportStaticStatement) {
            reference.multiResolve(false).any { it.isValidResult && it.element != null }
        } else {
            reference.resolve() != null
        }
    }

    private fun hasUnresolvedCodeReference(file: PsiJavaFile): Boolean =
        PsiTreeUtil.collectElementsOfType(file, PsiJavaCodeReferenceElement::class.java).any { reference ->
            PsiTreeUtil.getParentOfType(reference, PsiImportStatementBase::class.java, false) == null &&
                PsiTreeUtil.getParentOfType(reference, PsiPackageStatement::class.java, false) == null &&
                reference.parent !is PsiJavaCodeReferenceElement &&
                reference.resolve() == null
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
        return if (statement is PsiImportStaticStatement || statement.isOnDemand) {
            display
        } else {
            display.substringAfterLast('.')
        }
    }

    companion object {
        const val ID = "java"
    }
}
