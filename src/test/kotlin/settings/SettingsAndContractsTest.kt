package io.github.nemf1s.settings

import io.github.nemf1s.analysis.AnalysisQuality
import io.github.nemf1s.analysis.AnalysisSnapshot
import io.github.nemf1s.analysis.FreshnessToken
import io.github.nemf1s.analysis.ImportAnalyzer
import io.github.nemf1s.analysis.ImportEditPlan
import io.github.nemf1s.analysis.ImportEditPlanner
import io.github.nemf1s.analysis.ImportProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsAndContractsTest {
    @Test
    fun preferencesDefaultToEnabledAskModeAndDocumentedTimings() {
        val preferences = Preferences()

        assertTrue(preferences.enabled)
        assertEquals(RemovalMode.ASK, preferences.mode)
        assertEquals(750, preferences.debounceMs)
        assertEquals(10, preferences.timeoutSeconds)
    }

    @Test
    fun preferenceNormalizationClampsBothConfiguredRanges() {
        val below = Preferences(debounceMs = -1, timeoutSeconds = 0).normalized()
        val above = Preferences(debounceMs = 10_000, timeoutSeconds = 600).normalized()

        assertEquals(250, below.debounceMs)
        assertEquals(3, below.timeoutSeconds)
        assertEquals(3000, above.debounceMs)
        assertEquals(60, above.timeoutSeconds)
    }

    @Test
    fun providerCompositionRejectsMismatchedAnalyzerAndPlannerIds() {
        val analyzer = object : ImportAnalyzer {
            override val providerId = "one"
            override fun supports(file: com.intellij.psi.PsiFile) = false
            override fun analyze(
                file: com.intellij.psi.PsiFile,
                document: com.intellij.openapi.editor.Document,
                token: FreshnessToken,
                anchors: List<io.github.nemf1s.analysis.OccurrenceAnchor>,
            ) = AnalysisSnapshot(providerId, token, AnalysisQuality.RELIABLE)
        }
        val planner = object : ImportEditPlanner {
            override val providerId = "two"
            override fun plan(
                file: com.intellij.psi.PsiFile,
                document: com.intellij.openapi.editor.Document,
                snapshot: AnalysisSnapshot,
                accepted: List<io.github.nemf1s.analysis.Candidate>,
            ): ImportEditPlan? = null
        }

        assertThrows(IllegalArgumentException::class.java) {
            ImportProvider(analyzer, planner)
        }
    }
}
