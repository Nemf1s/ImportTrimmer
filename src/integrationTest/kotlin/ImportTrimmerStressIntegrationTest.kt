package io.github.nemf1s.integration

import com.intellij.driver.client.Driver
import com.intellij.driver.client.Remote
import com.intellij.driver.client.utility
import com.intellij.driver.sdk.openFile
import io.github.nemf1s.integration.heap.EclipseMatHeapVerifier
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.io.path.Path
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class ImportTrimmerStressIntegrationTest : ImportTrimmerIntegrationTestBase() {
    /**
     * Optional heavyweight validation for known lifecycle and retention risks. A passing run provides targeted
     * regression evidence from a real IDE, heap snapshots, and MAT analysis; it does not prove the plugin can never leak.
     */
    @Tag("stress")
    @Test
    fun fiveMinuteThreeTabRemovalUndoStressReportsResourceUsage() = withIde(
        testName = "import-trimmer-five-minute-stress",
        runTimeout = STRESS_DURATION + 5.minutes,
    ) {
        STRESS_FILES.forEach { openFile(it, waitForCodeAnalysis = false) }
        waitForBaselineAnalysis()
        requestIdeGc()
        Thread.sleep(RESOURCE_SETTLE.inWholeMilliseconds)

        val initial = resourceSample()
        val heapDiagnostics = if (HEAP_DIAGNOSTICS_ENABLED) {
            HeapDiagnostics(ideProcessId()).also { it.captureBaseline() }
        } else {
            null
        }
        var peakHeapBytes = initial.heapUsedBytes
        var peakProcessCpuLoad = initial.processCpuLoad
        var cpuLoadTotal = initial.processCpuLoad.takeIf { it >= 0.0 } ?: 0.0
        var cpuLoadSamples = if (initial.processCpuLoad >= 0.0) 1 else 0
        var editPairs = 0
        val startedAt = System.nanoTime()
        val deadline = startedAt + STRESS_DURATION.inWholeNanoseconds

        while (System.nanoTime() < deadline) {
            openFile(STRESS_FILES[editPairs % STRESS_FILES.size], waitForCodeAnalysis = false)
            Thread.sleep(TAB_SWITCH_SETTLE.inWholeMilliseconds)
            removeUsage()
            restoreUsage()
            editPairs++

            if (editPairs % RESOURCE_SAMPLE_INTERVAL == 0) {
                val sample = resourceSample()
                peakHeapBytes = maxOf(peakHeapBytes, sample.heapUsedBytes)
                if (sample.processCpuLoad >= 0.0) {
                    peakProcessCpuLoad = maxOf(peakProcessCpuLoad, sample.processCpuLoad)
                    cpuLoadTotal += sample.processCpuLoad
                    cpuLoadSamples++
                }
            }
        }
        val workloadElapsedNanos = System.nanoTime() - startedAt

        STRESS_FILES.forEach {
            openFile(it, waitForCodeAnalysis = false)
            val text = selectedDocument().getText()
            check(FIRST_IMPORT in text && USAGE in text) { "$it was not restored after stress operations" }
        }
        closeAllFiles()
        waitForSuggestionToClose()
        val beforeGc = resourceSample()
        peakHeapBytes = maxOf(peakHeapBytes, beforeGc.heapUsedBytes)
        requestIdeGc()
        Thread.sleep(RESOURCE_SETTLE.inWholeMilliseconds)
        val afterGc = resourceSample()
        val heapComparison = heapDiagnostics?.captureFinalAndCompare()
        val measurementElapsedNanos = System.nanoTime() - startedAt
        val editCommands = editPairs * 2
        val minimumEditCommands =
            (STRESS_MINIMUM_COMMANDS_PER_MINUTE * STRESS_DURATION.inWholeSeconds / 60).toInt()

        writeStressMetrics(
            StressMetrics(
                workloadElapsedNanos = workloadElapsedNanos,
                measurementElapsedNanos = measurementElapsedNanos,
                editPairs = editPairs,
                tabSwitches = editPairs + STRESS_FILES.size * 2,
                initialHeapBytes = initial.heapUsedBytes,
                peakHeapBytes = peakHeapBytes,
                beforeGcHeapBytes = beforeGc.heapUsedBytes,
                afterGcHeapBytes = afterGc.heapUsedBytes,
                averageProcessCpuLoad = if (cpuLoadSamples == 0) -1.0 else cpuLoadTotal / cpuLoadSamples,
                peakProcessCpuLoad = peakProcessCpuLoad,
                processCpuNanos = afterGc.processCpuNanos - initial.processCpuNanos,
                availableProcessors = afterGc.availableProcessors,
                heapComparison = heapComparison,
            ),
        )
        check(editCommands >= minimumEditCommands) {
            "Stress throughput was $editCommands edit commands; expected at least $minimumEditCommands"
        }
    }

    private fun Driver.resourceSample(): IdeResourceSample {
        val runtime = utility<RuntimeAccessor>().getRuntime()
        val operatingSystem = utility<ManagementFactoryAccessor>().getOperatingSystemMXBean()
        return IdeResourceSample(
            heapUsedBytes = runtime.totalMemory() - runtime.freeMemory(),
            processCpuLoad = operatingSystem.getProcessCpuLoad(),
            processCpuNanos = operatingSystem.getProcessCpuTime(),
            availableProcessors = runtime.availableProcessors(),
        )
    }

    private fun Driver.requestIdeGc() {
        utility<SystemAccessor>().gc()
    }

    private fun Driver.ideProcessId(): Long =
        utility<ManagementFactoryAccessor>().getRuntimeMXBean().getPid()

    private fun writeStressMetrics(metrics: StressMetrics) {
        val workloadSeconds = metrics.workloadElapsedNanos / 1_000_000_000.0
        val measurementSeconds = metrics.measurementElapsedNanos / 1_000_000_000.0
        val averageCpuPercent = metrics.averageProcessCpuLoad * 100.0
        val peakCpuPercent = metrics.peakProcessCpuLoad * 100.0
        val normalizedCpuPercent = metrics.processCpuNanos.toDouble() /
            metrics.measurementElapsedNanos / metrics.availableProcessors * 100.0
        val report = buildString {
            appendLine("workload.duration.seconds=${format(workloadSeconds)}")
            appendLine("measurement.duration.seconds=${format(measurementSeconds)}")
            appendLine("edit.pairs=${metrics.editPairs}")
            appendLine("removals=${metrics.editPairs}")
            appendLine("undos=${metrics.editPairs}")
            appendLine("tab.switches=${metrics.tabSwitches}")
            appendLine("throughput.commands.per.second=${format(metrics.editPairs * 2 / workloadSeconds)}")
            appendLine("heap.initial.mib=${format(toMiB(metrics.initialHeapBytes))}")
            appendLine("heap.peak.mib=${format(toMiB(metrics.peakHeapBytes))}")
            appendLine("heap.before.gc.mib=${format(toMiB(metrics.beforeGcHeapBytes))}")
            appendLine("heap.after.gc.mib=${format(toMiB(metrics.afterGcHeapBytes))}")
            appendLine("heap.after.gc.delta.mib=${format(toMiB(metrics.afterGcHeapBytes - metrics.initialHeapBytes))}")
            appendLine("cpu.sample.average.percent=${format(averageCpuPercent)}")
            appendLine("cpu.sample.peak.percent=${format(peakCpuPercent)}")
            appendLine("cpu.process.normalized.percent=${format(normalizedCpuPercent)}")
            appendLine("cpu.available.processors=${metrics.availableProcessors}")
            appendLine("heap.diagnostics.enabled=${metrics.heapComparison != null}")
            metrics.heapComparison?.let { comparison ->
                appendLine("heap.histogram.baseline.mib=${format(toMiB(comparison.baselineHistogramBytes))}")
                appendLine("heap.histogram.final.mib=${format(toMiB(comparison.finalHistogramBytes))}")
                appendLine(
                    "heap.histogram.delta.mib=${format(toMiB(comparison.finalHistogramBytes - comparison.baselineHistogramBytes))}",
                )
                appendLine("heap.dump.baseline.mib=${format(toMiB(comparison.baselineDumpBytes))}")
                appendLine("heap.dump.final.mib=${format(toMiB(comparison.finalDumpBytes))}")
                appendLine("heap.comparison.report=${comparison.reportPath}")
                appendLine("heap.dominator.report=${comparison.dominatorReportPath}")
                appendLine("heap.suspects2.report=${comparison.suspects2ReportPath}")
            }
        }
        val reportPath = Path("build/reports/integrationTest/import-trimmer-stress-metrics.txt").toAbsolutePath()
        Files.createDirectories(reportPath.parent)
        Files.writeString(reportPath, report)
        println("Import Trimmer stress metrics ($reportPath):\n$report")
    }

    private fun toMiB(bytes: Long): Double = bytes / (1024.0 * 1024.0)

    private fun format(value: Double): String = String.format(Locale.ROOT, "%.2f", value)

    companion object {
        private const val FIRST_IMPORT = "import dependency.SharedValue;"
        private const val USAGE = "    SharedValue value;\n"
        private const val RESOURCE_SAMPLE_INTERVAL = 25
        private const val STRESS_MINIMUM_COMMANDS_PER_MINUTE = 400
        private val STRESS_FILES = listOf(
            "src/lifecycle/StressTargetOne.java",
            "src/lifecycle/StressTargetTwo.java",
            "src/lifecycle/StressTargetThree.java",
        )
        private val RESOURCE_SETTLE = 2.seconds
        private val TAB_SWITCH_SETTLE = 10.milliseconds
        private val STRESS_DURATION = System.getProperty("integration.stress.duration.seconds")
            ?.toLongOrNull()
            ?.seconds
            ?: 5.minutes
        private val HEAP_DIAGNOSTICS_ENABLED =
            System.getProperty("integration.stress.heap.diagnostics", "true").toBoolean()
    }
}

