package io.github.nemf1s.tracking

import com.intellij.openapi.util.TextRange
import io.github.nemf1s.analysis.AnalysisQuality
import io.github.nemf1s.analysis.AnalysisSnapshot
import io.github.nemf1s.analysis.Candidate
import io.github.nemf1s.analysis.FreshnessToken
import io.github.nemf1s.analysis.ImportObservation
import io.github.nemf1s.analysis.OccurrenceKey
import io.github.nemf1s.analysis.SemanticStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImportTransitionTrackerAdditionalTest {
    private val tracker = ImportTransitionTracker()
    private val listKey = OccurrenceKey("java", "type:java.util.List@0")
    private val setKey = OccurrenceKey("java", "type:java.util.Set@30")

    @Test
    fun unsupportedObservationsNeverCreateExecutableRecords() {
        val state = tracker.observe(DocumentImportState(), snapshot(observation(listKey, SemanticStatus.UNKNOWN, supported = false)))

        assertTrue(state.records.isEmpty())
        assertFalse(state.executable)
        assertTrue(tracker.candidates(state, manual = true).isEmpty())
    }

    @Test
    fun uncommittedReliableSnapshotPreservesHistoryAndDisablesExecution() {
        val used = tracker.observe(DocumentImportState(), snapshot(observation(listKey, SemanticStatus.USED)))
        val uncommitted = tracker.observe(
            used,
            AnalysisSnapshot("java", token(), AnalysisQuality.RELIABLE, committed = false),
        )

        assertEquals(used.records, uncommitted.records)
        assertFalse(uncommitted.executable)
    }

    @Test
    fun absentImportRetiresItsRecordAndAnchor() {
        val used = tracker.observe(DocumentImportState(), snapshot(observation(listKey, SemanticStatus.USED)))
        val absent = tracker.observe(used, snapshot())

        assertTrue(absent.records.isEmpty())
        assertTrue(absent.anchors.isEmpty())
    }

    @Test
    fun insertionAfterImportPreservesItsIdentity() {
        val used = tracker.observe(DocumentImportState(), snapshot(observation(listKey, SemanticStatus.USED, 10)))
        val edited = tracker.edited(used, offset = 40, oldLength = 0, newLength = 3)

        assertEquals(TextRange(10, 32), edited.anchors.single().range)
        assertTrue(edited.records.containsKey(listKey))
        assertFalse(edited.executable)
    }

    @Test
    fun deletionBeforeImportShiftsItsAnchorBackward() {
        val used = tracker.observe(DocumentImportState(), snapshot(observation(listKey, SemanticStatus.USED, 20)))
        val edited = tracker.edited(used, offset = 2, oldLength = 5, newLength = 0)

        assertEquals(TextRange(15, 37), edited.anchors.single().range)
        assertTrue(edited.records.containsKey(listKey))
    }

    @Test
    fun overlappingDeletionRetiresOnlyTheTouchedOccurrence() {
        val used = tracker.observe(
            DocumentImportState(),
            snapshot(
                observation(listKey, SemanticStatus.USED, 0),
                observation(setKey, SemanticStatus.USED, 30),
            ),
        )
        val edited = tracker.edited(used, offset = 5, oldLength = 10, newLength = 0)

        assertFalse(edited.records.containsKey(listKey))
        assertTrue(edited.records.containsKey(setKey))
        assertEquals(TextRange(20, 41), edited.anchors.single().range)
    }

    @Test
    fun dismissingStaleEpisodeCannotSuppressNewerEpisode() {
        val used = tracker.observe(DocumentImportState(), snapshot(observation(listKey, SemanticStatus.USED)))
        val first = tracker.observe(used, snapshot(observation(listKey, SemanticStatus.UNUSED)))
        val usedAgain = tracker.observe(first, snapshot(observation(listKey, SemanticStatus.USED)))
        val second = tracker.observe(usedAgain, snapshot(observation(listKey, SemanticStatus.UNUSED)))

        val stale = Candidate(listKey, episode = 1, displayText = "java.util.List")
        val dismissed = tracker.dismiss(second, listOf(stale))

        assertEquals(2, tracker.candidates(dismissed).single().episode)
    }

    @Test
    fun dismissalSuppressesOnlySelectedCandidate() {
        val baseline = tracker.observe(
            DocumentImportState(),
            snapshot(
                observation(listKey, SemanticStatus.USED, 0),
                observation(setKey, SemanticStatus.USED, 30),
            ),
        )
        val unused = tracker.observe(
            baseline,
            snapshot(
                observation(listKey, SemanticStatus.UNUSED, 0),
                observation(setKey, SemanticStatus.UNUSED, 30),
            ),
        )
        val list = tracker.candidates(unused).single { it.key == listKey }
        val dismissed = tracker.dismiss(unused, listOf(list))

        assertEquals(listOf(setKey), tracker.candidates(dismissed).map { it.key })
        assertEquals(2, tracker.candidates(dismissed, manual = true).size)
    }

    @Test
    fun unknownObservationPreservesReliableStatusEpisodeAndEligibility() {
        val used = tracker.observe(DocumentImportState(), snapshot(observation(listKey, SemanticStatus.USED)))
        val unused = tracker.observe(used, snapshot(observation(listKey, SemanticStatus.UNUSED)))
        val unknown = tracker.observe(unused, snapshot(observation(listKey, SemanticStatus.UNKNOWN)))
        val record = unknown.records.getValue(listKey)

        assertEquals(SemanticStatus.UNUSED, record.lastReliable)
        assertEquals(1, record.episode)
        assertTrue(record.eligible)
        assertFalse(unknown.executable)
    }

    @Test
    fun freshBaselineCannotManufactureRetrospectiveTransition() {
        val prior = tracker.observe(DocumentImportState(), snapshot(observation(listKey, SemanticStatus.USED)))
        val rebaselined = DocumentImportState(generation = prior.generation + 1)
        val unused = tracker.observe(rebaselined, snapshot(observation(listKey, SemanticStatus.UNUSED)))

        assertTrue(tracker.candidates(unused).isEmpty())
        assertTrue(tracker.candidates(unused, manual = true).isEmpty())
    }

    @Test
    fun offeredEpisodeKeepsItsOriginalDeadlineAcrossEditAndReanalysis() {
        val used = tracker.observe(DocumentImportState(), snapshot(observation(listKey, SemanticStatus.USED)))
        val unused = tracker.observe(used, snapshot(observation(listKey, SemanticStatus.UNUSED)))
        val candidate = tracker.candidates(unused).single()
        val offered = tracker.markOffered(unused, listOf(candidate), deadlineMillis = 10_000)
        val edited = tracker.edited(offered, offset = 50, oldLength = 0, newLength = 1)
        val observedAgain = tracker.observe(edited, snapshot(observation(listKey, SemanticStatus.UNUSED)))

        assertEquals(10_000L, tracker.offerDeadline(observedAgain, candidate))
        assertEquals(
            10_000L,
            tracker.offerDeadline(
                tracker.markOffered(observedAgain, listOf(candidate), deadlineMillis = 20_000),
                candidate,
            ),
        )
    }

    private fun snapshot(vararg observations: ImportObservation) = AnalysisSnapshot(
        providerId = "java",
        token = token(),
        quality = AnalysisQuality.RELIABLE,
        observations = observations.toList(),
    )

    private fun observation(
        key: OccurrenceKey,
        status: SemanticStatus,
        start: Int = if (key == listKey) 0 else 30,
        supported: Boolean = true,
    ): ImportObservation {
        val text = if (key == listKey) "import java.util.List;" else "import java.util.Set;"
        return ImportObservation(key, text.removePrefix("import ").removeSuffix(";"), status, supported,
            TextRange(start, start + text.length), text)
    }

    private fun token() = FreshnessToken(stamp = 1, psi = 1, generation = 0, epoch = 0)
}
