package io.github.kdroidfilter.nucleus.systeminfo

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

@Suppress("TooManyFunctions")
internal interface PlatformSystemInfo {
    fun isAvailable(): Boolean

    fun osInfo(): OsInfo?

    fun memoryInfo(): MemoryInfo?

    fun cpuInfo(): CpuGlobalInfo?

    fun disks(): List<DiskInfo>

    fun components(): List<ComponentInfo>

    fun networks(): List<NetworkInterfaceInfo>

    fun users(): List<UserInfo>

    fun motherboard(): MotherboardInfo?

    fun product(): ProductInfo?

    fun processes(): List<ProcessInfo>

    fun process(pid: Long): ProcessInfo?

    fun gpus(): List<GpuInfo>
}
