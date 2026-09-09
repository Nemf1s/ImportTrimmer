package io.github.nemf1s.analysis.java

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.util.PsiModificationTracker
import com.intellij.testFramework.LightProjectDescriptor
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase
import io.github.nemf1s.analysis.AnalysisQuality
import io.github.nemf1s.analysis.AnalysisSnapshot
import io.github.nemf1s.analysis.Candidate
import io.github.nemf1s.analysis.FreshnessToken
import io.github.nemf1s.analysis.ImportEditPlan
import io.github.nemf1s.analysis.ImportProvider
import io.github.nemf1s.analysis.OccurrenceAnchor
import io.github.nemf1s.analysis.OccurrenceKey
import io.github.nemf1s.analysis.RemovalRequest
import io.github.nemf1s.analysis.SemanticStatus
import io.github.nemf1s.editing.ImportRemovalExecutor
import io.github.nemf1s.editing.RemovalResult
import io.github.nemf1s.editing.java.JavaImportEditPlanner
import io.github.nemf1s.tracking.DocumentImportState
import io.github.nemf1s.tracking.ImportTransitionTracker
import kotlinx.coroutines.runBlocking

class JavaImportAnalyzerAdditionalTest : LightJavaCodeInsightFixtureTestCase() {
    private val analyzer = JavaImportAnalyzer()
    private val planner = JavaImportEditPlanner()

    override fun getProjectDescriptor(): LightProjectDescriptor = JAVA_21

    fun testRemovingOneOfSeveralUsagesKeepsImportUsed() {
        val file = configure("SeveralUsages.java", """
            import java.util.List;
            class SeveralUsages {
                List<String> first;
                List<String> second;
            }
        """)
        val first = analyze(file)
        delete("    List<String> first;\n")
        val second = analyze(file, anchors(first))

        assertEquals(SemanticStatus.USED, observation(second, "java.util.List").status)
        val tracker = ImportTransitionTracker()
        val state = tracker.observe(tracker.observe(DocumentImportState(), first), second)
        assertTrue(tracker.candidates(state).isEmpty())
    }

    fun testAnnotationAndNestedClassImportsAreResolvedSemantically() {
        val file = configure("AnnotationNested.java", """
            import java.lang.annotation.Retention;
            import java.lang.annotation.RetentionPolicy;
            import java.util.Map.Entry;
            @Retention(RetentionPolicy.RUNTIME)
            class AnnotationNested { Entry<String, String> entry; }
        """)

        val snapshot = analyze(file)

        assertEquals(snapshot.toString(), AnalysisQuality.RELIABLE, snapshot.quality)
        assertTrue(snapshot.toString(), snapshot.observations.all { it.status == SemanticStatus.USED })
    }

    fun testStaticFieldAndOverloadedStaticMethodImportsRemainUsed() {
        val file = configure("StaticMembers.java", """
            import static java.lang.Math.PI;
            import static java.lang.Math.max;
            class StaticMembers {
                double circle = PI;
                int larger = max(1, 2);
            }
        """)

        val snapshot = analyze(file)

        assertEquals(snapshot.toString(), AnalysisQuality.RELIABLE, snapshot.quality)
        assertTrue(snapshot.toString(), snapshot.observations.all { it.status == SemanticStatus.USED })
    }

    fun testWildcardImportRemainsUsedWhileOneDependentReferenceSurvives() {
        val file = configure("Wildcard.java", """
            import java.util.*;
            class Wildcard {
                List<String> list;
                Set<String> set;
            }
        """)
        val first = analyze(file)
        delete("    List<String> list;\n")
        val second = analyze(file, anchors(first))

        assertEquals(SemanticStatus.USED, second.observations.single().status)
    }

    fun testFullyQualifiedReferencesAndEqualSimpleNamesDoNotCreateFalseUsage() {
        myFixture.addClass("package other; public class Date {}")
        val file = configure("Resolution.java", """
            import java.util.Date;
            import java.util.List;
            class Resolution {
                Date utilDate;
                other.Date otherDate;
            }
        """)

        val snapshot = analyze(file)

        assertEquals(snapshot.toString(), AnalysisQuality.RELIABLE, snapshot.quality)
        assertEquals(SemanticStatus.USED, observation(snapshot, "java.util.Date").status)
        assertEquals(SemanticStatus.UNUSED, observation(snapshot, "java.util.List").status)
    }

