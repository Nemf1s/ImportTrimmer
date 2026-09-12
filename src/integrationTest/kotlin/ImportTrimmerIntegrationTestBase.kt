package io.github.nemf1s.integration

import com.intellij.driver.client.Driver
import com.intellij.driver.client.Remote
import com.intellij.driver.client.service
import com.intellij.driver.client.utility
import com.intellij.driver.model.OnDispatcher
import com.intellij.driver.sdk.AnAction
import com.intellij.driver.sdk.Document
import com.intellij.driver.sdk.Editor
import com.intellij.driver.sdk.FileEditorManager
import com.intellij.driver.sdk.Notification
import com.intellij.driver.sdk.Project
import com.intellij.driver.sdk.getNotifications
import com.intellij.driver.sdk.invokeAction
import com.intellij.driver.sdk.openFile
import com.intellij.driver.sdk.singleProject
import com.intellij.driver.sdk.waitFor
import com.intellij.driver.sdk.waitForIndicators
import com.intellij.driver.sdk.ui.remote.Component
import com.intellij.ide.starter.ci.CIServer
import com.intellij.ide.starter.ci.NoCIServer
import com.intellij.ide.starter.di.di
import com.intellij.ide.starter.driver.engine.runIdeWithDriver
import com.intellij.ide.starter.models.IdeInfo
import com.intellij.ide.starter.models.TestCase
import com.intellij.ide.starter.plugins.PluginConfigurator
import com.intellij.ide.starter.project.LocalProjectInfo
import com.intellij.ide.starter.runner.Starter
import com.intellij.platform.testFramework.teamCity.TeamCityReporter.SyntheticTestKind
import com.intellij.tools.ide.starter.product.idea.ultimate.IdeaUltimate
import org.junit.jupiter.api.fail
import org.kodein.di.DI
import org.kodein.di.bindSingleton
import java.nio.file.Path
import kotlin.io.path.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

abstract class ImportTrimmerIntegrationTestBase {
    init {
        di = DI {
            extend(di)
            bindSingleton<CIServer>(overrides = true) {
                object : CIServer by NoCIServer {
                    override fun reportTestFailure(
                        testName: String,
                        message: String,
                        details: String,
                        linkToLogs: String?,
                        kind: SyntheticTestKind,
                        generifyTestName: Boolean,
                    ) {
                        if (message.contains(PLUGIN_PACKAGE) || details.contains(PLUGIN_PACKAGE)) {
                            fail { "$testName failed inside the IDE: $message\n$details" }
                        }
                    }
                }
            }
        }
    }

    protected fun withIde(
        testName: String,
        runTimeout: Duration = 5.minutes,
        test: Driver.() -> Unit,
    ) {
        val fixturePath = Path("src/integrationTest/testData/lifecycle-project").toAbsolutePath()
        val projectPath = Path("build/integrationTestProjects/$testName").toAbsolutePath()
        check(projectPath.toFile().deleteRecursively()) {
            "Could not reset the integration-test project at $projectPath"
        }
        check(fixturePath.toFile().copyRecursively(projectPath.toFile())) {
            "Could not copy the integration-test project from $fixturePath"
        }
        val pluginPath = Path.of(System.getProperty("path.to.build.plugin"))
        val context = Starter.newContext(
            testName,
            TestCase(
                IdeInfo.IdeaUltimate.copy(version = System.getProperty("integration.test.ide.version")),
                LocalProjectInfo(projectPath),
            ),
        ).apply {
            check(paths.configDir.toFile().deleteRecursively()) {
                "Could not reset the integration-test IDE config at ${paths.configDir}"
            }
            removeMigrateConfigAndCreateStubFile()
            PluginConfigurator(this).installPluginFromPath(pluginPath)
            addProjectToTrustedLocations()
            disableFusSendingOnIdeClose()
            applyVMOptionsPatch {
                addSystemProperty("ide.show.tips.on.startup.default.value", false)
            }
        }

        context.runIdeWithDriver(runTimeout = runTimeout).useDriverAndCloseIde {
            waitForIndicators(3.minutes)
            test()
        }
    }

