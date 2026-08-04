// Copyright 2024 anyone-Hub
// Phase 2 data model — dead code metrics parsed from R8 usage.txt.

package com.anyonehub.diagnostics.model

/**
 * Aggregated dead-code metrics extracted from the R8 ProGuard-style
 * `usage.txt` report. All counts represent items flagged as UNUSED by R8.
 *
 * This class is serialization-friendly (used only as plain data; no Kotlin
 * serialization annotations needed because we write structured plain-text).
 */
data class DeadCodeMetrics(
    /** Number of fully unused top-level or nested classes. */
    val unusedClasses: Int,
    /** Number of unused methods across all classes. */
    val unusedMethods: Int,
    /** Number of unused fields across all classes. */
    val unusedFields: Int,
    /** Number of unused method parameters. */
    val unusedParameters: Int,
    /**
     * Whether the R8 mapping/usage file was present at all.
     * When `false`, minification was disabled and all counts are 0.
     */
    val r8Enabled: Boolean,
) {
    /** Total unused code items. */
    val totalUnused: Int get() = unusedClasses + unusedMethods + unusedFields + unusedParameters

    /** Human-readable summary for intermediate report files. */
    fun toIntermediateText(): String = buildString {
        appendLine("=== DEAD_CODE_METRICS ===")
        appendLine("r8_enabled=$r8Enabled")
        appendLine("unused_classes=$unusedClasses")
        appendLine("unused_methods=$unusedMethods")
        appendLine("unused_fields=$unusedFields")
        appendLine("unused_parameters=$unusedParameters")
        appendLine("total_unused=$totalUnused")
        appendLine("=========================")
    }

    companion object {
        /** Sentinel returned when R8 is disabled (minification off). */
        val R8_DISABLED = DeadCodeMetrics(
            unusedClasses = 0,
            unusedMethods = 0,
            unusedFields = 0,
            unusedParameters = 0,
            r8Enabled = false,
        )

        /** Parse a [DeadCodeMetrics] from the intermediate text format. */
        fun fromIntermediateText(text: String): DeadCodeMetrics {
            val lines = text.lines().associate { line ->
                val idx = line.indexOf('=')
                if (idx < 0) "" to "" else line.substring(0, idx) to line.substring(idx + 1)
            }
            return DeadCodeMetrics(
                unusedClasses = lines["unused_classes"]?.toIntOrNull() ?: 0,
                unusedMethods = lines["unused_methods"]?.toIntOrNull() ?: 0,
                unusedFields = lines["unused_fields"]?.toIntOrNull() ?: 0,
                unusedParameters = lines["unused_parameters"]?.toIntOrNull() ?: 0,
                r8Enabled = lines["r8_enabled"]?.toBoolean() ?: false,
            )
        }
    }
}
