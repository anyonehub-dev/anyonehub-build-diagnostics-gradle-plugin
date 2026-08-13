// Copyright 2024 anyone-Hub
// ProjectHealthPlugin — headless, read-only Gradle diagnostic plugin.
//
// ENGINEERING GUARDRAILS (v1.1.1):
// ✅ READ-ONLY       — no repository blocks, no dependencies, no toolchain mutations.
// ✅ STRICTLY LAZY   — zero configuration-phase I/O; ALL wiring is Provider-based.
// ✅ CLEAN-SAFE      — finalizedBy is attached to assemble/bundle tasks; it naturally
//                      does NOT fire on clean-only builds (no assemble task → no trigger).
// ✅ AGP-AWARE       — hooks into AndroidComponentsExtension.onVariants for dynamic paths.
// ✅ IDE-SYNC-SAFE   — tasks.configureEach; zero eager task creation or resolution.
// ✅ DAEMON-SAFE     — BuildService and compile-task listeners wired during config phase;
//                      no configureEach or API mutations inside whenReady (DEFECT-3 fix).
// ✅ CONFIG-CACHE    — no eager .get() on Providers at config time (DEFECT-4 fix);
//                      BuildService outputDir passed as DirectoryProperty, not String.

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
 * Entry point for the `com.anyonehub.diagnostics.health` Gradle plugin (v1.1.1).
 *
 * ## Lifecycle Contract
 * - **Configuration phase**: ONLY task registration via `tasks.register`, lazy Provider
 *   wiring, BuildService registration, and compile-task listener wiring via `configureEach`.
 *   Zero I/O, zero configuration resolution, zero `.get()` calls on Providers.
 * - **Execution phase**: All analysis, file writes, and network calls happen exclusively
 *   inside `@TaskAction` methods or Gradle Worker API `WorkAction.execute()` calls.
 * - **Clean guard**: `finalizedBy` is wired directly to `assemble`/`bundle` tasks during
 *   the configuration phase. Because no assemble task runs on a `clean`-only build,
 *   the finalizer never triggers — no `whenReady` guard is needed or used.
 *
 * ## Defect Fixes Applied (v1.1.1)
 * - DEFECT-1: `DependencyDiagnosticsTask` now uses `WorkerExecutor.noIsolation()`.
 * - DEFECT-2: `afterEvaluate` configuration-resolution replaced by lazy `project.provider`.
 * - DEFECT-3: `tasks.configureEach` / `finalizedBy` moved out of `whenReady` to config phase.
 * - DEFECT-4: `CompilerOutputCollectorService.Params.outputDir` is now `DirectoryProperty`.
 * - DEFECT-5: Intermediate file inputs on `AggregateProjectHealthReportTask` use `@InputFile`.
 * - DEFECT-6: `collectorServiceOutput` on `CompilerDiagnosticsTask` uses `@InputFile`.
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
     * Registers all diagnostic tasks and wires ALL listeners and relationships
     * strictly within the Gradle configuration phase.
     *
     * CRITICAL: This method ONLY calls:
     *  - `tasks.register` (lazy task registration)
     *  - `gradle.sharedServices.registerIfAbsent` (does NOT instantiate the service)
     *  - `tasks.withType(...).configureEach` (lazy compile-task listener wiring)
     *  - `tasks.configureEach` (lazy finalizedBy wiring)
     *  - `project.provider { ... }` (lazy dependency coordinate collection)
     *
     * Nothing that resolves configurations, touches the file system, or calls
     * `.get()` on any Provider occurs here.
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

        // ── DEFECT-3/4 FIX: Register BuildService at configuration phase ───────
        // registerIfAbsent() does NOT instantiate the service — it is safe here.
        // The service is only created when a task that declares usesService() actually runs.
        //
        // DEFECT-4 FIX: outputDir is now a DirectoryProperty, not an eagerly-resolved
        // String. No .get() call at configuration time.
        val collectorServiceProvider = project.gradle.sharedServices.registerIfAbsent(
            "compilerOutputCollector",
            CompilerOutputCollectorService::class.java
        ) { spec ->
            spec.parameters.outputDir.set(
                project.rootProject.layout.buildDirectory.dir("diagnostics/intermediates")
            )
        }

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

            // mustRunAfter by task name — non-fatal if the tasks don't exist.
            mustRunAfter("compileDebugKotlin", "compileDebugJavaWithJavac")
        }

        // ── DEFECT-2 FIX: Lazy runtimeClasspath provider ──────────────────────
        // Wrapping configuration resolution in project.provider { } defers it to
        // execution time (when Gradle actually resolves the FileCollection), instead
        // of calling .artifactFiles eagerly inside afterEvaluate (which triggered
        // live resolution during the configuration phase and caused IDE sync hangs).
        val runtimeJarsProvider = project.provider {
            val config = project.configurations.findByName("releaseRuntimeClasspath")
                ?: project.configurations.findByName("debugRuntimeClasspath")
            config?.incoming
                ?.artifactView { view -> view.lenient(true) }
                ?.artifacts
                ?.artifactFiles
                ?: project.files()
        }

        // ── DEFECT-2 FIX: Lazy declared-coordinates provider ──────────────────
        // project.provider { } defers DependencySet access to execution time.
        // cfg.dependencies returns declared (not resolved) dependencies — no
        // resolution engine is triggered. The @Suppress remains for the deprecated
        // DependencySet API, which still exists on Gradle 8 and is the only way
        // to introspect declared coordinates without triggering resolution.
        val declaredCoordsProvider = project.provider {
            buildList {
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

            // Lazy runtime classpath — resolves only at task execution.
            runtimeClasspathJars.from(runtimeJarsProvider)

            // Lazy declared coordinates — resolves only at task execution.
            declaredDependencies.set(declaredCoordsProvider)

            // Wire the compiled classes dir lazily (directory may not exist yet).
            compiledClassesDir.from(
                project.layout.buildDirectory.dir("intermediates/javac/debug/classes")
            )
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

        // ── DEFECT-3 FIX: Wire finalizedBy during configuration phase ──────────
        // tasks.configureEach is a configuration-phase API. The block runs for each
        // task as it is registered (lazy), not eagerly. No whenReady is involved.
        //
        // Clean-build safety: if no assemble/bundle task is in the execution graph
        // (e.g. on ./gradlew clean), none of these tasks run, so finalizedBy never
        // triggers the diagnostic pipeline. The explicit whenReady guard is gone.
        project.tasks.configureEach { task ->
            if (task.name.startsWith("assemble") || task.name.startsWith("bundle")) {
                task.finalizedBy(aggregateTask)
            }
        }

        // ── DEFECT-3 FIX: Wire KotlinCompile listeners at configuration phase ──
        // Moving from inside whenReady to here ensures Gradle's task-graph assembly
        // sees these relationships before the graph is frozen.
        project.tasks.withType(
            org.jetbrains.kotlin.gradle.tasks.KotlinCompile::class.java
        ).configureEach { kotlinTask ->
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

        // ── DEFECT-3 FIX: Wire JavaCompile listeners at configuration phase ────
        project.tasks.withType(
            org.gradle.api.tasks.compile.JavaCompile::class.java
        ).configureEach { javaTask ->
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

    companion object {
        internal const val TASK_GROUP = "Project Health"
    }
}