    protected fun repeatedAutomaticRemovalAndUndo() = withIde("import-trimmer-automatic-removal-undo") {
        configureAutomaticRemoval()
        openFile(AUTOMATIC_TARGET_FILE)
        waitForBaselineAnalysis()

        repeat(AUTOMATIC_TEST_REPETITIONS) { repetition ->
            FIELD_GROUPS.forEachIndexed { index, fields ->
                val removedImports = IMPORTS.take(index + 1)
                val retainedImports = IMPORTS.drop(index + 1)

                removeText(fields)
                waitFor(
                    message = "Automatic removal ${index + 1} in repetition ${repetition + 1} did not finish " +
                        "within $AUTOMATIC_REMOVAL_BUDGET after the $AUTOMATIC_DEBOUNCE debounce",
                    timeout = AUTOMATIC_DEBOUNCE + AUTOMATIC_REMOVAL_BUDGET,
                    interval = AUTOMATIC_POLL_INTERVAL,
                ) {
                    val text = selectedDocument().getText()
                    removedImports.none(text::contains) && retainedImports.all(text::contains)
                }
                check(importTrimmerNotifications().isEmpty()) {
                    "Automatic removal unexpectedly displayed an Import Trimmer notification"
                }

                undo()
                waitFor(
                    message = "Undo did not restore the automatically removed imports",
                    timeout = UNDO_TIMEOUT,
                    interval = AUTOMATIC_POLL_INTERVAL,
                ) {
                    IMPORTS.all(selectedDocument().getText()::contains)
                }
                check(importTrimmerNotifications().isEmpty()) {
                    "Undo unexpectedly displayed an Import Trimmer notification"
                }

                // Import removal and the user's field deletion are separate undoable IDE commands.
                undo()
                waitFor(
                    message = "Undo did not restore the removed fields",
                    timeout = UNDO_TIMEOUT,
                    interval = AUTOMATIC_POLL_INTERVAL,
                ) {
                    FIELDS.all(selectedDocument().getText()::contains)
                }
                waitForBaselineAnalysis()
            }
        }
    }

    private fun Driver.configureAutomaticRemoval() {
        withContext(OnDispatcher.EDT) {
            val project = singleProject()
            val settings = service<RemoteImportTrimmerSettings>(project)
            val preferences = settings.getState()
            preferences.setEnabled(true)
            preferences.setMode(utility<RemoteRemovalMode>().valueOf("AUTOMATIC"))
            preferences.setDebounceMs(AUTOMATIC_DEBOUNCE.inWholeMilliseconds.toInt())
            settings.loadState(preferences)
            service<RemoteImportTrimmerProjectService>(project).settingsChanged()
        }
    }

    protected fun Driver.restoreUsage() {
        if (USAGE in selectedDocument().getText()) {
            return
        }

        undo()
        waitFor(
            message = "Usage is restored by the IDE undo command",
            timeout = 10.seconds,
            interval = 100.milliseconds,
        ) {
            USAGE in selectedDocument().getText()
        }
    }

    protected fun Driver.removeUsage() {
        removeText(USAGE)
    }

    protected fun Driver.removeText(text: String) {
        val editor = selectedEditor()
        val startOffset = editor.getDocument().getText().indexOf(text)
        check(startOffset >= 0) { "Expected text was not found: $text" }
        withContext(OnDispatcher.EDT) {
            editor.getSelectionModel().setSelection(startOffset, startOffset + text.length)
        }
        invokeEditorActionWithRetries("EditorBackSpace", editor)
        waitFor(
            message = "Usage is removed by the IDE editor command",
            timeout = 10.seconds,
            interval = 100.milliseconds,
        ) {
            text !in selectedDocument().getText()
        }
    }

    protected fun Driver.undo() {
        invokeEditorActionWithRetries("\$Undo")
    }

    protected fun Driver.invokeNotificationAction(actionText: String) {
        val notification = importTrimmerNotification()
            ?: error("No Import Trimmer notification")
        val action = notification.getActions().singleOrNull { it.getTemplateText() == actionText }
            ?: error("Notification action '$actionText' was not found")
        withContext(OnDispatcher.EDT) {
            utility<NotificationInvoker>().fire(notification, action, null)
        }
    }

    protected fun Driver.selectedDocument(): Document = selectedEditor().getDocument()

    protected fun Driver.closeCurrentFile() {
        withContext(OnDispatcher.EDT) {
            val project = singleProject()
            val manager = service<FileEditorManager>(project)
            manager.closeFile(manager.getCurrentFile())
        }
        waitFor(
            message = "Editor is released",
            timeout = 15.seconds,
            interval = 100.milliseconds,
        ) {
            withContext {
                val project = singleProject()
                service<FileEditorManager>(project).getAllEditors().isEmpty()
            }
        }
    }

    protected fun Driver.closeAllFiles() {
        withContext(OnDispatcher.EDT) {
            val project = singleProject()
            val manager = service<FileEditorManager>(project)
            manager.getAllEditors().forEach { manager.closeFile(it.getFile()) }
        }
        waitFor(
            message = "All stress editors are released",
            timeout = 15.seconds,
            interval = 100.milliseconds,
        ) {
            withContext {
                val project = singleProject()
                service<FileEditorManager>(project).getAllEditors().isEmpty()
            }
        }
    }

    protected fun Driver.waitForSuggestion(expectedContent: String = EXPECTED_SUGGESTION) {
        waitFor(
            message = "Import Trimmer suggestion is published",
            timeout = 30.seconds,
            interval = 100.milliseconds,
        ) {
            importTrimmerNotifications().singleOrNull() == expectedContent
        }
    }