    fun testLocalTypeShadowingLeavesTheImportUnused() {
        val file = configure("Shadowing.java", """
            import java.util.List;
            class Shadowing {
                void useLocalType() {
                    class List {}
                    List value = new List();
                }
            }
        """)

        val snapshot = analyze(file)

        assertEquals(snapshot.toString(), AnalysisQuality.RELIABLE, snapshot.quality)
        assertEquals(SemanticStatus.UNUSED, observation(snapshot, "java.util.List").status)
    }

    fun testJavaDocReferenceCountsButStringsAndCommentsDoNot() {
        val file = configure("Documentation.java", """
            import java.util.List;
            import java.util.Set;
            /** Uses {@link List}; Set appears only as text. */
            class Documentation { String value = "Set"; }
        """)

        val snapshot = analyze(file)

        assertEquals(AnalysisQuality.RELIABLE, snapshot.quality)
        assertEquals(SemanticStatus.USED, observation(snapshot, "java.util.List").status)
        assertEquals(SemanticStatus.UNUSED, observation(snapshot, "java.util.Set").status)
    }

    fun testSyntaxErrorsDeferWithoutProducingObservations() {
        val file = configure("Incomplete.java", """
            import java.util.List;
            class Incomplete { List<String> value
        """)

        val snapshot = analyze(file)

        assertEquals(AnalysisQuality.DEFERRED, snapshot.quality)
        assertTrue(snapshot.observations.isEmpty())
        assertTrue(snapshot.reason!!.contains("syntax", ignoreCase = true))
    }

    fun testZeroImportsAreReliableAndNonJavaFilesAreUnsupported() {
        val file = configure("NoImports.java", "class NoImports {}")
        val snapshot = analyze(file)
        val plain = PsiFileFactory.getInstance(project).createFileFromText("notes.txt", com.intellij.openapi.fileTypes.PlainTextLanguage.INSTANCE, "text")
        val unsupported = analyzer.analyze(plain, myFixture.editor.document, token(), emptyList())

        assertEquals(AnalysisQuality.RELIABLE, snapshot.quality)
        assertTrue(snapshot.observations.isEmpty())
        assertFalse(analyzer.supports(plain))
        assertEquals(AnalysisQuality.UNSUPPORTED, unsupported.quality)
    }

    fun testPlannerRemovesOnlyOneImportFromASharedPhysicalLine() {
        val before = "import java.util.Map; import java.util.List; class SharedLine {}"
        val file = configure("SharedLine.java", before)
        val snapshot = analyze(file)
        val plan = plan(file, snapshot, candidate(snapshot, "java.util.List"))

        apply(plan!!)

        assertEquals("import java.util.Map;  class SharedLine {}", document.text)
    }

    fun testPlannerRemovesFinalImportAtEndOfFile() {
        val file = configure("OnlyImport.java", "import java.util.List;")
        val snapshot = analyze(file)
        val plan = plan(file, snapshot, candidate(snapshot, "java.util.List"))

        apply(plan!!)

        assertEquals("", document.text)
    }

    fun testMultilineImportIsUnsupportedAndPlanningDeclinesWithoutThrowing() {
        val file = configureRaw(
            "MultilineImport.java",
            "import java.util.\n    List;\nclass MultilineImport {}",
        )
        val snapshot = analyze(file)
        val observation = snapshot.observations.single()

        assertFalse(observation.supported)
        assertEquals(SemanticStatus.UNKNOWN, observation.status)
        assertNull(plan(file, snapshot, Candidate(observation.key, 1, observation.displayText)))
    }

    fun testPlannerPreservesCrLfSeparatorsAroundRemovedImport() {
        val before = "import java.util.Map;\r\nimport java.util.List;\r\n\r\nclass CrLf {}"
        val file = configureRaw("CrLf.java", before)
        val snapshot = analyze(file)
        val plan = plan(file, snapshot, candidate(snapshot, "java.util.List"))

        apply(plan!!)

        assertEquals("import java.util.Map;\n\nclass CrLf {}", document.text)
    }

