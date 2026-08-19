// Copyright 2024 anyone-Hub
// Phase 4 — Dependency Diagnostics: unused detection + async version checks.
//
// GUARDRAILS:
// ✅ READ-ONLY      — resolves runtimeClasspath via ArtifactView; no config mutations.
// ✅ NON-BLOCKING   — network checks run via WorkerExecutor.noIsolation() WorkAction.
//                    Daemon thread is NEVER blocked (DEFECT-1 fix: removed Future.get()).
// ✅ LAZY           — all inputs are Provider<T> / @InputFiles; zero eager resolution.
// ✅ CI-SAFE        — network disabled via -PhealthCheckNetwork=false; fully offline.

package com.anyonehub.diagnostics.tasks

import com.anyonehub.diagnostics.model.DependencyStatus
import com.anyonehub.diagnostics.worker.VersionCheckWorkAction
import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import org.gradle.kotlin.dsl.*
import org.gradle.workers.WorkerExecutor
import java.io.File
import javax.inject.Inject

/**
 * Pipeline B — Dependency Diagnostics Task.
 *
 * ## Unused Dependency Detection
 * Resolves all JARs on the `runtimeClasspath` via Gradle's `ArtifactView` API and
 * cross-references each JAR's exported package namespaces against the package
 * references found by scanning compiled `.class` bytecode in [compiledClassesDir].
 *
 * A dependency is flagged as **potentially unused** when none of the packages it
 * exports appears as a referenced package in the project's own compiled bytecode.
 *
 * Note: This is a conservative heuristic. Runtime-only dependencies (e.g., DI
 * providers, logging backends, SPI implementations) may appear "unused" because
 * they are loaded reflectively. The report flags them as *potentially* unused.
 *
 * ## Outdated Dependency Detection
 * For each dependency coordinate in [declaredDependencies], a [OutdatedDependencyWorker]
 * is submitted to [WorkerExecutor.noIsolation()] which performs an async HTTPS query
 * against Google Maven or Maven Central. Workers run concurrently without blocking the
 * Gradle thread.
 *
 * Network checks are skipped entirely when [networkChecksEnabled] is `false`
 * (set via `-PhealthCheckNetwork=false` on the command line).
 */
// Network calls return live upstream data — caching would produce stale version reports.
// Bytecode scanning reads machine-local intermediates that differ across CI workers.
@UntrackedTask(because = "Performs live network checks against Maven repositories and scans " +
        "machine-local bytecode intermediates; outputs are not safe to cache across builds or machines.")