    protected fun Driver.waitForSuggestionToClose() {
        waitFor(
            message = "Import Trimmer suggestion is retired with its editor",
            timeout = 15.seconds,
            interval = 100.milliseconds,
        ) {
            importTrimmerNotifications().isEmpty()
        }
    }

    protected fun Driver.importTrimmerNotifications(): List<String> = withContext {
        importTrimmerNotification()?.let { listOf(it.getContent()) }.orEmpty()
    }

    protected fun waitForBaselineAnalysis() {
        Thread.sleep(BASELINE_SETTLE.inWholeMilliseconds)
    }

    private fun Driver.invokeEditorActionWithRetries(actionId: String, editor: Editor = selectedEditor()) {
        val component = cast(editor, EditorWithComponent::class).getContentComponent()
        var lastFailure: RuntimeException? = null
        repeat(ACTION_RETRIES + 1) { attempt ->
            try {
                invokeAction(actionId, component = component)
                return
            } catch (failure: RuntimeException) {
                lastFailure = failure
                if (attempt < ACTION_RETRIES) {
                    Thread.sleep(ACTION_RETRY_DELAY.inWholeMilliseconds)
                }
            }
        }
        throw checkNotNull(lastFailure)
    }

    private fun Driver.selectedEditor(): Editor {
        val project = singleProject()
        return service<FileEditorManager>(project).getSelectedTextEditor()
            ?: error("No selected text editor")
    }

    private fun Driver.importTrimmerNotification(): Notification? = withContext {
        val project: Project = singleProject()
        getNotifications(project)
            .singleOrNull { it.getGroupId() == NOTIFICATION_GROUP }
    }

    companion object {
        private const val PLUGIN_PACKAGE = "io.github.nemf1s"
        private const val NOTIFICATION_GROUP = "Import Trimmer suggestions"
        private const val EXPECTED_SUGGESTION = "SharedValue is no longer used. Remove its import?"
        private const val USAGE = "    SharedValue value;\n"
        private const val AUTOMATIC_TARGET_FILE = "src/lifecycle/AutomaticRemovalTarget.java"
        private const val FIELD_A = "    SharedValue fieldA;\n"
        private const val FIELD_B = "    SecondValue fieldB;\n"
        private const val FIELD_C = "    ThirdValue fieldC;\n"
        private const val AUTOMATIC_TEST_REPETITIONS = 10
        private const val ACTION_RETRIES = 10
        private val IMPORTS = listOf(
            "import dependency.SharedValue;",
            "import dependency.SecondValue;",
            "import dependency.ThirdValue;",
        )
        private val FIELDS = listOf(FIELD_A, FIELD_B, FIELD_C)
        private val FIELD_GROUPS = listOf(FIELD_A, FIELD_A + FIELD_B, FIELD_A + FIELD_B + FIELD_C)
        private val AUTOMATIC_DEBOUNCE = 250.milliseconds
        private val AUTOMATIC_REMOVAL_BUDGET = 150.milliseconds
        private val AUTOMATIC_POLL_INTERVAL = 10.milliseconds
        private val UNDO_TIMEOUT = 2.seconds
        private val BASELINE_SETTLE = 1.seconds
        private val ACTION_RETRY_DELAY = 100.milliseconds
    }
}

@Remote("io.github.nemf1s.ImportTrimmerProjectService", plugin = "io.github.nemf1s.ImportTrimmer")
private interface RemoteImportTrimmerProjectService {
    fun settingsChanged()
}

@Remote("io.github.nemf1s.settings.ImportTrimmerSettings", plugin = "io.github.nemf1s.ImportTrimmer")
private interface RemoteImportTrimmerSettings {
    fun getState(): RemotePreferences
    fun loadState(state: RemotePreferences)
}

@Remote("io.github.nemf1s.settings.Preferences", plugin = "io.github.nemf1s.ImportTrimmer")
private interface RemotePreferences {
    fun setEnabled(enabled: Boolean)
    fun setMode(mode: RemoteRemovalMode)
    fun setDebounceMs(debounceMs: Int)
}

@Remote("io.github.nemf1s.settings.RemovalMode", plugin = "io.github.nemf1s.ImportTrimmer")
private interface RemoteRemovalMode {
    fun valueOf(name: String): RemoteRemovalMode
}

@Remote("com.intellij.notification.Notification")
private interface NotificationInvoker {
    fun fire(notification: Notification, action: AnAction, context: RemoteDataContext?)
}

@Remote("com.intellij.openapi.actionSystem.DataContext")
private interface RemoteDataContext

@Remote("com.intellij.openapi.editor.Editor")
private interface EditorWithComponent {
    fun getContentComponent(): Component
}
