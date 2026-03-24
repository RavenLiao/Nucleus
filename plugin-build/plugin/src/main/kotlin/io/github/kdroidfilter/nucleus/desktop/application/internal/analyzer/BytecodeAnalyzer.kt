package io.github.kdroidfilter.nucleus.desktop.application.internal.analyzer

import io.github.kdroidfilter.nucleus.desktop.application.internal.analyzer.detectors.ClassForNameDetector
import io.github.kdroidfilter.nucleus.desktop.application.internal.analyzer.detectors.JarResourceDetector
import io.github.kdroidfilter.nucleus.desktop.application.internal.analyzer.detectors.KotlinSerializableDetector
import io.github.kdroidfilter.nucleus.desktop.application.internal.analyzer.detectors.MethodHandleDetector
import io.github.kdroidfilter.nucleus.desktop.application.internal.analyzer.detectors.NativeMethodDetector
import io.github.kdroidfilter.nucleus.desktop.application.internal.analyzer.detectors.ProxyDetector
import io.github.kdroidfilter.nucleus.desktop.application.internal.analyzer.detectors.ReflectionApiDetector
import io.github.kdroidfilter.nucleus.desktop.application.internal.analyzer.detectors.ResourceAccessDetector
import io.github.kdroidfilter.nucleus.desktop.application.internal.analyzer.detectors.ResourceBundleDetector
import io.github.kdroidfilter.nucleus.desktop.application.internal.analyzer.detectors.ServiceLoaderDetector
import java.io.File
import java.util.jar.JarFile

/**
 * Main entry point: scans one or more JARs and produces an [AnalysisResult].
 */
internal object BytecodeAnalyzer {

    /**
     * Analyzes a single JAR file.
     */
    fun analyzeJar(jarPath: File): AnalysisResult {
        require(jarPath.exists() && jarPath.name.endsWith(".jar")) {
            "Not a valid JAR: $jarPath"
        }

        val jniEntries = mutableSetOf<JniEntry>()
        val reflectionEntries = mutableSetOf<ReflectionEntry>()
        val resourcePatterns = mutableSetOf<ResourcePattern>()
        val serviceLoaderEntries = mutableSetOf<ReflectionEntry>()
        val jniReferencedTypes = mutableSetOf<String>()
        val jniFieldTypes = mutableSetOf<String>()
        // Index: internal class name (com/foo/Bar) -> class bytes, for second-pass JNI callback resolution
        val classBytesIndex = mutableMapOf<String, ByteArray>()

        JarFile(jarPath).use { jar ->
            // Service loader detection (scans META-INF/services/)
            val serviceResult = ServiceLoaderDetector.detect(jar)
            serviceLoaderEntries.addAll(serviceResult.reflectionEntries)
            resourcePatterns.addAll(serviceResult.resourcePatterns)

            // JAR resource detection (native libs, properties, analysis resources)
            resourcePatterns.addAll(JarResourceDetector.detect(jar))

            // Pass 1: scan all .class files
            for (entry in jar.entries()) {
                if (!entry.name.endsWith(".class") || entry.name.startsWith("META-INF/")) continue

                val classBytes = try {
                    jar.getInputStream(entry).use { it.readBytes() }
                } catch (_: Exception) {
                    continue
                }

                // Index by internal class name for second-pass lookup
                val internalName = entry.name.removeSuffix(".class")
                classBytesIndex[internalName] = classBytes

                // Native method detection -> JNI entries + referenced types + field types
                val nativeResult = NativeMethodDetector.detectWithReferences(classBytes)
                jniEntries.addAll(nativeResult.jniEntries)
                jniReferencedTypes.addAll(nativeResult.referencedTypes)
                jniFieldTypes.addAll(nativeResult.jniClassFieldTypes)

                analyzeClassBytes(classBytes, jniEntries, reflectionEntries, resourcePatterns, jniReferencedTypes)
            }

            // Pass 2: resolve JNI callback types — both field types AND parameter/return types
            // of native methods are potential callback targets called from native code
            val allJniCallbackCandidates = jniFieldTypes + jniReferencedTypes
            resolveJniCallbackTypes(allJniCallbackCandidates, classBytesIndex, jniEntries)
        }

        // Add remaining JNI-referenced types that weren't resolved as callbacks
        for (refType in jniReferencedTypes) {
            if (jniEntries.none { it.type == refType }) {
                jniEntries.add(JniEntry(type = refType))
            }
        }

        return AnalysisResult(
            reflectionEntries = reflectionEntries,
            jniEntries = jniEntries,
            resourcePatterns = resourcePatterns,
            serviceLoaderEntries = serviceLoaderEntries,
        )
    }