private data class IdeResourceSample(
    val heapUsedBytes: Long,
    val processCpuLoad: Double,
    val processCpuNanos: Long,
    val availableProcessors: Int,
)

private data class HeapComparisonSummary(
    val baselineHistogramBytes: Long,
    val finalHistogramBytes: Long,
    val baselineDumpBytes: Long,
    val finalDumpBytes: Long,
    val reportPath: Path,
    val dominatorReportPath: Path,
    val suspects2ReportPath: Path,
)

private data class HeapClassStats(
    val instances: Long,
    val bytes: Long,
)

private data class HeapClassDelta(
    val className: String,
    val baseline: HeapClassStats,
    val final: HeapClassStats,
) {
    val instanceDelta: Long = final.instances - baseline.instances
    val byteDelta: Long = final.bytes - baseline.bytes
}

private class HeapDiagnostics(private val processId: Long) {
    private val reportDirectory =
        Path("build/reports/integrationTest/heap-diagnostics").toAbsolutePath()
    private val matHome = Path.of(checkNotNull(System.getProperty("integration.stress.mat.home")) {
        "integration.stress.mat.home must point to an Eclipse MAT installation when heap diagnostics are enabled"
    })
    private lateinit var baseline: HeapSnapshot

    init {
        Files.createDirectories(reportDirectory)
        check(Files.isDirectory(matHome)) { "Eclipse MAT installation was not found at $matHome" }
    }

