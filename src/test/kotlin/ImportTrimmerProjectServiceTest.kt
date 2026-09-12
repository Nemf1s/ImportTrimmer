package io.github.nemf1s

import com.intellij.notification.Notification
import com.intellij.notification.NotificationsManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.roots.ModuleRootEvent
import com.intellij.openapi.roots.ModuleRootListener
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import io.github.nemf1s.settings.ImportTrimmerSettings
import io.github.nemf1s.settings.Preferences
import io.github.nemf1s.settings.RemovalMode
import io.github.nemf1s.tracking.ImportTransitionTracker
import io.github.nemf1s.analysis.*
import io.github.nemf1s.analysis.java.JavaImportAnalyzer
import io.github.nemf1s.editing.java.JavaImportEditPlanner

class ImportTrimmerProjectServiceTest : LightJavaCodeInsightFixtureTestCase() {
    private var service: ImportTrimmerProjectService? = null

    override fun getProjectDescriptor(): LightProjectDescriptor = JAVA_21

    override fun tearDown() {
        try {
            service?.let(::resetProviders)
            service?.dispose()
            expireNotifications()
        } finally {
            super.tearDown()
        }
    }

    fun testAskModePublishesOnlyAfterObservedUsedToUnusedTransition() {
        configureTransitionFile("AskExample.java", "AskExample")
        val service = start(RemovalMode.ASK)
        awaitBaseline(service)

        assertTrue(activeNotifications().isEmpty())
        deleteLastUsage()
        waitUntil("Ask-mode notification was not published") { activeNotifications().size == 1 }

        val notification = activeNotifications().single()
        assertEquals("List is no longer used. Remove its import?", notification.content)
        assertTrue(document.text.contains("import java.util.Map;"))
        assertTrue(document.text.contains("import java.util.List;"))
    }

    fun testAutomaticModeRemovesAndUndoDoesNotImmediatelyRemoveAgain() {
        val before = configureTransitionFile("AutomaticExample.java", "AutomaticExample")
        val service = start(RemovalMode.AUTOMATIC)
        awaitBaseline(service)

        deleteLastUsage()
        waitUntil("Automatic mode did not remove the eligible import") {
            !document.text.contains("import java.util.List;")
        }
        assertTrue(activeNotifications().isEmpty())

        UndoManager.getInstance(project).undo(FileEditorManager.getInstance(project).selectedEditor)
        waitForBackgroundWork(800)

        assertEquals(before.replace("    List<String> names;\n", ""), document.text)
        assertTrue(document.text.contains("import java.util.List;"))
    }

    fun testManualModeKeepsImportAndPublishesOnlyForExplicitReview() {
        configureTransitionFile("ManualExample.java", "ManualExample")
        val service = start(RemovalMode.MANUAL)
        awaitBaseline(service)
        deleteLastUsage()
        waitUntil("Manual candidate was not tracked") {
            snapshot(service).documentState
                ?.let { ImportTransitionTracker().candidates(it, manual = true).isNotEmpty() } == true
        }

        assertTrue(activeNotifications().isEmpty())
        assertTrue(document.text.contains("import java.util.List;"))

        service.review(myFixture.editor)
        waitUntil("Manual review did not publish after fresh analysis") { activeNotifications().size == 1 }
        assertEquals("List is no longer used. Remove its import?", activeNotifications().single().content)
    }

    fun testSettingsChangeExpiresConsentAndRebaselinesWithoutDeletingBacklog() {
        configureTransitionFile("SettingsExample.java", "SettingsExample")
        val service = start(RemovalMode.ASK)
        awaitBaseline(service)
        deleteLastUsage()
        waitUntil("Ask-mode notification was not published") { activeNotifications().size == 1 }
        val oldNotification = activeNotifications().single()

        project.service<ImportTrimmerSettings>().loadState(preferences(RemovalMode.AUTOMATIC))
        service.settingsChanged()
        dispatchEvents()
        waitForBackgroundWork(500)

        assertTrue(oldNotification.isExpired)
        assertTrue(document.text.contains("import java.util.List;"))
        assertTrue(activeNotifications().isEmpty())
        assertTrue(ImportTransitionTracker().candidates(state(service)).isEmpty())
    }

    fun testSplitReleaseKeepsStateAndFinalReleaseRetiresIt() {
        configureTransitionFile("SplitExample.java", "SplitExample")
        val service = start(RemovalMode.ASK)
        awaitBaseline(service)
        val split = EditorFactory.getInstance().createEditor(document, project)
        waitUntil("Split editor was not observed") { snapshot(service).editorCount == 2 }
        service.setPluginEditForTest(document, active = true)

        EditorFactory.getInstance().releaseEditor(split)
        dispatchEvents()
        assertEquals(1, snapshot(service).editorCount)
        assertNotNull(snapshot(service).documentState)
        assertTrue(snapshot(service).pluginEditActive)

        service.releaseEditorForTest(myFixture.editor)
        assertNull(snapshot(service).documentState)
        assertEquals(0, snapshot(service).editorCount)
        assertFalse(snapshot(service).pluginEditActive)
    }

