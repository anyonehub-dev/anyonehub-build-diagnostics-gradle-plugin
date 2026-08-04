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
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * Aggregation task that reads all three pipeline intermediate files and renders
 * the final `build/reports/project-health.md` Markdown report.
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

    /** Intermediate dead-code metrics from [GenerateDeadCodeReportTask]. */
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

        // ── Compute summary statistics ────────────────────────────────────────
        val cppWarnings = compilerWarnings.filter { it.language == "C++" }
        val kotlinWarnings = compilerWarnings.filter { it.language == "Kotlin" }
        val javaWarnings = compilerWarnings.filter { it.language == "Java" }
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

        // ── Render Markdown ───────────────────────────────────────────────────
        reportFile.bufferedWriter().use { it.write(renderMarkdown(
            timestamp = timestamp,
            variant = variant,
            module = module,
            cppWarnings = cppWarnings,
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
    // Markdown renderer
    // ─────────────────────────────────────────────────────────────────────────

    @Suppress("LongParameterList")
    private fun renderMarkdown(
        timestamp: String,
        variant: String,
        module: String,
        cppWarnings: List<CompilerWarning>,
        kotlinWarnings: List<CompilerWarning>,
        javaWarnings: List<CompilerWarning>,
        deadCodeMetrics: DeadCodeMetrics,
        outdated: List<DependencyStatus>,
        unused: List<DependencyStatus>,
        allDependencies: List<DependencyStatus>,
    ): String = buildString {

        // ── Header ────────────────────────────────────────────────────────────
        appendLine("# 🏥 Project Health Report — ${projectName.get()}")
        appendLine()
        appendLine("> **Generated:** $timestamp")
        appendLine("> **Module:** `$module`")
        appendLine("> **Variant:** `$variant`")
        appendLine("> **Plugin:** `com.anyonehub.diagnostics.health` v1.0.0")
        appendLine()

        // ── Executive Summary ─────────────────────────────────────────────────
        appendLine("---")
        appendLine()
        appendLine("## 📊 Executive Summary")
        appendLine()
        appendLine("| Category | Count | Severity |")
        appendLine("|----------|-------|----------|")
        appendLine("| 🔧 C++ Compiler Warnings | ${cppWarnings.size} | ${severityBadge(cppWarnings.size)} |")
        appendLine("| ☕ Kotlin Deprecations | ${kotlinWarnings.size} | ${severityBadge(kotlinWarnings.size)} |")
        appendLine("| ☕ Java Deprecations | ${javaWarnings.size} | ${severityBadge(javaWarnings.size)} |")
        appendLine("| 💀 Dead Code Items (R8) | ${deadCodeMetrics.totalUnused} | ${severityBadge(deadCodeMetrics.totalUnused)} |")
        appendLine("| 📦 Outdated Dependencies | ${outdated.size} | ${severityBadge(outdated.size)} |")
        appendLine("| 🗑️ Potentially Unused Deps | ${unused.size} | ${severityBadge(unused.size)} |")
        appendLine()

        // ── Section 1: C++ Compiler Warnings ─────────────────────────────────
        appendLine("---")
        appendLine()
        appendLine("## 🔧 C++ Compiler Warnings")
        appendLine()
        if (cppWarnings.isEmpty()) {
            appendLine("✅ **No C++ compiler warnings detected.**")
            appendLine()
            appendLine("> *The `.cxx/` build tree was scanned for Clang/GCC diagnostic events.*")
        } else {
            appendLine("| Source File | Line | Col | Flag | Snippet |")
            appendLine("|-------------|------|-----|------|---------|")
            cppWarnings.sortedBy { it.sourceFile }.forEach { w ->
                val file = w.sourceFile.substringAfterLast('/')
                val line = if (w.line > 0) "`${w.line}`" else "—"
                val col  = if (w.column > 0) "`${w.column}`" else "—"
                appendLine("| `$file` | $line | $col | `${w.flag}` | ${escapeMarkdown(w.snippet)} |")
            }
        }
        appendLine()

        // ── Section 2: Kotlin Deprecations ───────────────────────────────────
        appendLine("---")
        appendLine()
        appendLine("## ☕ Kotlin Deprecation Warnings")
        appendLine()
        if (kotlinWarnings.isEmpty()) {
            appendLine("✅ **No Kotlin deprecation warnings found in build reports.**")
            appendLine()
            appendLine("> *To enable persistent Kotlin Build Reports, add to `gradle.properties`:*")
            appendLine("> ```")
            appendLine("> kotlin.build.report.output=file")
            appendLine("> kotlin.build.report.file.output.dir=build/reports/kotlin-build")
            appendLine("> ```")
        } else {
            appendLine("| Source File | Line | Col | API | Suggestion |")
            appendLine("|-------------|------|-----|-----|------------|")
            kotlinWarnings.sortedBy { it.sourceFile }.forEach { w ->
                val file = w.sourceFile.substringAfterLast('/')
                val line = if (w.line > 0) "`${w.line}`" else "—"
                val col  = if (w.column > 0) "`${w.column}`" else "—"
                appendLine("| `$file` | $line | $col | `${w.flag}` | ${escapeMarkdown(w.snippet)} |")
            }
        }
        appendLine()

        // ── Section 3: Java Deprecations ──────────────────────────────────────
        appendLine("---")
        appendLine()
        appendLine("## ☕ Java Deprecation Warnings")
        appendLine()
        if (javaWarnings.isEmpty()) {
            appendLine("✅ **No Java deprecation warnings found.**")
        } else {
            appendLine("| Source File | Line | Flag | Snippet |")
            appendLine("|-------------|------|------|---------|")
            javaWarnings.sortedBy { it.sourceFile }.forEach { w ->
                val file = w.sourceFile.substringAfterLast('/')
                val line = if (w.line > 0) "`${w.line}`" else "—"
                appendLine("| `$file` | $line | `${w.flag}` | ${escapeMarkdown(w.snippet)} |")
            }
        }
        appendLine()

        // ── Section 4: Dead Code (R8 Analysis) ───────────────────────────────
        appendLine("---")
        appendLine()
        appendLine("## 💀 Dead Code Analysis (R8)")
        appendLine()
        if (!deadCodeMetrics.r8Enabled) {
            appendLine("> ⚠️ **[MINIFICATION DISABLED]** R8 was not active for this variant.")
            appendLine("> Dead-code analysis requires `isMinifyEnabled = true` in the build config.")
            appendLine("> This plugin does **not** enable R8 automatically.")
        } else if (deadCodeMetrics.totalUnused == 0) {
            appendLine("✅ **R8 removed no code** (all declared code is reachable, or keep rules cover everything).")
        } else {
            appendLine("| Metric | Count |")
            appendLine("|--------|-------|")
            appendLine("| Unused Classes | ${deadCodeMetrics.unusedClasses} |")
            appendLine("| Unused Methods | ${deadCodeMetrics.unusedMethods} |")
            appendLine("| Unused Fields | ${deadCodeMetrics.unusedFields} |")
            appendLine("| Unused Parameters | ${deadCodeMetrics.unusedParameters} |")
            appendLine("| **Total Removed Items** | **${deadCodeMetrics.totalUnused}** |")
            appendLine()
            appendLine("> *Source: `usage.txt` in the R8 output directory (same parent as `mapping.txt`).*")
            appendLine("> *Review ProGuard keep rules to reduce this count.*")
        }
        appendLine()

        // ── Section 5: Dependency Status ──────────────────────────────────────
        appendLine("---")
        appendLine()
        appendLine("## 📦 Dependency Status")
        appendLine()

        // 5a: Outdated
        appendLine("### ⚠️ Outdated Dependencies")
        appendLine()
        if (outdated.isEmpty()) {
            appendLine("✅ **All checked dependencies are up-to-date.**")
        } else {
            appendLine("| Dependency | Current | Latest | Gap |")
            appendLine("|------------|---------|--------|-----|")
            outdated.sortedBy { it.groupId }.forEach { dep ->
                val gap = versionGapLabel(dep.currentVersion, dep.latestVersion)
                appendLine("| `${dep.groupId}:${dep.artifactId}` | `${dep.currentVersion}` | `${dep.latestVersion ?: "unknown"}` | $gap |")
            }
        }
        appendLine()

        // 5b: Unused
        appendLine("### 🗑️ Potentially Unused Dependencies")
        appendLine()
        appendLine("> *A dependency is flagged when none of its exported packages appear in*")
        appendLine("> *the project's compiled bytecode. Runtime-only (DI, SPI) deps may appear*")
        appendLine("> *here as false positives — verify before removing.*")
        appendLine()
        if (unused.isEmpty()) {
            appendLine("✅ **No obviously unused dependencies detected.**")
        } else {
            appendLine("| Dependency | Version | Reason |")
            appendLine("|------------|---------|--------|")
            unused.sortedBy { it.groupId }.forEach { dep ->
                appendLine("| `${dep.groupId}:${dep.artifactId}` | `${dep.currentVersion}` | ${dep.unusedReason} |")
            }
        }
        appendLine()

        // 5c: Network errors (if any)
        val networkErrors = allDependencies.filter { it.networkError != null }
        if (networkErrors.isNotEmpty()) {
            appendLine("### ❌ Version-Check Errors")
            appendLine()
            appendLine("| Dependency | Error |")
            appendLine("|------------|-------|")
            networkErrors.forEach { dep ->
                appendLine("| `${dep.groupId}:${dep.artifactId}` | ${dep.networkError} |")
            }
            appendLine()
        }

        // ── Footer ────────────────────────────────────────────────────────────
        appendLine("---")
        appendLine()
        appendLine("*Generated by the `ProjectHealthPlugin` — a headless, read-only Gradle diagnostic plugin.*")
        appendLine("*This report does not modify build configuration and is safe to run on any build.*")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Markdown utilities
    // ─────────────────────────────────────────────────────────────────────────

    /** Returns a severity badge string based on a warning/issue count. */
    private fun severityBadge(count: Int): String = when {
        count == 0  -> "✅ None"
        count <= 5  -> "🟡 Low ($count)"
        count <= 20 -> "🟠 Medium ($count)"
        else        -> "🔴 High ($count)"
    }

    /**
     * Provides a human-readable label for the version gap between [current] and [latest].
     * Uses semver major/minor/patch classification.
     */
    private fun versionGapLabel(current: String, latest: String?): String {
        if (latest == null) return "Unknown"
        val cur = current.split(".").mapNotNull { it.toIntOrNull() }
        val lat = latest.split(".").mapNotNull { it.toIntOrNull() }
        return when {
            lat.getOrElse(0) { 0 } > cur.getOrElse(0) { 0 } -> "🔴 Major"
            lat.getOrElse(1) { 0 } > cur.getOrElse(1) { 0 } -> "🟠 Minor"
            lat.getOrElse(2) { 0 } > cur.getOrElse(2) { 0 } -> "🟡 Patch"
            else -> "✅ Current"
        }
    }

    /** Escapes Markdown table cell content (pipes and backticks). */
    private fun escapeMarkdown(text: String): String =
        text.replace("|", "\\|").replace("`", "'")
}
