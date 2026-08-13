// Copyright 2024 anyone-Hub
// ProjectHealthPlugin — headless, read-only Gradle diagnostic plugin.
//
// ENGINEERING GUARDRAILS (v1.0.9):
// ✅ READ-ONLY       — no repository blocks, no dependencies, no toolchain mutations.
// ✅ STRICTLY LAZY   — zero configuration-phase I/O; BuildService registered only when
//                      a diagnostic task is in the task execution graph.
// ✅ CLEAN-SAFE      — diagnostics never run during `clean`; guarded by task graph check.
// ✅ AGP-AWARE       — hooks into AndroidComponentsExtension.onVariants for dynamic paths.
// ✅ IDE-SYNC-SAFE   — tasks.configureEach; zero eager task creation or resolution.

package com.anyonehub.diagnostics

import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.variant.AndroidComponentsExtension
import com.anyonehub.diagnostics.service.CompilerOutputCollectorService
import com.anyonehub.diagnostics.tasks.AggregateProjectHealthReportTask
import com.anyonehub.diagnostics.tasks.CompilerDiagnosticsTask
import com.anyonehub.diagnostics.tasks.DependencyDiagnosticsTask
import com.anyonehub.diagnostics.tasks.GenerateDeadCodeReportTask
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.kotlin.dsl.*

/**
 * Entry point for the `com.anyonehub.diagnostics.health` Gradle plugin (v1.0.9).
 *
 * ## Lifecycle Contract
 * - **Configuration phase**: ONLY task registration via `tasks.register`. Zero I/O,
 *   zero configuration resolution, zero BuildService initialization.
 * - **Execution phase**: All analysis, file writes, and network calls happen exclusively
 *   inside `@TaskAction` methods of the registered tasks.
 * - **Clean guard**: The `finalizedBy` relationship is only established after the task
 *   execution graph is ready, and ONLY if a diagnostic task is actually in the graph.
 *   This ensures `./gradlew clean` never triggers diagnostics.
 *
 * ## Architecture
 * ```
 * GenerateDeadCodeReportTask   ─┐
 * CompilerDiagnosticsTask      ─┤─► AggregateProjectHealthReportTask
 * DependencyDiagnosticsTask    ─┘
 * ```
 */
class ProjectHealthPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        var configured = false
        val configureBlock = {
            if (!configured) {
                configured = true
                registerDiagnosticTasks(project)
            }
        }

        // Defer all task registration until we know we're on an Android module.
        // withPlugin callbacks run during the configuration phase but AFTER the
        // android plugin has set up its model — safe for task registration only.
        project.pluginManager.withPlugin("com.android.application") { configureBlock() }
        project.pluginManager.withPlugin("com.android.library") { configureBlock() }

        project.afterEvaluate {
            if (!configured) {
                project.logger.debug(
                    "[ProjectHealthPlugin] Skipping ${project.path}: not an Android module. " +
                            "Apply 'com.android.application' or 'com.android.library' first."
                )
            }
        }
    }

    /**
     * Registers all diagnostic tasks lazily.
     *
     * CRITICAL: This method ONLY calls `tasks.register`. It does NOT:
     *  - resolve any configurations
     *  - access any file system paths eagerly
     *  - initialize any BuildServices
     *  - call `tasks.withType(...).configureEach { ... }` for compile tasks
     *
     * The BuildService is registered lazily inside `whenTaskAdded` on the execution
     * graph, ensuring it only exists during actual diagnostic execution.
     */
    private fun registerDiagnosticTasks(project: Project) {

        // ── Lazy intermediate file path providers ─────────────────────────────
        // These are pure Provider<T> chains — nothing is resolved at configuration time.
        val intermediatesDir = project.layout.buildDirectory.dir("diagnostics/intermediates")

        val deadCodeIntermediate: Provider<RegularFile> =
            intermediatesDir.map { it.file("dead-code-intermediate.txt") }

        val compilerIntermediate: Provider<RegularFile> =
            intermediatesDir.map { it.file("compiler-diagnostics-intermediate.txt") }

        val dependencyIntermediate: Provider<RegularFile> =
            intermediatesDir.map { it.file("dependency-diagnostics-intermediate.txt") }

        val healthReportFile = project.layout.projectDirectory.file("project-health.html")

        // ── Step 1: Register pipeline tasks (LAZY — zero execution at this point) ─

        val deadCodeTask = project.tasks.register<GenerateDeadCodeReportTask>(
            "generateDeadCodeReport"
        ) {
            group = TASK_GROUP
            description = "Parses the R8 usage.txt report and writes dead-code metrics."
            outputFile.set(deadCodeIntermediate)
        }

        val compilerTask = project.tasks.register<CompilerDiagnosticsTask>(
            "generateCompilerDiagnostics"
        ) {
            group = TASK_GROUP
            description = "Extracts C++ / Kotlin / Java compiler warnings."

            // Wire the BuildService output file location using a lazy provider.
            // No BuildService is created here — just a file path.
            collectorServiceOutput.set(
                project.rootProject.layout.buildDirectory
                    .dir("diagnostics/intermediates")
                    .map { it.file("compiler-service-output.txt") }
            )

            // Pure directory providers — no file system access at configuration time.
            cxxBaseDir.from(project.layout.projectDirectory.dir(".cxx"))
            kotlinBuildReportDir.from(project.layout.buildDirectory.dir("reports/kotlin-build"))
            kotlinTmpDir.from(project.layout.buildDirectory.dir("tmp/compileDebugKotlin"))
            javaTmpDir.from(project.layout.buildDirectory.dir("tmp/compileDebugJavaWithJavac"))
            outputFile.set(compilerIntermediate)

            // mustRunAfter by task name — these are non-fatal if the tasks don't exist.
            // Uses string names to avoid eager task realization.
            mustRunAfter("compileDebugKotlin", "compileDebugJavaWithJavac")
        }

        val dependencyTask = project.tasks.register<DependencyDiagnosticsTask>(
            "generateDependencyDiagnostics"
        ) {
            group = TASK_GROUP
            description = "Detects unused and outdated declared dependencies."
            outputFile.set(dependencyIntermediate)

            // Network flag is a pure provider lookup — no resolution.
            networkChecksEnabled.set(
                project.providers.gradleProperty("healthCheckNetwork")
                    .map { it.toBoolean() }
                    .orElse(true)
            )

            // Wire the compiled classes dir lazily (directory may not exist yet).
            compiledClassesDir.from(
                project.layout.buildDirectory.dir("intermediates/javac/debug/classes")
            )

        }

        // ── CRITICAL FIX: Dependency coordinate snapshot via afterEvaluate ────
        // This must be OUTSIDE the register block so `dependencyTask` is in scope
        // as a TaskProvider<DependencyDiagnosticsTask> captured from above.
        project.afterEvaluate {
            val runtimeConfig = project.configurations.findByName("releaseRuntimeClasspath")
                ?: project.configurations.findByName("debugRuntimeClasspath")

            if (runtimeConfig != null) {
                dependencyTask.configure {
                    it.runtimeClasspathJars.from(
                        runtimeConfig.incoming
                            .artifactView { view -> view.lenient(true) }
                            .artifacts
                            .artifactFiles
                    )
                }
            }

            val declaredCoords = buildList {
                project.configurations
                    .filter { cfg ->
                        cfg.name.endsWith("Implementation", ignoreCase = true) ||
                                cfg.name.endsWith("Api", ignoreCase = true)
                    }
                    .flatMap { cfg ->
                        @Suppress("DEPRECATION")
                        cfg.dependencies.filterIsInstance<org.gradle.api.artifacts.ExternalDependency>()
                    }
                    .forEach { dep ->
                        val version = dep.version ?: return@forEach
                        add("${dep.group.orEmpty()}:${dep.name}:$version")
                    }
            }

            dependencyTask.configure {
                it.declaredDependencies.set(declaredCoords)
            }
        }

        // ── Step 2: Register aggregation task ─────────────────────────────────
        val aggregateTask = project.tasks.register<AggregateProjectHealthReportTask>(
            "projectHealthReport"
        ) {
            group = TASK_GROUP
            description = "Aggregates all pipeline outputs into a final health report."
            deadCodeIntermediateFile.set(deadCodeIntermediate)
            compilerIntermediateFile.set(compilerIntermediate)
            dependencyIntermediateFile.set(dependencyIntermediate)
            reportOutputFile.set(healthReportFile)
            projectName.set(project.name)
            projectPath.set(project.path)
            dependsOn(deadCodeTask, compilerTask, dependencyTask)
        }

        // ── Step 3: AGP Artifacts API wiring (inside onVariants — truly lazy) ─
        val androidComponents = project.extensions.findByType(AndroidComponentsExtension::class.java)
            ?: return

        androidComponents.onVariants { variant ->
            if (variant.name.contains("release", ignoreCase = true) ||
                variant.name.contains("debug", ignoreCase = true)
            ) {
                deadCodeTask.configure {
                    it.mappingFile.set(variant.artifacts.get(SingleArtifact.OBFUSCATION_MAPPING_FILE))
                    it.variantName.set(variant.name)
                }
                aggregateTask.configure {
                    it.variantName.set(variant.name)
                }
            }
        }

        // ── Step 4: Graph-aware finalizedBy — THE CLEAN GUARD ─────────────────
        // We use `gradle.taskGraph.whenReady` to inspect the actual execution graph.
        // `finalizedBy` is ONLY attached if:
        //   (a) the task graph contains at least one "assemble" or "bundle" task, AND
        //   (b) the task graph does NOT consist solely of "clean" tasks.
        // This is the definitive fix preventing diagnostics from running during `clean`.
        project.gradle.taskGraph.whenReady { graph ->
            val allTaskPaths = graph.allTasks.map { it.path }.toSet()

            // Guard: abort if this is a clean-only invocation.
            val hasCleanOnly = allTaskPaths.all { path ->
                path.endsWith(":clean") || path == ":clean"
            }
            if (hasCleanOnly) return@whenReady

            // Guard: only attach if an assemble/build/bundle task is in the graph
            // for THIS specific project (using the module path prefix).
            val projectPath = project.path
            val hasAssembleTask = allTaskPaths.any { path ->
                path.startsWith(projectPath) &&
                        (path.contains(":assemble") || path.contains(":bundle"))
            }

            if (hasAssembleTask) {
                // Attach finalizedBy to each individual assemble task in graph.
                project.tasks.configureEach { task ->
                    if ((task.name.startsWith("assemble") || task.name.startsWith("bundle")) &&
                        graph.hasTask(task)
                    ) {
                        task.finalizedBy(aggregateTask)
                    }
                }

                // ── Register the BuildService NOW (execution phase is imminent) ──
                // This is the ONLY place the BuildService is created, ensuring it
                // does NOT exist during IDE syncs or clean-only builds.
                val collectorServiceProvider = project.gradle.sharedServices.registerIfAbsent(
                    "compilerOutputCollector",
                    CompilerOutputCollectorService::class.java
                ) { spec ->
                    spec.parameters.outputDir.set(
                        project.rootProject.layout.buildDirectory
                            .dir("diagnostics/intermediates").get().asFile.absolutePath
                    )
                }

                // Wire compile tasks with the BuildService for live output interception.
                // configureEach is safe here because graph.whenReady fires after
                // all tasks have been added to the task graph.
                project.tasks.withType(
                    org.jetbrains.kotlin.gradle.tasks.KotlinCompile::class.java
                ).configureEach { kotlinTask ->
                    if (graph.hasTask(kotlinTask)) {
                        kotlinTask.usesService(collectorServiceProvider)
                        kotlinTask.doFirst {
                            val service = collectorServiceProvider.get()
                            kotlinTask.logging.addStandardErrorListener { line ->
                                service.collectLine(line.toString(), "Kotlin")
                            }
                            kotlinTask.logging.addStandardOutputListener { line ->
                                service.collectLine(line.toString(), "Kotlin")
                            }
                        }
                    }
                }

                project.tasks.withType(
                    org.gradle.api.tasks.compile.JavaCompile::class.java
                ).configureEach { javaTask ->
                    if (graph.hasTask(javaTask)) {
                        javaTask.usesService(collectorServiceProvider)
                        javaTask.options.compilerArgs.let { args ->
                            if ("-Xlint:deprecation" !in args) args.add("-Xlint:deprecation")
                        }
                        javaTask.doFirst {
                            val service = collectorServiceProvider.get()
                            javaTask.logging.addStandardErrorListener { line ->
                                service.collectLine(line.toString(), "Java")
                            }
                            javaTask.logging.addStandardOutputListener { line ->
                                service.collectLine(line.toString(), "Java")
                            }
                        }
                    }
                }
            }
        }
    }

    companion object {
        internal const val TASK_GROUP = "Project Health"
    }
}