    fun captureBaseline() {
        baseline = capture("baseline")
    }

    fun captureFinalAndCompare(): HeapComparisonSummary {
        check(::baseline.isInitialized) { "Baseline heap diagnostics were not captured" }
        val final = capture("final")
        verifyClassCounts(baseline, final)
        val reportPath = reportDirectory.resolve("heap-comparison.txt")
        writeComparison(reportPath, baseline, final)
        val dominatorReportPath = reportDirectory.resolve("dominator-verification.txt")
        runDominatorVerifier(baseline.dumpPath, final.dumpPath, dominatorReportPath)
        val suspects2ReportPath = reportDirectory.resolve("final_Leak_Suspects_Delta.zip")
        check(Files.isRegularFile(suspects2ReportPath)) {
            "Eclipse MAT suspects2 report was not created at $suspects2ReportPath"
        }
        return HeapComparisonSummary(
            baselineHistogramBytes = baseline.classes.values.sumOf { it.bytes },
            finalHistogramBytes = final.classes.values.sumOf { it.bytes },
            baselineDumpBytes = Files.size(baseline.dumpPath),
            finalDumpBytes = Files.size(final.dumpPath),
            reportPath = reportPath,
            dominatorReportPath = dominatorReportPath,
            suspects2ReportPath = suspects2ReportPath,
        )
    }

