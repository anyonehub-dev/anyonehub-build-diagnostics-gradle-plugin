// Copyright 2024 anyone-Hub
// Phase 4 (Worker API) — Asynchronous version-check worker.
//
// GUARDRAILS:
// ✅ NON-BLOCKING  — runs entirely off the main Gradle thread via WorkAction.
// ✅ ZERO NEW DEPS — uses java.net.HttpURLConnection; no OkHttp or other libs.
// ✅ FAIL-SAFE     — any network exception writes an error note; never throws.
// ✅ -PhealthCheckNetwork=false — caller skips submitting work when toggled off.

package com.anyonehub.diagnostics.worker

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.workers.WorkAction
import org.gradle.workers.WorkParameters
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

/**
 * Worker parameters for [OutdatedDependencyWorker].
 *
 * Each submitted work item corresponds to one declared dependency.
 * The worker writes a single result line to [outputFile], which the
 * [com.anyonehub.diagnostics.tasks.DependencyDiagnosticsTask] aggregates.
 */
interface OutdatedDependencyParameters : WorkParameters {
    /** Maven group ID (e.g., `androidx.compose.ui`). */
    val groupId: Property<String>
    /** Maven artifact ID (e.g., `ui`). */
    val artifactId: Property<String>
    /** Currently declared version string (e.g., `1.6.0`). */
    val currentVersion: Property<String>
    /**
     * Output file unique to this work item.
     * Named as `<group>__<artifact>.txt` inside the intermediates directory.
     */
    val outputFile: RegularFileProperty
}

/**
 * Gradle [WorkAction] that queries Maven Central and Google Maven for the latest
 * stable release of a single dependency, then writes a result line to [outputFile].
 *
 * ## Query Strategy
 * 1. **Google Maven first** — for `androidx.*`, `com.android.*`, `com.google.*` groups.
 *    Uses `https://dl.google.com/dl/android/maven2/<group-path>/group-index.xml`.
 * 2. **Maven Central fallback** — for all other coordinates.
 *    Uses `https://search.maven.org/solrsearch/select?q=g:<group>+a:<artifact>&wt=json`.
 *
 * ## Result File Format
 * A single plain-text line:
 * ```
 * <groupId>|<artifactId>|<currentVersion>|<latestVersion>|<isOutdated>|<error?>
 * ```
 */
abstract class OutdatedDependencyWorker : WorkAction<OutdatedDependencyParameters> {

