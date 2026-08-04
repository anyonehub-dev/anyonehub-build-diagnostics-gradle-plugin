#!/usr/bin/env kotlin

import java.io.File

val srcDir = File("src")
if (!srcDir.exists()) {
    println("src directory not found!")
    System.exit(1)
}

val newCopyright = "// Copyright 2024 anyone-Hub\n"
val blockCommentRegex = Regex("""(?s)^/\*.*?Copyright.*?\*/\s*""", RegexOption.IGNORE_CASE)
val lineCommentRegex = Regex("""(?m)^\s*//\s*Copyright.*${'$'}\n?""", RegexOption.IGNORE_CASE)

srcDir.walkTopDown().forEach { file ->
    if (file.isFile && (file.extension == "kt" || file.extension == "kts")) {
        var content = file.readText()
        
        // Remove block copyright headers at the start
        content = blockCommentRegex.replace(content, "")
        // Remove line copyright headers
        content = lineCommentRegex.replace(content, "")
        
        // Inject new copyright
        content = newCopyright + content
        
        file.writeText(content)
        println("Updated: ${'$'}{file.path}")
    }
}
println("Copyright injection complete.")
