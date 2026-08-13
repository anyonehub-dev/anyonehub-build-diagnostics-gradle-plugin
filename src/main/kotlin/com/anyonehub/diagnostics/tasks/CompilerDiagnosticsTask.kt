// Copyright 2024 anyone-Hub
// Phase 3 — Compiler Diagnostics: C++ CMake warnings + Kotlin/Java deprecations.
//
// GUARDRAILS:
// ✅ READ-ONLY  — never adds compiler flags; only reads existing build artifacts.
// ✅ LAZY       — cxxBaseDir and kotlinBuildReportDir wired as DirectoryProperty.
// ✅ GRACEFUL   — all paths are probed; missing artifacts produce advisory notes.

package com.anyonehub.diagnostics.tasks

import com.anyonehub.diagnostics.model.CompilerWarning
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * Pipeline A — Compiler Diagnostics Task.
 *
 * Scans two distinct artifact trees for compiler warnings without touching
 * any live compiler process or modifying build flags:
 *
 * C++ Path: Walks the .cxx/ directory tree (input: [cxxBaseDir]) and parses:
 *   - CMakeConfigureLog.yaml  — CMake configure-phase warnings
 *   - compile_commands.json   — Per-TU command lines for flag analysis
 *
 * Kotlin/Java Path: Reads from [kotlinBuildReportDir] if present (enabled via
 * kotlin.build.report.output=file in gradle.properties), and from the Gradle
 * tmp directories for persisted warning output files.
 */
