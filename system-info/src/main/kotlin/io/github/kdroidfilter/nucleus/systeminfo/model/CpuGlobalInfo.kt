package io.github.kdroidfilter.nucleus.systeminfo.model

data class CpuGlobalInfo(
    val globalCpuUsage: Float,
    val physicalCoreCount: Int?,
    val cpus: List<CpuInfo>,
)
