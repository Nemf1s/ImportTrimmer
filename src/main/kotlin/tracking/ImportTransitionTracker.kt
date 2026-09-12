package io.github.nemf1s.tracking

import com.intellij.openapi.util.TextRange
import io.github.nemf1s.analysis.AnalysisQuality
import io.github.nemf1s.analysis.AnalysisSnapshot
import io.github.nemf1s.analysis.Candidate
import io.github.nemf1s.analysis.ImportObservation
import io.github.nemf1s.analysis.OccurrenceAnchor
import io.github.nemf1s.analysis.OccurrenceKey
import io.github.nemf1s.analysis.SemanticStatus

data class TrackedImport(
    val observation: ImportObservation,
    val lastReliable: SemanticStatus,
    val episode: Long = 0,
    val eligible: Boolean = false,
    val dismissed: Boolean = false,
    val offerDeadlineMillis: Long? = null,
)

data class DocumentImportState(
    val records: Map<OccurrenceKey, TrackedImport> = emptyMap(),
    val anchors: List<OccurrenceAnchor> = emptyList(),
    val generation: Long = 0,
    val executable: Boolean = false,
    val interactionRange: TextRange? = null,
)

class ImportTransitionTracker {
    fun observe(state: DocumentImportState, snapshot: AnalysisSnapshot): DocumentImportState {
        if (snapshot.quality != AnalysisQuality.RELIABLE || !snapshot.committed) return state.copy(executable = false)
        val records = snapshot.observations
            .filter(ImportObservation::supported)
            .associate { observation ->
                observation.key to transition(state.records[observation.key], observation)
            }
        return state.copy(
            records = records,
            executable = snapshot.observations.none { it.status == SemanticStatus.UNKNOWN },
            anchors = snapshot.observations.map { OccurrenceAnchor(it.key, it.range, it.expectedText) },
            interactionRange = snapshot.interactionRange,
        )
    }

    fun candidates(state: DocumentImportState, manual: Boolean = false): List<Candidate> {
        if (!state.executable) return emptyList()
        return state.records.values
            .filter { record ->
                record.eligible &&
                    record.observation.supported &&
                    record.observation.status == SemanticStatus.UNUSED &&
                    (manual || !record.dismissed)
            }
            .map { record ->
                Candidate(
                    key = record.observation.key,
                    episode = record.episode,
                    displayText = record.observation.displayText,
                    promptText = record.observation.promptText,
                )
            }
    }

    fun markOffered(
        state: DocumentImportState,
        offered: List<Candidate>,
        deadlineMillis: Long,
    ): DocumentImportState {
        val episodes = offered.associate { it.key to it.episode }
        val records = state.records.mapValues { (key, record) ->
            val canSetDeadline = episodes[key] == record.episode &&
                record.eligible &&
                record.offerDeadlineMillis == null
            if (canSetDeadline) record.copy(offerDeadlineMillis = deadlineMillis) else record
        }
        return state.copy(records = records)
    }

    fun offerDeadline(state: DocumentImportState, candidate: Candidate): Long? =
        state.records[candidate.key]?.takeIf { it.episode == candidate.episode }?.offerDeadlineMillis

    fun dismiss(state: DocumentImportState, accepted: List<Candidate>): DocumentImportState {
        val episodes = accepted.associate { it.key to it.episode }
        val records = state.records.mapValues { (key, record) ->
            if (episodes[key] == record.episode && record.eligible) record.copy(dismissed = true) else record
        }
        return state.copy(records = records)
    }

    /** Edits touching an occurrence retire it, including replacement with identical text. */
    fun edited(state: DocumentImportState, offset: Int, oldLength: Int, newLength: Int): DocumentImportState {
        val anchors = state.anchors.mapNotNull { anchor ->
            when {
                oldLength == 0 && offset < anchor.range.startOffset ->
                    anchor.copy(range = anchor.range.shiftRight(newLength))
                oldLength > 0 && offset + oldLength <= anchor.range.startOffset ->
                    anchor.copy(range = anchor.range.shiftRight(newLength - oldLength))
                offset >= anchor.range.endOffset -> anchor
                else -> null
            }
        }
        val retained = anchors.map { it.key }.toSet()
        return state.copy(
            anchors = anchors,
            records = state.records.filterKeys { it in retained },
            generation = state.generation + 1,
            executable = false,
            interactionRange = null,
        )
    }

    private fun transition(previous: TrackedImport?, current: ImportObservation): TrackedImport {
        return when (current.status) {
            SemanticStatus.UNKNOWN -> previous?.copy(observation = current)
                ?: TrackedImport(current, SemanticStatus.UNKNOWN)

            SemanticStatus.USED -> TrackedImport(
                observation = current,
                lastReliable = SemanticStatus.USED,
                episode = previous?.episode ?: 0,
            )

            SemanticStatus.UNUSED -> transitionToUnused(previous, current)
        }
    }

    private fun transitionToUnused(previous: TrackedImport?, current: ImportObservation): TrackedImport {
        return if (previous?.lastReliable == SemanticStatus.USED) {
            TrackedImport(
                observation = current,
                lastReliable = SemanticStatus.UNUSED,
                episode = previous.episode + 1,
                eligible = true,
            )
        } else {
            previous?.copy(observation = current, lastReliable = SemanticStatus.UNUSED)
                ?: TrackedImport(current, SemanticStatus.UNUSED)
        }
    }
}
