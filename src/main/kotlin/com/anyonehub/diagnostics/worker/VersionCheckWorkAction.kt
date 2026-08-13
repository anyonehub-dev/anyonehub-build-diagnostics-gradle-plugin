// Copyright 2024 anyone-Hub
// Gradle Worker API work unit for async Maven version-check.
// Replaces raw Executors.newFixedThreadPool + Future.get() (DEFECT-1 fix).
//
// GUARDRAILS:
// ✅ NON-BLOCKING  — runs on Gradle's managed thread pool via noIsolation().
// ✅ DAEMON-SAFE   — never holds the Gradle daemon's main thread.
// ✅ ZERO NEW DEPS — DependencyVersionChecker uses java.net.HttpURLConnection only.

package com.anyonehub.diagnostics.worker

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters

/**
 * Parameters contract for [VersionCheckWorkAction].
 * All fields are lazy [Property] / [RegularFileProperty] — serialised safely
 * by the Worker API across process boundaries (noIsolation = same classloader).
 */
interface VersionCheckWorkParameters : WorkParameters {
    /** Maven group ID (e.g. `androidx.compose.ui`). */
    val group: Property<String>

    /** Maven artifact ID (e.g. `ui`). */
    val artifact: Property<String>

    /** Version string currently declared in build scripts. */
    val currentVersion: Property<String>

    /**
     * Output file where the result line is written.
     * Format: `group|artifact|current|latest|isOutdated|errorStr\n`
     * Consumed by [com.anyonehub.diagnostics.tasks.DependencyDiagnosticsTask.collectWorkerResults].
     */
    val outputFile: RegularFileProperty
}

/**
 * Gradle Worker API [WorkAction] that performs a single asynchronous HTTPS
 * version-check for one Maven coordinate against Google Maven or Maven Central.
 *
 * ## Lifecycle
 * Submitted via [org.gradle.workers.WorkerExecutor.noIsolation] inside
 * [com.anyonehub.diagnostics.tasks.DependencyDiagnosticsTask.submitVersionCheckWorkers].
 * The submitting task's `@TaskAction` returns immediately; Gradle awaits the
 * [org.gradle.workers.WorkQueue] via an explicit `queue.await()` call before
 * proceeding to result collection — guaranteeing result files are fully written
 * while keeping Gradle's parallel execution model intact.
 *
 * ## Thread Safety
 * Each work item writes to a unique output file keyed by `group__artifact.txt`,
 * so there are no shared-file write conflicts between concurrent workers.
 */
abstract class VersionCheckWorkAction : WorkAction<VersionCheckWorkParameters> {

    override fun execute() {
        DependencyVersionChecker.checkVersion(
            group   = parameters.group.get(),
            artifact = parameters.artifact.get(),
            current  = parameters.currentVersion.get(),
            output   = parameters.outputFile.get().asFile,
        )
    }
}
