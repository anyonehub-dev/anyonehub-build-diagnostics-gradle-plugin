// Copyright 2024 anyone-Hub
// Phase 4 data model — version and usage status of a declared dependency.

package com.anyonehub.diagnostics.model

/**
 * Describes the health status of one declared Gradle dependency.
 *
 * @property groupId       Maven group, e.g. `androidx.compose.ui`
 * @property artifactId    Maven artifact, e.g. `ui`
 * @property currentVersion Version declared in the build scripts.
 * @property latestVersion  Newest stable release on Maven Central or Google Maven.
 *                          `null` if the network check was skipped or failed.
 * @property isOutdated     `true` when [latestVersion] > [currentVersion] (semver).
 * @property isUnused       `true` when no compiled .class file references this jar's packages.
 * @property unusedReason   Human-readable explanation for the unused verdict (or empty string).
 * @property networkError   Non-null if the async version-check threw an exception.
 */
data class DependencyStatus(
    val groupId: String,
    val artifactId: String,
    val currentVersion: String,
    val latestVersion: String?,
    val isOutdated: Boolean,
    val isUnused: Boolean,
    val unusedReason: String = "",
    val networkError: String? = null,
) {
    /** Fully qualified Maven coordinates. */
    val coordinates: String get() = "$groupId:$artifactId:$currentVersion"

    /** Serialise to a single pipe-delimited line. */
    fun toIntermediateLine(): String =
        "$groupId|$artifactId|$currentVersion|${latestVersion ?: "unknown"}|$isOutdated|$isUnused|${unusedReason.take(200)}|${networkError?.take(200) ?: ""}"

    companion object {
        private const val SEPARATOR = "|"
        const val SECTION_HEADER = "=== DEPENDENCY_STATUS ==="
        const val SECTION_FOOTER = "========================="

        /** Parse from one intermediate-format line. Returns `null` on malformed input. */
        fun fromIntermediateLine(raw: String): DependencyStatus? {
            val parts = raw.split(SEPARATOR)
            if (parts.size < 8) return null
            return DependencyStatus(
                groupId = parts[0],
                artifactId = parts[1],
                currentVersion = parts[2],
                latestVersion = parts[3].takeIf { it != "unknown" },
                isOutdated = parts[4].toBoolean(),
                isUnused = parts[5].toBoolean(),
                unusedReason = parts[6],
                networkError = parts[7].takeIf { it.isNotBlank() },
            )
        }
    }
}