    override fun execute() {
        val group = parameters.groupId.get()
        val artifact = parameters.artifactId.get()
        val current = parameters.currentVersion.get()
        val output = parameters.outputFile.get().asFile
        output.parentFile.mkdirs()

        val (latest, error) = fetchLatestVersion(group, artifact)

        val isOutdated = if (latest != null) isNewerVersion(current, latest) else false
        val errorStr = error ?: ""

        output.writeText("$group|$artifact|$current|${latest ?: "unknown"}|$isOutdated|$errorStr\n")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Version fetching logic
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Attempts to determine the latest stable version for [group]:[artifact].
     * Returns a [Pair] of (latestVersion, errorMessage?).
     */
    private fun fetchLatestVersion(group: String, artifact: String): Pair<String?, String?> {
        return if (isGoogleMavenGroup(group)) {
            fetchFromGoogleMaven(group, artifact)
                .let { (v, e) -> if (v != null) Pair(v, null) else fetchFromMavenCentral(group, artifact) }
        } else {
            fetchFromMavenCentral(group, artifact)
        }
    }

    /**
     * Checks whether a dependency group is hosted on Google Maven.
     * Google Maven hosts `androidx.*`, `com.android.*`, `com.google.android.*`,
     * `com.google.firebase.*`, and related Google namespaces.
     */
    private fun isGoogleMavenGroup(group: String): Boolean {
        return group.startsWith("androidx.") ||
                group.startsWith("com.android.") ||
                group.startsWith("com.google.android.") ||
                group.startsWith("com.google.firebase.") ||
                group.startsWith("com.google.gms.") ||
                group.startsWith("com.google.dagger")
    }

    /**
     * Queries `https://dl.google.com/dl/android/maven2/<group-path>/group-index.xml`.
     *
     * The group-index.xml format:
     * ```xml
     * <root>
     *   <compose-ui versions="1.6.0,1.6.1,1.7.0-alpha01,..."/>
     * </root>
     * ```
     * We extract the `versions` attribute and pick the last stable entry.
     */
    private fun fetchFromGoogleMaven(group: String, artifact: String): Pair<String?, String?> {
        val groupPath = group.replace('.', '/')
        val url = "https://dl.google.com/dl/android/maven2/$groupPath/group-index.xml"

        return try {
            val content = httpGet(url, timeoutMs = 8_000)
                ?: return Pair(null, "Empty response from Google Maven")

            // Find the element matching our artifact (hyphens in element names).
            val artifactTag = artifact.replace('.', '-').replace('_', '-')
            val versionsRegex = Regex("""<$artifactTag\s+versions="([^"]+)"""")
            val match = versionsRegex.find(content)
                ?: return Pair(null, "Artifact '$artifact' not found in Google Maven group-index")

            val versions = match.groupValues[1].split(",")
            val latestStable = versions.lastOrNull { isStableVersion(it) }
                ?: versions.lastOrNull()

            Pair(latestStable, null)
        } catch (e: IOException) {
            Pair(null, "Google Maven network error: ${e.message?.take(100)}")
        }
    }

    /**
     * Queries Maven Central Solr Search API.
     *
     * Endpoint: `https://search.maven.org/solrsearch/select?q=g:<group>+a:<artifact>&rows=1&wt=json`
     *
     * The JSON response `response.docs[0].latestVersion` contains the most recent version.
     */
    private fun fetchFromMavenCentral(group: String, artifact: String): Pair<String?, String?> {
        val query = "g:$group+a:$artifact"
        val url = "https://search.maven.org/solrsearch/select?q=$query&rows=1&core=gav&wt=json"

        return try {
            val content = httpGet(url, timeoutMs = 10_000)
                ?: return Pair(null, "Empty response from Maven Central")

            // Extract latestVersion from JSON without a JSON library.
            // Response: {..., "response":{"docs":[{"latestVersion":"x.y.z",...}],...},...}
            val latestVersionRegex = Regex(""""latestVersion"\s*:\s*"([^"]+)"""")
            val match = latestVersionRegex.find(content)
                ?: return Pair(null, "No latestVersion in Maven Central response")

            Pair(match.groupValues[1], null)
        } catch (e: IOException) {
            Pair(null, "Maven Central network error: ${e.message?.take(100)}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // HTTP utility
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Performs a single synchronous HTTP GET and returns the response body as a String,
     * or `null` on a non-200 status. Uses only [java.net.HttpURLConnection] — no new deps.
     */
    private fun httpGet(urlString: String, timeoutMs: Int): String? {
        val connection = URI.create(urlString).toURL().openConnection() as HttpURLConnection
        return try {
            connection.apply {
                requestMethod = "GET"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                setRequestProperty("Accept", "application/json, application/xml, text/xml")
                setRequestProperty("User-Agent", "ProjectHealthPlugin/1.0 Gradle-Diagnostic")
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            connection.inputStream.bufferedReader().readText()
        } finally {
            connection.disconnect()
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Version comparison utilities
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Returns `true` if [candidate] is a strict semantic-version upgrade over [current].
     *
     * Comparison rules:
     * 1. Split on `.` and compare each numeric component.
     * 2. Non-numeric components (e.g. `-alpha01`) cause the version to sort lower.
     * 3. Snapshot and pre-release suffixes are treated as less than a stable release.
     */
    private fun isNewerVersion(current: String, candidate: String): Boolean {
        if (current == candidate) return false
        return compareVersions(candidate, current) > 0
    }

    private fun compareVersions(a: String, b: String): Int {
        val partsA = a.split(".", "-").map { it.toIntOrNull() ?: 0 }
        val partsB = b.split(".", "-").map { it.toIntOrNull() ?: 0 }
        val maxLen = maxOf(partsA.size, partsB.size)
        for (i in 0 until maxLen) {
            val pa = partsA.getOrElse(i) { 0 }
            val pb = partsB.getOrElse(i) { 0 }
            if (pa != pb) return pa.compareTo(pb)
        }
        return 0
    }

    /**
     * Returns `true` if [version] does NOT contain pre-release qualifiers
     * (`alpha`, `beta`, `rc`, `snapshot`, `dev`, `eap`, `milestone`).
     */
    private fun isStableVersion(version: String): Boolean {
        val lower = version.lowercase()
        return !lower.contains("alpha") &&
                !lower.contains("beta") &&
                !lower.contains("-rc") &&
                !lower.contains("snapshot") &&
                !lower.contains("dev") &&
                !lower.contains("eap") &&
                !lower.contains("milestone") &&
                !lower.contains("-m")
    }
}
