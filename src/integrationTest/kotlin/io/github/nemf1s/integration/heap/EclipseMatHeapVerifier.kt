package io.github.nemf1s.integration.heap

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Comparator
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.system.exitProcess

object EclipseMatHeapVerifier {
    private const val PLUGIN_PACKAGE = "io.github.nemf1s"

    // Hide platform/library dominators so any plugin object in the dominator chain is surfaced.
    private const val NON_PLUGIN_CLASS_PATTERN = "^(?!io\\.github\\.nemf1s).*"
    private val pluginClassPattern = Regex("""io\.github\.nemf1s[\w.$+]*""")
    private val retentionTargets = listOf(
        "com.intellij.openapi.editor.impl.DocumentImpl",
        "com.intellij.openapi.editor.impl.EditorImpl",
        "com.intellij.psi.impl.source.PsiJavaFileImpl",
    )
    private val transientPluginClasses = listOf(
        "io.github.nemf1s.analysis.ImportObservation",
        "io.github.nemf1s.tracking.DocumentImportState",
        "io.github.nemf1s.tracking.TrackedImport",
    )

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 4) {
            "Expected <mat-home> <baseline.hprof> <final.hprof> <report-path>"
        }

        val matHome = Path.of(args[0]).toAbsolutePath()
        val baselinePath = Path.of(args[1]).toAbsolutePath()
        val dumpPath = Path.of(args[2]).toAbsolutePath()
        val reportPath = Path.of(args[3]).toAbsolutePath()
        require(Files.isRegularFile(baselinePath) && Files.isRegularFile(dumpPath)) {
            "Baseline and final HPROF files must exist"
        }
        val evidenceDirectory = reportPath.resolveSibling("eclipse-mat-evidence")
        val mat = MatRunner(matHome, dumpPath, evidenceDirectory)
        val report = mutableListOf<String>()
        val violations = mutableListOf<String>()

        report += "analyzer=Eclipse Memory Analyzer"
        report += "baseline=$baselinePath"
        report += "snapshot=$dumpPath"

        for (className in transientPluginClasses) {
            val histogram = mat.run(
                "histogram $className",
                "${safeLabel(className)}-histogram",
            )
            val instanceCount = histogramInstanceCount(histogram.lines, className)
            report += "transient.$className.instances=$instanceCount"
            report += "transient.$className.histogram.report=${histogram.reportPath}"
            if (instanceCount != 0L) {
                violations += "$className has $instanceCount live instances after editor close"
            }
        }

        for (className in retentionTargets) {
            verifyRetention(mat, className, report, violations)
        }

        val suspects = mat.runSuspectsComparison(baselinePath)
        report += "suspects2.report=${suspects.reportPath}"
        report += "suspects2.problemSuspects=${suspects.descriptions.size}"
        val pluginSuspects = mutableListOf<Int>()
        suspects.descriptions.forEachIndexed { index, description ->
            val suspectNumber = index + 1
            report += "suspects2.suspect.$suspectNumber=${description.lineSequence().firstOrNull().orEmpty()}"
            val suspectPluginClasses = pluginClasses(listOf(description))
            report += "suspects2.suspect.$suspectNumber.pluginClasses=$suspectPluginClasses"
            if (suspectPluginClasses.isNotEmpty()) {
                pluginSuspects += suspectNumber
            }
        }
        report += "suspects2.pluginProblemSuspects=$pluginSuspects"
        if (pluginSuspects.isNotEmpty()) {
            violations += "MAT suspects2 implicated plugin classes in problem suspects $pluginSuspects"
        }

        report += "violations=${violations.size}"
        report += violations.map { "violation=$it" }
        Files.createDirectories(reportPath.parent)
        Files.write(reportPath, report, StandardCharsets.UTF_8)
        if (violations.isNotEmpty()) {
            System.err.println(violations.joinToString(System.lineSeparator()))
            exitProcess(2)
        }
    }

    private fun verifyRetention(
        mat: MatRunner,
        className: String,
        report: MutableList<String>,
        violations: MutableList<String>,
    ) {
        val label = safeLabel(className)
        val histogram = mat.run("histogram $className", "$label-histogram")
        val instanceCount = histogramInstanceCount(histogram.lines, className)
        report += "target.$className.instances=$instanceCount"
        report += "target.$className.histogram.report=${histogram.reportPath}"

        val dominators = mat.run(
            "immediate_dominators $className -skip $NON_PLUGIN_CLASS_PATTERN",
            "$label-dominators",
        )
        val pluginDominators = pluginClasses(dominators.lines)
        report += "target.$className.pluginDominators=$pluginDominators"
        report += "target.$className.dominators.report=${dominators.reportPath}"
        if (pluginDominators.isNotEmpty()) {
            violations += "$className is dominated by plugin objects: $pluginDominators"
        }
    }

    private fun histogramInstanceCount(lines: List<String>, className: String): Long {
        for (line in lines) {
            val cells = line.split('|')
            if (cells.size >= 2 && cells[0].trim() == className) {
                return cells[1].replace(",", "").trim().toLong()
            }
        }
        return 0
    }

    private fun pluginClasses(lines: List<String>): Set<String> {
        val classes = linkedSetOf<String>()
        for (line in lines) {
            pluginClassPattern.findAll(line)
                .map { it.value }
                .filter { it.startsWith(PLUGIN_PACKAGE) }
                .forEach(classes::add)
        }
        return classes
    }

    private fun safeLabel(className: String): String =
        className.replace('.', '-').replace('$', '-')

    private data class QueryResult(
        val reportPath: Path,
        val lines: List<String>,
    )

    private data class SuspectsReport(
        val reportPath: Path,
        val descriptions: List<String>,
    )

    private class MatRunner(
        matHome: Path,
        private val dumpPath: Path,
        private val evidenceDirectory: Path,
    ) {
        private val executable = findExecutable(matHome)
        private val launcherIni = findLauncherIni(matHome)
        private val queryDirectory = queryDirectory(dumpPath)
        private val processLog = evidenceDirectory.resolve("eclipse-mat-process.log")

        init {
            deleteTree(evidenceDirectory)
            Files.createDirectories(evidenceDirectory)
        }

        fun run(query: String, reportName: String): QueryResult {
            deleteTree(queryDirectory)
            val command = listOf(
                executable.toString(),
                "--launcher.ini",
                launcherIni.toString(),
                "-consoleLog",
                "-nosplash",
                "-data",
                evidenceDirectory.resolve("workspace").toString(),
                "-application",
                "org.eclipse.mat.api.parse",
                dumpPath.toString(),
                "-command=$query",
                "-format=txt",
                "-unzip",
                "org.eclipse.mat.api:query",
                "-vmargs",
                "-Xmx8g",
            )
            runProcess(command, "query: $query")

            val queryReport = queryDirectory.resolve("pages/Query_Command2.txt")
            check(Files.isRegularFile(queryReport)) {
                "Eclipse MAT did not create the expected query report for: $query\n${Files.readString(processLog)}"
            }
            val savedReport = evidenceDirectory.resolve("$reportName.txt")
            Files.copy(queryReport, savedReport, StandardCopyOption.REPLACE_EXISTING)
            val lines = Files.readAllLines(savedReport, StandardCharsets.UTF_8)
            check(lines.none { "Problem reported:" in it }) {
                "Eclipse MAT rejected query: $query\n${lines.joinToString("\n")}"
            }
            return QueryResult(savedReport, lines)
        }

        fun runSuspectsComparison(baselinePath: Path): SuspectsReport {
            val reportArchive = reportArchive(dumpPath)
            Files.deleteIfExists(reportArchive)
            deleteTree(reportArchive.resolveSibling(stripExtension(reportArchive.fileName.toString())))
            val command = listOf(
                executable.toString(),
                "--launcher.ini",
                launcherIni.toString(),
                "-consoleLog",
                "-nosplash",
                "-data",
                evidenceDirectory.resolve("workspace").toString(),
                "-application",
                "org.eclipse.mat.api.parse",
                dumpPath.toString(),
                "-baseline=$baselinePath",
                "-format=txt",
                "org.eclipse.mat.api:suspects2",
                "-vmargs",
                "-Xmx8g",
            )
            runProcess(command, "suspects2 comparison")
            check(Files.isRegularFile(reportArchive)) {
                "Eclipse MAT did not create the suspects2 report at $reportArchive\n${Files.readString(processLog)}"
            }

            val descriptions = mutableListOf<String>()
            ZipFile(reportArchive.toFile(), StandardCharsets.UTF_8).use { report ->
                val index = checkNotNull(report.getEntry("index.html")) {
                    "Eclipse MAT suspects2 report has no index.html: $reportArchive"
                }
                val indexHtml = report.getInputStream(index).use { input ->
                    String(input.readAllBytes(), StandardCharsets.UTF_8)
                }
                val entries = report.entries().asSequence()
                    .filter { it.name.matches(DESCRIPTION_ENTRY_PATTERN) }
                    .sortedBy(::descriptionEntryNumber)
                    .toList()
                for (entry in entries) {
                    val description = report.getInputStream(entry).use { input ->
                        String(input.readAllBytes(), StandardCharsets.UTF_8).trim()
                    }
                    descriptions += description
                }
                val suspectLabels = PROBLEM_SUSPECT_PATTERN.findAll(indexHtml)
                    .map { it.value }
                    .toCollection(linkedSetOf())
                check(suspectLabels.size == descriptions.size) {
                    "Eclipse MAT suspects2 report contained ${suspectLabels.size} suspect headings but " +
                        "${descriptions.size} descriptions: $reportArchive"
                }
            }
            return SuspectsReport(reportArchive, descriptions)
        }

        private fun runProcess(command: List<String>, operation: String) {
            val process = ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(processLog.toFile()))
                .start()
            if (!process.waitFor(QUERY_TIMEOUT_MINUTES, TimeUnit.MINUTES)) {
                process.destroyForcibly()
                error("Eclipse MAT timed out during $operation")
            }
            check(process.exitValue() == 0) {
                "Eclipse MAT failed with exit code ${process.exitValue()} during $operation\n" +
                    Files.readString(processLog)
            }
        }

        companion object {
            private const val QUERY_TIMEOUT_MINUTES = 10L
            private val PROBLEM_SUSPECT_PATTERN = Regex("""Problem\s+Suspect\s+\d+""")
            private val DESCRIPTION_ENTRY_PATTERN = Regex("""pages[\\/]Description\d+\.txt""")

            private fun descriptionEntryNumber(entry: ZipEntry): Int =
                entry.name.substringAfter("Description").substringBeforeLast('.').toInt()

            private fun findExecutable(matHome: Path): Path {
                val candidates = listOf(
                    matHome.resolve("MemoryAnalyzerc.exe"),
                    matHome.resolve("MemoryAnalyzer.exe"),
                    matHome.resolve("MemoryAnalyzer"),
                    matHome.resolve("MemoryAnalyzer.app/Contents/MacOS/MemoryAnalyzer"),
                )
                return candidates.firstOrNull { Files.isRegularFile(it) }
                    ?: throw IllegalArgumentException("Eclipse MAT executable was not found in $matHome")
            }

            private fun findLauncherIni(matHome: Path): Path {
                val candidates = listOf(
                    matHome.resolve("MemoryAnalyzer.ini"),
                    matHome.resolve("MemoryAnalyzer.app/Contents/Eclipse/MemoryAnalyzer.ini"),
                )
                return candidates.firstOrNull { Files.isRegularFile(it) }
                    ?: throw IllegalArgumentException("Eclipse MAT launcher configuration was not found in $matHome")
            }

            private fun queryDirectory(dumpPath: Path): Path {
                val baseName = stripExtension(dumpPath.fileName.toString())
                return dumpPath.resolveSibling("${baseName}_Query")
            }

            private fun reportArchive(dumpPath: Path): Path {
                val baseName = stripExtension(dumpPath.fileName.toString())
                return dumpPath.resolveSibling("${baseName}_Leak_Suspects_Delta.zip")
            }

            private fun stripExtension(fileName: String): String = fileName.substringBeforeLast('.', fileName)

            private fun deleteTree(root: Path) {
                if (!Files.exists(root)) return
                Files.walk(root).use { paths ->
                    for (path in paths.sorted(Comparator.reverseOrder()).toList()) {
                        Files.deleteIfExists(path)
                    }
                }
            }
        }
    }
}