    fun testPlannerPreservesUnicodeOutsideTheDeletionSpan() {
        val file = configure("UnicodeExample.java", """
            import java.util.List;
            class Café { String café = "λ"; }
        """)
        val snapshot = analyze(file)
        val plan = plan(file, snapshot, candidate(snapshot, "java.util.List"))

        apply(plan!!)

        assertEquals("class Café { String café = \"λ\"; }", document.text)
    }

    fun testPlannerPreservesLeadingCommentOnItsOwnLine() {
        val file = configure("LeadingComment.java", """
            /* Keep this import note. */
            import java.util.List;
            class LeadingComment {}
        """)
        val snapshot = analyze(file)
        val plan = plan(file, snapshot, candidate(snapshot, "java.util.List"))

        apply(plan!!)

        assertEquals("/* Keep this import note. */\nclass LeadingComment {}", document.text)
    }

    fun testSamePackageAndJavaLangImportsAreObservedAsInitiallyUnused() {
        myFixture.addClass("package sample; public class LocalType {}")
        val file = configure("RedundantKinds.java", """
            package sample;
            import sample.LocalType;
            import java.lang.String;
            class RedundantKinds { LocalType local; String text; }
        """)
        val snapshot = analyze(file)
        val tracker = ImportTransitionTracker()
        val state = tracker.observe(DocumentImportState(), snapshot)

        assertEquals(AnalysisQuality.RELIABLE, snapshot.quality)
        assertTrue(tracker.candidates(state).isEmpty())
    }

    fun testPlannerRejectsMissingCandidateWithoutChangingDocument() {
        val file = configure("MissingCandidate.java", """
            import java.util.List;
            class MissingCandidate {}
        """)
        val snapshot = analyze(file)
        val before = document.text
        val missing = Candidate(OccurrenceKey("java", "missing"), 1, "java.util.Set")

        assertNull(plan(file, snapshot, missing))
        assertEquals(before, document.text)
    }

    fun testPlannerRejectsDuplicateAcceptedOccurrenceWithoutChangingDocument() {
        val file = configure("DuplicateAcceptance.java", """
            import java.util.List;
            class DuplicateAcceptance {}
        """)
        val snapshot = analyze(file)
        val accepted = candidate(snapshot, "java.util.List")
        val before = document.text

        assertNull(plan(file, snapshot, accepted, accepted))
        assertEquals(before, document.text)
    }

    fun testExecutorRejectsUnauthorizedAndReadOnlyRequestsWithoutEditing() {
        val javaFile = configure("Authorization.java", """
            import java.util.List;
            class Authorization {}
        """)
        val snapshot = analyze(javaFile)
        val request = RemovalRequest("java", listOf(candidate(snapshot, "java.util.List")))
        val before = document.text

        assertEquals(RemovalResult.STALE, execute(request, anchors(snapshot), isAuthorized = { false }))
        assertEquals(before, document.text)
        document.setReadOnly(true)
        try {
            assertEquals(RemovalResult.DECLINED, execute(request, anchors(snapshot), isAuthorized = { true }))
        } finally {
            document.setReadOnly(false)
        }
        assertEquals(before, document.text)
    }

    fun testExecutorRechecksWritabilityAfterPlanningBeforeWrite() {
        val javaFile = configure("WriteRace.java", """
            import java.util.List;
            class WriteRace {}
        """)
        val snapshot = analyze(javaFile)
        val request = RemovalRequest("java", listOf(candidate(snapshot, "java.util.List")))
        val before = document.text
        val executor = ImportRemovalExecutor(
            project,
            listOf(ImportProvider(analyzer, planner)),
            beforeWrite = { document.setReadOnly(true) },
        )

        try {
            val result = PlatformTestUtil.callOnBgtSynchronously({
                runBlocking {
                    executor.execute(
                        document,
                        request,
                        anchors(snapshot),
                        generation = 0,
                        epoch = 0,
                        isAuthorized = { true },
                        markPluginEdit = {},
                    )
                }
            }, 30)
            assertEquals(RemovalResult.STALE, result)
            assertEquals(before, document.text)
        } finally {
            document.setReadOnly(false)
        }
    }