    fun testDisposeClearsPluginEditDocuments() {
        configureTransitionFile("DisposeExample.java", "DisposeExample")
        val service = start(RemovalMode.ASK)
        awaitBaseline(service)
        service.setPluginEditForTest(document, active = true)

        service.dispose()

        assertEquals(0, snapshot(service).pluginEditCount)
    }

    fun testRapidSupersedingEditCannotPublishStaleUnusedResult() {
        configureTransitionFile("RapidExample.java", "RapidExample")
        val service = start(RemovalMode.ASK)
        awaitBaseline(service)

        deleteLastUsage()
        WriteCommandAction.runWriteCommandAction(project) {
            val insertion = document.text.lastIndexOf('}')
            document.insertString(insertion, "    List<String> replacement;\n")
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        waitUntil("Superseding analysis did not settle") {
            snapshot(service).let { snapshot ->
                snapshot.pendingAnalysisCount == 0 && snapshot.documentState?.records?.values?.any {
                    it.observation.displayText == "java.util.List" && it.lastReliable == SemanticStatus.USED
                } == true
            }
        }

        assertTrue(activeNotifications().isEmpty())
        assertTrue(document.text.contains("import java.util.List;"))
    }

    fun testRootModelChangeExpiresNotificationAndInvalidatesExecutableState() {
        configureTransitionFile("RootsExample.java", "RootsExample")
        val service = start(RemovalMode.ASK)
        awaitBaseline(service)
        deleteLastUsage()
        waitUntil("Ask-mode notification was not published") { activeNotifications().size == 1 }
        val notification = activeNotifications().single()
        val priorGeneration = state(service).generation
        val event = object : ModuleRootEvent(project) {
            override fun isCausedByFileTypesChange() = false
            override fun isCausedByWorkspaceModelChangesOnly() = false
        }

        WriteCommandAction.runWriteCommandAction(project) {
            val publisher = project.messageBus.syncPublisher(ModuleRootListener.TOPIC)
            publisher.beforeRootsChange(event)
            publisher.rootsChanged(event)
        }
        dispatchEvents()

        assertTrue(notification.isExpired)
        assertTrue(state(service).generation > priorGeneration)
        assertTrue(state(service).records.isEmpty())
    }

    fun testIndexingEntryWithdrawsSuggestionButPreservesReliableHistory() {
        configureTransitionFile("IndexingExample.java", "IndexingExample")
        val service = start(RemovalMode.ASK)
        awaitBaseline(service)
        deleteLastUsage()
        waitUntil("Ask-mode notification was not published") { activeNotifications().size == 1 }
        val notification = activeNotifications().single()
        val records = state(service).records

        project.messageBus.syncPublisher(DumbService.DUMB_MODE).enteredDumbMode()
        dispatchEvents()

        assertTrue(notification.isExpired)
        assertEquals(records, state(service).records)
        assertFalse(state(service).executable)
    }

    fun testUnrelatedTypingRetainsOriginalEpisodeDeadline() {
        configureTransitionFile("DeadlineExample.java", "DeadlineExample")
        val service = start(RemovalMode.ASK)
        awaitBaseline(service)
        deleteLastUsage()
        waitUntil("Initial notification was not published") { activeNotifications().size == 1 }
        val initialNotification = activeNotifications().single()
        val initialState = state(service)
        val candidate = ImportTransitionTracker().candidates(initialState).single()
        val initialDeadline = initialState.records.getValue(candidate.key).offerDeadlineMillis

        WriteCommandAction.runWriteCommandAction(project) {
            val insertion = document.text.lastIndexOf('}')
            document.insertString(insertion, "    int unrelated;\n")
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        waitUntil("Suggestion was not revalidated") {
            snapshot(service).let { it.pendingAnalysisCount == 0 && it.documentState?.executable == true }
        }

        val refreshedState = state(service)
        assertSame(initialNotification, activeNotifications().single())
        assertEquals(initialDeadline, refreshedState.records.getValue(candidate.key).offerDeadlineMillis)
    }

    fun testManualReviewWaitsUntilCaretLeavesImportBlock() {
        configureTransitionFile("ManualInteraction.java", "ManualInteraction")
        val service = start(RemovalMode.MANUAL)
        awaitBaseline(service)
        deleteLastUsage()
        waitUntil("Manual candidate was not tracked") {
            snapshot(service).documentState
                ?.let { ImportTransitionTracker().candidates(it, manual = true).isNotEmpty() } == true
        }
        myFixture.editor.caretModel.moveToOffset(document.text.indexOf("import"))

        service.review(myFixture.editor)
        waitForBackgroundWork(600)
        assertTrue(activeNotifications().isEmpty())

        myFixture.editor.caretModel.moveToOffset(document.text.lastIndexOf('}'))
        waitUntil("Manual review did not resume after leaving the import block") {
            activeNotifications().size == 1
        }
    }

    fun testNonJavaEditorIsNeverTrackedOrPrompted() {
        myFixture.configureByText("notes.txt", "List is plain text")
        val service = start(RemovalMode.ASK)
        waitForBackgroundWork(400)

        assertEquals(0, snapshot(service).trackedDocumentCount)
        assertEquals(0, snapshot(service).observedDocumentCount)
        assertTrue(activeNotifications().isEmpty())
    }

    fun testProjectPsiChangeInvalidatesSnapshotBeforePublication() {
        configureTransitionFile("FreshnessExample.java", "FreshnessExample")
        val service = start(RemovalMode.ASK)
        awaitBaseline(service)
        val state = state(service)
        val currentEpoch = snapshot(service).epoch
        val snapshot = AnalysisSnapshot(
            providerId = JavaImportAnalyzer.ID,
            token = FreshnessToken(
                document.modificationStamp,
                PsiModificationTracker.getInstance(project).modificationCount,
                state.generation,
                currentEpoch,
            ),
            quality = AnalysisQuality.RELIABLE,
        )
        assertTrue(service.snapshotIsCurrent(document, snapshot, state.generation, currentEpoch))

        myFixture.addClass("class IndependentPsiChange {}")

        assertFalse(service.snapshotIsCurrent(document, snapshot, state.generation, currentEpoch))
    }

    fun testAnalysisJobIsReleasedWhenNoProviderMatches() {
        configureTransitionFile("NoProvider.java", "NoProvider")
        project.service<ImportTrimmerSettings>().loadState(preferences(RemovalMode.ASK))
        val service = project.service<ImportTrimmerProjectService>().also { this.service = it }
        service.replaceProvidersForTest(emptyList())

        service.start()

        waitUntil("Analysis job retained after provider selection declined") {
            snapshot(service).pendingAnalysisCount == 0
        }
        resetProviders(service)
    }

    fun testRemovalJobIsReleasedAndStateReconciledAfterPlannerFailure() {
        configureTransitionFile("PlannerFailure.java", "PlannerFailure")
        val service = start(RemovalMode.ASK)
        awaitBaseline(service)
        deleteLastUsage()
        waitUntil("Candidate was not established") {
            snapshot(service).documentState?.let { ImportTransitionTracker().candidates(it).isNotEmpty() } == true
        }
        val candidates = ImportTransitionTracker().candidates(state(service))
        val analyzer = JavaImportAnalyzer()
        val failingPlanner = object : ImportEditPlanner {
            override val providerId = JavaImportAnalyzer.ID
            override fun plan(
                file: com.intellij.psi.PsiFile,
                document: Document,
                snapshot: AnalysisSnapshot,
                accepted: List<Candidate>,
            ): ImportEditPlan? {
                throw IllegalStateException("deliberate planner failure")
            }
        }
        service.replaceProvidersForTest(listOf(ImportProvider(analyzer, failingPlanner)))

        service.removeAcceptedForTest(document, candidates)

        waitUntil("Removal failure was not reconciled through fresh analysis") {
            snapshot(service).let {
                it.pendingRemovalCount == 0 && it.pendingAnalysisCount == 0 &&
                    it.documentState?.let { state -> state.executable && state.records.size == 2 } == true
            }
        }
        resetProviders(service)
        assertTrue(document.text.contains("import java.util.List;"))
        assertEquals(2, state(service).records.size)
        assertTrue(ImportTransitionTracker().candidates(state(service)).isEmpty())
    }

    fun testRemovalFailureAfterPartialMutationSchedulesFreshAnalysis() {
        val text = """
            import java.util.concurrent.atomic.AtomicReference;

            class PartialFailure {
                AtomicReference<String> value;
            }
        """.trimIndent()
        myFixture.configureByText("PartialFailure.java", text)
        myFixture.editor.caretModel.moveToOffset(text.lastIndexOf('}'))
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val service = start(RemovalMode.ASK)
        waitUntil("Initial import baseline was not established") {
            snapshot(service).documentState?.records?.size == 1
        }

        WriteCommandAction.runWriteCommandAction(project) {
            val usage = "    AtomicReference<String> value;\n"
            val start = document.text.indexOf(usage)
            assertTrue(start >= 0)
            document.deleteString(start, start + usage.length)
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        waitUntil("Candidate was not established") {
            snapshot(service).documentState?.let { ImportTransitionTracker().candidates(it).size == 1 } == true
        }
        val candidates = ImportTransitionTracker().candidates(state(service))
        val generationBeforeRemoval = state(service).generation
        val delegate = JavaImportEditPlanner()
        val partiallyFailingPlanner = object : ImportEditPlanner {
            override val providerId = JavaImportAnalyzer.ID
            override fun plan(
                file: com.intellij.psi.PsiFile,
                document: Document,
                snapshot: AnalysisSnapshot,
                accepted: List<Candidate>,
            ): ImportEditPlan? {
                val valid = delegate.plan(file, document, snapshot, accepted) ?: return null
                val deletion = valid.deletions.single()
                return valid.copy(deletions = listOf(deletion, deletion))
            }
        }
        service.replaceProvidersForTest(
            listOf(ImportProvider(JavaImportAnalyzer(), partiallyFailingPlanner)),
        )

        service.removeAcceptedForTest(document, candidates)

        waitUntil("Partial removal failure was not reconciled through fresh analysis") {
            snapshot(service).let {
                it.pendingRemovalCount == 0 && it.pendingAnalysisCount == 0 &&
                    it.documentState?.let { state ->
                        state.generation > generationBeforeRemoval && state.executable && state.records.isEmpty()
                    } == true
            }
        }
        resetProviders(service)
        assertFalse(document.text.contains("import java.util.concurrent.atomic.AtomicReference;"))
        assertTrue(document.text.contains("class PartialFailure"))
        assertTrue(ImportTransitionTracker().candidates(state(service)).isEmpty())
    }

    private val document: Document
        get() = myFixture.editor.document

    private fun configureTransitionFile(name: String, className: String): String {
        val text = """
            import java.util.Map;
            import java.util.List;

            class $className {
                List<String> names;
            }
        """.trimIndent()
        myFixture.configureByText(name, text)
        myFixture.editor.caretModel.moveToOffset(text.lastIndexOf('}'))
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        return text
    }

    private fun start(mode: RemovalMode): ImportTrimmerProjectService {
        project.service<ImportTrimmerSettings>().loadState(preferences(mode))
        return project.service<ImportTrimmerProjectService>().also {
            service = it
            it.start()
        }
    }

    private fun preferences(mode: RemovalMode) = Preferences(
        enabled = true,
        mode = mode,
        debounceMs = 250,
        timeoutSeconds = 60,
    )

    private fun awaitBaseline(service: ImportTrimmerProjectService) {
        waitUntil("Initial import baseline was not established") {
            service.hasReliableBaseline(document)
        }
        assertEquals(2, snapshot(service).documentState?.records?.size)
    }

    private fun deleteLastUsage() {
        WriteCommandAction.runWriteCommandAction(project) {
            val text = "    List<String> names;\n"
            val start = document.text.indexOf(text)
            assertTrue(start >= 0)
            document.deleteString(start, start + text.length)
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
    }

    private fun waitUntil(message: String, timeoutMillis: Long = 8_000, condition: () -> Boolean) {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (System.nanoTime() < deadline) {
            dispatchEvents()
            if (condition()) return
            Thread.sleep(20)
        }
        fail(message)
    }

    private fun waitForBackgroundWork(millis: Long) {
        val deadline = System.nanoTime() + millis * 1_000_000
        while (System.nanoTime() < deadline) {
            dispatchEvents()
            Thread.sleep(20)
        }
        dispatchEvents()
    }

    private fun dispatchEvents() {
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }

    private fun activeNotifications(): List<Notification> = NotificationsManager.getNotificationsManager()
        .getNotificationsOfType(Notification::class.java, project)
        .filter { it.groupId == GROUP_ID && !it.isExpired }

    private fun expireNotifications() {
        NotificationsManager.getNotificationsManager()
            .getNotificationsOfType(Notification::class.java, project)
            .forEach(Notification::expire)
        dispatchEvents()
    }

    private fun resetProviders(service: ImportTrimmerProjectService) {
        service.replaceProvidersForTest(
            listOf(ImportProvider(JavaImportAnalyzer(), JavaImportEditPlanner())),
        )
    }

    private fun snapshot(service: ImportTrimmerProjectService): ImportTrimmerServiceSnapshot =
        service.diagnosticSnapshot(document)

    private fun state(service: ImportTrimmerProjectService) =
        requireNotNull(snapshot(service).documentState)

    companion object {
        private const val GROUP_ID = "Import Trimmer suggestions"
    }
}
