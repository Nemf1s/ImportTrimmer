package io.github.nemf1s.integration

import com.intellij.driver.sdk.getOpenProjects
import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.openFile
import com.intellij.driver.sdk.waitFor
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class ImportTrimmerLifecycleIntegrationTest : ImportTrimmerIntegrationTestBase() {
    @Test
    fun automaticRemovalAndUndoRemainReliableAcrossRepeatedFieldGroups() {
        repeatedAutomaticRemovalAndUndo()
    }

    @Test
    fun repeatedEditorCloseAndProjectDisposalCancelPluginWork() {
        repeat(PROJECT_CYCLES) { projectCycle ->
            withIde("import-trimmer-lifecycle-$projectCycle") {
                repeat(EDITOR_CYCLES) {
                    openFile(TARGET_FILE)
                    restoreUsage()
                    waitForBaselineAnalysis()
                    removeUsage()
                    waitForSuggestion()

                    closeCurrentFile()
                    waitForSuggestionToClose()
                }

                openFile(TARGET_FILE)
                restoreUsage()
                waitForBaselineAnalysis()
                removeUsage()
                restoreUsage()

                invokeAction("CloseProject")
                waitFor(
                    message = "Project is closed and disposed",
                    timeout = 30.seconds,
                    interval = 100.milliseconds,
                ) {
                    getOpenProjects().isEmpty()
                }
            }
        }
    }

    @Test
    fun removeActionsRemoveOfferedImports() = withIde("import-trimmer-remove") {
        openFile(TARGET_FILE)
        waitForBaselineAnalysis()
        removeUsage()
        waitForSuggestion()

        invokeNotificationAction("Remove")

        waitFor(
            message = "The accepted import is removed",
            timeout = 15.seconds,
            interval = 100.milliseconds,
        ) {
            FIRST_IMPORT !in selectedDocument().getText() && importTrimmerNotifications().isEmpty()
        }

        openFile(DECISION_FILE)
        waitForBaselineAnalysis()
        removeText(FIRST_USAGE)
        removeText(SECOND_USAGE)
        waitForSuggestion(EXPECTED_MULTIPLE_SUGGESTION)

        invokeNotificationAction("Remove all")

        waitFor(
            message = "Both accepted imports are removed",
            timeout = 15.seconds,
            interval = 100.milliseconds,
        ) {
            val text = selectedDocument().getText()
            FIRST_IMPORT !in text && SECOND_IMPORT !in text && importTrimmerNotifications().isEmpty()
        }
    }

    @Test
    fun keepActionPreservesOfferedImports() = withIde("import-trimmer-keep") {
        openFile(TARGET_FILE)
        waitForBaselineAnalysis()
        removeUsage()
        waitForSuggestion()

        invokeNotificationAction("Keep")

        waitFor(
            message = "The kept import remains and the suggestion closes",
            timeout = 15.seconds,
            interval = 100.milliseconds,
        ) {
            FIRST_IMPORT in selectedDocument().getText() && importTrimmerNotifications().isEmpty()
        }

        openFile(DECISION_FILE)
        waitForBaselineAnalysis()
        removeText(FIRST_USAGE)
        removeText(SECOND_USAGE)
        waitForSuggestion(EXPECTED_MULTIPLE_SUGGESTION)

        invokeNotificationAction("Keep")

        waitFor(
            message = "Kept imports remain and the suggestion closes",
            timeout = 15.seconds,
            interval = 100.milliseconds,
        ) {
            val text = selectedDocument().getText()
            FIRST_IMPORT in text && SECOND_IMPORT in text && importTrimmerNotifications().isEmpty()
        }
    }

    companion object {
        private const val PROJECT_CYCLES = 2
        private const val EDITOR_CYCLES = 3
        private const val TARGET_FILE = "src/lifecycle/LifecycleTarget.java"
        private const val DECISION_FILE = "src/lifecycle/DecisionTarget.java"
        private const val EXPECTED_MULTIPLE_SUGGESTION = "2 imports are no longer used. Remove them?"
        private const val FIRST_IMPORT = "import dependency.SharedValue;"
        private const val SECOND_IMPORT = "import dependency.SecondValue;"
        private const val FIRST_USAGE = "    SharedValue first;\n"
        private const val SECOND_USAGE = "    SecondValue second;\n"
    }
}