    /**
     * Analyzes a directory of compiled .class files (e.g. build/classes/kotlin/jvm/main).
     */
    fun analyzeClassDir(dir: File): AnalysisResult {
        if (!dir.exists() || !dir.isDirectory) return AnalysisResult()

        val jniEntries = mutableSetOf<JniEntry>()
        val reflectionEntries = mutableSetOf<ReflectionEntry>()
        val resourcePatterns = mutableSetOf<ResourcePattern>()
        val serviceLoaderEntries = mutableSetOf<ReflectionEntry>()
        val jniReferencedTypes = mutableSetOf<String>()
        val jniFieldTypes = mutableSetOf<String>()
        val classBytesIndex = mutableMapOf<String, ByteArray>()

        // Scan META-INF/services/ in class directories
        val servicesDir = File(dir, "META-INF/services")
        if (servicesDir.isDirectory) {
            servicesDir.listFiles()?.filter { it.isFile }?.forEach { serviceFile ->
                val serviceName = serviceFile.name
                resourcePatterns.add(ResourcePattern(glob = "META-INF/services/$serviceName"))
                val implementations = serviceFile.readLines()
                    .map { it.substringBefore('#').trim() }
                    .filter { it.isNotEmpty() }
                for (impl in implementations) {
                    serviceLoaderEntries.add(
                        ReflectionEntry(
                            type = impl,
                            methods = setOf(MethodSignature("<init>", emptyList())),
                        ),
                    )
                }
            }
        }

        // Pass 1: scan all .class files recursively
        dir.walkTopDown()
            .filter { it.isFile && it.extension == "class" }
            .forEach { classFile ->
                val classBytes = try {
                    classFile.readBytes()
                } catch (_: Exception) {
                    return@forEach
                }

                val relativePath = classFile.relativeTo(dir).path.removeSuffix(".class")
                classBytesIndex[relativePath] = classBytes

                val nativeResult = NativeMethodDetector.detectWithReferences(classBytes)
                jniEntries.addAll(nativeResult.jniEntries)
                jniReferencedTypes.addAll(nativeResult.referencedTypes)
                jniFieldTypes.addAll(nativeResult.jniClassFieldTypes)

                analyzeClassBytes(classBytes, jniEntries, reflectionEntries, resourcePatterns, jniReferencedTypes)
            }

        // Pass 2: resolve JNI callback types
        val allJniCallbackCandidates = jniFieldTypes + jniReferencedTypes
        resolveJniCallbackTypes(allJniCallbackCandidates, classBytesIndex, jniEntries)

        for (refType in jniReferencedTypes) {
            if (jniEntries.none { it.type == refType }) {
                jniEntries.add(JniEntry(type = refType))
            }
        }

        return AnalysisResult(
            reflectionEntries = reflectionEntries,
            jniEntries = jniEntries,
            resourcePatterns = resourcePatterns,
            serviceLoaderEntries = serviceLoaderEntries,
        )
    }

    /**
     * Analyzes a classpath that may contain both JARs and class directories.
     */
    fun analyzeClasspath(files: Iterable<File>): AnalysisResult {
        var merged = AnalysisResult()
        for (file in files) {
            merged = merged + when {
                file.isDirectory -> analyzeClassDir(file)
                file.isFile && file.name.endsWith(".jar") -> analyzeJar(file)
                else -> continue
            }
        }
        return merged
    }

    /**
     * Analyzes multiple JARs and merges results.
     */
    fun analyzeJars(jarPaths: List<File>): AnalysisResult {
        var merged = AnalysisResult()
        for (jar in jarPaths) {
            merged = merged + analyzeJar(jar)
        }
        return merged
    }

    /**
     * Resolves JNI callback types: for each type found as a field in a JNI class,
     * scan that class to extract all non-private methods/fields as JNI-accessible entries.
     */
    private fun resolveJniCallbackTypes(
        jniFieldTypes: Set<String>,
        classBytesIndex: Map<String, ByteArray>,
        jniEntries: MutableSet<JniEntry>,
    ) {
        for (typeName in jniFieldTypes) {
            // Skip JDK types and types already fully covered
            if (typeName.startsWith("java.") || typeName.startsWith("javax.")) continue
            if (jniEntries.any { it.type == typeName && it.methods.isNotEmpty() }) continue

            // Look up the class bytes by internal name (com/foo/Bar)
            val internalName = typeName.replace('.', '/')
            val classBytes = classBytesIndex[internalName] ?: continue

            val callbackEntry = NativeMethodDetector.extractJniCallbackEntry(classBytes)
            if (callbackEntry != null && (callbackEntry.methods.isNotEmpty() || callbackEntry.fields.isNotEmpty())) {
                // Remove any existing bare entry and replace with the detailed one
                jniEntries.removeAll { it.type == typeName }
                jniEntries.add(callbackEntry)
            }
        }
    }

    private fun analyzeClassBytes(
        classBytes: ByteArray,
        @Suppress("UNUSED_PARAMETER") jniEntries: MutableSet<JniEntry>,
        reflectionEntries: MutableSet<ReflectionEntry>,
        resourcePatterns: MutableSet<ResourcePattern>,
        @Suppress("UNUSED_PARAMETER") jniReferencedTypes: MutableSet<String>,
    ) {
        // Note: NativeMethodDetector is now called separately by the caller (pass 1)
        reflectionEntries.addAll(ClassForNameDetector.detect(classBytes))
        reflectionEntries.addAll(ReflectionApiDetector.detect(classBytes))
        resourcePatterns.addAll(ResourceBundleDetector.detect(classBytes))
        resourcePatterns.addAll(ResourceAccessDetector.detect(classBytes))
        reflectionEntries.addAll(MethodHandleDetector.detect(classBytes))
        reflectionEntries.addAll(ProxyDetector.detect(classBytes))
        reflectionEntries.addAll(KotlinSerializableDetector.detect(classBytes))
    }
}
