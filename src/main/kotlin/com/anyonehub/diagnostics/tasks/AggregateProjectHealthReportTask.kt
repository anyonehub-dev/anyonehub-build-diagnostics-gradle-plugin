// Copyright 2024 anyone-Hub
// Phase 5 — Aggregation: combines all pipeline intermediates into one Markdown report.
//
// GUARDRAILS:
// ✅ READ-ONLY — only reads the three intermediate files; produces one output file.
// ✅ LAZY      — all inputs are @InputFile; no eager resolution.
// ✅ CACHEABLE — marked @CacheableTask so Gradle's build cache can restore the report.

package com.anyonehub.diagnostics.tasks

import com.anyonehub.diagnostics.model.CompilerWarning
import com.anyonehub.diagnostics.model.DeadCodeMetrics
import com.anyonehub.diagnostics.model.DependencyStatus
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Aggregation task that reads all three pipeline intermediate files and renders
 * the final `build/reports/project-health.html` HTML report.
 *
 * Declared task dependencies (`dependsOn(deadCodeTask, compilerTask, dependencyTask)`)
 * are set in [com.anyonehub.diagnostics.ProjectHealthPlugin]; this task only declares
 * `@InputFile` for incremental correctness.
 *
 * The `@CacheableTask` annotation allows Gradle to restore the report from the build
 * cache when none of the intermediate inputs have changed.
 */
@CacheableTask
abstract class AggregateProjectHealthReportTask : DefaultTask() {

    /**
     * Intermediate dead-code metrics from [GenerateDeadCodeReportTask].
     *
     * DEFECT-5 fix: was `@Internal` which made Gradle consider this task always
     * up-to-date regardless of upstream changes. Now `@InputFile` so the cache
     * key is correctly computed from all three intermediate pipeline outputs.
     */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    @get:Optional
    abstract val deadCodeIntermediateFile: RegularFileProperty

    /** Intermediate compiler warnings from [CompilerDiagnosticsTask]. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    @get:Optional
    abstract val compilerIntermediateFile: RegularFileProperty

    /** Intermediate dependency statuses from [DependencyDiagnosticsTask]. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    @get:Optional
    abstract val dependencyIntermediateFile: RegularFileProperty

    /** The final Markdown report output. */
    @get:OutputFile
    abstract val reportOutputFile: RegularFileProperty

    /** Human-readable module name (e.g., `anyones-Terminal`). */
    @get:Input
    abstract val projectName: Property<String>

    /** Gradle project path (e.g., `:anyones-Terminal`). */
    @get:Input
    abstract val projectPath: Property<String>

    /** Android variant name (e.g., `release`, `debug`). */
    @get:Input
    @get:Optional
    abstract val variantName: Property<String>

