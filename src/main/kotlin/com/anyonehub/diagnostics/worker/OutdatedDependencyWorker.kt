// Copyright 2024 anyone-Hub
// Phase 4 — Asynchronous version-check worker (Thread-Pool compatible).
//
// GUARDRAILS:
// ✅ NON-BLOCKING  — runs off the main Gradle thread via ExecutorService.
// ✅ ZERO NEW DEPS — uses java.net.HttpURLConnection; no OkHttp or other libs.
// ✅ FAIL-SAFE     — any network exception writes an error note; never throws.

package com.anyonehub.diagnostics.worker

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI

object DependencyVersionChecker {

    fun checkVersion(group: String, artifact: String, current: String, output: File) {
        output.parentFile.mkdirs()

        val (latest, error) = fetchLatestVersion(group, artifact)

        val isOutdated = if (latest != null) isNewerVersion(current, latest) else false
        val errorStr = error ?: ""

        output.writeText("$group|$artifact|$current|${latest ?: "unknown"}|$isOutdated|$errorStr\n")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Version fetching logic
    // ─────────────────────────────────────────────────────────────────────────

    private fun fetchLatestVersion(group: String, artifact: String): Pair<String?, String?> {
        return if (isGoogleMavenGroup(group)) {
            fetchFromGoogleMaven(group, artifact)
                .let { (v, e) -> if (v != null) Pair(v, null) else fetchFromMavenCentral(group, artifact) }
        } else {
            fetchFromMavenCentral(group, artifact)
        }
    }

    private fun isGoogleMavenGroup(group: String): Boolean {
        return group.startsWith("androidx.") ||
                group.startsWith("com.android.") ||
                group.startsWith("com.google.android.") ||
                group.startsWith("com.google.firebase.") ||
                group.startsWith("com.google.gms.") ||
                group.startsWith("com.google.dagger")
    }

    private fun fetchFromGoogleMaven(group: String, artifact: String): Pair<String?, String?> {
        val groupPath = group.replace('.', '/')
        val url = "https://dl.google.com/dl/android/maven2/$groupPath/group-index.xml"

        return try {
            val content = httpGet(url, timeoutMs = 8_000)
                ?: return Pair(null, "Empty response from Google Maven")

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

    private fun fetchFromMavenCentral(group: String, artifact: String): Pair<String?, String?> {
        val query = "g:$group+a:$artifact"
        val url = "https://search.maven.org/solrsearch/select?q=$query&rows=1&core=gav&wt=json"

        return try {
            val content = httpGet(url, timeoutMs = 10_000)
                ?: return Pair(null, "Empty response from Maven Central")

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
