package io.github.nemf1s.tracking

import com.intellij.openapi.util.TextRange
import io.github.nemf1s.analysis.*
import org.junit.Assert.*
import org.junit.Test

class ImportTransitionTrackerTest {
    private val tracker = ImportTransitionTracker()
    private val key = OccurrenceKey("java", "type:java.util.List@0")

    @Test
    fun initiallyUnusedIsNeverEligibleIncludingManualReview() {
        val state = tracker.observe(DocumentImportState(), snapshot(SemanticStatus.UNUSED))
        assertTrue(tracker.candidates(state).isEmpty())
        assertTrue(tracker.candidates(state, manual = true).isEmpty())
    }

    @Test
    fun usedToUnusedCreatesOneStableEpisode() {
        val baseline = tracker.observe(DocumentImportState(), snapshot(SemanticStatus.USED))
        val unused = tracker.observe(baseline, snapshot(SemanticStatus.UNUSED))
        val candidate = tracker.candidates(unused).single()
        assertEquals(1, candidate.episode)

        val repeated = tracker.observe(unused, snapshot(SemanticStatus.UNUSED))
        assertEquals(candidate, tracker.candidates(repeated).single())
    }

    @Test
    fun dismissalIsRevisitableManuallyAndUseArmsANewEpisode() {
        val used = tracker.observe(DocumentImportState(), snapshot(SemanticStatus.USED))
        val first = tracker.observe(used, snapshot(SemanticStatus.UNUSED))
        val dismissed = tracker.dismiss(first, tracker.candidates(first))
        assertTrue(tracker.candidates(dismissed).isEmpty())
        assertEquals(1, tracker.candidates(dismissed, manual = true).single().episode)

        val usedAgain = tracker.observe(dismissed, snapshot(SemanticStatus.USED))
        val second = tracker.observe(usedAgain, snapshot(SemanticStatus.UNUSED))
        assertEquals(2, tracker.candidates(second).single().episode)
    }

    @Test
    fun uncertainAnalysisPreservesHistoryButDisablesExecution() {
        val used = tracker.observe(DocumentImportState(), snapshot(SemanticStatus.USED))
        val deferred = tracker.observe(used, AnalysisSnapshot(
            "java", token(), AnalysisQuality.DEFERRED, reason = "indexing"
        ))
        assertEquals(SemanticStatus.USED, deferred.records.getValue(key).lastReliable)
        assertFalse(deferred.executable)
        assertTrue(tracker.candidates(deferred).isEmpty())
    }

    @Test
    fun editsBeforeImportShiftIdentityWhileEditsInImportRetireIt() {
        val used = tracker.observe(DocumentImportState(), snapshot(SemanticStatus.USED, 20))
        val shifted = tracker.edited(used, 5, 0, 4)
        assertEquals(TextRange(24, 46), shifted.anchors.single().range)
        assertTrue(shifted.records.containsKey(key))

        val replaced = tracker.edited(shifted, 24, 0, 1)
        assertTrue(replaced.records.isEmpty())
        assertTrue(replaced.anchors.isEmpty())
    }

    private fun snapshot(status: SemanticStatus, start: Int = 0) = AnalysisSnapshot(
        "java", token(), AnalysisQuality.RELIABLE,
        listOf(ImportObservation(key, "java.util.List", status, true, TextRange(start, start + 22), "import java.util.List;"))
    )
    private fun token() = FreshnessToken(1, 1, 0, 0)
}