@CacheableTask
abstract class CompilerDiagnosticsTask : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val cxxBaseDir: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val kotlinBuildReportDir: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val kotlinTmpDir: ConfigurableFileCollection

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val javaTmpDir: ConfigurableFileCollection

    /**
     * Output file from the [CompilerOutputCollectorService] BuildService.
     * This is the PRIMARY source for Kotlin/Java deprecation warnings,
     * intercepted live from compiler stderr during task execution.
     * Falls back gracefully when the file doesn't exist (e.g., no compile tasks ran).
     */
    @get:org.gradle.api.tasks.Internal
    abstract val collectorServiceOutput: RegularFileProperty

    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun execute() {
        val output = outputFile.get().asFile
        output.parentFile.mkdirs()

        val allWarnings = mutableListOf<CompilerWarning>()
        // Track seen warnings by (file + line) to prevent duplicates across sources.
        val seenKeys = mutableSetOf<String>()

        fun addWarning(warning: CompilerWarning) {
            val key = "${warning.language}:${warning.sourceFile}:${warning.line}:${warning.snippet.take(60)}"
            if (seenKeys.add(key)) {
                allWarnings += warning
            }
        }

        // ── PRIMARY SOURCE: BuildService live interception ────────────────────
        val serviceFile = collectorServiceOutput.orNull?.asFile
        if (serviceFile != null && serviceFile.exists() && serviceFile.length() > 0) {
            logger.lifecycle("[ProjectHealth/Compiler] Reading live BuildService output: ${serviceFile.path}")
            serviceFile.readLines()
                .filter { it.isNotBlank() }
                .mapNotNull { CompilerWarning.fromIntermediateLine(it) }
                .forEach { addWarning(it) }
            logger.lifecycle(
                "[ProjectHealth/Compiler] BuildService captured ${allWarnings.size} deprecation warnings."
            )
        } else {
            logger.lifecycle(
                "[ProjectHealth/Compiler] No BuildService output found — falling back to log scanning."
            )
        }

        // ── C++ warnings (always from file scanning — no BuildService needed) ──
        cxxBaseDir.files.filter { it.exists() }.forEach { cxxDir ->
            logger.lifecycle("[ProjectHealth/Compiler] Scanning C++ build artifacts in ${cxxDir.path}")
            parseCxxWarnings(cxxDir).forEach { addWarning(it) }
        }

        // ── SECONDARY FALLBACK: Kotlin Build Reports ──────────────────────────
        val allReports = buildList {
            kotlinBuildReportDir.files.filter { it.exists() }.forEach { dir ->
                addAll(dir.walkTopDown().filter { it.extension == "txt" })
            }
        }
        if (allReports.isNotEmpty()) {
            logger.lifecycle("[ProjectHealth/Compiler] Reading Kotlin Build Reports (secondary source)")
            parseKotlinBuildReports(allReports).forEach { addWarning(it) }
        }

        // ── TERTIARY FALLBACK: Gradle tmp directories ──────────────────────────
        kotlinTmpDir.files.filter { it.exists() }.forEach { kotlinTmp ->
            parseCompileTmpDir(kotlinTmp, language = "Kotlin").forEach { addWarning(it) }
        }
        javaTmpDir.files.filter { it.exists() }.forEach { javaTmp ->
            parseCompileTmpDir(javaTmp, language = "Java").forEach { addWarning(it) }
        }

        val hasKotlinJava = allWarnings.any { it.language == "Kotlin" || it.language == "Java" }
        val advisory = if (!hasKotlinJava) {
            buildString {
                appendLine("ADVISORY: No Kotlin/Java deprecation warnings detected.")
                appendLine("The BuildService listener captured 0 deprecation lines from compiler output.")
                appendLine("To enable Kotlin Build Reports as a secondary source, add to gradle.properties:")
                appendLine("  kotlin.build.report.output=file")
                appendLine("  kotlin.build.report.file.output.dir=build/reports/kotlin-build")
            }
        } else null

        val cppCount = allWarnings.count { it.language == "C++" }
        val kotlinCount = allWarnings.count { it.language == "Kotlin" }
        val javaCount = allWarnings.count { it.language == "Java" }
        logger.lifecycle(
            "[ProjectHealth/Compiler] Collected ${allWarnings.size} compiler warnings " +
                    "($cppCount C++, $kotlinCount Kotlin, $javaCount Java)."
        )

        output.bufferedWriter().use { writer ->
            writer.appendLine(CompilerWarning.SECTION_HEADER)
            allWarnings.forEach { warning ->
                writer.appendLine(warning.toIntermediateLine())
            }
            advisory?.let { writer.appendLine("# ADVISORY: $it") }
            writer.appendLine(CompilerWarning.SECTION_FOOTER)
        }
    }

    // ── C++ warning parsers ───────────────────────────────────────────────────

    private fun parseCxxWarnings(cxxDir: File): List<CompilerWarning> {
        val warnings = mutableListOf<CompilerWarning>()
        cxxDir.walkTopDown()
            .onEnter { !it.name.startsWith(".gradle") }
            .filter { it.isFile }
            .forEach { file ->
                when (file.name) {
                    "CMakeConfigureLog.yaml" -> warnings += parseCMakeConfigureLog(file)
                    "compile_commands.json"  -> warnings += parseCompileCommandsJson(file)
                }
            }
        return warnings
    }

    /**
     * Parses CMakeConfigureLog.yaml for configure-phase warnings.
     *
     * Pattern matched: lines containing [-W (GCC/Clang warning flags).
     * Clang diagnostic format: /path/to/file.cpp:line:col: warning: text [-Wflag]
     */
    private fun parseCMakeConfigureLog(logFile: File): List<CompilerWarning> {
        val warnings = mutableListOf<CompilerWarning>()
        val warnFlagRegex = Regex("""\[-W([\w-]+)]""")
        val clangDiagnosticRegex = Regex(
            """^(?<file>[^:]+):(?<line>\d+):(?<col>\d+):\s+warning:\s+(?<msg>.+?)(?:\s*\[(?<flag>-W[\w-]+)])?$"""
        )

        var currentFile = logFile.path

        logFile.useLines { lines ->
            for (rawLine in lines) {
                val line = rawLine.trimEnd()

                if (line.contains("file:") && (line.contains(".cpp") || line.contains(".c:"))) {
                    val fileMatch = Regex("""file:\s*['""]?([^'""\s]+\.[ch](?:pp|xx)?)['""]?""")
                        .find(line)
                    if (fileMatch != null) currentFile = fileMatch.groupValues[1]
                }

                val diagMatch = clangDiagnosticRegex.find(line)
                if (diagMatch != null) {
                    val flag = diagMatch.groups["flag"]?.value ?: "-Wunknown"
                    if (flag.contains("deprecated", ignoreCase = true) ||
                        line.contains("deprecated", ignoreCase = true)
                    ) {
                        warnings += CompilerWarning(
                            language = "C++",
                            sourceFile = diagMatch.groups["file"]?.value ?: currentFile,
                            line = diagMatch.groups["line"]?.value?.toIntOrNull() ?: -1,
                            column = diagMatch.groups["col"]?.value?.toIntOrNull() ?: -1,
                            flag = flag,
                            snippet = diagMatch.groups["msg"]?.value?.take(120) ?: "",
                        )
                    }
                }

                if (line.contains("warning", ignoreCase = true) &&
                    warnFlagRegex.containsMatchIn(line)
                ) {
                    val flagMatch = warnFlagRegex.find(line) ?: continue
                    val flag = "-W${flagMatch.groupValues[1]}"
                    if (flag.contains("deprecated", ignoreCase = true) ||
                        line.contains("deprecated", ignoreCase = true)
                    ) {
                        val snippet = line.trim().take(120)
                        if (warnings.none { it.snippet == snippet }) {
                            warnings += CompilerWarning(
                                language = "C++",
                                sourceFile = currentFile,
                                line = -1,
                                column = -1,
                                flag = flag,
                                snippet = snippet,
                            )
                        }
                    }
                }
            }
        }
        return warnings
    }

    /**
     * Parses compile_commands.json to extract source files compiled with
     * deprecation-related flags and reports them as structural notes.
     */
    private fun parseCompileCommandsJson(jsonFile: File): List<CompilerWarning> {
        val warnings = mutableListOf<CompilerWarning>()
        val content = jsonFile.readText()

        val commandRegex = Regex(""""command"\s*:\s*"([^"]+)"""")
        val fileRegex = Regex(""""file"\s*:\s*"([^"]+)"""")

        val commandMatches = commandRegex.findAll(content).toList()
        val fileMatches = fileRegex.findAll(content).toList()

        commandMatches.forEachIndexed { idx, cmdMatch ->
            val command = cmdMatch.groupValues[1].replace("\\\"", "\"")
            val sourceFile = fileMatches.getOrNull(idx)?.groupValues?.get(1) ?: "unknown"

            val hasDeprecatedFlag = command.contains("-Wdeprecated") ||
                    command.contains("-Weverything") ||
                    command.contains("-Wall")

            if (hasDeprecatedFlag) {
                warnings += CompilerWarning(
                    language = "C++",
                    sourceFile = sourceFile,
                    line = -1,
                    column = -1,
                    flag = "-Wdeprecated (from compile_commands.json)",
                    snippet = "[Deprecation tracking active for this translation unit]",
                )
            }
        }

        return warnings
    }

    // ── Kotlin / Java warning parsers ─────────────────────────────────────────

    /**
     * Parses Kotlin Build Report files from build/reports/kotlin-build/.
     *
     * Kotlin Build Reports (enabled via kotlin.build.report.output=file) contain
     * structured warning data. Format:
     *   w: file:///path/to/File.kt:42:5: 'ClassName' is deprecated.
     */
    private fun parseKotlinBuildReports(allReports: List<File>): List<CompilerWarning> {
        val warnings = mutableListOf<CompilerWarning>()

        val kotlinDeprecationRegex = Regex(
            """w:\s+(?:file:///)?(?<file>[^:]+\.kt):(?<line>\d+):(?<col>\d+):\s+(?<msg>.+)"""
        )
        val summaryDeprecationRegex = Regex(
            """'(?<api>[^']+)'\s+is\s+deprecated\.?\s*(?<suggestion>.*)"""
        )

        allReports.forEach { file ->
            val lines = file.readLines()
            for (rawLine in lines) {
                val line = rawLine.trimEnd()
                if (!line.contains("deprecated", ignoreCase = true)) continue

                val match = kotlinDeprecationRegex.find(line) ?: continue
                val msg = match.groups["msg"]?.value ?: ""
                val apiMatch = summaryDeprecationRegex.find(msg)
                val suggestion = apiMatch?.groups?.get("suggestion")?.value ?: ""
                val apiName = apiMatch?.groups?.get("api")?.value
                val snippet = if (apiName != null) "$apiName is deprecated. $suggestion" else msg

                warnings += CompilerWarning(
                    language = "Kotlin",
                    sourceFile = match.groups["file"]?.value ?: file.path,
                    line = match.groups["line"]?.value?.toIntOrNull() ?: -1,
                    column = match.groups["col"]?.value?.toIntOrNull() ?: -1,
                    flag = "w: (deprecated)",
                    snippet = snippet.take(120),
                )
            }
        }

        return warnings
    }

    /**
     * Scans a Gradle task tmp directory (build/tmp/compileXxx) for any .txt
     * files containing compiler warning output persisted across incremental builds.
     */
    private fun parseCompileTmpDir(tmpDir: File, language: String): List<CompilerWarning> {
        val warnings = mutableListOf<CompilerWarning>()

        val pattern = when (language) {
            "Kotlin" -> Regex("""w:\s+.+deprecated.+""", RegexOption.IGNORE_CASE)
            "Java"   -> Regex("""warning:\s+\[deprecation\].+""", RegexOption.IGNORE_CASE)
            else     -> return warnings
        }

        tmpDir.walkTopDown()
            .filter { it.isFile && it.extension == "txt" }
            .forEach { file ->
                file.useLines { lines ->
                    lines.forEachIndexed { idx, rawLine ->
                        if (pattern.containsMatchIn(rawLine)) {
                            warnings += CompilerWarning(
                                language = language,
                                sourceFile = file.name,
                                line = idx + 1,
                                column = -1,
                                flag = if (language == "Kotlin") "w: (deprecated)" else "[deprecation]",
                                snippet = rawLine.trim().take(120),
                            )
                        }
                    }
                }
            }

        return warnings
    }
}
