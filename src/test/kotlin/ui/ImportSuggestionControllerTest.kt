package io.github.nemf1s.ui

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationsManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import io.github.nemf1s.analysis.Candidate
import io.github.nemf1s.analysis.OccurrenceKey

class ImportSuggestionControllerTest : LightJavaCodeInsightFixtureTestCase() {
    private lateinit var scheduler: FakeScheduler
    private lateinit var controller: ImportSuggestionController
    private val accepted = mutableListOf<List<Candidate>>()
    private val closed = mutableListOf<Pair<List<Candidate>, SuggestionCloseReason>>()
    private var settingsOpened = 0

    override fun getProjectDescriptor(): LightProjectDescriptor = JAVA_21

    override fun setUp() {
        super.setUp()
        myFixture.configureByText("Notification.java", "class Notification {}")
        expireProjectNotifications()
        scheduler = FakeScheduler()
        controller = ImportSuggestionController(
            project = project,
            scheduler = scheduler,
            onAccepted = { _, candidates -> accepted += candidates },
            onClosed = { _, candidates, reason -> closed += candidates to reason },
            openSettings = { settingsOpened++ },
        )
    }

    override fun tearDown() {
        try {
            controller.close(SuggestionCloseReason.DISPOSED)
            dispatchEvents()
            expireProjectNotifications()
        } finally {
            super.tearDown()
        }
    }

    fun testRegisteredGroupPublishesConciseNativeNotificationWithoutMovingCaret() {
        assertTrue(NotificationGroupManager.getInstance().isGroupRegistered(GROUP_ID))
        myFixture.editor.caretModel.moveToOffset(3)

        controller.show(myFixture.editor, listOf(listCandidate()), timeoutMillis = 10_000)
        dispatchEvents()
        val notification = activeNotification()

        assertEquals("Unused import detected", notification.title)
        assertEquals("List is no longer used. Remove its import?", notification.content)
        assertFalse(notification.content.contains("Ignoring", ignoreCase = true))
        assertEquals(listOf("Keep", "Remove", "Settings"), notification.actions.map { it.templateText })
        assertEquals(3, myFixture.editor.caretModel.offset)
        assertTrue(controller.owns(myFixture.editor.document))
    }

    fun testConfiguredTimeoutExpiresAtBoundaryWithoutAccepting() {
        controller.show(myFixture.editor, listOf(listCandidate()), timeoutMillis = 10_000)
        dispatchEvents()
        val notification = activeNotification()

        scheduler.advance(9_999)
        dispatchEvents()
        assertFalse(notification.isExpired)
        assertTrue(closed.isEmpty())

        scheduler.advance(1)
        dispatchEvents()
        assertTrue(notification.isExpired)
        assertTrue(accepted.isEmpty())
        assertEquals(SuggestionCloseReason.TIMEOUT, closed.single().second)
        assertFalse(controller.owns(myFixture.editor.document))
    }

    fun testKeepActionExpiresWithoutAcceptingAndReportsKeep() {
        controller.show(myFixture.editor, listOf(listCandidate()), timeoutMillis = 10_000)
        dispatchEvents()
        val notification = activeNotification()

        invoke(notification, actionIndex = 0)

        assertTrue(notification.isExpired)
        assertTrue(accepted.isEmpty())
        assertEquals(listOf(listCandidate()) to SuggestionCloseReason.KEEP, closed.single())
        assertTrue(scheduler.tasks.single().cancelled)
    }

    fun testSettingsActionOpensPluginSettingsWithoutClosingSuggestion() {
        controller.show(myFixture.editor, listOf(listCandidate()), timeoutMillis = 10_000)
        dispatchEvents()
        val notification = activeNotification()

        invoke(notification, actionIndex = 2)

        assertEquals(1, settingsOpened)
        assertFalse(notification.isExpired)
        assertTrue(accepted.isEmpty())
        assertTrue(closed.isEmpty())
        assertTrue(controller.owns(myFixture.editor.document))
    }

    fun testPlatformDismissalReportsDismissedAndReleasesSnapshot() {
        controller.show(myFixture.editor, listOf(listCandidate()), timeoutMillis = 10_000)
        dispatchEvents()
        val notification = activeNotification()

        notification.expire()
        dispatchEvents()

        assertEquals(SuggestionCloseReason.DISMISSED, closed.single().second)
        assertFalse(controller.owns(myFixture.editor.document))
        assertTrue(scheduler.tasks.single().cancelled)
    }

    fun testStaleExpirationCannotCloseReplacementNotification() {
        controller.show(myFixture.editor, listOf(listCandidate()), timeoutMillis = 10_000)
        dispatchEvents()
        val firstNotification = activeNotification()
        val firstTask = scheduler.tasks.single()

        controller.show(myFixture.editor, listOf(setCandidate()), timeoutMillis = 10_000)
        dispatchEvents()
        val replacement = activeNotification()
        firstTask.runIgnoringCancellation()
        dispatchEvents()

        assertTrue(firstNotification.isExpired)
        assertFalse(replacement.isExpired)
        assertEquals("Set is no longer used. Remove its import?", replacement.content)
        assertTrue(controller.owns(myFixture.editor.document))
    }