    private fun verifyClassCounts(baseline: HeapSnapshot, final: HeapSnapshot) {
        val violations = RETENTION_TARGET_CLASSES.mapNotNull { className ->
            val baselineCount = baseline.classes[className]?.instances ?: 0
            val finalCount = final.classes[className]?.instances ?: 0
            when {
                className.startsWith(PLUGIN_PACKAGE) && finalCount != 0L ->
                    "$className has $finalCount live instances after editor close"
                !className.startsWith(PLUGIN_PACKAGE) && finalCount > baselineCount ->
                    "$className grew from $baselineCount to $finalCount live instances"
                else -> null
            }
        }
        check(violations.isEmpty()) {
            "Heap histogram retention verification failed:\n${violations.joinToString("\n")}"
        }
    }

    private fun runDominatorVerifier(baselinePath: Path, dumpPath: Path, reportPath: Path) {
        val javaExecutable = Path.of(System.getProperty("java.home")).resolve(
            if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
                "bin/java.exe"
            } else {
                "bin/java"
            },
        )
        check(Files.isRegularFile(javaExecutable)) { "Java runtime was not found at $javaExecutable" }
        val verifierClasses = Path.of(
            EclipseMatHeapVerifier::class.java.protectionDomain.codeSource.location.toURI(),
        )
        val kotlinRuntime = Path.of(Unit::class.java.protectionDomain.codeSource.location.toURI())
        val verifierClasspath = listOf(verifierClasses, kotlinRuntime)
            .distinct()
            .joinToString(File.pathSeparator)
        val processLog = reportDirectory.resolve("dominator-verifier-process.log")
        Files.deleteIfExists(reportPath)
        Files.deleteIfExists(processLog)
        val process = ProcessBuilder(
            javaExecutable.toString(),
            "-cp",
            verifierClasspath,
            EclipseMatHeapVerifier::class.java.name,
            matHome.toString(),
            baselinePath.toString(),
            dumpPath.toString(),
            reportPath.toString(),
        )
            .redirectErrorStream(true)
            .redirectOutput(processLog.toFile())
            .start()
        if (!process.waitFor(DOMINATOR_VERIFIER_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
            process.destroyForcibly()
            error("Eclipse MAT heap verifier timed out after $DOMINATOR_VERIFIER_TIMEOUT_MINUTES minutes")
        }
        check(process.exitValue() == 0) {
            buildString {
                appendLine("Eclipse MAT heap verification failed with exit code ${process.exitValue()}")
                if (Files.isRegularFile(reportPath)) appendLine(Files.readString(reportPath))
                if (Files.isRegularFile(processLog)) appendLine(Files.readString(processLog))
            }
        }
    }

