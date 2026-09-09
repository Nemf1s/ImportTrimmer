package io.github.nemf1s.analysis.java

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.PlatformTestUtil
import io.github.nemf1s.analysis.*
import io.github.nemf1s.editing.java.JavaImportEditPlanner
import io.github.nemf1s.editing.ImportRemovalExecutor
import io.github.nemf1s.editing.RemovalResult
import kotlinx.coroutines.runBlocking

class JavaImportAnalyzerTest : LightJavaCodeInsightFixtureTestCase() {
    private val analyzer = JavaImportAnalyzer()
    private val planner = JavaImportEditPlanner()
    override fun getProjectDescriptor(): LightProjectDescriptor =
        LightJavaCodeInsightFixtureTestCase.JAVA_21

    fun testSemanticTransitionAndInitiallyUnusedImport() {
        val file = myFixture.configureByText("Example.java", """
            import java.util.Map;
            import java.util.List;
            import java.util.Set;

            class Example {
                List<String> names;
                Set<String> tags;
            }
        """.trimIndent()) as PsiJavaFile
        val document = myFixture.editor.document
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val first = analyze(file, emptyList())
        assertEquals("analysis reason: ${first.reason}", AnalysisQuality.RELIABLE, first.quality)
        assertEquals(SemanticStatus.UNUSED, status(first, "java.util.Map"))
        assertEquals(SemanticStatus.USED, status(first, "java.util.List"))
        assertEquals(SemanticStatus.USED, status(first, "java.util.Set"))

        WriteCommandAction.runWriteCommandAction(project) {
            val text = "    List<String> names;\n"
            val start = document.text.indexOf(text)
            document.deleteString(start, start + text.length)
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val second = analyze(file, first.observations.map { OccurrenceAnchor(it.key, it.range, it.expectedText) })
        val tracker = io.github.nemf1s.tracking.ImportTransitionTracker()
        val baseline = tracker.observe(
            io.github.nemf1s.tracking.DocumentImportState(interactionRange = null),
            first,
        )
        val changed = tracker.observe(baseline, second)
        val candidate = tracker.candidates(changed).single()
        assertEquals("java.util.List", candidate.displayText)
        assertFalse(tracker.candidates(changed).any { it.displayText == "java.util.Map" })
    }

    fun testPlannerPreservesOrderGroupingAndTrailingComment() {
        val before = """
            import java.util.Map;
            import java.util.List; // keep this note

            import java.util.Set;

            class Example { Set<String> tags; }
        """.trimIndent()
        val file = myFixture.configureByText("Example.java", before) as PsiJavaFile
        val document = myFixture.editor.document
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val snapshot = analyze(file, emptyList())
        val list = snapshot.observations.single { it.displayText == "java.util.List" }
        val accepted = listOf(Candidate(list.key, 1, list.displayText, list.promptText))
        val plan = ApplicationManager.getApplication().runReadAction<ImportEditPlan?> {
            planner.plan(file, document, snapshot, accepted)
        }
        assertNotNull("snapshot=${snapshot.quality}/${snapshot.reason}, observation=$list", plan)
        WriteCommandAction.runWriteCommandAction(project) {
            plan!!.deletions.sortedByDescending { it.range.startOffset }.forEach {
                assertEquals(it.expectedText, document.getText(it.range))
                document.deleteString(it.range.startOffset, it.range.endOffset)
            }
        }
        assertEquals("""
            import java.util.Map;
             // keep this note

            import java.util.Set;

            class Example { Set<String> tags; }
        """.trimIndent(), document.text)
    }

    fun testUnresolvedReferenceDefersWholeFile() {
        val file = myFixture.configureByText("Broken.java", """
            import java.util.List;
            class Broken { MissingType value; }
        """.trimIndent()) as PsiJavaFile
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        assertEquals(AnalysisQuality.DEFERRED, analyze(file, emptyList()).quality)
    }

    fun testStaticAndWildcardImportsUsePlatformResolution() {
        val file = myFixture.configureByText("Statics.java", """
            import static java.util.Collections.emptyList;
            import static java.util.Collections.*;

            class Statics {
                Object first = emptyList();
                Object second = singletonList("x");
            }
        """.trimIndent()) as PsiJavaFile
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val snapshot = analyze(file, emptyList())
        assertEquals(AnalysisQuality.RELIABLE, snapshot.quality)
        assertTrue(snapshot.observations.all { it.status == SemanticStatus.USED })
        assertEquals(
            setOf("static java.util.Collections.emptyList", "static java.util.Collections.*"),
            snapshot.observations.map { it.promptText }.toSet(),
        )
    }

    fun testDuplicatesAndInternalImportCommentsAreUnsupported() {
        val file = myFixture.configureByText("OddImports.java", """
            import java.util.List;
            import java.util.List;
            import java.util./* deliberate */Set;
            class OddImports {}
        """.trimIndent()) as PsiJavaFile
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val snapshot = analyze(file, emptyList())
        assertEquals(AnalysisQuality.RELIABLE, snapshot.quality)
        assertTrue(snapshot.observations.all { !it.supported && it.status == SemanticStatus.UNKNOWN })
    }

    fun testExecutorRemovesOnlyAcceptedImportAndUndoRestoresIt() {
        val before = """
            import java.util.Map;
            import java.util.List;

            class UndoExample {}
        """.trimIndent()
        val file = myFixture.configureByText("UndoExample.java", before) as PsiJavaFile
        val document = myFixture.editor.document
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val snapshot = analyze(file, emptyList())
        val list = snapshot.observations.single { it.displayText == "java.util.List" }
        val executor = ImportRemovalExecutor(
            project,
            listOf(ImportProvider(analyzer, planner)),
            beforeWrite = {},
        )
        val result = PlatformTestUtil.callOnBgtSynchronously({
            runBlocking {
                executor.execute(
                    document,
                    RemovalRequest("java", listOf(Candidate(list.key, 1, list.displayText, list.promptText))),
                    snapshot.observations.map { OccurrenceAnchor(it.key, it.range, it.expectedText) },
                    generation = 0,
                    epoch = 0,
                    isAuthorized = { true },
                    markPluginEdit = {},
                )
            }
        }, 30)
        assertEquals(RemovalResult.APPLIED, result)
        assertTrue(document.text.contains("import java.util.Map;"))
        assertFalse(document.text.contains("import java.util.List;"))

        UndoManager.getInstance(project).undo(FileEditorManager.getInstance(project).selectedEditor)
        assertEquals(before, document.text)
    }

    fun testProviderSelectionDeclinesAmbiguityAndSupportsAlternateAdapter() {
        val file = myFixture.configureByText("Provider.java", "class Provider {}")
        val alternateAnalyzer = object : ImportAnalyzer {
            override val providerId = "alternate"
            override fun supports(file: com.intellij.psi.PsiFile) = true
            override fun analyze(
                file: com.intellij.psi.PsiFile,
                document: com.intellij.openapi.editor.Document,
                token: FreshnessToken,
                anchors: List<OccurrenceAnchor>,
            ) = AnalysisSnapshot(providerId, token, AnalysisQuality.RELIABLE, interactionRange = null)
        }
        val alternatePlanner = object : ImportEditPlanner {
            override val providerId = "alternate"
            override fun plan(
                file: com.intellij.psi.PsiFile,
                document: com.intellij.openapi.editor.Document,
                snapshot: AnalysisSnapshot,
                accepted: List<Candidate>,
            ) = ImportEditPlan(snapshot.token, emptyList())
        }
        val alternate = ImportProvider(alternateAnalyzer, alternatePlanner)
        assertSame(alternate, selectProvider(listOf(alternate), file))
        assertNull(selectProvider(listOf(alternate, alternate), file))
    }

    private fun analyze(file: PsiJavaFile, anchors: List<OccurrenceAnchor>): AnalysisSnapshot =
        ApplicationManager.getApplication().runReadAction<AnalysisSnapshot> {
            val document = myFixture.editor.document
            analyzer.analyze(file, document, FreshnessToken(
                document.modificationStamp,
                PsiModificationTracker.getInstance(project).modificationCount,
                0,
                0,
            ), anchors)
        }

    private fun status(snapshot: AnalysisSnapshot, name: String) =
        snapshot.observations.single { it.displayText == name }.status
}
