package io.github.nemf1s.ui

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import io.github.nemf1s.MyMessageBundle.message
import io.github.nemf1s.analysis.Candidate
import java.util.concurrent.atomic.AtomicLong
import javax.swing.SwingUtilities

enum class SuggestionCloseReason {
    KEEP, TIMEOUT, DISMISSED, NAVIGATION, ACCEPTED, OBSOLETE, UNCERTAIN, DISPOSED
}

class ImportSuggestionController(
    private val project: Project,
    private val scheduler: DelayScheduler,
    private val onAccepted: (Document, List<Candidate>) -> Unit,
    private val onClosed: (Document, List<Candidate>, SuggestionCloseReason) -> Unit,
) {
    private var notification: Notification? = null
    private var notificationToken = 0L
    private var document: Document? = null
    private var candidates: List<Candidate> = emptyList()
    private var timeout: CancelHandle? = null
    private var requestedCloseReason: SuggestionCloseReason? = null

    fun show(editor: Editor, offered: List<Candidate>, timeoutSeconds: Int) {
        if (offered.isEmpty() || project.isDisposed) return
        close(SuggestionCloseReason.OBSOLETE)
        notificationToken = TOKENS.incrementAndGet()
        document = editor.document
        candidates = offered.toList()
        requestedCloseReason = null

        val prompt = if (offered.size == 1) {
            message("notification.single", offered.single().displayText.substringAfterLast('.'))
        } else {
            message("notification.multiple", offered.size)
        }
        val created = NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP_ID)
            .createNotification(message("notification.title"), prompt, NotificationType.INFORMATION)
            .setImportant(false)
        val token = notificationToken
        created.addAction(NotificationAction.create(message("notification.keep")) { _, current ->
            if (notificationToken == token && requestedCloseReason == null) {
                requestedCloseReason = SuggestionCloseReason.KEEP
                current.expire()
            }
        })
        created.addAction(NotificationAction.create(
            message(if (offered.size == 1) "notification.remove" else "notification.remove.all"),
        ) { _, current ->
            val acceptedDocument = document
            val accepted = candidates
            if (notificationToken == token && acceptedDocument != null && requestedCloseReason == null) {
                requestedCloseReason = SuggestionCloseReason.ACCEPTED
                onAccepted(acceptedDocument, accepted)
                current.expire()
            }
        })
        created.whenExpired {
            SwingUtilities.invokeLater {
                finishClose(token, requestedCloseReason ?: SuggestionCloseReason.DISMISSED)
            }
        }

        notification = created
        timeout = scheduler.schedule(timeoutSeconds * 1_000L) {
            SwingUtilities.invokeLater {
                if (notificationToken == token) close(SuggestionCloseReason.TIMEOUT)
            }
        }
        created.notify(project)
    }

    fun close(reason: SuggestionCloseReason) {
        val current = notification ?: return
        if (requestedCloseReason == null) requestedCloseReason = reason
        timeout?.cancel()
        current.expire()
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
        requestedCloseReason = null
        notificationToken = 0
        if (closedDocument != null) onClosed(closedDocument, closedCandidates, reason)
    }

    companion object {
        private val TOKENS = AtomicLong()
        private const val NOTIFICATION_GROUP_ID = "Import Trimmer suggestions"
    }
}
