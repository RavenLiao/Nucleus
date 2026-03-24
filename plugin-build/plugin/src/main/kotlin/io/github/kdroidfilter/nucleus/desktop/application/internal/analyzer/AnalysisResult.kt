package io.github.kdroidfilter.nucleus.desktop.application.internal.analyzer

/**
 * A single reflection type entry discovered by static analysis.
 */
internal data class ReflectionEntry(
    val type: String,
    val allDeclaredFields: Boolean = false,
    val allDeclaredMethods: Boolean = false,
    val allDeclaredConstructors: Boolean = false,
    val allPublicFields: Boolean = false,
    val allPublicMethods: Boolean = false,
    val allPublicConstructors: Boolean = false,
    val unsafeAllocated: Boolean = false,
    val methods: Set<MethodSignature> = emptySet(),
    val fields: Set<String> = emptySet(),
)

/**
 * A single JNI type entry discovered by static analysis.
 */
internal data class JniEntry(
    val type: String,
    val methods: Set<MethodSignature> = emptySet(),
    val fields: Set<String> = emptySet(),
    val jniAccessible: Boolean = false,
)

/**
 * A method signature with name and parameter types.
 */
internal data class MethodSignature(
    val name: String,
    val parameterTypes: List<String> = emptyList(),
)

/**
 * A resource access pattern discovered by static analysis.
 */
internal data class ResourcePattern(
    val glob: String? = null,
    val bundle: String? = null,
    val module: String? = null,
)

/**
 * Aggregated results from all detectors running over a set of JARs.
 */
internal data class AnalysisResult(
    val reflectionEntries: Set<ReflectionEntry> = emptySet(),
    val jniEntries: Set<JniEntry> = emptySet(),
    val resourcePatterns: Set<ResourcePattern> = emptySet(),
    val serviceLoaderEntries: Set<ReflectionEntry> = emptySet(),
) {
    /**
     * All reflection entries including service loader implementations.
     */
    val allReflectionEntries: Set<ReflectionEntry>
        get() = reflectionEntries + serviceLoaderEntries

    /**
     * Merges this result with another.
     */
    operator fun plus(other: AnalysisResult): AnalysisResult =
        AnalysisResult(
            reflectionEntries = reflectionEntries + other.reflectionEntries,
            jniEntries = jniEntries + other.jniEntries,
            resourcePatterns = resourcePatterns + other.resourcePatterns,
            serviceLoaderEntries = serviceLoaderEntries + other.serviceLoaderEntries,
        )
}
