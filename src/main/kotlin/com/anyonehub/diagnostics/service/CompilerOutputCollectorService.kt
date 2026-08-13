// Copyright 2024 anyone-Hub
// BuildService — live compiler output interception for deprecation warning collection.
//
// GUARDRAILS:
// ✅ THREAD-SAFE  — ConcurrentLinkedQueue for multi-worker collection.
// ✅ LAZY         — only active when compile tasks actually run.
// ✅ READ-ONLY    — does not modify compiler behavior; only captures output.

package com.anyonehub.diagnostics.service

import com.anyonehub.diagnostics.model.CompilerWarning
import org.gradle.api.provider.Property
import org.gradle.api.services.BuildService
import org.gradle.api.services.BuildServiceParameters
import org.gradle.tooling.events.FinishEvent
import org.gradle.tooling.events.OperationCompletionListener
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * A Gradle [BuildService] that collects compiler deprecation warnings emitted
 * to stdout/stderr during `KotlinCompile` and `JavaCompile` task execution.
 *
 * ## How It Works
 * 1. The plugin registers this service and wires it via `usesService()` on compile tasks.
 * 2. Compile tasks call [collectLine] from their `StandardOutputListener` / `StandardErrorListener`
 *    callbacks, feeding raw compiler output lines into a thread-safe queue.
 * 3. After all compile tasks finish, the diagnostics task calls [drainToFile] to
 *    flush collected warnings to an intermediate file.
 *
 * ## Thread Safety
 * Multiple compile tasks may run in parallel (e.g., `compileDebugKotlin` and
 * `compileDebugJavaWithJavac`). [ConcurrentLinkedQueue] guarantees lock-free
 * thread-safe insertion from any Gradle worker thread.
 *
 * ## Output Format
 * Each collected warning is serialized via [CompilerWarning.toIntermediateLine].
 */
abstract class CompilerOutputCollectorService :
    BuildService<CompilerOutputCollectorService.Params>,
    OperationCompletionListener,
    AutoCloseable {

    interface Params : BuildServiceParameters {
        /** Directory where the service writes its output file. */
        val outputDir: Property<String>
    }

    /**
     * Thread-safe queue of parsed compiler warnings.
     * Populated by [collectLine] from parallel compile task listeners.
     */
    private val collectedWarnings = ConcurrentLinkedQueue<CompilerWarning>()

    /**
     * Regex matching Kotlin compiler deprecation warnings.
     * Format: `w: file:///path/to/File.kt:42:5: 'ClassName' is deprecated. Use X instead.`
     * Also matches: `w: /path/to/File.kt:42:5: ...`
     */
    private val kotlinDeprecationRegex = Regex(
        """w:\s+(?:file:///)?((?:[A-Za-z]:)?[^:]+\.kt):(\d+):(\d+):\s+(.+deprecated.+)""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Regex matching Java compiler deprecation warnings.
     * Format: `/path/to/File.java:42: warning: [deprecation] method() in Class has been deprecated`
     * Also matches: `warning: [deprecation] ...`
     */
    private val javaDeprecationRegex = Regex(
        """((?:[A-Za-z]:)?[^:]+\.java):(\d+):\s+warning:\s+\[deprecation]\s+(.+)""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Fallback regex for generic deprecation warnings without file location.
     * Matches any line containing "deprecated" from compiler output streams.
     */
    private val genericDeprecationRegex = Regex(
        """(?:w:|warning:)\s+.*deprecated.*""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Called from `StandardOutputListener` / `StandardErrorListener` callbacks
     * on compile tasks. Parses a single line and, if it matches a known
     * deprecation pattern, queues a [CompilerWarning].
     *
     * @param line    Raw compiler output line.
     * @param source  The language of the originating compile task ("Kotlin" or "Java").
     */
    fun collectLine(line: String, source: String) {
        val trimmed = line.trimEnd()
        if (!trimmed.contains("deprecated", ignoreCase = true)) return

        when (source) {
            "Kotlin" -> {
                val match = kotlinDeprecationRegex.find(trimmed)
                if (match != null) {
                    val apiMatch = Regex("""'([^']+)'\s+is\s+deprecated\.?\s*(.*)""")
                        .find(match.groupValues[4])
                    val apiName = apiMatch?.groupValues?.get(1)
                    val suggestion = apiMatch?.groupValues?.get(2) ?: ""
                    val snippet = if (apiName != null) {
                        "$apiName is deprecated. $suggestion"
                    } else {
                        match.groupValues[4]
                    }

                    collectedWarnings.add(CompilerWarning(
                        language = "Kotlin",
                        sourceFile = match.groupValues[1],
                        line = match.groupValues[2].toIntOrNull() ?: -1,
                        column = match.groupValues[3].toIntOrNull() ?: -1,
                        flag = "w: (deprecated)",
                        snippet = snippet.take(120),
                    ))
                    return
                }
                // Fallback: generic Kotlin deprecation without file location
                if (genericDeprecationRegex.containsMatchIn(trimmed)) {
                    collectedWarnings.add(CompilerWarning(
                        language = "Kotlin",
                        sourceFile = "unknown",
                        line = -1,
                        column = -1,
                        flag = "w: (deprecated)",
                        snippet = trimmed.take(120),
                    ))
                }
            }
            "Java" -> {
                val match = javaDeprecationRegex.find(trimmed)
                if (match != null) {
                    collectedWarnings.add(CompilerWarning(
                        language = "Java",
                        sourceFile = match.groupValues[1],
                        line = match.groupValues[2].toIntOrNull() ?: -1,
                        column = -1,
                        flag = "[deprecation]",
                        snippet = match.groupValues[3].take(120),
                    ))
                    return
                }
                // Fallback: generic Java deprecation without file location
                if (genericDeprecationRegex.containsMatchIn(trimmed)) {
                    collectedWarnings.add(CompilerWarning(
                        language = "Java",
                        sourceFile = "unknown",
                        line = -1,
                        column = -1,
                        flag = "[deprecation]",
                        snippet = trimmed.take(120),
                    ))
                }
            }
        }
    }

    /**
     * Legacy method. File writing is now deferred to [close] to prevent
     * in-flight disk I/O from blocking Gradle worker threads during parallel compilation.
     */
    fun drainToFile(outputFile: File): Int {
        // No-op. Deferring to close().
        return 0
    }

    /** Returns the current count of collected warnings (for diagnostics). */
    fun warningCount(): Int = collectedWarnings.size

    /** [OperationCompletionListener] — no-op; we drain on-demand from the task. */
    override fun onFinish(event: FinishEvent) {
        // Intentionally empty. The service collects passively; draining is explicit.
    }

    override fun close() {
        val outDirStr = parameters.outputDir.orNull
        if (outDirStr != null) {
            val outputFile = File(outDirStr, "compiler-service-output.txt")
            outputFile.parentFile?.mkdirs()
            val warnings = collectedWarnings.toList()
            if (warnings.isNotEmpty()) {
                outputFile.bufferedWriter().use { writer ->
                    warnings.forEach { warning ->
                        writer.appendLine(warning.toIntermediateLine())
                    }
                }
            }
        }
        collectedWarnings.clear()
    }
}
