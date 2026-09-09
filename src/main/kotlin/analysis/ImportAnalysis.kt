package io.github.nemf1s.analysis

import com.intellij.openapi.editor.Document
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiFile

enum class SemanticStatus { USED, UNUSED, UNKNOWN }
enum class AnalysisQuality { RELIABLE, DEFERRED, UNSUPPORTED }

data class OccurrenceKey(val providerId: String, val value: String)
data class ImportObservation(
    val key: OccurrenceKey,
    val displayText: String,
    val status: SemanticStatus,
    val supported: Boolean,
    val range: TextRange,
    val expectedText: String,
    val promptText: String = displayText,
)
data class FreshnessToken(val stamp: Long, val psi: Long, val generation: Long, val epoch: Long)
data class AnalysisSnapshot(
    val providerId: String,
    val token: FreshnessToken,
    val quality: AnalysisQuality,
    val observations: List<ImportObservation> = emptyList(),
    val reason: String? = null,
    val committed: Boolean = true,
    val interactionRange: TextRange? = null,
)
data class OccurrenceAnchor(val key: OccurrenceKey, val range: TextRange, val text: String)

/** Called under a committed, smart background read action. Results contain no PSI handles. */
interface ImportAnalyzer {
    val providerId: String
    fun supports(file: PsiFile): Boolean
    fun analyze(file: PsiFile, document: Document, token: FreshnessToken, anchors: List<OccurrenceAnchor>): AnalysisSnapshot
}

data class Candidate(
    val key: OccurrenceKey,
    val episode: Long,
    val displayText: String,
    val promptText: String = displayText,
)
data class RemovalRequest(val providerId: String, val candidates: List<Candidate>)
data class TextDeletion(val range: TextRange, val expectedText: String)
data class ImportEditPlan(val token: FreshnessToken, val deletions: List<TextDeletion>)

/** Read-only planning in the same read action as final analysis. Decline the full batch on ambiguity. */
interface ImportEditPlanner {
    val providerId: String
    fun plan(file: PsiFile, document: Document, snapshot: AnalysisSnapshot, accepted: List<Candidate>): ImportEditPlan?
}

data class ImportProvider(val analyzer: ImportAnalyzer, val planner: ImportEditPlanner) {
    init { require(analyzer.providerId == planner.providerId) }
}
fun selectProvider(providers: List<ImportProvider>, file: PsiFile): ImportProvider? =
    providers.singleOrNull { it.analyzer.supports(file) }