    @TaskAction
    fun execute() {
        val reportFile = reportOutputFile.get().asFile
        reportFile.parentFile.mkdirs()

        // ── Parse intermediate inputs ─────────────────────────────────────────
        val deadCodeMetrics = readDeadCodeMetrics()
        val compilerWarnings = readCompilerWarnings()
        val dependencyStatuses = readDependencyStatuses()

        // ── Group compiler warnings by category ───────────────────────────────
        val cppWarnings         = compilerWarnings.filter { it.category == CompilerWarning.CAT_CPP }
        val deprecations        = compilerWarnings.filter { it.category == CompilerWarning.CAT_DEPRECATIONS }
        val kotlin24Redundancies = compilerWarnings.filter { it.category == CompilerWarning.CAT_KOTLIN_REDUNDANCIES }
        val antlrUnsafe         = compilerWarnings.filter { it.category == CompilerWarning.CAT_ANTLR_UNSAFE }
        val javaCompilerWarnings = compilerWarnings.filter { it.category == CompilerWarning.CAT_JAVA_COMPILER }
        // Legacy language-split (kept for the summary table)
        val kotlinWarnings = compilerWarnings.filter { it.language == "Kotlin" }
        val javaWarnings   = compilerWarnings.filter { it.language == "Java" }
        val outdated = dependencyStatuses.filter { it.isOutdated }
        val unused = dependencyStatuses.filter { it.isUnused }

        val timestamp = ZonedDateTime.now().format(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z")
        )
        val variant = variantName.orNull ?: "unknown"
        val module = projectPath.get()

        logger.lifecycle(
            "[ProjectHealth] Generating report → ${reportFile.path}\n" +
                    "  C++ warnings    : ${cppWarnings.size}\n" +
                    "  Kotlin warnings : ${kotlinWarnings.size}\n" +
                    "  Java warnings   : ${javaWarnings.size}\n" +
                    "  Dead code items : ${deadCodeMetrics.totalUnused}\n" +
                    "  Outdated deps   : ${outdated.size}\n" +
                    "  Unused deps     : ${unused.size}"
        )

        // ── Render HTML ───────────────────────────────────────────────────
        reportFile.bufferedWriter().use { it.write(renderHtml(
            timestamp = timestamp,
            variant = variant,
            module = module,
            cppWarnings = cppWarnings,
            deprecations = deprecations,
            kotlin24Redundancies = kotlin24Redundancies,
            antlrUnsafe = antlrUnsafe,
            javaCompilerWarnings = javaCompilerWarnings,
            kotlinWarnings = kotlinWarnings,
            javaWarnings = javaWarnings,
            deadCodeMetrics = deadCodeMetrics,
            outdated = outdated,
            unused = unused,
            allDependencies = dependencyStatuses,
        )) }

        logger.lifecycle("[ProjectHealth] ✅ Report written → ${reportFile.absolutePath}")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Intermediate file readers
    // ─────────────────────────────────────────────────────────────────────────

    private fun readDeadCodeMetrics(): DeadCodeMetrics {
        val file = deadCodeIntermediateFile.orNull?.asFile ?: return DeadCodeMetrics.R8_DISABLED
        if (!file.exists()) return DeadCodeMetrics.R8_DISABLED
        return try {
            DeadCodeMetrics.fromIntermediateText(file.readText())
        } catch (_: Exception) {
            DeadCodeMetrics.R8_DISABLED
        }
    }

    private fun readCompilerWarnings(): List<CompilerWarning> {
        val file = compilerIntermediateFile.orNull?.asFile ?: return emptyList()
        if (!file.exists()) return emptyList()
        return file.readLines()
            .filter { it.isNotBlank() && !it.startsWith("===") && !it.startsWith("#") }
            .mapNotNull { CompilerWarning.fromIntermediateLine(it) }
    }

    private fun readDependencyStatuses(): List<DependencyStatus> {
        val file = dependencyIntermediateFile.orNull?.asFile ?: return emptyList()
        if (!file.exists()) return emptyList()
        return file.readLines()
            .filter { it.isNotBlank() && !it.startsWith("===") }
            .mapNotNull { DependencyStatus.fromIntermediateLine(it) }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HTML renderer
    // ─────────────────────────────────────────────────────────────────────────

    @Suppress("LongParameterList")
    private fun renderHtml(
        timestamp: String,
        variant: String,
        module: String,
        cppWarnings: List<CompilerWarning>,
        deprecations: List<CompilerWarning>,
        kotlin24Redundancies: List<CompilerWarning>,
        antlrUnsafe: List<CompilerWarning>,
        javaCompilerWarnings: List<CompilerWarning>,
        kotlinWarnings: List<CompilerWarning>,
        javaWarnings: List<CompilerWarning>,
        deadCodeMetrics: DeadCodeMetrics,
        outdated: List<DependencyStatus>,
        unused: List<DependencyStatus>,
        allDependencies: List<DependencyStatus>,
    ): String = buildString {

        appendLine("<!DOCTYPE html>")
        appendLine("<html lang=\"en\">")
        appendLine("<head>")
        appendLine("    <meta charset=\"UTF-8\">")
        appendLine("    <meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">")
        appendLine("    <title>Project Health Report — ${projectName.get()}</title>")
        appendLine("    <style>")
        appendLine("        :root {")
        appendLine("            --bg-color: #0d1117;")
        appendLine("            --text-color: #c9d1d9;")
        appendLine("            --heading-color: #ffffff;")
        appendLine("            --border-color: #30363d;")
        appendLine("            --table-header-bg: #161b22;")
        appendLine("            --table-row-even: #0d1117;")
        appendLine("            --table-row-odd: #161b22;")
        appendLine("            --accent-color: #58a6ff;")
        appendLine("            --success-color: #2ea043;")
        appendLine("            --warning-color: #d29922;")
        appendLine("            --error-color: #f85149;")
        appendLine("            --code-bg: #161b22;")
        appendLine("            --card-bg: #1c2128;")
        appendLine("            --details-bg: #21262d;")
        appendLine("        }")
        appendLine("        body {")
        appendLine("            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif;")
        appendLine("            background-color: var(--bg-color);")
        appendLine("            color: var(--text-color);")
        appendLine("            line-height: 1.6;")
        appendLine("            padding: 2rem;")
        appendLine("            max-width: 1200px;")
        appendLine("            margin: 0 auto;")
        appendLine("        }")
        appendLine("        h1, h2, h3 { color: var(--heading-color); }")
        appendLine("        h1 { border-bottom: 2px solid var(--border-color); padding-bottom: 10px; }")
        appendLine("        h2 { margin-top: 2.5rem; border-bottom: 1px solid var(--border-color); padding-bottom: 8px; }")
        appendLine("        .meta-card {")
        appendLine("            background: var(--card-bg); border: 1px solid var(--border-color);")
        appendLine("            border-radius: 6px; padding: 1.5rem; margin-bottom: 2rem;")
        appendLine("        }")
        appendLine("        .meta-card p { margin: 0.5rem 0; font-weight: 500; }")
        appendLine("        .meta-card span { color: var(--accent-color); font-weight: bold; }")
        appendLine("        table {")
        appendLine("            width: 100%; border-collapse: collapse; margin-top: 1rem; margin-bottom: 1.5rem;")
        appendLine("            background-color: var(--bg-color);")
        appendLine("        }")
        appendLine("        th, td { border: 1px solid var(--border-color); padding: 12px 16px; text-align: left; }")
        appendLine("        th { background-color: var(--table-header-bg); font-weight: 600; color: var(--heading-color); }")
        appendLine("        tr:nth-child(even) { background-color: var(--table-row-even); }")
        appendLine("        tr:nth-child(odd) { background-color: var(--table-row-odd); }")
        appendLine("        code {")
        appendLine("            font-family: ui-monospace, SFMono-Regular, SF Mono, Menlo, Consolas, Liberation Mono, monospace;")
        appendLine("            background-color: var(--code-bg); padding: 0.2em 0.4em;")
        appendLine("            border-radius: 6px; font-size: 85%;")
        appendLine("        }")
        appendLine("        .badge {")
        appendLine("            padding: 4px 8px; border-radius: 12px; font-size: 0.85em; font-weight: 600; display: inline-block;")
        appendLine("        }")
        appendLine("        .badge.success { background-color: rgba(46,160,67,0.2); color: var(--success-color); border: 1px solid var(--success-color); }")
        appendLine("        .badge.warning { background-color: rgba(210,153,34,0.2); color: var(--warning-color); border: 1px solid var(--warning-color); }")
        appendLine("        .badge.error { background-color: rgba(248,81,73,0.2); color: var(--error-color); border: 1px solid var(--error-color); }")
        appendLine("        .badge.info { background-color: rgba(88,166,255,0.2); color: var(--accent-color); border: 1px solid var(--accent-color); }")
        appendLine("        details {")
        appendLine("            background: var(--details-bg); border: 1px solid var(--border-color);")
        appendLine("            border-radius: 6px; margin: 1rem 0;")
        appendLine("        }")
        appendLine("        summary {")
        appendLine("            padding: 1rem; font-weight: 600; cursor: pointer; color: var(--heading-color);")
        appendLine("        }")
        appendLine("        summary:hover { background: var(--table-header-bg); }")
        appendLine("        .details-content { padding: 0 1rem 1rem 1rem; overflow-x: auto; }")
        appendLine("        .alert {")
        appendLine("            padding: 1rem; border-radius: 6px; border-left: 4px solid var(--accent-color);")
        appendLine("            background: var(--card-bg); margin: 1rem 0;")
        appendLine("        }")
        appendLine("        footer { margin-top: 3rem; text-align: center; color: #8b949e; font-size: 0.9rem; border-top: 1px solid var(--border-color); padding-top: 1rem; }")
        appendLine("    </style>")
        appendLine("</head>")
        appendLine("<body>")
        
        // Header
        appendLine("<h1>🏥 Project Health Report — ${projectName.get()}</h1>")
        appendLine("<div class=\"meta-card\">")
        appendLine("    <p>Generated: <span>$timestamp</span></p>")
        appendLine("    <p>Module: <code>$module</code></p>")
        appendLine("    <p>Variant: <code>$variant</code></p>")
        appendLine("    <p>Plugin: <code>com.anyonehub.diagnostics.health v1.1.2</code></p>")
        appendLine("</div>")
        
        // Executive Summary
        appendLine("<h2>📊 Executive Summary</h2>")
        appendLine("<table>")
        appendLine("    <thead><tr><th>Category</th><th>Count</th><th>Severity</th></tr></thead>")
        appendLine("    <tbody>")
        appendLine("        <tr><td>🔧 C++ Compiler Warnings</td><td>${cppWarnings.size}</td><td>${severityBadge(cppWarnings.size)}</td></tr>")
        appendLine("        <tr><td>⚠️ Deprecations (Kotlin/Java)</td><td>${deprecations.size}</td><td>${severityBadge(deprecations.size)}</td></tr>")
        appendLine("        <tr><td>🔁 Kotlin 2.4 Redundancies</td><td>${kotlin24Redundancies.size}</td><td>${severityBadge(kotlin24Redundancies.size)}</td></tr>")
        appendLine("        <tr><td>🦠 ANTLR Unsafe Calls</td><td>${antlrUnsafe.size}</td><td>${severityBadge(antlrUnsafe.size)}</td></tr>")
        appendLine("        <tr><td>☕ Java Compiler Warnings</td><td>${javaCompilerWarnings.size}</td><td>${severityBadge(javaCompilerWarnings.size)}</td></tr>")
        appendLine("        <tr><td>💀 Dead Code Items (R8)</td><td>${deadCodeMetrics.totalUnused}</td><td>${severityBadge(deadCodeMetrics.totalUnused)}</td></tr>")
        appendLine("        <tr><td>📦 Outdated Dependencies</td><td>${outdated.size}</td><td>${severityBadge(outdated.size)}</td></tr>")
        appendLine("        <tr><td>🗑️ Potentially Unused Deps</td><td>${unused.size}</td><td>${severityBadge(unused.size)}</td></tr>")
        appendLine("    </tbody>")
        appendLine("</table>")

        // C++ Compiler Warnings
        appendLine("<h2>🔧 C++ Compiler Warnings</h2>")
        if (cppWarnings.isEmpty()) {
            appendLine("<div class=\"alert\">✅ <strong>No C++ compiler warnings detected.</strong><br/><small>The <code>.cxx/</code> build tree was scanned for Clang/GCC diagnostic events.</small></div>")
        } else {
            appendLine("<details><summary>View ${cppWarnings.size} C++ Warnings</summary><div class=\"details-content\">")
            appendLine("<table>")
            appendLine("    <thead><tr><th>Source File</th><th>Line</th><th>Col</th><th>Flag</th><th>Snippet</th></tr></thead>")
            appendLine("    <tbody>")
            cppWarnings.sortedBy { it.sourceFile }.forEach { w ->
                val file = w.sourceFile.substringAfterLast('/')
                val line = if (w.line > 0) "<code>${w.line}</code>" else "—"
                val col  = if (w.column > 0) "<code>${w.column}</code>" else "—"
                appendLine("        <tr><td><code>$file</code></td><td>$line</td><td>$col</td><td><span class=\"badge info\">${w.flag}</span></td><td>${escapeHtml(w.snippet)}</td></tr>")
            }
            appendLine("    </tbody></table></div></details>")
        }

        // ── Section: Deprecations ─────────────────────────────────────────────
        appendLine("<h2>⚠️ Deprecations</h2>")
        if (deprecations.isEmpty()) {
            appendLine("<div class=\"alert\">✅ <strong>No deprecation warnings detected.</strong></div>")
        } else {
            appendLine("<details open><summary>View ${deprecations.size} Deprecation Warnings</summary><div class=\"details-content\">")
            appendLine("<table>")
            appendLine("    <thead><tr><th>Lang</th><th>Source File</th><th>Line</th><th>API / Snippet</th></tr></thead>")
            appendLine("    <tbody>")
            deprecations.sortedBy { it.sourceFile }.forEach { w ->
                val file = w.sourceFile.substringAfterLast('/')
                val line = if (w.line > 0) "<code>${w.line}</code>" else "—"
                appendLine("        <tr><td><span class=\"badge info\">${w.language}</span></td><td><code>$file</code></td><td>$line</td><td>${escapeHtml(w.snippet)}</td></tr>")
            }
            appendLine("    </tbody></table></div></details>")
        }

        // ── Section: Kotlin 2.4 Redundancies ─────────────────────────────────
        appendLine("<h2>🔁 Kotlin 2.4 Redundancies</h2>")
        if (kotlin24Redundancies.isEmpty()) {
            appendLine("<div class=\"alert\">✅ <strong>No redundant Kotlin 2.4 compiler arguments detected.</strong></div>")
        } else {
            appendLine("<p><small>These compiler arguments are no longer needed for Kotlin 2.4 and can be safely removed from your <code>build.gradle.kts</code>.</small></p>")
            appendLine("<details open><summary>View ${kotlin24Redundancies.size} Redundant Arguments</summary><div class=\"details-content\">")
            appendLine("<table>")
            appendLine("    <thead><tr><th>Argument</th></tr></thead>")
            appendLine("    <tbody>")
            kotlin24Redundancies.distinctBy { it.snippet }.forEach { w ->
                appendLine("        <tr><td><code>${escapeHtml(w.snippet)}</code></td></tr>")
            }
            appendLine("    </tbody></table></div></details>")
        }

        // ── Section: ANTLR Unsafe Calls ───────────────────────────────────────
        appendLine("<h2>🦠 ANTLR Unsafe Call Suppressions</h2>")
        if (antlrUnsafe.isEmpty()) {
            appendLine("<div class=\"alert\">✅ <strong>No ANTLR UNSAFE_CALL suppressions detected.</strong></div>")
        } else {
            appendLine("<p><small>These suppressions indicate compiler behavior is <strong>UNSPECIFIED</strong>. Review each ANTLR grammar file to resolve the underlying null-safety issue.</small></p>")
            appendLine("<details open><summary>View ${antlrUnsafe.size} UNSAFE_CALL Suppressions</summary><div class=\"details-content\">")
            appendLine("<table>")
            appendLine("    <thead><tr><th>Source File</th><th>Snippet</th></tr></thead>")
            appendLine("    <tbody>")
            antlrUnsafe.forEach { w ->
                val file = w.sourceFile.substringAfterLast('/')
                appendLine("        <tr><td><code>$file</code></td><td>${escapeHtml(w.snippet)}</td></tr>")
            }
            appendLine("    </tbody></table></div></details>")
        }

        // ── Section: Java Compiler Warnings ───────────────────────────────────
        appendLine("<h2>☕ Java Compiler Warnings</h2>")
        if (javaCompilerWarnings.isEmpty()) {
            appendLine("<div class=\"alert\">✅ <strong>No Java compiler warnings detected.</strong></div>")
        } else {
            appendLine("<details open><summary>View ${javaCompilerWarnings.size} Java Compiler Warnings</summary><div class=\"details-content\">")
            appendLine("<table>")
            appendLine("    <thead><tr><th>Flag</th><th>Source File</th><th>Line</th><th>Snippet</th></tr></thead>")
            appendLine("    <tbody>")
            javaCompilerWarnings.sortedBy { it.sourceFile }.forEach { w ->
                val file = w.sourceFile.substringAfterLast('/')
                val line = if (w.line > 0) "<code>${w.line}</code>" else "—"
                appendLine("        <tr><td><span class=\"badge warning\">${w.flag}</span></td><td><code>$file</code></td><td>$line</td><td>${escapeHtml(w.snippet)}</td></tr>")
            }
            appendLine("    </tbody></table></div></details>")
        }

        // Dead Code (R8)
        appendLine("<h2>💀 Dead Code Analysis (R8)</h2>")
        if (!deadCodeMetrics.r8Enabled) {
            appendLine("<div class=\"alert\" style=\"border-left-color: var(--warning-color);\">⚠️ <strong>[MINIFICATION DISABLED]</strong> R8 was not active for this variant.<br/><small>Dead-code analysis requires <code>isMinifyEnabled = true</code> in the build config. This plugin does <strong>not</strong> enable R8 automatically.</small></div>")
        } else if (deadCodeMetrics.totalUnused == 0) {
            appendLine("<div class=\"alert\">✅ <strong>R8 removed no code</strong> (all declared code is reachable, or keep rules cover everything).</div>")
        } else {
            appendLine("<table>")
            appendLine("    <thead><tr><th>Metric</th><th>Count</th></tr></thead>")
            appendLine("    <tbody>")
            appendLine("        <tr><td>Unused Classes</td><td>${deadCodeMetrics.unusedClasses}</td></tr>")
            appendLine("        <tr><td>Unused Methods</td><td>${deadCodeMetrics.unusedMethods}</td></tr>")
            appendLine("        <tr><td>Unused Fields</td><td>${deadCodeMetrics.unusedFields}</td></tr>")
            appendLine("        <tr><td>Unused Parameters</td><td>${deadCodeMetrics.unusedParameters}</td></tr>")
            appendLine("        <tr style=\"font-weight: 600;\"><td>Total Removed Items</td><td>${deadCodeMetrics.totalUnused}</td></tr>")
            appendLine("    </tbody>")
            appendLine("</table>")
            appendLine("<p><small>Source: <code>usage.txt</code> in the R8 output directory. Review ProGuard keep rules to reduce this count.</small></p>")
        }

        // Dependencies
        appendLine("<h2>📦 Dependency Status</h2>")
        
        appendLine("<h3>⚠️ Outdated Dependencies</h3>")
        if (outdated.isEmpty()) {
            appendLine("<div class=\"alert\">✅ <strong>All checked dependencies are up-to-date.</strong></div>")
        } else {
            appendLine("<details><summary>View ${outdated.size} Outdated Dependencies</summary><div class=\"details-content\">")
            appendLine("<table>")
            appendLine("    <thead><tr><th>Dependency</th><th>Current</th><th>Latest</th><th>Gap</th></tr></thead>")
            appendLine("    <tbody>")
            outdated.sortedBy { it.groupId }.forEach { dep ->
                val gap = versionGapLabelHtml(dep.currentVersion, dep.latestVersion)
                appendLine("        <tr><td><code>${dep.groupId}:${dep.artifactId}</code></td><td><code>${dep.currentVersion}</code></td><td><code>${dep.latestVersion ?: "unknown"}</code></td><td>$gap</td></tr>")
            }
            appendLine("    </tbody></table></div></details>")
        }

        appendLine("<h3>🗑️ Potentially Unused Dependencies</h3>")
        if (unused.isEmpty()) {
            appendLine("<div class=\"alert\">✅ <strong>No obviously unused dependencies detected.</strong></div>")
        } else {
            appendLine("<p><small>A dependency is flagged when none of its exported packages appear in the project's compiled bytecode. Runtime-only (DI, SPI) deps may appear here as false positives — verify before removing.</small></p>")
            appendLine("<details><summary>View ${unused.size} Unused Dependencies</summary><div class=\"details-content\">")
            appendLine("<table>")
            appendLine("    <thead><tr><th>Dependency</th><th>Version</th><th>Reason</th></tr></thead>")
            appendLine("    <tbody>")
            unused.sortedBy { it.groupId }.forEach { dep ->
                appendLine("        <tr><td><code>${dep.groupId}:${dep.artifactId}</code></td><td><code>${dep.currentVersion}</code></td><td>${dep.unusedReason}</td></tr>")
            }
            appendLine("    </tbody></table></div></details>")
        }

        val networkErrors = allDependencies.filter { it.networkError != null }
        if (networkErrors.isNotEmpty()) {
            appendLine("<h3>❌ Version-Check Errors</h3>")
            appendLine("<table>")
            appendLine("    <thead><tr><th>Dependency</th><th>Error</th></tr></thead>")
            appendLine("    <tbody>")
            networkErrors.forEach { dep ->
                appendLine("        <tr><td><code>${dep.groupId}:${dep.artifactId}</code></td><td>${dep.networkError}</td></tr>")
            }
            appendLine("    </tbody></table>")
        }

        // Footer
        appendLine("<footer>")
        appendLine("    Generated by the <code>ProjectHealthPlugin</code> — a headless, read-only Gradle diagnostic plugin.<br/>")
        appendLine("    This report does not modify build configuration and is safe to run on any build.")
        appendLine("</footer>")
        appendLine("</body>")
        appendLine("</html>")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HTML utilities
    // ─────────────────────────────────────────────────────────────────────────

    /** Returns an HTML span badge based on a warning/issue count. */
    private fun severityBadge(count: Int): String = when {
        count == 0  -> "<span class=\"badge success\">✅ None</span>"
        count <= 5  -> "<span class=\"badge warning\">🟡 Low ($count)</span>"
        count <= 20 -> "<span class=\"badge warning\">🟠 Medium ($count)</span>"
        else        -> "<span class=\"badge error\">🔴 High ($count)</span>"
    }

    /** Provides a human-readable HTML label for the version gap. */
    private fun versionGapLabelHtml(current: String, latest: String?): String {
        if (latest == null) return "<span class=\"badge\">Unknown</span>"
        val cur = current.split(".").mapNotNull { it.toIntOrNull() }
        val lat = latest.split(".").mapNotNull { it.toIntOrNull() }
        return when {
            lat.getOrElse(0) { 0 } > cur.getOrElse(0) { 0 } -> "<span class=\"badge error\">🔴 Major</span>"
            lat.getOrElse(1) { 0 } > cur.getOrElse(1) { 0 } -> "<span class=\"badge warning\">🟠 Minor</span>"
            lat.getOrElse(2) { 0 } > cur.getOrElse(2) { 0 } -> "<span class=\"badge warning\">🟡 Patch</span>"
            else -> "<span class=\"badge success\">✅ Current</span>"
        }
    }

    /** Escapes HTML special characters. */
    private fun escapeHtml(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
