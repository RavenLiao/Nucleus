package io.github.kdroidfilter.nucleus.desktop.application.internal.analyzer.detectors

import io.github.kdroidfilter.nucleus.desktop.application.internal.analyzer.ResourcePattern
import java.util.jar.JarFile

/**
 * Scans JAR entries to detect resources that GraalVM native-image needs to include:
 * - Native libraries (.so, .dll, .dylib, .jnilib)
 * - Properties files at root or known paths (excluding META-INF)
 * - Text resources in analysis/NLP resource directories (stopwords, etc.)
 */
internal object JarResourceDetector {

    private val NATIVE_LIB_EXTENSIONS = setOf("so", "dll", "dylib", "jnilib", "a")

    private val RESOURCE_EXTENSIONS = setOf("properties", "txt", "xml", "json", "cfg", "conf")

    fun detect(jarFile: JarFile): Set<ResourcePattern> {
        val patterns = mutableSetOf<ResourcePattern>()

        for (entry in jarFile.entries()) {
            if (entry.isDirectory) continue
            val name = entry.name

            // Skip class files and META-INF signatures/manifests
            if (name.endsWith(".class")) continue
            if (name.startsWith("META-INF/MANIFEST.MF")) continue
            if (name.startsWith("META-INF/maven/")) continue
            if (name.startsWith("META-INF/versions/")) continue
            if (name.endsWith(".SF") || name.endsWith(".RSA") || name.endsWith(".DSA")) continue

            val ext = name.substringAfterLast('.', "")

            when {
                // Native libraries — always include, they're loaded at runtime
                ext in NATIVE_LIB_EXTENSIONS -> {
                    patterns.add(ResourcePattern(glob = name))
                }

                // Properties files — commonly loaded via getResourceAsStream at runtime
                // Include root-level and known framework paths
                ext == "properties" && !name.startsWith("META-INF/") -> {
                    patterns.add(ResourcePattern(glob = name))
                }

                // Text/config resources in analysis directories (Lucene stopwords, etc.)
                ext in RESOURCE_EXTENSIONS && isAnalysisResourcePath(name) -> {
                    patterns.add(ResourcePattern(glob = name))
                }
            }
        }

        return patterns
    }

    /**
     * Heuristic: resources in paths that look like NLP/analysis framework resource directories.
     */
    private fun isAnalysisResourcePath(name: String): Boolean =
        name.contains("/analysis/") ||
            name.contains("/resources/") ||
            name.contains("/data/") ||
            name.contains("/dictionaries/") ||
            name.contains("/stopwords/")
}