    private fun capture(label: String): HeapSnapshot {
        val histogramPath = reportDirectory.resolve("$label-class-histogram.txt")
        runJcmd(histogramPath, "GC.class_histogram")

        val dumpPath = reportDirectory.resolve("$label.hprof")
        val dumpLogPath = reportDirectory.resolve("$label-heap-dump.log")
        Files.deleteIfExists(dumpPath)
        runJcmd(dumpLogPath, "GC.heap_dump", dumpPath.toString())
        check(Files.isRegularFile(dumpPath) && Files.size(dumpPath) > 0) {
            "Heap dump was not created at $dumpPath"
        }
        return HeapSnapshot(histogramPath, dumpPath, parseHistogram(histogramPath))
    }

    private fun runJcmd(outputPath: Path, vararg diagnosticCommand: String) {
        Files.deleteIfExists(outputPath)
        val command = buildList {
            add(jcmdExecutable())
            add(processId.toString())
            addAll(diagnosticCommand)
        }
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(outputPath.toFile())
            .start()
        if (!process.waitFor(JCMD_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
            process.destroyForcibly()
            error("jcmd timed out after $JCMD_TIMEOUT_MINUTES minutes: ${command.joinToString(" ")}")
        }
        check(process.exitValue() == 0) {
            "jcmd failed with exit code ${process.exitValue()}: ${Files.readString(outputPath)}"
        }
    }

    private fun jcmdExecutable(): String {
        val executable = if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            "jcmd.exe"
        } else {
            "jcmd"
        }
        val javaHome = Path.of(System.getProperty("java.home"))
        return sequenceOf(javaHome.resolve("bin/$executable"), javaHome.resolve("../bin/$executable"))
            .map { it.normalize() }
            .firstOrNull { Files.isRegularFile(it) }
            ?.toString()
            ?: executable
    }

    private fun parseHistogram(path: Path): Map<String, HeapClassStats> {
        val classes = Files.readAllLines(path).mapNotNull { line ->
            HISTOGRAM_ROW.matchEntire(line)?.let { match ->
                match.groupValues[3] to HeapClassStats(
                    instances = match.groupValues[1].toLong(),
                    bytes = match.groupValues[2].toLong(),
                )
            }
        }.toMap()
        check(classes.isNotEmpty()) { "No class rows could be parsed from heap histogram $path" }
        return classes
    }

    private fun writeComparison(path: Path, baseline: HeapSnapshot, final: HeapSnapshot) {
        val classNames = baseline.classes.keys + final.classes.keys
        val deltas = classNames.map { className ->
            HeapClassDelta(
                className = className,
                baseline = baseline.classes[className] ?: EMPTY_CLASS_STATS,
                final = final.classes[className] ?: EMPTY_CLASS_STATS,
            )
        }
        val growing = deltas.filter { it.byteDelta > 0 }.sortedByDescending { it.byteDelta }
        val shrinking = deltas.filter { it.byteDelta < 0 }.sortedBy { it.byteDelta }
        val focused = deltas
            .filter { delta -> FOCUS_CLASS_MARKERS.any { it in delta.className } }
            .sortedByDescending { kotlin.math.abs(it.byteDelta) }
        val retentionTargets = RETENTION_TARGET_CLASSES.map { className ->
            deltas.singleOrNull { it.className == className }
                ?: HeapClassDelta(className, EMPTY_CLASS_STATS, EMPTY_CLASS_STATS)
        }

        val report = buildString {
            appendLine("ide.process.id=$processId")
            appendLine("baseline.histogram=${baseline.histogramPath}")
            appendLine("final.histogram=${final.histogramPath}")
            appendLine("baseline.dump=${baseline.dumpPath}")
            appendLine("final.dump=${final.dumpPath}")
            appendLine("baseline.histogram.bytes=${baseline.classes.values.sumOf { it.bytes }}")
            appendLine("final.histogram.bytes=${final.classes.values.sumOf { it.bytes }}")
            appendLine("histogram.delta.bytes=${final.classes.values.sumOf { it.bytes } - baseline.classes.values.sumOf { it.bytes }}")
            appendLine()
            appendDeltaTable("Top growing classes", growing.take(REPORT_ROW_LIMIT))
            appendLine()
            appendDeltaTable("Top shrinking classes", shrinking.take(REPORT_ROW_LIMIT))
            appendLine()
            appendDeltaTable("Plugin, document, editor, and PSI classes", focused.take(FOCUS_REPORT_ROW_LIMIT))
            appendLine()
            appendDeltaTable("Exact document-retention targets", retentionTargets)
        }
        Files.writeString(path, report)
        println("Import Trimmer heap comparison ($path)")
    }

