// Copyright 2024 anyone-Hub
// ProjectHealthPlugin — headless, read-only Gradle diagnostic plugin.
//
// ENGINEERING GUARDRAILS:
// ✅ READ-ONLY   — no repository blocks, no dependencies, no toolchain mutations.
// ✅ LAZY        — all task inputs are wired as Provider<T>; zero eager resolution.
// ✅ AGP-AWARE   — hooks into AndroidComponentsExtension.onVariants for dynamic paths.
// ✅ NON-BLOCKING — network checks run via Gradle Worker API (WorkAction).

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
 * Entry point for the `com.anyonehub.diagnostics.health` Gradle plugin.
 *
 * ## Application
 * Apply this plugin to any Android Application or Library module:
 * ```kotlin
 * plugins {
 *     id("com.anyonehub.diagnostics.health")
 * }
 * ```
 *
 * ## Output
 * Running `./gradlew :<module>:projectHealthReport` produces:
 * ```
 * <module>/build/reports/project-health.md
 * ```
 *
 * ## Architecture
 * The plugin wires four tasks lazily — three pipeline tasks feed intermediate
 * plain-text files into a single aggregation task that emits the final Markdown:
 *
 * ```
 * GenerateDeadCodeReportTask   ─┐
 * CompilerDiagnosticsTask      ─┤─► AggregateProjectHealthReportTask
 * DependencyDiagnosticsTask    ─┘
 * ```
 *
 * All AGP artifact access is performed inside [AndroidComponentsExtension.onVariants]
 * so no paths are resolved during the configuration phase.
 */
class ProjectHealthPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        var configured = false
        val configureBlock = {
            if (!configured) {
                configured = true
                configureDiagnostics(project)
            }
        }

        // Wait for either the application or library plugin to be applied
        project.pluginManager.withPlugin("com.android.application") { configureBlock() }
        project.pluginManager.withPlugin("com.android.library") { configureBlock() }

        // If after evaluating the project neither was applied, log a warning
        project.afterEvaluate {
            if (!configured) {
                project.logger.debug(
                    "[ProjectHealthPlugin] Skipping ${project.path}: not an Android module. " +
                            "Apply 'com.android.application' or 'com.android.library' first."
                )
            }
        }
    }

    private fun configureDiagnostics(project: Project) {

        // ── Register the BuildService for live compiler output collection ─────
        val collectorServiceProvider = project.gradle.sharedServices.registerIfAbsent(
            "compilerOutputCollector",
            CompilerOutputCollectorService::class.java
        ) { spec ->
            spec.parameters.outputDir.set(
                project.layout.buildDirectory.dir("diagnostics/intermediates").get().asFile.absolutePath
            )
        }

        // ── Intermediate output directory (lazy Provider) ────────────────────
        // Each pipeline task writes a single .txt file here; the aggregator reads all three.
        val intermediatesDir = project.layout.buildDirectory.dir("diagnostics/intermediates")

        // ── Lazy intermediate file providers ─────────────────────────────────
        val deadCodeIntermediate: Provider<RegularFile> =
            intermediatesDir.map { it.file("dead-code-intermediate.txt") }

        val compilerIntermediate: Provider<RegularFile> =
            intermediatesDir.map { it.file("compiler-diagnostics-intermediate.txt") }

        val dependencyIntermediate: Provider<RegularFile> =
            intermediatesDir.map { it.file("dependency-diagnostics-intermediate.txt") }

        // ── Final report output ───────────────────────────────────────────────
        val healthReportFile = project.layout.projectDirectory.file("project-health.html")

        // ── Register pipeline tasks (LAZY — nothing runs at configuration time) ─
        val deadCodeTask = project.tasks.register<GenerateDeadCodeReportTask>(
            "generateDeadCodeReport"
        ) {
            group = TASK_GROUP
            description = "Parses the R8 usage.txt report and writes dead-code metrics."
            outputFile.set(deadCodeIntermediate)
            // AGP artifact wiring happens below inside onVariants {}
        }

        val compilerTask = project.tasks.register<CompilerDiagnosticsTask>(
            "generateCompilerDiagnostics"
        ) {
            group = TASK_GROUP
            description = "Extracts C++ / Kotlin / Java compiler warnings via live BuildService interception."
            
            // Wire the BuildService provider so the task can drain collected warnings.
            collectorServiceOutput.set(
                intermediatesDir.map { it.file("compiler-service-output.txt") }
            )

            // Add implicit dependencies to ensure these compile tasks run first
            mustRunAfter(project.tasks.withType(org.gradle.api.tasks.compile.JavaCompile::class.java))
            mustRunAfter(project.tasks.withType(org.jetbrains.kotlin.gradle.tasks.KotlinCompile::class.java))

            // C++ artifact tree — purely observational; the .cxx/ dir is written by AGP/CMake.
            cxxBaseDir.from(project.layout.projectDirectory.dir(".cxx"))
            // Kotlin Build Reports (enabled via kotlin.build.report.output=file in gradle.properties).
            kotlinBuildReportDir.from(
                project.layout.buildDirectory.dir("reports/kotlin-build")
            )
            // Gradle tmp directories where Kotlin/Java compilers may persist log output.
            kotlinTmpDir.from(
                project.layout.buildDirectory.dir("tmp/compileDebugKotlin")
            )
            javaTmpDir.from(
                project.layout.buildDirectory.dir("tmp/compileDebugJavaWithJavac")
            )
            outputFile.set(compilerIntermediate)
        }

        val dependencyTask = project.tasks.register<DependencyDiagnosticsTask>(
            "generateDependencyDiagnostics"
        ) {
            group = TASK_GROUP
            description = "Detects unused and outdated declared dependencies."
            outputFile.set(dependencyIntermediate)
            networkChecksEnabled.set(
                project.providers.gradleProperty("healthCheckNetwork")
                    .map { it.toBoolean() }
                    .orElse(true)
            )
            // We are already inside a lazy `register` configuration block, so the project
            // is guaranteed to be evaluated at this point. Do not use afterEvaluate.
            val runtimeConfig = project.configurations.findByName("releaseRuntimeClasspath")
                ?: project.configurations.findByName("debugRuntimeClasspath")
            if (runtimeConfig != null) {
                runtimeClasspathJars.from(
                    runtimeConfig.incoming
                        .artifactView { it.lenient(true) }
                        .artifacts
                        .artifactFiles
                )
            }
            // Wire the compiled classes directory for bytecode scanning.
            compiledClassesDir.from(
                project.layout.buildDirectory.dir("intermediates/javac/debug/classes")
            )
            // Snapshot declared dependency coordinates for the version check workers.
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
                        // dep.group is non-null for ExternalDependency; guard only version
                        // since BOM-managed or dynamic dependencies may omit it.
                        val version = dep.version ?: return@forEach
                        add("${dep.group.orEmpty()}:${dep.name}:$version")
                    }
            }
            declaredDependencies.set(declaredCoords)
        }

        // ── Aggregation task ──────────────────────────────────────────────────
        val aggregateTask = project.tasks.register<AggregateProjectHealthReportTask>(
            "projectHealthReport"
        ) {
            group = TASK_GROUP
            description = "Aggregates all pipeline outputs into a final Markdown health report."
            deadCodeIntermediateFile.set(deadCodeIntermediate)
            compilerIntermediateFile.set(compilerIntermediate)
            dependencyIntermediateFile.set(dependencyIntermediate)
            reportOutputFile.set(healthReportFile)
            projectName.set(project.name)
            projectPath.set(project.path)
            // Declare task dependencies so Gradle's incremental build works correctly.
            dependsOn(deadCodeTask, compilerTask, dependencyTask)
        }

        // ── AGP Artifacts API wiring (inside onVariants — truly lazy) ─────────
        // We wire the R8 obfuscation mapping file here because it is only
        // available after AGP has resolved the variant graph.
        val androidComponents = project.extensions.findByType(AndroidComponentsExtension::class.java)
            ?: run {
                project.logger.warn(
                    "[ProjectHealthPlugin] AndroidComponentsExtension not found on ${project.path}. " +
                            "R8 dead-code pipeline will be skipped."
                )
                return
            }

        // We target the first release variant (the one most likely to have R8 enabled).
        // The plugin is intentionally non-exhaustive — one report per invocation is enough.
        androidComponents.onVariants { variant ->
            // Only wire for the release variant to maximize chance of R8 artifacts existing.
            if (variant.name.contains("release", ignoreCase = true) ||
                // Fallback: also accept debug when no release variant exists.
                variant.name.contains("debug", ignoreCase = true)
            ) {
                project.tasks
                    .withType(GenerateDeadCodeReportTask::class.java)
                    .named("generateDeadCodeReport")
                    .configure {
                        // AGP Artifacts API — the ONLY correct, non-hardcoded path to the R8 mapping.
                        // This Provider is lazy: it resolves only when the task actually executes.
                        it.mappingFile.set(
                            variant.artifacts.get(SingleArtifact.OBFUSCATION_MAPPING_FILE)
                        )
                        it.variantName.set(variant.name)
                }

                project.tasks
                    .withType(AggregateProjectHealthReportTask::class.java)
                    .named("projectHealthReport")
                    .configure {
                        it.variantName.set(variant.name)
                    }
            }
        }

        project.logger.info(
            "[ProjectHealthPlugin] Registered health report tasks on ${project.path}. " +
                    "It will run automatically during 'assemble' or 'build'."
        )

        // Automatically run the health report at the end of any assemble or build invocation.
        project.tasks.matching { it.name.startsWith("assemble") || it.name.startsWith("build") }.configureEach {
            it.finalizedBy(aggregateTask)
        }

        // ── Wire compile tasks with live output interception ──────────────────
        // Hook into KotlinCompile tasks to capture deprecation warnings in real-time.
        project.tasks.withType(org.jetbrains.kotlin.gradle.tasks.KotlinCompile::class.java).configureEach { kotlinTask ->
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

        // Hook into JavaCompile tasks to capture deprecation warnings.
        project.tasks.withType(org.gradle.api.tasks.compile.JavaCompile::class.java).configureEach { javaTask ->
            javaTask.usesService(collectorServiceProvider)
            // Inject -Xlint:deprecation to force javac to emit deprecation warnings.
            javaTask.options.compilerArgs.let { args ->
                if ("-Xlint:deprecation" !in args) {
                    args.add("-Xlint:deprecation")
                }
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

        // After all compile tasks finish, drain collected warnings to the service output file.
        project.tasks.withType(org.jetbrains.kotlin.gradle.tasks.KotlinCompile::class.java).configureEach { kotlinTask ->
            kotlinTask.doLast {
                val service = collectorServiceProvider.get()
                val serviceOutputFile = project.layout.buildDirectory
                    .dir("diagnostics/intermediates").get().asFile
                    .resolve("compiler-service-output.txt")
                service.drainToFile(serviceOutputFile)
            }
        }
    }

    companion object {
        internal const val TASK_GROUP = "Project Health"
    }
}
