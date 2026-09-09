package io.github.nemf1s.actions

import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.components.service
import io.github.nemf1s.ImportTrimmerProjectService

class ReviewNewlyUnusedImportsAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(event: AnActionEvent) {
        event.presentation.isEnabled = event.project != null && event.getData(CommonDataKeys.EDITOR) != null
    }

    override fun actionPerformed(event: AnActionEvent) {
        val project = event.project ?: return
        val editor = event.getData(CommonDataKeys.EDITOR) ?: return
        project.service<ImportTrimmerProjectService>().review(editor)
    }
}
