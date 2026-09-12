package io.github.nemf1s.ui

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.Project
import io.github.nemf1s.ImportTrimmerBundle.message
import io.github.nemf1s.analysis.Candidate
import io.github.nemf1s.settings.ImportTrimmerConfigurable
import java.util.concurrent.atomic.AtomicLong
import javax.swing.SwingUtilities

enum class SuggestionCloseReason {
    KEEP,
    TIMEOUT,
    DISMISSED,
    NAVIGATION,
    ACCEPTED,
    OBSOLETE,
    UNCERTAIN,
    DISPOSED,
}

class ImportSuggestionController(
    private val project: Project,
    private val scheduler: DelayScheduler,
    private val onAccepted: (Document, List<Candidate>) -> Unit,
    private val onClosed: (Document, List<Candidate>, SuggestionCloseReason) -> Unit,
    private val openSettings: (Project) -> Unit = {
        ShowSettingsUtil.getInstance().showSettingsDialog(it, ImportTrimmerConfigurable::class.java)
    },
) {
    private var notification: Notification? = null
    private var notificationToken = 0L
    private var document: Document? = null
    private var candidates: List<Candidate> = emptyList()
    private var timeout: CancelHandle? = null
    private var acceptanceEnabled = false

    fun show(editor: Editor, offered: List<Candidate>, timeoutMillis: Long) {
        if (offered.isEmpty() || project.isDisposed) return
        reconcileExpiredNotification()
        if (isInvalidatedSuggestion(editor.document, offered)) {
            acceptanceEnabled = true
            return
        }
        close(SuggestionCloseReason.OBSOLETE)
        notificationToken = TOKENS.incrementAndGet()
        document = editor.document
        candidates = offered.toList()
        acceptanceEnabled = true

        val prompt = suggestionPrompt(offered)
        val created = NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP_ID)
            .createNotification(message("notification.title"), prompt, NotificationType.INFORMATION)
            .setImportant(false)
        val token = notificationToken
        created.addAction(NotificationAction.create(message("notification.keep")) { _, current ->
            if (notificationToken == token) {
                finishClose(token, SuggestionCloseReason.KEEP)
                current.expire()
            }
        })
        created.addAction(
            NotificationAction.create(
                message(if (offered.size == 1) "notification.remove" else "notification.remove.all"),
            ) { _, current ->
                val acceptedDocument = document
                val acceptedCandidates = candidates
                if (notificationToken == token && acceptedDocument != null && acceptanceEnabled) {
                    finishClose(token, SuggestionCloseReason.ACCEPTED)
                    onAccepted(acceptedDocument, acceptedCandidates)
                    current.expire()
                }
            },
        )
        created.addAction(NotificationAction.create(message("notification.settings")) { _, _ ->
            openSettings(project)
        })
        created.whenExpired {
            SwingUtilities.invokeLater {
                finishClose(token, SuggestionCloseReason.DISMISSED)
            }
        }

        notification = created
        timeout = scheduler.schedule(timeoutMillis) {
            SwingUtilities.invokeLater {
                if (notificationToken == token) {
                    close(SuggestionCloseReason.TIMEOUT)
                }
            }
        }
        created.notify(project)
    }

    fun close(reason: SuggestionCloseReason) {
        val current = notification ?: return
        if (current.isExpired) {
            finishClose(notificationToken, SuggestionCloseReason.DISMISSED)
            return
        }
        finishClose(notificationToken, reason)
        current.expire()
    }

    fun invalidateAcceptance(target: Document) {
        if (owns(target)) {
            acceptanceEnabled = false
        }
    }

    fun owns(target: Document): Boolean = notification != null && document === target

    private fun finishClose(token: Long, reason: SuggestionCloseReason) {
        if (notificationToken != token) return
        val closedDocument = document
        val closedCandidates = candidates
        timeout?.cancel()
        notification = null
        document = null
        candidates = emptyList()
        timeout = null
        acceptanceEnabled = false
        notificationToken = 0L
        if (closedDocument != null) {
            onClosed(closedDocument, closedCandidates, reason)
        }
    }

    private fun reconcileExpiredNotification() {
        if (notification?.isExpired == true) {
            finishClose(notificationToken, SuggestionCloseReason.DISMISSED)
        }
    }

    private fun isInvalidatedSuggestion(target: Document, offered: List<Candidate>): Boolean =
        !acceptanceEnabled && notification != null && document === target && candidates == offered

    private fun suggestionPrompt(offered: List<Candidate>): String = if (offered.size == 1) {
        message("notification.single", offered.single().promptText)
    } else {
        message("notification.multiple", offered.size)
    }

    companion object {
        private val TOKENS = AtomicLong()
        private const val NOTIFICATION_GROUP_ID = "Import Trimmer suggestions"
    }
}
