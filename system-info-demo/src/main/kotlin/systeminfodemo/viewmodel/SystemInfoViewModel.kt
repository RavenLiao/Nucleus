@file:Suppress("TooManyFunctions", "MagicNumber")

package systeminfodemo.viewmodel

import io.github.kdroidfilter.nucleus.systeminfo.SystemInfo
import io.github.kdroidfilter.nucleus.systeminfo.model.ComponentInfo
import io.github.kdroidfilter.nucleus.systeminfo.model.CpuGlobalInfo
import io.github.kdroidfilter.nucleus.systeminfo.model.DiskInfo
import io.github.kdroidfilter.nucleus.systeminfo.model.GpuInfo
import io.github.kdroidfilter.nucleus.systeminfo.model.MemoryInfo
import io.github.kdroidfilter.nucleus.systeminfo.model.MotherboardInfo
import io.github.kdroidfilter.nucleus.systeminfo.model.NetworkInterfaceInfo
import io.github.kdroidfilter.nucleus.systeminfo.model.OsInfo
import io.github.kdroidfilter.nucleus.systeminfo.model.ProcessInfo
import io.github.kdroidfilter.nucleus.systeminfo.model.ProductInfo
import io.github.kdroidfilter.nucleus.systeminfo.model.UserInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

data class SystemInfoState(
    val osInfo: OsInfo? = null,
    val memoryInfo: MemoryInfo? = null,
    val cpuInfo: CpuGlobalInfo? = null,
    val disks: List<DiskInfo> = emptyList(),
    val components: List<ComponentInfo> = emptyList(),
    val networks: List<NetworkInterfaceInfo> = emptyList(),
    val users: List<UserInfo> = emptyList(),
    val motherboard: MotherboardInfo? = null,
    val product: ProductInfo? = null,
    val processes: List<ProcessInfo> = emptyList(),
    val gpus: List<GpuInfo> = emptyList(),
    val cpuHistory: List<Float> = emptyList(),
    val memoryHistory: List<Float> = emptyList(),
    val gpuUsageHistory: Map<Int, List<Float>> = emptyMap(),
    val gpuTempHistory: Map<Int, List<Float>> = emptyMap(),
)

private const val REFRESH_INTERVAL_MS = 1000L
private const val HISTORY_MAX_SIZE = 240

// Number of fast sub-samples per refresh to smooth GPU usage.
// Apple Silicon GPU works in bursts — a single IOKit snapshot often reads 0%
// even under load. We take multiple quick readings and average them.
private const val GPU_SUB_SAMPLES = 4
private const val GPU_SUB_SAMPLE_DELAY_MS = 50L

object SystemInfoViewModel {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(SystemInfoState())
    val state: StateFlow<SystemInfoState> = _state.asStateFlow()

    init {
        scope.launch { collectLoop() }
    }

    private suspend fun collectLoop() {
        while (true) {
            refresh()
            delay(REFRESH_INTERVAL_MS.milliseconds)
        }
    }

    private fun refresh() {
        val os = SystemInfo.osInfo()
        val mem = SystemInfo.memoryInfo()
        val cpu = SystemInfo.cpuInfo()
        val disks = SystemInfo.disks()
        val components = SystemInfo.components()
        val networks = SystemInfo.networks()
        val users = SystemInfo.users()
        val mb = SystemInfo.motherboard()
        val product = SystemInfo.product()
        val procs = SystemInfo.processes()

        // Sample GPU multiple times quickly and average the usage readings.
        // Each call to gpus() is a fast IOKit snapshot (~1ms), but Apple Silicon
        // GPU utilization alternates between 0% and the real value in bursts.
        val gpuSamples = (0 until GPU_SUB_SAMPLES).map {
            if (it > 0) Thread.sleep(GPU_SUB_SAMPLE_DELAY_MS)
            SystemInfo.gpus()
        }

        // Average usage across sub-samples for each GPU
        val gpus = if (gpuSamples.isNotEmpty()) {
            gpuSamples.first().mapIndexed { i, gpu ->
                val avgUsage = gpuSamples.mapNotNull { it.getOrNull(i)?.gpuUsage }.average().toFloat()
                gpu.copy(gpuUsage = if (avgUsage.isNaN()) gpu.gpuUsage else avgUsage)
            }
        } else {
            SystemInfo.gpus()
        }

        val current = _state.value
        val cpuHist = (current.cpuHistory + (cpu?.globalCpuUsage ?: 0f)).takeLast(HISTORY_MAX_SIZE)
        val memPercent =
            if (mem != null && mem.totalMemory > 0) {
                mem.usedMemory.toFloat() / mem.totalMemory.toFloat() * 100f
            } else {
                0f
            }
        val memHist = (current.memoryHistory + memPercent).takeLast(HISTORY_MAX_SIZE)

        val gpuUsageHist = current.gpuUsageHistory.toMutableMap()
        val gpuTempHist = current.gpuTempHistory.toMutableMap()
        gpus.forEachIndexed { i, gpu ->
            gpu.gpuUsage?.let { usage ->
                gpuUsageHist[i] = ((gpuUsageHist[i] ?: emptyList()) + usage).takeLast(HISTORY_MAX_SIZE)
            }
            gpu.temperature?.let { temp ->
                gpuTempHist[i] = ((gpuTempHist[i] ?: emptyList()) + temp).takeLast(HISTORY_MAX_SIZE)
            }
        }

        _state.value =
            SystemInfoState(
                osInfo = os,
                memoryInfo = mem,
                cpuInfo = cpu,
                disks = disks,
                components = components,
                networks = networks,
                users = users,
                motherboard = mb,
                product = product,
                processes = procs,
                gpus = gpus,
                cpuHistory = cpuHist,
                memoryHistory = memHist,
                gpuUsageHistory = gpuUsageHist,
                gpuTempHistory = gpuTempHist,
            )
    }
}
