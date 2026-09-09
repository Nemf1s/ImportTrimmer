package io.github.nemf1s.tracking

import io.github.nemf1s.analysis.*

data class TrackedImport(
    val observation: ImportObservation,
    val lastReliable: SemanticStatus,
    val episode: Long = 0,
    val eligible: Boolean = false,
    val dismissed: Boolean = false,
)
data class DocumentImportState(
    val records: Map<OccurrenceKey, TrackedImport> = emptyMap(),
    val anchors: List<OccurrenceAnchor> = emptyList(),
    val generation: Long = 0,
    val executable: Boolean = false,
)

class ImportTransitionTracker {
    fun observe(state: DocumentImportState, snapshot: AnalysisSnapshot): DocumentImportState {
        if (snapshot.quality != AnalysisQuality.RELIABLE || !snapshot.committed) return state.copy(executable = false)
        val records = snapshot.observations.filter { it.supported }.associate { current ->
            val old = state.records[current.key]
            val next = when (current.status) {
                SemanticStatus.UNKNOWN -> old?.copy(observation = current)
                    ?: TrackedImport(current, SemanticStatus.UNKNOWN)
                SemanticStatus.USED -> TrackedImport(current, SemanticStatus.USED, old?.episode ?: 0)
                SemanticStatus.UNUSED -> if (old?.lastReliable == SemanticStatus.USED) {
                    TrackedImport(current, SemanticStatus.UNUSED, old.episode + 1, eligible = true)
                } else old?.copy(observation = current, lastReliable = SemanticStatus.UNUSED)
                    ?: TrackedImport(current, SemanticStatus.UNUSED)
            }
            current.key to next
        }
        return state.copy(records = records, executable = snapshot.observations.none { it.status == SemanticStatus.UNKNOWN },
            anchors = snapshot.observations.map { OccurrenceAnchor(it.key, it.range, it.expectedText) })
    }

    fun candidates(state: DocumentImportState, manual: Boolean = false): List<Candidate> {
        if (!state.executable) return emptyList()
        return state.records.values.filter {
            it.eligible && it.observation.supported && it.observation.status == SemanticStatus.UNUSED && (manual || !it.dismissed)
        }.map { Candidate(it.observation.key, it.episode, it.observation.displayText) }
    }

    fun dismiss(state: DocumentImportState, accepted: List<Candidate>): DocumentImportState {
        val episodes = accepted.associate { it.key to it.episode }
        return state.copy(records = state.records.mapValues { (key, record) ->
            if (episodes[key] == record.episode && record.eligible) record.copy(dismissed = true) else record
        })
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
        return state.copy(anchors = anchors, records = state.records.filterKeys { it in retained },
            generation = state.generation + 1, executable = false)
    }
}
