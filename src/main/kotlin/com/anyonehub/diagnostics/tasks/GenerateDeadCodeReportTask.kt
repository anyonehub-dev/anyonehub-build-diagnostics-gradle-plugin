// Copyright 2024 anyone-Hub
// Phase 2 — Dead Code & Structural Diagnostics via R8 usage.txt.
//
// GUARDRAILS:
// ✅ READ-ONLY  — never forces isMinifyEnabled; only reads existing artifacts.
// ✅ LAZY       — mappingFile wired via AGP Artifacts API (Provider<RegularFile>).
// ✅ GRACEFUL   — emits [MINIFICATION DISABLED] when R8 is not active; never fails.

package com.anyonehub.diagnostics.tasks

import com.anyonehub.diagnostics.model.DeadCodeMetrics
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
import java.io.File

/**
 * Pipeline C — Parses the R8/ProGuard output artifacts to extract dead-code metrics.
 *
 * ## Input wiring (lazy, via AGP Artifacts API)
 * The [mappingFile] is wired in [com.anyonehub.diagnostics.ProjectHealthPlugin] using:
 * ```kotlin
 * variant.artifacts.get(SingleArtifact.OBFUSCATION_MAPPING_FILE)
 * ```
 * which resolves only at task execution time — never during configuration.
 *
 * ## R8 usage.txt format
 * The `usage.txt` file lives alongside `mapping.txt` and lists code removed by R8:
 * ```
 * com.example.UnusedClass          <- removed class (no trailing colon)
 * com.example.PartiallyUsed:
 *     void unusedMethod(int)       <- removed method
 *     String unusedField           <- removed field
 * ```
 *
 * ## Graceful skip (A2 ruling)
 * When [mappingFile] does not exist (minification disabled), the task writes the
 * [MINIFICATION_DISABLED] sentinel and exits with metrics all set to 0.
 */
@CacheableTask
abstract class GenerateDeadCodeReportTask : DefaultTask() {

    /**
     * R8 obfuscation mapping file (`mapping.txt`) provided by the AGP Artifacts API.
     * `@Optional` because minification may be disabled — the task handles absence gracefully.
     */
    @get:Optional
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mappingFile: RegularFileProperty

    /** Android variant this task targets (e.g., `release`, `debug`). */
    @get:Input
    @get:Optional
    abstract val variantName: Property<String>

    /** Intermediate output file consumed by the aggregation task. */
    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun execute() {
        val output = outputFile.get().asFile
        output.parentFile.mkdirs()

        val mapping = mappingFile.orNull?.asFile

        // ── Graceful skip: minification is disabled ───────────────────────────
        if (mapping == null || !mapping.exists()) {
            logger.lifecycle(
                "[ProjectHealth/DeadCode] R8 mapping file not found for variant " +
                        "'${variantName.orNull ?: "unknown"}'. " +
                        "Minification is likely disabled — skipping dead-code analysis."
            )
            output.writeText(buildString {
                appendLine(SECTION_HEADER)
                appendLine(MINIFICATION_DISABLED)
                appendLine(DeadCodeMetrics.R8_DISABLED.toIntermediateText())
                appendLine(SECTION_FOOTER)
            })
            return
        }

        logger.lifecycle("[ProjectHealth/DeadCode] Parsing R8 mapping: ${mapping.path}")

        // ── Locate usage.txt alongside mapping.txt ────────────────────────────
        // `usage.txt` is always emitted in the same directory as `mapping.txt` by R8.
        // This is the only safe, non-hardcoded derivation of its path.
        val usageFile = File(mapping.parentFile, "usage.txt")

        val metrics = if (usageFile.exists()) {
            parseUsageFile(usageFile)
        } else {
            // mapping.txt exists but usage.txt does not — R8 ran but removed nothing,
            // or the usage report was suppressed by a keep rule.
            logger.lifecycle(
                "[ProjectHealth/DeadCode] usage.txt not found at ${usageFile.path}. " +
                        "R8 may have kept all code via keep rules. Reporting zero removals."
            )
            DeadCodeMetrics(
                unusedClasses = 0,
                unusedMethods = 0,
                unusedFields = 0,
                unusedParameters = 0,
                r8Enabled = true,
            )
        }

        logger.lifecycle(
            "[ProjectHealth/DeadCode] Found ${metrics.totalUnused} unused code items " +
                    "(${metrics.unusedClasses} classes, ${metrics.unusedMethods} methods, " +
                    "${metrics.unusedFields} fields, ${metrics.unusedParameters} parameters)."
        )

        output.writeText(buildString {
            appendLine(SECTION_HEADER)
            append(metrics.toIntermediateText())
            appendLine(SECTION_FOOTER)
        })
    }

    // ── R8 usage.txt parser ───────────────────────────────────────────────────

    /**
     * Parses an R8/ProGuard-format `usage.txt` file into [DeadCodeMetrics].
     *
     * The format is:
     * ```
     * com.example.UnusedClass           <- top-level removed class (no colon)
     * com.example.PartiallyPruned:      <- class with removed members (has colon)
     *     void removedMethod(int, long) <- method signature (indented, has parentheses)
     *     android.view.View removedFld  <- field (indented, no parens)
     * # comment line                    <- ignored
     * ```
     */
    private fun parseUsageFile(usageFile: File): DeadCodeMetrics {
        var unusedClasses = 0
        var unusedMethods = 0
        var unusedFields = 0
        var unusedParameters = 0

        // Regex for detecting a method signature: indented, contains parentheses.
        val methodSignatureRegex = Regex("""^\s+.+\(.*\)\s*$""")
        // Regex for detecting a field: indented, no parentheses, has at least two tokens.
        val fieldSignatureRegex = Regex("""^\s+\S+\s+\S+\s*$""")
        // Regex for class entry: not indented, ends with optional colon, no parentheses.
        val classEntryRegex = Regex("""^[a-zA-Z${'$'}][\w.${'$'}]*:?\s*$""")

        usageFile.useLines { lines ->
            for (rawLine in lines) {
                val line = rawLine.trimEnd()
                when {
                    line.isBlank() || line.startsWith("#") -> {
                        // Skip comments and blank lines.
                    }
                    line.matches(classEntryRegex) -> {
                        // Top-level removed class or class header for removed members.
                        unusedClasses++
                    }
                    line.matches(methodSignatureRegex) -> {
                        unusedMethods++
                        // Count parameters: scan the parameter list between '(' and ')'.
                        val paramSection = line.substringAfter("(").substringBefore(")")
                        if (paramSection.isNotBlank()) {
                            unusedParameters += paramSection.split(",").count { it.isNotBlank() }
                        }
                    }
                    line.matches(fieldSignatureRegex) -> {
                        unusedFields++
                    }
                }
            }
        }

        return DeadCodeMetrics(
            unusedClasses = unusedClasses,
            unusedMethods = unusedMethods,
            unusedFields = unusedFields,
            unusedParameters = unusedParameters,
            r8Enabled = true,
        )
    }

    companion object {
        private const val SECTION_HEADER = "=== DEAD_CODE_SECTION_BEGIN ==="
        private const val SECTION_FOOTER = "=== DEAD_CODE_SECTION_END ==="
        internal const val MINIFICATION_DISABLED =
            "STATUS=MINIFICATION_DISABLED"
    }
}