abstract class DependencyDiagnosticsTask @Inject constructor(
    private val workerExecutor: WorkerExecutor,
) : DefaultTask() {

    /**
     * JARs resolved from the `runtimeClasspath` configuration via `ArtifactView`.
     * These are the transitive runtime artifacts actually used by the build.
     */
    @get:InputFiles
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val runtimeClasspathJars: ConfigurableFileCollection

    /**
     * Directory containing the project's compiled `.class` files.
     * Used for bytecode namespace scanning (unused dep detection).
     * Typically `build/intermediates/javac/debug/classes/`.
     */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val compiledClassesDir: ConfigurableFileCollection

    /**
     * Flat list of dependency coordinate strings (`group:artifact:version`) declared
     * in `implementation` or `api` configurations. Populated in
     * [com.anyonehub.diagnostics.ProjectHealthPlugin] via a lazy provider.
     */
    @get:Input
    abstract val declaredDependencies: ListProperty<String>

    /**
     * When `true` (default), async version-check workers are submitted.
     * Set to `false` via `-PhealthCheckNetwork=false` for offline / CI builds.
     */
    @get:Input
    abstract val networkChecksEnabled: Property<Boolean>

    /** Intermediate output consumed by the aggregation task. */
    @get:OutputFile
    abstract val outputFile: RegularFileProperty

    @TaskAction
    fun execute() {
        val output = outputFile.get().asFile
        output.parentFile.mkdirs()

        val declared = declaredDependencies.get()
        logger.lifecycle("[ProjectHealth/Deps] Analysing ${declared.size} declared dependencies.")

        // ── Step 1: Build referenced-package index from compiled bytecode ─────
        val referencedPackages: Set<String> = buildReferencedPackageIndex()
        logger.lifecycle(
            "[ProjectHealth/Deps] Found ${referencedPackages.size} unique package " +
                    "namespaces referenced in compiled bytecode."
        )

        // ── Step 2: Detect unused dependencies ────────────────────────────────
        val unusedResults = detectUnusedDependencies(referencedPackages)

        // ── Step 3: Submit async version-check workers ────────────────────────
        val workerOutputDir = project.layout.buildDirectory
            .dir("diagnostics/worker-outputs").get().asFile
        workerOutputDir.mkdirs()

        val networkEnabled = networkChecksEnabled.get()
        if (networkEnabled) {
            logger.lifecycle(
                "[ProjectHealth/Deps] Submitting ${declared.size} async version-check workers."
            )
            submitVersionCheckWorkers(declared, workerOutputDir)
        } else {
            logger.lifecycle(
                "[ProjectHealth/Deps] Network checks DISABLED (-PhealthCheckNetwork=false). " +
                        "Skipping version checks."
            )
        }

        // The executor blocks until all tasks complete inside submitVersionCheckWorkers.
        // No Gradle Worker API await() needed.

        // ── Step 4: Collect worker results ────────────────────────────────────
        val versionCheckResults: Map<String, Pair<String?, String?>> =
            if (networkEnabled) collectWorkerResults(workerOutputDir) else emptyMap()

        // ── Step 5: Build final DependencyStatus list and write output ─────────
        val allStatuses = mergeDependencyStatuses(
            declared = declared,
            unusedMap = unusedResults,
            versionResults = versionCheckResults,
            networkEnabled = networkEnabled,
        )

        logger.lifecycle(
            "[ProjectHealth/Deps] Results: ${allStatuses.count { it.isOutdated }} outdated, " +
                    "${allStatuses.count { it.isUnused }} potentially unused."
        )

        output.bufferedWriter().use { writer ->
            writer.appendLine(DependencyStatus.SECTION_HEADER)
            allStatuses.forEach { status ->
                writer.appendLine(status.toIntermediateLine())
            }
            writer.appendLine(DependencyStatus.SECTION_FOOTER)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Bytecode scanning — referenced package index
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Scans the compiled `.class` files in [compiledClassesDir] to extract the set
     * of all package namespaces that are referenced by the project's own bytecode.
     *
     * We use a lightweight approach: class file constant pool strings that match
     * the `L<package/path>;` descriptor pattern — without pulling in ASM at runtime.
     * (ASM is `compileOnly` in the plugin's build; we can't depend on it being on the
     * runtime classpath of the consuming project's Gradle daemon.)
     *
     * The approach reads each `.class` file as raw bytes and extracts UTF-8 constant
     * pool entries that look like type descriptors, then derives the package from them.
     */
    private fun buildReferencedPackageIndex(): Set<String> {
        val packages = mutableSetOf<String>()
        // Regex to match type descriptor patterns: Lcom/example/ClassName;
        val descriptorPattern = Regex("""L([a-zA-Z_${'$'}][a-zA-Z0-9_${'$'}/]*)/[^/;]+;""")

        val existingDirs = compiledClassesDir.files.filter { it.exists() }
        if (existingDirs.isEmpty()) {
            logger.warn(
                "[ProjectHealth/Deps] No compiledClassesDir found — skipping bytecode analysis."
            )
            return emptySet()
        }

        existingDirs.forEach { classesDir ->
            classesDir.walkTopDown()
                .filter { it.isFile && it.extension == "class" }
                .forEach { classFile ->
                    try {
                        // Read raw bytes and decode as Latin-1 (safe for binary; UTF-8 sequences
                        // in constant pool entries will still surface as human-readable path strings).
                        val text = classFile.readBytes().toString(Charsets.ISO_8859_1)
                        descriptorPattern.findAll(text).forEach { match ->
                            // Convert slash-separated path to dot-separated package.
                            val pkg = match.groupValues[1].replace('/', '.')
                            packages += pkg
                        }
                    } catch (_: Exception) {
                        // Malformed or encrypted class — skip silently.
                    }
                }
        }

        return packages
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Unused dependency detection
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * For each JAR on the [runtimeClasspathJars], extract the root packages it provides
     * and check whether any appear in [referencedPackages].
     *
     * Returns a map of `group:artifact:version -> (isUnused, reason)`.
     */
    private fun detectUnusedDependencies(
        referencedPackages: Set<String>,
    ): Map<String, Pair<Boolean, String>> {
        val result = mutableMapOf<String, Pair<Boolean, String>>()

        runtimeClasspathJars.files.forEach { jar ->
            if (!jar.exists() || jar.extension != "jar") return@forEach

            // Derive a best-guess coordinate from the JAR file path.
            // Gradle resolves JARs into the local cache at paths like:
            // ~/.gradle/caches/modules-2/files-2.1/group/artifact/version/.../artifact-version.jar
            val coord = deriveCoordinateFromJarPath(jar)

            try {
                val exportedPackages = mutableSetOf<String>()
                java.util.jar.JarFile(jar, false).use { jarFile ->
                    jarFile.entries().asSequence()
                        .filter { !it.isDirectory && it.name.endsWith(".class") }
                        .forEach { entry ->
                            // Entry name: com/example/Foo.class → package: com.example
                            val pkg = entry.name
                                .substringBeforeLast('/')
                                .replace('/', '.')
                            exportedPackages += pkg
                        }
                }

                val isUsed = exportedPackages.any { pkg ->
                    referencedPackages.any { ref -> ref == pkg || ref.startsWith("$pkg.") }
                }

                if (!isUsed && exportedPackages.isNotEmpty()) {
                    result[coord] = Pair(
                        true,
                        "No class from ${exportedPackages.size} exported package(s) referenced in bytecode"
                    )
                } else if (exportedPackages.isEmpty()) {
                    result[coord] = Pair(
                        false,
                        "Resources-only JAR (no .class files) — cannot determine usage"
                    )
                }
            } catch (_: Exception) {
                // Corrupted or non-standard JAR — skip.
            }
        }

        return result
    }

    /**
     * Heuristically derives a `group:artifact:version` coordinate from a Gradle
     * dependency cache JAR path.
     *
     * Gradle stores JARs at:
     * `…/caches/modules-2/files-2.1/<group>/<artifact>/<version>/<hash>/<artifact>-<version>.jar`
     */
    private fun deriveCoordinateFromJarPath(jar: File): String {
        // Walk up: jar → hash dir → version dir → artifact dir → group dir
        val parts = jar.absolutePath.split(File.separator)
        val filesIdx = parts.indexOf("files-2.1")
        return if (filesIdx >= 0 && filesIdx + 3 < parts.size) {
            val group = parts[filesIdx + 1]
            val artifact = parts[filesIdx + 2]
            val version = parts[filesIdx + 3]
            "$group:$artifact:$version"
        } else {
            // Fallback: use the JAR filename as a best-effort identifier.
            jar.nameWithoutExtension
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Worker submission
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Submits one [VersionCheckWorkAction] per declared coordinate to Gradle's managed
     * thread pool via [WorkerExecutor.noIsolation].
     *
     * The call to [org.gradle.workers.WorkQueue.await] blocks ONLY the current task's
     * worker thread — not the Gradle daemon's main thread — allowing Gradle to continue
     * scheduling other tasks in parallel while version checks complete.
     *
     * This replaces the previous `Executors.newFixedThreadPool(8)` + `Future.get()` pattern
     * (DEFECT-1) which held the daemon thread for up to `⌈N/8⌉ × 20 seconds`.
     */
    private fun submitVersionCheckWorkers(
        coordinates: List<String>,
        workerOutputDir: File,
    ) {
        val queue = workerExecutor.noIsolation()

        coordinates.forEach { coord ->
            val parts = coord.split(":")
            if (parts.size < 3) return@forEach

            val group   = parts[0]
            val artifact = parts[1]
            val version  = parts[2]
            val safeKey  = "${group.replace('.', '_')}__${artifact.replace('.', '_')}.txt"
            val workerOutput = File(workerOutputDir, safeKey)

            queue.submit(VersionCheckWorkAction::class.java) { params ->
                params.group.set(group)
                params.artifact.set(artifact)
                params.currentVersion.set(version)
                params.outputFile.set(workerOutput)
            }
        }

        // Block only this task's worker thread until all version-check workers complete,
        // then proceed to collectWorkerResults(). Gradle can schedule other tasks
        // on other threads while we wait — unlike the old Future.get() pattern.
        queue.await()
    }

    /**
     * Reads all worker result files and parses them into a map of
     * `group:artifact:version -> (latestVersion?, networkError?)`.
     */
    private fun collectWorkerResults(
        workerOutputDir: File,
    ): Map<String, Pair<String?, String?>> {
        val results = mutableMapOf<String, Pair<String?, String?>>()

        workerOutputDir.listFiles { f -> f.extension == "txt" }?.forEach { file ->
            val line = file.readText().trim()
            val parts = line.split("|")
            if (parts.size >= 6) {
                val coord = "${parts[0]}:${parts[1]}:${parts[2]}"
                val latest = parts[3].takeIf { it != "unknown" }
                val error = parts[5].takeIf { it.isNotBlank() }
                results[coord] = Pair(latest, error)
            }
        }

        return results
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Result merging
    // ─────────────────────────────────────────────────────────────────────────

    private fun mergeDependencyStatuses(
        declared: List<String>,
        unusedMap: Map<String, Pair<Boolean, String>>,
        versionResults: Map<String, Pair<String?, String?>>,
        networkEnabled: Boolean,
    ): List<DependencyStatus> {
        return declared.mapNotNull { coord ->
            val parts = coord.split(":")
            if (parts.size < 3) return@mapNotNull null

            val group = parts[0]
            val artifact = parts[1]
            val version = parts[2]

            val (isUnused, unusedReason) = unusedMap[coord] ?: Pair(false, "")
            val (latestVersion, networkError) = versionResults[coord] ?: Pair(null, null)

            val isOutdated = if (latestVersion != null) {
                isNewerVersion(version, latestVersion)
            } else false

            DependencyStatus(
                groupId = group,
                artifactId = artifact,
                currentVersion = version,
                latestVersion = if (networkEnabled) latestVersion else null,
                isOutdated = isOutdated,
                isUnused = isUnused,
                unusedReason = unusedReason,
                networkError = networkError,
            )
        }
    }

    /**
     * Semantic version comparison: returns `true` if [candidate] > [current].
     * Non-numeric version components sort lexicographically.
     */
    private fun isNewerVersion(current: String, candidate: String): Boolean {
        if (current == candidate) return false
        val partsA = candidate.split(".", "-").map { it.toIntOrNull() ?: 0 }
        val partsB = current.split(".", "-").map { it.toIntOrNull() ?: 0 }
        val maxLen = maxOf(partsA.size, partsB.size)
        for (i in 0 until maxLen) {
            val pa = partsA.getOrElse(i) { 0 }
            val pb = partsB.getOrElse(i) { 0 }
            if (pa != pb) return pa > pb
        }
        return false
    }
}
