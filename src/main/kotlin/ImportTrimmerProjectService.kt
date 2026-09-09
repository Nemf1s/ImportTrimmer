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

@Service(Service.Level.PROJECT)
class ImportTrimmerProjectService(
    private val project: Project,
    private val coroutineScope: CoroutineScope,
) : Disposable {
    private val providers = listOf(ImportProvider(JavaImportAnalyzer(), JavaImportEditPlanner()))
    private val tracker = ImportTransitionTracker()
    private val executor = ImportRemovalExecutor(project, providers)
    private val states = IdentityHashMap<Document, DocumentImportState>()
    private val editorCounts = IdentityHashMap<Document, Int>()
    private val analysisJobs = IdentityHashMap<Document, Job>()
    private val removalJobs = IdentityHashMap<Document, Job>()
    private val pluginEdits = Collections.newSetFromMap(IdentityHashMap<Document, Boolean>())
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

        factory.allEditors.filter { it.project === project }.forEach(::observeEditor)
    }

    fun settingsChanged() {
        epoch++
        analysisJobs.values.forEach(Job::cancel)
        removalJobs.values.forEach(Job::cancel)
        analysisJobs.clear()
        removalJobs.clear()
        controller.close(SuggestionCloseReason.OBSOLETE)
        states.replaceAll { _, state -> DocumentImportState(generation = state.generation + 1) }
        if (settings().enabled) states.keys.toList().forEach(::schedule)
    }

    fun review(editor: Editor) {
        if (!settings().enabled || !eligibleEditor(editor)) return
        observeIfNeeded(editor)
        val candidates = states[editor.document]?.let { tracker.candidates(it, manual = true) }.orEmpty()
        if (candidates.isEmpty()) {
            HintManager.getInstance().showInformationHint(editor, message("review.none"))
        } else {
            controller.show(editor, candidates, settings().timeoutSeconds)
        }
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
            delay(preferences.debounceMs.toLong())
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
                if (states[document]?.generation != generation || epoch != scheduledEpoch ||
                    document.modificationStamp != snapshot.token.stamp
                ) return@withContext
                analysisJobs.remove(document)
                publish(document, snapshot)
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
        val candidates = tracker.candidates(next)
        if (candidates.isEmpty()) {
            if (controller.owns(document)) controller.close(SuggestionCloseReason.OBSOLETE)
            return
        }
        val editor = FileEditorManager.getInstance(project).selectedTextEditor
            ?.takeIf { it.document === document && eligibleEditor(it) }
            ?: return
        if (LookupManager.getActiveLookup(editor) != null ||
            TemplateManager.getInstance(project).getActiveTemplate(editor) != null
        ) {
            schedule(document)
            return
        }
        when (settings().mode) {
            RemovalMode.ASK -> controller.show(editor, candidates, settings().timeoutSeconds)
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
            awaitCommitted(document)
            val result = executor.execute(
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
            withContext(Dispatchers.EDT) {
                removalJobs.remove(document)
                if (result != RemovalResult.APPLIED && states.containsKey(document)) schedule(document)
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
    }

    companion object {
        private val DISMISSAL_REASONS = setOf(
            SuggestionCloseReason.KEEP,
            SuggestionCloseReason.TIMEOUT,
            SuggestionCloseReason.DISMISSED,
            SuggestionCloseReason.NAVIGATION,
        )
    }
}
