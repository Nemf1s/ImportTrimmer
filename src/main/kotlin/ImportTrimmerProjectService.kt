package io.github.nemf1s

import com.intellij.codeInsight.hint.HintManager
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.codeInsight.template.TemplateManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.smartReadAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.*
import com.intellij.openapi.fileEditor.*
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.DumbService
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.GeneratedSourcesFilter
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.roots.ModuleRootEvent
import com.intellij.openapi.roots.ModuleRootListener
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.ide.scratch.ScratchUtil
import io.github.nemf1s.analysis.*
import io.github.nemf1s.analysis.java.JavaImportAnalyzer
import io.github.nemf1s.editing.ImportRemovalExecutor
import io.github.nemf1s.editing.RemovalResult
import io.github.nemf1s.editing.java.JavaImportEditPlanner
import io.github.nemf1s.settings.*
import io.github.nemf1s.tracking.*
import io.github.nemf1s.ui.*
import io.github.nemf1s.MyMessageBundle.message
import kotlinx.coroutines.*
import kotlin.coroutines.resume
import java.util.*
import kotlin.time.Duration.Companion.milliseconds

@Service(Service.Level.PROJECT)
class ImportTrimmerProjectService(
    private val project: Project,
    private val coroutineScope: CoroutineScope,
) : Disposable {
    private val providers = mutableListOf(ImportProvider(JavaImportAnalyzer(), JavaImportEditPlanner()))
    private val tracker = ImportTransitionTracker()
    private val executor = ImportRemovalExecutor(project, providers)
    private val states = IdentityHashMap<Document, DocumentImportState>()
    private val editorCounts = IdentityHashMap<Document, Int>()
    private val analysisJobs = IdentityHashMap<Document, Job>()
    private val removalJobs = IdentityHashMap<Document, Job>()
    private val pluginEdits = Collections.newSetFromMap(IdentityHashMap<Document, Boolean>())
    private val manualReviewRequests = Collections.newSetFromMap(IdentityHashMap<Document, Boolean>())
    private val loggedFailures = Collections.newSetFromMap(IdentityHashMap<Document, Boolean>())
    private var epoch = 0L
    private var started = false

    private val controller = ImportSuggestionController(
        project,
        CoroutineDelayScheduler(coroutineScope),
        ::removeAccepted,
        ::suggestionClosed,
    )

    fun start() {
        if (started || project.isDisposed) return
        started = true
        val factory = EditorFactory.getInstance()
        factory.addEditorFactoryListener(object : EditorFactoryListener {
            override fun editorCreated(event: EditorFactoryEvent) = observeEditor(event.editor)
            override fun editorReleased(event: EditorFactoryEvent) = releaseEditor(event.editor)
        }, this)
        factory.eventMulticaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) = changed(event)
        }, this)

        val connection = project.messageBus.connect(this)
        connection.subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) {
                controller.close(SuggestionCloseReason.NAVIGATION)
                FileEditorManager.getInstance(project).selectedTextEditor?.let(::observeIfNeeded)
            }
        })
        connection.subscribe(FileDocumentManagerListener.TOPIC, object : FileDocumentManagerListener {
            override fun beforeFileContentReload(file: VirtualFile, document: Document) {
                if (states.containsKey(document)) rebaseline(document)
            }
            override fun fileContentReloaded(file: VirtualFile, document: Document) {
                if (states.containsKey(document)) schedule(document)
            }
        })
        connection.subscribe(ModuleRootListener.TOPIC, object : ModuleRootListener {
            override fun rootsChanged(event: ModuleRootEvent) {
                coroutineScope.launch(Dispatchers.EDT) { invalidateAll() }
            }
        })
        connection.subscribe(DumbService.DUMB_MODE, object : DumbService.DumbModeListener {
            override fun enteredDumbMode() = invalidateForIndexing()
            override fun exitDumbMode() {
                if (settings().enabled) states.keys.toList().forEach(::schedule)
            }
        })

        factory.allEditors.filter { it.project === project }.forEach(::observeEditor)
    }

    fun settingsChanged() {
        epoch++
        analysisJobs.values.forEach(Job::cancel)
        removalJobs.values.forEach(Job::cancel)
        analysisJobs.clear()
        removalJobs.clear()
        manualReviewRequests.clear()
        controller.close(SuggestionCloseReason.OBSOLETE)
        states.replaceAll { _, state -> DocumentImportState(generation = state.generation + 1) }
        if (settings().enabled) states.keys.toList().forEach(::schedule)
    }

    fun review(editor: Editor) {
        if (!settings().enabled || !eligibleEditor(editor)) return
        observeIfNeeded(editor)
        manualReviewRequests.add(editor.document)
        schedule(editor.document)
    }

    private fun observeIfNeeded(editor: Editor) {
        if (!states.containsKey(editor.document)) {
            observeEditor(editor)
        } else if (settings().enabled) {
            schedule(editor.document)
        }
    }

    private fun observeEditor(editor: Editor) {
        if (!eligibleEditor(editor)) return
        val document = editor.document
        editorCounts[document] = (editorCounts[document] ?: 0) + 1
        if (states.putIfAbsent(document, DocumentImportState()) == null && settings().enabled) schedule(document)
    }

    private fun releaseEditor(editor: Editor) {
        if (editor.project !== project) return
        val document = editor.document
        val remaining = (editorCounts[document] ?: return) - 1
        if (remaining > 0) {
            editorCounts[document] = remaining
            return
        }
        editorCounts.remove(document)
        states.remove(document)
        analysisJobs.remove(document)?.cancel()
        removalJobs.remove(document)?.cancel()
        manualReviewRequests.remove(document)
        loggedFailures.remove(document)
        if (controller.owns(document)) controller.close(SuggestionCloseReason.DISPOSED)
    }

    private fun changed(event: DocumentEvent) {
        val old = states[event.document] ?: return
        controller.takeIf { it.owns(event.document) }?.close(SuggestionCloseReason.OBSOLETE)
        val state = if (pluginEdits.contains(event.document) ||
            UndoManager.getInstance(project).isUndoOrRedoInProgress
        ) {
            DocumentImportState(generation = old.generation + 1)
        } else {
            tracker.edited(old, event.offset, event.oldLength, event.newLength)
        }
        states[event.document] = state
        schedule(event.document)
    }

    private fun schedule(document: Document) {
        analysisJobs.remove(document)?.cancel()
        val preferences = settings()
        if (!preferences.enabled || !states.containsKey(document)) return
        val generation = states.getValue(document).generation
        val anchors = states.getValue(document).anchors
        val scheduledEpoch = epoch
        analysisJobs[document] = coroutineScope.launch {
            val currentJob = coroutineContext.job
            try {
                delay(preferences.debounceMs.milliseconds)
                awaitCommitted(document)
                val snapshot = smartReadAction(project) {
                    val manager = PsiDocumentManager.getInstance(project)
                    val file = manager.getPsiFile(document) ?: return@smartReadAction null
                    val provider = selectProvider(providers, file) ?: return@smartReadAction null
                    val token = FreshnessToken(
                        document.modificationStamp,
                        PsiModificationTracker.getInstance(project).modificationCount,
                        generation,
                        scheduledEpoch,
                    )
                    provider.analyzer.analyze(file, document, token, anchors)
                } ?: return@launch
                withContext(Dispatchers.EDT) {
                    if (!snapshotIsCurrent(document, snapshot, generation, scheduledEpoch)) return@withContext
                    loggedFailures.remove(document)
                    publish(document, snapshot)
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                withContext(NonCancellable + Dispatchers.EDT) {
                    logUnexpectedOnce(document, "Java import analysis failed", exception)
                    states[document]?.let { states[document] = it.copy(executable = false) }
                    if (controller.owns(document)) controller.close(SuggestionCloseReason.UNCERTAIN)
                }
            } finally {
                withContext(NonCancellable + Dispatchers.EDT) {
                    if (analysisJobs[document] === currentJob) analysisJobs.remove(document)
                }
            }
        }
    }

    private fun publish(document: Document, snapshot: AnalysisSnapshot) {
        val old = states[document] ?: return
        if (snapshot.quality != AnalysisQuality.RELIABLE) {
            states[document] = tracker.observe(old, snapshot)
            if (controller.owns(document)) controller.close(SuggestionCloseReason.UNCERTAIN)
            return
        }
        val next = tracker.observe(old, snapshot)
        states[document] = next
        val manualReview = manualReviewRequests.contains(document)
        val candidates = tracker.candidates(next, manual = manualReview)
        if (candidates.isEmpty()) {
            if (controller.owns(document)) controller.close(SuggestionCloseReason.OBSOLETE)
            if (manualReview) {
                manualReviewRequests.remove(document)
                activeEditor(document)?.let { HintManager.getInstance().showInformationHint(it, message("review.none")) }
            }
            return
        }
        val editor = activeEditor(document) ?: run {
            manualReviewRequests.remove(document)
            return
        }
        if (presentationBlocked(editor, next)) {
            schedule(document)
            return
        }
        if (manualReview) {
            manualReviewRequests.remove(document)
            controller.show(editor, candidates, settings().timeoutSeconds * 1_000L)
            return
        }
        when (settings().mode) {
            RemovalMode.ASK -> showAskSuggestion(editor, next, candidates)
            RemovalMode.AUTOMATIC -> removeAccepted(document, candidates)
            RemovalMode.MANUAL -> Unit
        }
    }

    private fun removeAccepted(document: Document, candidates: List<Candidate>) {
        if (removalJobs.containsKey(document) || candidates.isEmpty()) return
        val state = states[document] ?: return
        val providerId = candidates.map { it.key.providerId }.distinct().singleOrNull() ?: return
        val request = RemovalRequest(providerId, candidates.toList())
        val generation = state.generation
        val requestEpoch = epoch
        removalJobs[document] = coroutineScope.launch {
            val currentJob = coroutineContext.job
            var result: RemovalResult? = null
            var failed = false
            try {
                awaitCommitted(document)
                result = executor.execute(
                    document,
                    request,
                    state.anchors,
                    generation,
                    requestEpoch,
                    isAuthorized = {
                        epoch == requestEpoch && states[document]?.generation == generation &&
                            request.candidates.all { candidate ->
                                states[document]?.records?.get(candidate.key)?.let {
                                    it.episode == candidate.episode && it.eligible &&
                                        it.observation.status == SemanticStatus.UNUSED
                                } == true
                            }
                    },
                    markPluginEdit = { active ->
                        if (active) pluginEdits.add(document) else pluginEdits.remove(document)
                    },
                )
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                failed = true
                withContext(NonCancellable + Dispatchers.EDT) {
                    logUnexpectedOnce(document, "Import removal failed", exception)
                }
            } finally {
                withContext(NonCancellable + Dispatchers.EDT) {
                    if (removalJobs[document] === currentJob) removalJobs.remove(document)
                    if (failed) {
                        val current = states[document]
                        if (current != null) {
                            states[document] = DocumentImportState(generation = current.generation + 1)
                        }
                        if (controller.owns(document)) controller.close(SuggestionCloseReason.UNCERTAIN)
                    } else if (result != RemovalResult.APPLIED && states.containsKey(document)) {
                        schedule(document)
                    }
                }
            }
        }
    }

    private fun suggestionClosed(document: Document, offered: List<Candidate>, reason: SuggestionCloseReason) {
        if (reason in DISMISSAL_REASONS) {
            states[document]?.let { states[document] = tracker.dismiss(it, offered) }
        }
    }

    private fun rebaseline(document: Document) {
        val current = states[document] ?: return
        analysisJobs.remove(document)?.cancel()
        removalJobs.remove(document)?.cancel()
        if (controller.owns(document)) controller.close(SuggestionCloseReason.UNCERTAIN)
        states[document] = DocumentImportState(generation = current.generation + 1)
    }

    private fun invalidateAll() {
        epoch++
        analysisJobs.values.forEach(Job::cancel)
        removalJobs.values.forEach(Job::cancel)
        analysisJobs.clear()
        removalJobs.clear()
        controller.close(SuggestionCloseReason.UNCERTAIN)
        states.replaceAll { _, state -> DocumentImportState(generation = state.generation + 1) }
        if (settings().enabled) states.keys.toList().forEach(::schedule)
    }

    private fun invalidateForIndexing() {
        epoch++
        analysisJobs.values.forEach(Job::cancel)
        removalJobs.values.forEach(Job::cancel)
        analysisJobs.clear()
        removalJobs.clear()
        controller.close(SuggestionCloseReason.UNCERTAIN)
        states.replaceAll { _, state ->
            state.copy(generation = state.generation + 1, executable = false)
        }
    }

    internal fun snapshotIsCurrent(
        document: Document,
        snapshot: AnalysisSnapshot,
        generation: Long,
        scheduledEpoch: Long,
    ): Boolean = states[document]?.generation == generation && epoch == scheduledEpoch &&
        document.modificationStamp == snapshot.token.stamp &&
        PsiModificationTracker.getInstance(project).modificationCount == snapshot.token.psi

    private fun activeEditor(document: Document): Editor? =
        FileEditorManager.getInstance(project).selectedTextEditor
            ?.takeIf { it.document === document && eligibleEditor(it) }

    private fun presentationBlocked(editor: Editor, state: DocumentImportState): Boolean =
        LookupManager.getActiveLookup(editor) != null ||
            TemplateManager.getInstance(project).getActiveTemplate(editor) != null ||
            state.interactionRange?.containsOffset(editor.caretModel.offset) == true

    private fun showAskSuggestion(
        editor: Editor,
        state: DocumentImportState,
        candidates: List<Candidate>,
    ) {
        val now = monotonicMillis()
        var next = tracker.markOffered(
            state,
            candidates,
            now + settings().timeoutSeconds * 1_000L,
        )
        val expired = candidates.filter { (tracker.offerDeadline(next, it) ?: Long.MAX_VALUE) <= now }
        if (expired.isNotEmpty()) next = tracker.dismiss(next, expired)
        states[editor.document] = next
        val offered = tracker.candidates(next)
        if (offered.isEmpty()) return
        val remaining = offered.minOf { tracker.offerDeadline(next, it) ?: now } - now
        if (remaining <= 0) {
            states[editor.document] = tracker.dismiss(next, offered)
            return
        }
        controller.show(editor, offered, remaining)
    }

    private fun logUnexpectedOnce(document: Document, summary: String, exception: Throwable) {
        if (loggedFailures.add(document)) LOG.warn(summary, exception)
    }

    private suspend fun awaitCommitted(document: Document) {
        withContext(Dispatchers.EDT) {
            suspendCancellableCoroutine { continuation ->
                PsiDocumentManager.getInstance(project).performForCommittedDocument(document) {
                    if (continuation.isActive) continuation.resume(Unit)
                }
            }
        }
    }

    private fun eligibleEditor(editor: Editor): Boolean {
        if (editor.project !== project || editor.isViewer || project.isDisposed) return false
        val file = FileDocumentManager.getInstance().getFile(editor.document) ?: return false
        if (!file.isValid || !file.isWritable || !file.name.endsWith(".java", ignoreCase = true)) return false
        if (ScratchUtil.isScratch(file)) return false
        val index = ProjectFileIndex.getInstance(project)
        return index.isInContent(file) && !index.isExcluded(file) &&
            !GeneratedSourcesFilter.isGeneratedSourceByAnyFilter(file, project)
    }

    private fun settings(): Preferences = project.service<ImportTrimmerSettings>().state

    override fun dispose() {
        controller.close(SuggestionCloseReason.DISPOSED)
        analysisJobs.values.forEach(Job::cancel)
        removalJobs.values.forEach(Job::cancel)
        states.clear()
        editorCounts.clear()
        manualReviewRequests.clear()
        loggedFailures.clear()
    }

    companion object {
        private val LOG = Logger.getInstance(ImportTrimmerProjectService::class.java)
        private fun monotonicMillis(): Long = System.nanoTime() / 1_000_000L
        private val DISMISSAL_REASONS = setOf(
            SuggestionCloseReason.KEEP,
            SuggestionCloseReason.TIMEOUT,
            SuggestionCloseReason.DISMISSED,
            SuggestionCloseReason.NAVIGATION,
        )
    }
}