    fun testRevalidatedSameOfferReusesNotificationAndOriginalTimeout() {
        controller.show(myFixture.editor, listOf(listCandidate()), timeoutMillis = 10_000)
        dispatchEvents()
        val notification = activeNotification()
        val timeout = scheduler.tasks.single()
        scheduler.advance(3_000)

        controller.invalidateAcceptance(myFixture.editor.document)
        invoke(notification, actionIndex = 1)
        assertTrue(accepted.isEmpty())
        assertFalse(notification.isExpired)

        controller.show(myFixture.editor, listOf(listCandidate()), timeoutMillis = 10_000)

        assertSame(notification, activeNotification())
        assertSame(timeout, scheduler.tasks.single())
        scheduler.advance(6_999)
        dispatchEvents()
        assertFalse(notification.isExpired)

        scheduler.advance(1)
        dispatchEvents()
        assertTrue(notification.isExpired)
        assertEquals(SuggestionCloseReason.TIMEOUT, closed.single().second)
    }

    fun testMultipleCandidatesStayFrozenAndUseRemoveAllAction() {
        val offered = mutableListOf(listCandidate(), setCandidate())
        controller.show(myFixture.editor, offered, timeoutMillis = 10_000)
        dispatchEvents()
        val notification = activeNotification()
        offered += Candidate(OccurrenceKey("java", "type:java.util.Map@60"), 1, "java.util.Map")

        assertEquals("2 imports are no longer used. Remove them?", notification.content)
        assertEquals(listOf("Keep", "Remove all", "Settings"), notification.actions.map { it.templateText })
        invoke(notification, actionIndex = 1)

        assertEquals(listOf(listCandidate(), setCandidate()), accepted.single())
        assertEquals(SuggestionCloseReason.ACCEPTED, closed.single().second)
    }

    fun testCloseIsReconciledBeforeImmediateReplacement() {
        controller.show(myFixture.editor, listOf(listCandidate()), timeoutMillis = 10_000)
        dispatchEvents()

        controller.close(SuggestionCloseReason.NAVIGATION)
        controller.show(myFixture.editor, listOf(setCandidate()), timeoutMillis = 10_000)

        assertEquals(listOf(listCandidate()) to SuggestionCloseReason.NAVIGATION, closed.single())
        assertEquals("Set is no longer used. Remove its import?", activeNotification().content)
    }

    fun testAdapterPromptTextPreservesQualifiedStaticAndWildcardLabels() {
        val static = Candidate(
            OccurrenceKey("java", "static:java.util.Collections.emptyList@0"),
            episode = 1,
            displayText = "static java.util.Collections.emptyList",
            promptText = "static java.util.Collections.emptyList",
        )
        controller.show(myFixture.editor, listOf(static), timeoutMillis = 10_000)
        dispatchEvents()

        assertEquals(
            "static java.util.Collections.emptyList is no longer used. Remove its import?",
            activeNotification().content,
        )

        val wildcard = Candidate(
            OccurrenceKey("java", "type:java.util@30:*"),
            episode = 1,
            displayText = "java.util.*",
            promptText = "java.util.*",
        )
        controller.show(myFixture.editor, listOf(wildcard), timeoutMillis = 10_000)
        dispatchEvents()

        assertEquals("java.util.* is no longer used. Remove its import?", activeNotification().content)
    }

    private fun activeNotification(): Notification = NotificationsManager.getNotificationsManager()
        .getNotificationsOfType(Notification::class.java, project)
        .single { it.groupId == GROUP_ID && !it.isExpired }

    private fun invoke(notification: Notification, actionIndex: Int) {
        val action = notification.actions[actionIndex] as NotificationAction
        val event = AnActionEvent.createEvent(
            DataContext.EMPTY_CONTEXT,
            null,
            ActionPlaces.UNKNOWN,
            ActionUiKind.NONE,
            null,
        )
        action.actionPerformed(event, notification)
        dispatchEvents()
    }

    private fun expireProjectNotifications() {
        NotificationsManager.getNotificationsManager()
            .getNotificationsOfType(Notification::class.java, project)
            .forEach(Notification::expire)
        dispatchEvents()
    }

    private fun dispatchEvents() {
        PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
    }

    private fun listCandidate() = Candidate(
        OccurrenceKey("java", "type:java.util.List@0"),
        episode = 1,
        displayText = "java.util.List",
        promptText = "List",
    )

    private fun setCandidate() = Candidate(
        OccurrenceKey("java", "type:java.util.Set@30"),
        episode = 1,
        displayText = "java.util.Set",
        promptText = "Set",
    )

    private class FakeScheduler : DelayScheduler {
        val tasks = mutableListOf<Task>()
        private var now = 0L

        override fun schedule(delayMillis: Long, block: () -> Unit): CancelHandle {
            val task = Task(now + delayMillis, block)
            tasks += task
            return CancelHandle { task.cancelled = true }
        }

        fun advance(millis: Long) {
            now += millis
            tasks.filter { !it.cancelled && !it.ran && it.at <= now }.sortedBy { it.at }.forEach(Task::run)
        }

        class Task(val at: Long, private val block: () -> Unit) {
            var cancelled = false
            var ran = false

            fun run() {
                if (cancelled || ran) return
                ran = true
                block()
            }

            fun runIgnoringCancellation() {
                if (ran) return
                ran = true
                block()
            }
        }
    }

    companion object {
        private const val GROUP_ID = "Import Trimmer suggestions"
    }
}