    fun testExecutorReanalysisRejectsImportThatBecameUsedBeforeAcceptance() {
        val file = configure("BecameUsed.java", """
            import java.util.List;
            class BecameUsed {}
        """)
        val snapshot = analyze(file)
        val request = RemovalRequest("java", listOf(candidate(snapshot, "java.util.List")))
        WriteCommandAction.runWriteCommandAction(project) {
            val insertion = document.text.lastIndexOf('}')
            document.insertString(insertion, " List<String> value;")
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()

        val result = execute(request, anchors(snapshot), isAuthorized = { true })

        assertEquals(RemovalResult.DECLINED, result)
        assertTrue(document.text.contains("import java.util.List;"))
        assertTrue(document.text.contains("List<String> value;"))
        assertTrue(file.isValid)
    }

    fun testExecutorDeclinesMismatchedProviderWithoutEditing() {
        configure("WrongProvider.java", """
            import java.util.List;
            class WrongProvider {}
        """)
        val before = document.text
        val request = RemovalRequest("alternate", listOf(Candidate(OccurrenceKey("alternate", "x"), 1, "x")))

        assertEquals(RemovalResult.DECLINED, execute(request, emptyList(), isAuthorized = { true }))
        assertEquals(before, document.text)
    }

    private val document: Document
        get() = myFixture.editor.document

    private fun configure(name: String, text: String): PsiJavaFile = configureRaw(name, text.trimIndent())

    private fun configureRaw(name: String, text: String): PsiJavaFile {
        val file = myFixture.configureByText(name, text) as PsiJavaFile
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        return file
    }

    private fun delete(text: String) {
        WriteCommandAction.runWriteCommandAction(project) {
            val start = document.text.indexOf(text)
            assertTrue("Missing text to delete: $text", start >= 0)
            document.deleteString(start, start + text.length)
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
    }

    private fun analyze(file: PsiJavaFile, anchors: List<OccurrenceAnchor> = emptyList()): AnalysisSnapshot =
        ApplicationManager.getApplication().runReadAction<AnalysisSnapshot> {
            analyzer.analyze(file, document, token(), anchors)
        }

    private fun token() = FreshnessToken(
        stamp = document.modificationStamp,
        psi = PsiModificationTracker.getInstance(project).modificationCount,
        generation = 0,
        epoch = 0,
    )

    private fun anchors(snapshot: AnalysisSnapshot) = snapshot.observations.map {
        OccurrenceAnchor(it.key, it.range, it.expectedText)
    }

    private fun observation(snapshot: AnalysisSnapshot, displayText: String) =
        snapshot.observations.single { it.displayText == displayText }

    private fun candidate(snapshot: AnalysisSnapshot, displayText: String): Candidate {
        val observation = observation(snapshot, displayText)
        return Candidate(observation.key, 1, observation.displayText)
    }

    private fun plan(file: PsiJavaFile, snapshot: AnalysisSnapshot, vararg accepted: Candidate): ImportEditPlan? =
        ApplicationManager.getApplication().runReadAction<ImportEditPlan?> {
            planner.plan(file, document, snapshot, accepted.toList())
        }

    private fun apply(plan: ImportEditPlan) {
        WriteCommandAction.runWriteCommandAction(project) {
            plan.deletions.sortedByDescending { it.range.startOffset }.forEach {
                assertEquals(it.expectedText, document.getText(it.range))
                document.deleteString(it.range.startOffset, it.range.endOffset)
            }
        }
    }

    private fun execute(
        request: RemovalRequest,
        anchors: List<OccurrenceAnchor>,
        isAuthorized: () -> Boolean,
    ): RemovalResult {
        val executor = ImportRemovalExecutor(project, listOf(ImportProvider(analyzer, planner)))
        return PlatformTestUtil.callOnBgtSynchronously({
            runBlocking {
                executor.execute(
                    document = document,
                    request = request,
                    anchors = anchors,
                    generation = 0,
                    epoch = 0,
                    isAuthorized = isAuthorized,
                    markPluginEdit = {},
                )
            }
        }, 30)!!
    }
}
