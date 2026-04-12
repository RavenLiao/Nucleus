package io.github.kdroidfilter.nucleus.systeminfo.model

data class ComponentInfo(
    val label: String,
    val temperature: Float?,
    val max: Float?,
    val critical: Float?,
)