    private fun StringBuilder.appendDeltaTable(title: String, deltas: List<HeapClassDelta>) {
        appendLine(title)
        appendLine("bytes.delta\tinstances.delta\tfinal.bytes\tfinal.instances\tbaseline.bytes\tbaseline.instances\tclass")
        deltas.forEach { delta ->
            appendLine(
                "${delta.byteDelta}\t${delta.instanceDelta}\t${delta.final.bytes}\t${delta.final.instances}\t" +
                    "${delta.baseline.bytes}\t${delta.baseline.instances}\t${delta.className}",
            )
        }
    }

    companion object {
        private const val JCMD_TIMEOUT_MINUTES = 3L
        private const val DOMINATOR_VERIFIER_TIMEOUT_MINUTES = 5L
        private const val PLUGIN_PACKAGE = "io.github.nemf1s"
        private const val REPORT_ROW_LIMIT = 50
        private const val FOCUS_REPORT_ROW_LIMIT = 100
        private val HISTOGRAM_ROW = Regex("""\s*\d+:\s+(\d+)\s+(\d+)\s+(.+)""")
        private val EMPTY_CLASS_STATS = HeapClassStats(instances = 0, bytes = 0)
        private val FOCUS_CLASS_MARKERS = listOf(
            "io.github.nemf1s",
            "com.intellij.openapi.editor",
            "com.intellij.psi",
            "Document",
            "Editor",
            "Psi",
        )
        private val RETENTION_TARGET_CLASSES = listOf(
            "com.intellij.openapi.editor.impl.DocumentImpl",
            "com.intellij.openapi.editor.impl.EditorImpl",
            "com.intellij.psi.impl.source.PsiJavaFileImpl",
            "io.github.nemf1s.analysis.ImportObservation",
            "io.github.nemf1s.tracking.DocumentImportState",
            "io.github.nemf1s.tracking.TrackedImport",
        )
    }
}

private data class HeapSnapshot(
    val histogramPath: Path,
    val dumpPath: Path,
    val classes: Map<String, HeapClassStats>,
)

private data class StressMetrics(
    val workloadElapsedNanos: Long,
    val measurementElapsedNanos: Long,
    val editPairs: Int,
    val tabSwitches: Int,
    val initialHeapBytes: Long,
    val peakHeapBytes: Long,
    val beforeGcHeapBytes: Long,
    val afterGcHeapBytes: Long,
    val averageProcessCpuLoad: Double,
    val peakProcessCpuLoad: Double,
    val processCpuNanos: Long,
    val availableProcessors: Int,
    val heapComparison: HeapComparisonSummary?,
)

@Remote("java.lang.Runtime")
private interface RuntimeAccessor {
    fun getRuntime(): RuntimeMetrics
}

@Remote("java.lang.Runtime")
private interface RuntimeMetrics {
    fun availableProcessors(): Int
    fun freeMemory(): Long
    fun totalMemory(): Long
}

@Remote("java.lang.management.ManagementFactory")
private interface ManagementFactoryAccessor {
    fun getOperatingSystemMXBean(): OperatingSystemMetrics
    fun getRuntimeMXBean(): RuntimeManagementMetrics
}

@Remote("java.lang.management.RuntimeMXBean")
private interface RuntimeManagementMetrics {
    fun getPid(): Long
}

@Remote("com.sun.management.OperatingSystemMXBean")
private interface OperatingSystemMetrics {
    fun getProcessCpuLoad(): Double
    fun getProcessCpuTime(): Long
}

@Remote("java.lang.System")
private interface SystemAccessor {
    fun gc()
}
