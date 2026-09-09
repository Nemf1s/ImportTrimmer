package io.github.nemf1s

import com.intellij.notification.Notification
import com.intellij.notification.NotificationsManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
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
import io.github.nemf1s.tracking.DocumentImportState
import io.github.nemf1s.tracking.ImportTransitionTracker
import io.github.nemf1s.analysis.*
import io.github.nemf1s.analysis.java.JavaImportAnalyzer
import io.github.nemf1s.editing.java.JavaImportEditPlanner
import java.util.IdentityHashMap
import kotlinx.coroutines.Job

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
            states(service)[document]?.let { ImportTransitionTracker().candidates(it, manual = true).isNotEmpty() } == true
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
        assertTrue(ImportTransitionTracker().candidates(states(service).getValue(document)).isEmpty())
    }

    fun testSplitReleaseKeepsStateAndFinalReleaseRetiresIt() {
        configureTransitionFile("SplitExample.java", "SplitExample")
        val service = start(RemovalMode.ASK)
        awaitBaseline(service)
        val split = EditorFactory.getInstance().createEditor(document, project)
        waitUntil("Split editor was not observed") { editorCounts(service)[document] == 2 }

        EditorFactory.getInstance().releaseEditor(split)
        dispatchEvents()
        assertEquals(1, editorCounts(service)[document])
        assertTrue(states(service).containsKey(document))

        invokeReleaseEditor(service, myFixture.editor)
        assertFalse(states(service).containsKey(document))
        assertFalse(editorCounts(service).containsKey(document))
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
            analysisJobs(service).isEmpty() && states(service)[document]?.records?.values?.any {
                it.observation.displayText == "java.util.List" && it.lastReliable == SemanticStatus.USED
            } == true
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
        val priorGeneration = states(service).getValue(document).generation
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
        assertTrue(states(service).getValue(document).generation > priorGeneration)
        assertTrue(states(service).getValue(document).records.isEmpty())
    }

    fun testIndexingEntryWithdrawsSuggestionButPreservesReliableHistory() {
        configureTransitionFile("IndexingExample.java", "IndexingExample")
        val service = start(RemovalMode.ASK)
        awaitBaseline(service)
        deleteLastUsage()
        waitUntil("Ask-mode notification was not published") { activeNotifications().size == 1 }
        val notification = activeNotifications().single()
        val records = states(service).getValue(document).records

        project.messageBus.syncPublisher(DumbService.DUMB_MODE).enteredDumbMode()
        dispatchEvents()

        assertTrue(notification.isExpired)
        assertEquals(records, states(service).getValue(document).records)
        assertFalse(states(service).getValue(document).executable)
    }

    fun testUnrelatedTypingRetainsOriginalEpisodeDeadline() {
        configureTransitionFile("DeadlineExample.java", "DeadlineExample")
        val service = start(RemovalMode.ASK)
        awaitBaseline(service)
        deleteLastUsage()
        waitUntil("Initial notification was not published") { activeNotifications().size == 1 }
        val initialState = states(service).getValue(document)
        val candidate = ImportTransitionTracker().candidates(initialState).single()
        val initialDeadline = initialState.records.getValue(candidate.key).offerDeadlineMillis

        WriteCommandAction.runWriteCommandAction(project) {
            val insertion = document.text.lastIndexOf('}')
            document.insertString(insertion, "    int unrelated;\n")
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        waitUntil("Revalidated notification was not published") { activeNotifications().size == 1 }

        val refreshedState = states(service).getValue(document)
        assertEquals(initialDeadline, refreshedState.records.getValue(candidate.key).offerDeadlineMillis)
    }

    fun testManualReviewWaitsUntilCaretLeavesImportBlock() {
        configureTransitionFile("ManualInteraction.java", "ManualInteraction")
        val service = start(RemovalMode.MANUAL)
        awaitBaseline(service)
        deleteLastUsage()
        waitUntil("Manual candidate was not tracked") {
            states(service)[document]?.let { ImportTransitionTracker().candidates(it, manual = true).isNotEmpty() } == true
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

        assertTrue(states(service).isEmpty())
        assertTrue(editorCounts(service).isEmpty())
        assertTrue(activeNotifications().isEmpty())
    }

    fun testProjectPsiChangeInvalidatesSnapshotBeforePublication() {
        configureTransitionFile("FreshnessExample.java", "FreshnessExample")
        val service = start(RemovalMode.ASK)
        awaitBaseline(service)
        val state = states(service).getValue(document)
        val currentEpoch = field("epoch").getLong(service)
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
        providers(service).clear()

        service.start()

        waitUntil("Analysis job retained after provider selection declined") {
            analysisJobs(service).isEmpty()
        }
        resetProviders(service)
    }

    fun testRemovalJobIsReleasedAndStateReconciledAfterPlannerFailure() {
        configureTransitionFile("PlannerFailure.java", "PlannerFailure")
        val service = start(RemovalMode.ASK)
        awaitBaseline(service)
        deleteLastUsage()
        waitUntil("Candidate was not established") {
            states(service)[document]?.let { ImportTransitionTracker().candidates(it).isNotEmpty() } == true
        }
        val candidates = ImportTransitionTracker().candidates(states(service).getValue(document))
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
        providers(service).apply {
            clear()
            add(ImportProvider(analyzer, failingPlanner))
        }

        invokeRemoveAccepted(service, candidates)

        waitUntil("Removal job retained after planner failure") { removalJobs(service).isEmpty() }
        resetProviders(service)
        assertTrue(document.text.contains("import java.util.List;"))
        assertTrue(states(service).getValue(document).records.isEmpty())
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
            states(service)[document]?.records?.size == 2
        }
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

    @Suppress("UNCHECKED_CAST")
    private fun states(service: ImportTrimmerProjectService): IdentityHashMap<Document, DocumentImportState> =
        field("states").get(service) as IdentityHashMap<Document, DocumentImportState>

    @Suppress("UNCHECKED_CAST")
    private fun editorCounts(service: ImportTrimmerProjectService): IdentityHashMap<Document, Int> =
        field("editorCounts").get(service) as IdentityHashMap<Document, Int>

    @Suppress("UNCHECKED_CAST")
    private fun analysisJobs(service: ImportTrimmerProjectService): IdentityHashMap<Document, Job> =
        field("analysisJobs").get(service) as IdentityHashMap<Document, Job>

    @Suppress("UNCHECKED_CAST")
    private fun removalJobs(service: ImportTrimmerProjectService): IdentityHashMap<Document, Job> =
        field("removalJobs").get(service) as IdentityHashMap<Document, Job>

    @Suppress("UNCHECKED_CAST")
    private fun providers(service: ImportTrimmerProjectService): MutableList<ImportProvider> =
        field("providers").get(service) as MutableList<ImportProvider>

    private fun resetProviders(service: ImportTrimmerProjectService) {
        providers(service).apply {
            clear()
            add(ImportProvider(JavaImportAnalyzer(), JavaImportEditPlanner()))
        }
    }

    private fun field(name: String) = ImportTrimmerProjectService::class.java.getDeclaredField(name).apply {
        isAccessible = true
    }

    private fun invokeReleaseEditor(service: ImportTrimmerProjectService, editor: Editor) {
        ImportTrimmerProjectService::class.java.getDeclaredMethod("releaseEditor", Editor::class.java).apply {
            isAccessible = true
        }.invoke(service, editor)
    }

    private fun invokeRemoveAccepted(service: ImportTrimmerProjectService, candidates: List<Candidate>) {
        ImportTrimmerProjectService::class.java.getDeclaredMethod(
            "removeAccepted",
            Document::class.java,
            List::class.java,
        ).apply { isAccessible = true }.invoke(service, document, candidates)
    }

    companion object {
        private const val GROUP_ID = "Import Trimmer suggestions"
    }
}
