// Copyright 2024 anyone-Hub
// Phase 3 data model — a single compiler warning from C++/Kotlin/Java.

package com.anyonehub.diagnostics.model

/**
 * Represents one compiler diagnostic warning captured from either:
 * - Clang/GCC C++ compilation (parsed from CMake build logs / ninja log), or
 * - KotlinCompile / JavaCompile deprecation diagnostics.
 */
data class CompilerWarning(
    /** Compiler origin: "C++", "Kotlin", or "Java". */
    val language: String,
    /** Project-relative source file path (e.g., `src/main/cpp/foo.cpp`). */
    val sourceFile: String,
    /** 1-based line number; -1 if unavailable. */
    val line: Int,
    /** 1-based column number; -1 if unavailable. */
    val column: Int,
    /**
     * The compiler flag or diagnostic code:
     * - C++: e.g., `-Wdeprecated-declarations`
     * - Kotlin: e.g., `w: (deprecated)`
     * - Java: e.g., `[deprecation]`
     */
    val flag: String,
    /** Short context snippet of the flagged code (truncated to 120 chars). */
    val snippet: String,
) {
    /** Serialise to the one-line intermediate format (pipe-delimited). */
    fun toIntermediateLine(): String =
        "$language|$sourceFile|$line|$column|$flag|${snippet.replace('|', '¦').take(120)}"

    companion object {
        private const val SEPARATOR = "|"

        /** Parse from a single intermediate-format line. */
        fun fromIntermediateLine(line: String): CompilerWarning? {
            val parts = line.split(SEPARATOR)
            if (parts.size < 6) return null
            return CompilerWarning(
                language = parts[0],
                sourceFile = parts[1],
                line = parts[2].toIntOrNull() ?: -1,
                column = parts[3].toIntOrNull() ?: -1,
                flag = parts[4],
                snippet = parts[5].replace('¦', '|'),
            )
        }

        /** Section header written to the intermediate file. */
        const val SECTION_HEADER = "=== COMPILER_WARNINGS ==="
        const val SECTION_FOOTER = "=========================="
    }
}
