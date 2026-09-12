package io.github.nemf1s.editing

import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ReadConstraint
import com.intellij.openapi.application.constrainedReadAction
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.util.PsiModificationTracker
import io.github.nemf1s.MyMessageBundle.message
import io.github.nemf1s.analysis.FreshnessToken
import io.github.nemf1s.analysis.ImportEditPlan
import io.github.nemf1s.analysis.ImportProvider
import io.github.nemf1s.analysis.OccurrenceAnchor
import io.github.nemf1s.analysis.RemovalRequest
import io.github.nemf1s.analysis.selectProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class RemovalResult {
    APPLIED,
    STALE,
    DECLINED,
}

class ImportRemovalExecutor(
    private val project: Project,
    private val providers: List<ImportProvider>,
    private val beforeWrite: suspend () -> Unit = {},
) {
    suspend fun execute(
        document: Document,
        request: RemovalRequest,
        anchors: List<OccurrenceAnchor>,
        generation: Long,
        epoch: Long,
        isAuthorized: () -> Boolean,
        markPluginEdit: (Boolean) -> Unit,
    ): RemovalResult {
        if (!document.isWritable || project.isDisposed) return RemovalResult.DECLINED
        val planned = constrainedReadAction(
            ReadConstraint.inSmartMode(project),
            ReadConstraint.withDocumentsCommitted(project),
        ) {
            val manager = PsiDocumentManager.getInstance(project)
            val file = manager.getPsiFile(document) ?: return@constrainedReadAction null
            val provider = selectProvider(providers, file) ?: return@constrainedReadAction null
            if (provider.analyzer.providerId != request.providerId) return@constrainedReadAction null
            val token = FreshnessToken(
                stamp = document.modificationStamp,
                psi = PsiModificationTracker.getInstance(project).modificationCount,
                generation = generation,
                epoch = epoch,
            )
            val snapshot = provider.analyzer.analyze(file, document, token, anchors)
            provider.planner.plan(file, document, snapshot, request.candidates)
                ?.let { file to it }
        } ?: return RemovalResult.DECLINED

        beforeWrite()
        return withContext(Dispatchers.EDT) {
            var result = RemovalResult.STALE
            val (file, plan) = planned
            WriteCommandAction.runWriteCommandAction(
                project,
                message("command.remove"),
                null,
                Runnable {
                    if (!canApply(document, file, plan, isAuthorized)) return@Runnable

                    markPluginEdit(true)
                    try {
                        plan.deletions.sortedByDescending { it.range.startOffset }.forEach { deletion ->
                            document.deleteString(deletion.range.startOffset, deletion.range.endOffset)
                        }
                        result = RemovalResult.APPLIED
                    } finally {
                        markPluginEdit(false)
                    }
                },
                file,
            )
            result
        }
    }

    private fun canApply(
        document: Document,
        file: PsiFile,
        plan: ImportEditPlan,
        isAuthorized: () -> Boolean,
    ): Boolean {
        val token = plan.token
        val isCurrent = document.isWritable &&
            file.isValid &&
            file.isWritable &&
            document.modificationStamp == token.stamp &&
            currentPsiModificationCount() == token.psi &&
            isAuthorized()
        return isCurrent && plan.deletions.all { deletion ->
            deletion.range.endOffset <= document.textLength &&
                document.getText(deletion.range) == deletion.expectedText
        }
    }

    private fun currentPsiModificationCount(): Long =
        PsiModificationTracker.getInstance(project).modificationCount
}
